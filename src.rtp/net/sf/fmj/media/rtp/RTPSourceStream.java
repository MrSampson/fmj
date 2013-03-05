package net.sf.fmj.media.rtp;

import info.monitorenter.gui.chart.Chart2D;
import info.monitorenter.gui.chart.ITrace2D;
import info.monitorenter.gui.chart.traces.Trace2DLtd;

import java.awt.*;

import javax.media.*;
import javax.media.control.*;
import javax.media.format.*;
import javax.media.protocol.*;

import net.sf.fmj.media.*;
import net.sf.fmj.media.protocol.*;
import net.sf.fmj.media.protocol.rtp.DataSource;
import net.sf.fmj.media.rtp.util.*;

public class RTPSourceStream
    extends BasicSourceStream
    implements PushBufferStream, Runnable, PacketQueueControl
{
    private int nbAdd = 0;
    private int nbReset = 0;
    private int nbAppend = 0;
    private int nbInsert = 0;
    private int nbCutByHalf = 0;
    private int nbGrow = 0;
    private int nbPrepend = 0;
    private int nbRemoveAt = 0;
    private int nbReadWhileEmpty = 0;
    private int nbShrink = 0;
    private int nbDiscardedFull = 0;
    private int nbDiscardedShrink = 0;
    private int nbDiscardedLate = 0;
    private int nbDiscardedReset = 0;
    private int nbDiscardedVeryLate = 0;
    private int maxSizeReached = 0;

    private void printStats()
    {
        String cn = this.getClass().getCanonicalName()+" ";
        Log.info(cn+"Total packets added: " + nbAdd);
        Log.info(cn+"Times reset() called: " + nbReset);
        Log.info(cn+"Times append() called: " + nbAppend);
        Log.info(cn+"Times insert() called: " + nbInsert);
        Log.info(cn+"Times cutByHalf() called: " + nbCutByHalf);
        Log.info(cn+"Times grow() called: " + nbGrow);
        Log.info(cn+"Times shrink() called: " + nbShrink);
        Log.info(cn+"Times prepend() called: " + nbPrepend);
        Log.info(cn+"Times removeAt() called: " + nbRemoveAt);
        Log.info(cn+"Times read() while empty:" + nbReadWhileEmpty);
        Log.info(cn+"Packets dropped because full: " + nbDiscardedFull);
        Log.info(cn+"Packets dropped while shrinking: " + nbDiscardedShrink);
        Log.info(cn+"Packets dropped because they were late: " + nbDiscardedLate);
        Log.info(cn+"Packets dropped because they were late by more than MAX_SIZE: " + nbDiscardedVeryLate);
        Log.info(cn+"Packets dropped in reset(): " + nbDiscardedReset);
        Log.info(cn + "Max size reached: " + maxSizeReached);
    }

    private static final int NOT_SPECIFIED = -1;
    private DataSource dsource;
    private Format format = null;
    BufferTransferHandler handler = null;

    boolean started = false;
    boolean killed = false;
    boolean replenish = true;

    Object startReq;
    private RTPMediaThread thread = null;
    private Buffer lastRead = null;
    private BufferControlImpl bc = null;

    /**
     * Sequence number of the last <tt>Buffer</tt> added to the queue.
     */
    private long lastSeqRecv = NOT_SPECIFIED;

    /**
     * Sequence number of the last <tt>Buffer</tt> read from the queue.
     */
    private long lastSeqSent = NOT_SPECIFIED;

    private BufferListener listener = null;

    private boolean prebuffering = false;
    private boolean prebufferNotice = false;

    private boolean bufferWhenStopped = true;
    static AudioFormat mpegAudio = new AudioFormat("mpegaudio/rtp");
    static VideoFormat mpegVideo = new VideoFormat("mpeg/rtp");
    static VideoFormat h264Video = new VideoFormat("h264/rtp");
	  private JitterBufferSimple q;

	private int maxJitterQueueSize = 8;

    public RTPSourceStream(DataSource datasource)
    {
        startReq = new Object();
        dsource = datasource;
        datasource.setSourceStream(this);

        Log.info("Creating RTPSourceStream " + this.hashCode() +", for datasource " + datasource.hashCode() + "(SSRC="+datasource.getSSRC()+")");

        q = new JitterBufferSimple(maxJitterQueueSize);
        createThread();
    }

    public static Chart2D chart = null;
    public static int datapointsToKeep = 400;
    private ITrace2D intrace = null;
    private ITrace2D outtrace = null;
    private ITrace2D sizetrace = null;
    private long lastArrivalTimeNanos = System.nanoTime();
    private long lastDepartureTimeNanos = System.nanoTime();
    
    private boolean shouldChart()
    {
    	if (intrace != null)
    	{
    		return true;
    	}
    	
    	if (chart != null)
    	{
    		intrace = new Trace2DLtd(datapointsToKeep, String.valueOf(dsource.getSSRC() + " IN Delta (ms)"));
    		intrace.setColor(Color.red);
    		
    		outtrace = new Trace2DLtd(datapointsToKeep, String.valueOf(dsource.getSSRC() + " OUT Delta (ms)"));
    		outtrace.setColor(Color.green);
    		
    		sizetrace = new Trace2DLtd(datapointsToKeep, String.valueOf(dsource.getSSRC() + " Size"));
    		sizetrace.setColor(Color.black);
    		
    		chart.addTrace(intrace);
//    		chart.addTrace(outtrace);
//    		chart.addTrace(sizetrace);
    		
    		return true;
    	}
    	
    	return false;
    }
    
    /**
     * Adds <tt>buffer</tt> to the queue.
     *
     * In case the queue is full: if <tt>buffer</tt>'s sequence number comes
     * before the sequence numbers of the <tt>Buffer</tt>s in the queue, nothing
     * is done. Otherwise, a packet is dropped using PktQue.dropPkt()
     *
     * @param buffer the buffer to add
     */
    public void add(Buffer buffer)
    {
        if (!started && !bufferWhenStopped)
            return;

        long bufferSN = buffer.getSequenceNumber();

        if (lastSeqRecv - bufferSN > 256L)
        {
            Log.info("Resetting queue, last seq added: " + lastSeqRecv +
                    ", current seq: " + bufferSN);
            reset();
            lastSeqRecv = bufferSN;
        }
        if (lastSeqSent != NOT_SPECIFIED &&
                bufferSN < lastSeqSent &&
                format instanceof AudioFormat)
            {
            return;
        }

        nbAdd++;
        lastSeqRecv = bufferSN;
        boolean almostFull = false;

            if (q.isFull()) //Buffer is full
            {
                nbDiscardedFull++;
                q.dropOldest();
                //now drop a packet from the buffer?
        }

// Check if the buffer is almost full            almostFull = true;

        Buffer freeBuffer = new Buffer(); // TODO - Need a buffer pool?

        // Copy the data around...
        // TODO - Why not just add this buffer to the JB?
        byte bufferData[] = (byte[]) buffer.getData();
        byte freeBufferData[] = (byte[]) freeBuffer.getData(); // TODO Won't this always be empty since we just got a free buffer?

        if (freeBufferData == null || freeBufferData.length < bufferData.length) //Ah - we reuse buffers to avoid object creation and this means we can avoid creating the data array
            freeBufferData = new byte[bufferData.length];

        System.arraycopy(bufferData, buffer.getOffset(), freeBufferData,
                buffer.getOffset(), buffer.getLength());

        freeBuffer.copy(buffer); //Interesting - this copies all the headers, but also appears to copy the data...
        freeBuffer.setData(freeBufferData); //And put the data from buffer into freeBuffer...

        // Set flags on the buffer, to indication that the packet shouldn't be
        // dropped and possible that the buffer is almost full.
//        if (almostFull) //with this packet added, the queue will be full
//            freeBuffer.setFlags(freeBuffer.getFlags() |
//                    Buffer.FLAG_BUF_OVERFLOWN | Buffer.FLAG_NO_DROP);
//        else
            freeBuffer.setFlags(freeBuffer.getFlags() | Buffer.FLAG_NO_DROP);

        // Add the packet to the queue...
        q.add(freeBuffer);

            if (replenish && (format instanceof AudioFormat))
            {
                //delay the call to notifyAll until the queue is 'replenished'
                // - TODO this replenish stuff won't be working because I'm
                // always "notify"ing as soon as there is data.
                if (q.getCurrentSize() >= q.getMaxSize() / 2)
                {
                    replenish = false;
                    // TODO Notify the data transfer handler that there's data ready
                    // to be read.
                }
            } else
            {
                // TODO Notify the data transfer handler that there's data ready
                // to be read.
            }
        }

    public void close()
    {
        if (killed)
            return;
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
        if (bc != null)
            bc.removeSourceStream(this);
    }

    public void connect()
    {
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
    public void read(Buffer buffer)
    {
//        if (q.isEmpty())
//        {
//            nbReadWhileEmpty++;
//            buffer.setDiscard(true);
//            return;
//        }
//        
        if (shouldChart())
        {
        	long timeNow = System.nanoTime();
//        	outtrace.addPoint(timeNow, (timeNow - lastDepartureTimeNanos)/1000000);
//        	sizetrace.addPoint(timeNow,q.getCurrentSize());
        	lastDepartureTimeNanos = timeNow;
        }    	
        
        Buffer bufferFromQueue = lastRead;
        lastRead = null;
                    lastSeqSent = bufferFromQueue.getSequenceNumber();

                    buffer.copy(bufferFromQueue);
//        bufferFromQueue.setData(bufferData);
//        bufferFromQueue.setHeader(bufferHeader);
//        pktQ.returnFree(bufferFromQueue);
//        TODO Return a free packet to the jitter buffer?

            if (format instanceof AudioFormat && q.isEmpty())
                    {
                    replenish = true; //start to replenish when the queue empties
        }
            // TODO Notify the data transfer handler that there's data ready
            // to be read.
    }

    /**
     * Resets the queue, dropping all packets.
     */
    public void reset()
    {
        q.reset();
            lastSeqSent = NOT_SPECIFIED;
        }

	public void run() {
        while (true)
			try {
				synchronized (startReq) {
                    while ((!started || prebuffering) && !killed)
                        startReq.wait();
                }

				if (lastRead == null && !killed) {
					lastRead = q.get();
                }

				if (killed) {
                    break;
            }
				if (handler != null) {
					handler.transferData(this);
            }
			} catch (InterruptedException interruptedexception) {
				Log.error("Thread " + interruptedexception.getMessage());
        }
    }

    public void setBufferControl(BufferControl buffercontrol)
    {
        bc = (BufferControlImpl) buffercontrol;
        updateBuffer(bc.getBufferLength());
        updateThreshold(bc.getMinimumThreshold());
    }

    public void setBufferListener(BufferListener bufferlistener)
    {
        listener = bufferlistener;
    }

    public void setBufferWhenStopped(boolean flag)
    {
        bufferWhenStopped = flag;
    }

    void setContentDescriptor(String s)
    {
        super.contentDescriptor = new ContentDescriptor(s);
    }

    protected void setFormat(Format format1)
    {
        format = format1;
    }

    public void setTransferHandler(BufferTransferHandler buffertransferhandler)
    {
        handler = buffertransferhandler;
    }

    public void start()
    {
        Log.info("Starting RTPSourceStream " + this.hashCode());
        synchronized (startReq)
        {
            started = true;
            startReq.notifyAll();
        }
    }

    public void stop()
    {
        Log.info("Stopping RTPSourceStream " + this.hashCode() +" , dumping stack trace (this is not " +
                "an error)");
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
            prebuffering = false;
            if (!bufferWhenStopped)
                reset();
        }
    }

    public long updateBuffer(long l)
    {
        return l;
    }

    public long updateThreshold(long l)
    {
        return l;
    }

    /**
     * {@inheritDoc}
     */
    public int getDiscarded()
    {
        return nbDiscardedFull + nbDiscardedLate +
               nbDiscardedReset + nbDiscardedShrink + nbDiscardedVeryLate;
    }

    /**
     * {@inheritDoc}
     */
    public int getDiscardedShrink()
    {
        return nbDiscardedShrink;
    }

    /**
     * {@inheritDoc}
     */
    public int getDiscardedLate()
    {
        return nbDiscardedLate;
    }

    /**
     * {@inheritDoc}
     */
    public int getDiscardedReset()
    {
        return nbDiscardedReset;
    }

    /**
     * {@inheritDoc}
     */
    public int getDiscardedFull()
    {
        return nbDiscardedFull;
    }

    /**
     * {@inheritDoc}
     */
    public int getCurrentDelayMs()
    {
        return (int) (getCurrentDelayPackets() * 20); //TODO
    }

    /**
     * {@inheritDoc}
     */
    public int getCurrentDelayPackets()
    {
        return  999; //TODO
    }

    /**
     * {@inheritDoc}
     */
    public int getCurrentSizePackets()
    {
        return maxJitterQueueSize;
    }

    /**
     * {@inheritDoc}
     */
    public int getMaxSizeReached()
    {
        return maxSizeReached;
    }

    /**
     * {@inheritDoc}
     */
    public boolean isAdaptiveBufferEnabled()
    {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public int getCurrentPacketCount()
    {
        return q.getCurrentSize();
    }

    /**
     * Stub. Return null.
     *
     * @return <tt>null</tt>
     */
    public Component getControlComponent()
    {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public Object getControl(String controlType)
    {
        if(PacketQueueControl.class.getName().equals(controlType))
        {
            return this;
        }
        else
        {
            return super.getControl(controlType);
        }
    }

    /**
     * {@inheritDoc}
     */
    public Object[] getControls()
    {
        Object[] superControls = super.getControls();
        Object[] controls = new Object[superControls.length + 1];
        System.arraycopy(superControls, 0, controls, 0, superControls.length);
        controls[superControls.length] = this;
        return controls;
    }
}
