package net.sf.fmj.media.rtp;

import java.awt.*;
import java.util.concurrent.atomic.*;

import javax.media.*;
import javax.media.control.*;
import javax.media.protocol.*;

import net.sf.fmj.media.*;
import net.sf.fmj.media.protocol.*;
import net.sf.fmj.media.protocol.rtp.DataSource;
import net.sf.fmj.media.rtp.util.*;

/**
 *
 */
public class RTPSourceStream
    extends BasicSourceStream
    implements PushBufferStream, Runnable, PacketQueueControl
{
    private int nbDiscardedFull   = 0;
    private int nbDiscardedShrink = 0;
    private int nbDiscardedLate   = 0;
    private int nbDiscardedReset  = 0;
    private int nbTimesReset      = 0;
    AtomicInteger totalPackets = new AtomicInteger(0);

    private void printStats()
    {
        String cn = this.getClass().getCanonicalName()+" ";
        Log.info(cn+"Packets dropped because full: " + nbDiscardedFull);
        Log.info(cn+"Packets dropped while shrinking: " + nbDiscardedShrink);
        Log.info(cn+"Packets dropped because they were late: " + nbDiscardedLate);
        Log.info(cn+"Packets dropped in reset(): " + nbDiscardedReset);
        Log.info(cn+"Number of resets(): " + nbTimesReset);
    }

    private static final int NOT_SPECIFIED = -1;
    private Format format = null;
    private BufferTransferHandler handler = null;

    private boolean started = false;
    private boolean killed = false;

    private Object startReq;

    private RTPMediaThread thread = null;
    private Buffer lastRead = null;

    /**
     * Sequence number of the last <tt>Buffer</tt> added to the queue.
     */
    private long lastSeqRecv = NOT_SPECIFIED;

	public JitterBufferSimple q;
	public final int maxJitterQueueSize = 8;

    /**
     * cTor
     *
     * @param datasource The datasource that this stream is the source for.
     */
    public RTPSourceStream(DataSource datasource)
    {
        startReq = new Object();
        datasource.setSourceStream(this);

        Log.info("Creating RTPSourceStream " + this.hashCode() +", for datasource " + datasource.hashCode() + "(SSRC="+datasource.getSSRC()+")");

        q = new JitterBufferSimple(maxJitterQueueSize);
        createThread();
    }

    /**
     * Adds <tt>buffer</tt> to the queue.
     *
     * In case the queue is full: if <tt>buffer</tt>'s sequence number comes
     * before the sequence numbers of the <tt>Buffer</tt>s in the queue, nothing
     * is done.
     *
     * @param buffer the buffer to add
     */
    public void add(Buffer buffer)
    {
        long bufferSN = buffer.getSequenceNumber();
        totalPackets.incrementAndGet();

        if (q.isFull())
        {
            Log.warning(String.format("RTPSourceStream %s buffer is full.", this.hashCode()));
//            nbDiscardedFull += q.dropOldest();
            reset();
        }

        lastSeqRecv = bufferSN;

        Buffer freeBuffer = new Buffer();
        freeBuffer.copy(buffer);
        freeBuffer.setFlags(freeBuffer.getFlags() | Buffer.FLAG_NO_DROP);

        q.add(freeBuffer);
    }

    /**
     * Stop the stream and put it in the killed state.
     */
    public void close()
    {
        Log.info(String.format("close() RTPSourceStream %s", this.hashCode()));

        if (killed)
        {
            Log.warning(String.format("RTPSourceStream %s already closed", this.hashCode()));
            return;
        }

        printStats();
        stop();
        killed = true;

        synchronized (startReq)
        {
            startReq.notifyAll();
        }

        //TODO: Wake up any threads that are waiting on the JB
        // Maybe this involves putting a "kill" buffer in the stream?

        thread = null;
    }

    /**
     * Starts the stream (which starts a thread to move buffers around)
     */
    public void connect()
    {
        Log.info(String.format("connect() RTPSourceStream %s", this.hashCode()));
        killed = false;
        createThread();
    }

    private void createThread()
    {
        if (thread != null)
            return;
        thread = new RTPMediaThread(this, "RTPStream");
        thread.useControlPriority();
        thread.start();
    }

    @Override
    public Format getFormat()
    {
        return format;
    }



    /**
     * Pops an element off the queue and copies it to <tt>buffer</tt>. The data
     * and header arrays of <tt>buffer</tt> are reused.
     *
     * @param buffer The <tt>Buffer</tt> object to copy an element of the queue
     * to.
     */
    @Override
    public void read(Buffer buffer)
    {
//        if (q.isEmpty())
//        {
//            if (! loggedEmpty)
//            {
//                Log.warning(String.format("Read from RTPSourceStream %s when empty", this.hashCode()));
//                loggedEmpty = true;
//            }
//            buffer.setDiscard(true);
//            return;
//        }
//        else
//        {
//            loggedEmpty = false;
//        }

        Buffer bufferFromQueue = lastRead;
        lastRead = null;
        buffer.copy(bufferFromQueue);
    }

    /**
     * Resets the queue, dropping all packets.
     */
    public void reset()
    {
        Log.info(String.format("reset() RTPSourceStream %s", this.hashCode()));
        nbDiscardedReset+= q.getCurrentSize();
        nbTimesReset ++;
        q.reset();
    }

	@Override
    public void run() {
        while (true)
            try
            {
                synchronized (startReq)
                {
                    while ((!started) && !killed)
                    {
                        // Block waiting for the stream to be started
                        startReq.wait();
                    }
                }

                if (lastRead == null && !killed)
                {
                    lastRead = q.get();
                }

                if (killed)
                {
                    Log.info(String.format("Ending thread for RTPSourceStream %s", this.hashCode()));
                    break;
                }
                if (handler != null)
                {
                    handler.transferData(this);
                }
            }
            catch (InterruptedException interruptedexception)
            {
                Log.error("Thread " + interruptedexception.getMessage());
            }
    }

    /**
     * @param flag
     */
    public void setBufferWhenStopped(boolean flag)
    {
        //Nothing calls it so removing
    }

    /**
     * @param s
     */
    void setContentDescriptor(String s)
    {
        super.contentDescriptor = new ContentDescriptor(s);
    }

    /**
     * @param format1
     */
    protected void setFormat(Format format1)
    {
        format = format1;
    }

    @Override
    public void setTransferHandler(BufferTransferHandler buffertransferhandler)
    {
        handler = buffertransferhandler;
    }

    /**
     * Puts the source stream into the "started" state
     */
    public void start()
    {
        Log.info(String.format("start() RTPSourceStream %s", this.hashCode()));
        synchronized (startReq)
        {
            started = true;
            // Wake up the thread.
            startReq.notifyAll();
        }
    }

    /**
     * Puts the source stream in the "stop" state.
     */
    public void stop()
    {
        Log.info(String.format("stop() RTPSourceStream %s", this.hashCode()));
        StringBuffer buf = new StringBuffer();
        for(StackTraceElement s : new Throwable().getStackTrace())
        {
          buf.append(s.toString());
          buf.append("\n");
        }

        Log.info(buf.toString());

        synchronized (startReq)
        {
            started = false;
        }
    }

    /**
     * Stub. Return null.
     *
     * @return <tt>null</tt>
     */
    @Override
    public Component getControlComponent()
    {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object getControl(String controlType)
    {
        if (PacketQueueControl.class.getName().equals(controlType))
        {
            return this;
        }

        return super.getControl(controlType);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object[] getControls()
    {
        Object[] superControls = super.getControls();
        Object[] superControlsAndThis = new Object[superControls.length + 1];
        System.arraycopy(superControls, 0, superControlsAndThis, 0, superControls.length);
        superControlsAndThis[superControls.length] = this;
        return superControlsAndThis;
    }

    ////////////////////////////////////////////////////////////////////////////
    // Getters for STATS
    ////////////////////////////////////////////////////////////////////////////
    public int getTimesReset()
    {
        return nbTimesReset;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getDiscarded()
    {
        return nbDiscardedFull + nbDiscardedLate +
               nbDiscardedReset + nbDiscardedShrink;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getDiscardedShrink()
    {
        return nbDiscardedShrink;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getDiscardedLate()
    {
        return nbDiscardedLate;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getDiscardedReset()
    {
        return nbDiscardedReset;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getDiscardedFull()
    {
        return nbDiscardedFull;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getCurrentDelayMs()
    {
        return getCurrentDelayPackets() * 20; //Assuming fixed packetization interval
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getCurrentDelayPackets()
    {
        return  q.getCurrentSize();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getCurrentSizePackets()
    {
        return maxJitterQueueSize;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isAdaptiveBufferEnabled()
    {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getCurrentPacketCount()
    {
        return q.getCurrentSize();
    }
}
