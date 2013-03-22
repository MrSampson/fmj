package net.sf.fmj.media.rtp;

import info.monitorenter.gui.chart.*;
import info.monitorenter.gui.chart.traces.*;

import java.awt.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import javax.media.*;
import javax.media.control.*;
import javax.media.format.*;
import javax.media.protocol.*;

import net.sf.fmj.media.*;
import net.sf.fmj.media.protocol.*;
import net.sf.fmj.media.protocol.rtp.DataSource;

/**
 * The RTPSource stream is passed packets, buffers them in a jitter buffer and
 * passes them onto a BufferTransferHandler<p>
 *
 * It can be in one of a number of states
 * <ul>
 * <li>Newly created/Closed - No thread running.</li>
 * <li>Started/Stopped - controls the started flag.</li>
 * <ul><li>The thread only does something when started=true</li>
 *     <li>Items are only added to the queue if started=true</li>
 * </ul>
 * <li>Connected - The thread is started</li>
 * </ul>
 * <p>
 * When the RTPSourceStream isn't started, packets can still be added to it.
 */
public class RTPSourceStream
    extends BasicSourceStream
    implements PushBufferStream, PacketQueueControl
{
    // Constants
    private static final int ADJUSTER_THREAD_INTERVAL = 1000;
    private static final int PACKETIZATION_INTERVAL   = 20;

    private int nbDiscardedFull   = 0;
    private int nbDiscardedShrink = 0;
    private int nbDiscardedLate   = 0;
    private int nbDiscardedReset  = 0;
    private int nbTimesReset      = 0;
    private int nbSilenceInserted = 0;

    AtomicInteger totalPackets = new AtomicInteger(0);

    /**
     * Sequence number of the last <tt>Buffer</tt> added to the queue.
     */
    private long                  lastSeqRecv        = NOT_SPECIFIED;
    private static final int      NOT_SPECIFIED      = -1;
    private static final int requiredDelta = 2;

    private Format                format             = null;
    private BufferTransferHandler handler            = null;

    private AtomicBoolean         started            = new AtomicBoolean();
    private AtomicBoolean         prebuffering       = new AtomicBoolean();

    private TimerTask             readThread         = null;
    private TimerTask             adjusterThread     = null;
    private Timer                 readTimer          = new Timer();
    private Timer                 adjusterTimer      = new Timer();

    public JitterBufferSimple     q;
    public int                    maxJitterQueueSize = 10;
    private int targetDelayInPackets = maxJitterQueueSize / 2;
    private AverageDelayTracker delayTracker = new AverageDelayTracker();
    private DataSource datasource;

    /**
     * cTor
     *
     * @param datasource The datasource that this stream is the source for.
     */
    public RTPSourceStream(DataSource datasource)
    {
        this.datasource = datasource;
        datasource.setSourceStream(this);

        Log.info("Creating RTPSourceStream " + this.hashCode() +", for datasource " + datasource.hashCode() + "(SSRC="+datasource.getSSRC()+")");

        q = new JitterBufferSimple(maxJitterQueueSize);
        createThreads();
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
    		intrace = new Trace2DLtd(datapointsToKeep, String.valueOf(datasource.getSSRC() + " IN Delta (ms)"));
    		intrace.setColor(Color.red);

    		outtrace = new Trace2DLtd(datapointsToKeep, String.valueOf(datasource.getSSRC() + " OUT Delta (ms)"));
    		outtrace.setColor(Color.green);

    		sizetrace = new Trace2DLtd(datapointsToKeep, String.valueOf(datasource.getSSRC() + " Size"));
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
     * is done.
     *
     * @param buffer the buffer to add
     */
    public void add(Buffer buffer)
    {
        if (shouldChart())
        {
            long timeNow = System.nanoTime();
//          outtrace.addPoint(timeNow, (timeNow - lastDepartureTimeNanos)/1000000);
//          sizetrace.addPoint(timeNow,q.getCurrentSize());
            lastDepartureTimeNanos = timeNow;
        }

        totalPackets.incrementAndGet();

        if (! started.get())
        {
            // TODO - For now we'll still add the packets.
            Log.warning(String.format("RTPSourceStream %s add() called but not started.", this.hashCode()));
        }

        if (q.theShipHasSailed(buffer))
        {
            // The packet is too late, the ship has sailed.
            // Dn't add the packet to the queue. This is equivalent to a
            // packet dropped by the network.
            nbDiscardedLate++;
        }
        else
        {

            if (q.isFull())
            {
                Log.warning(String.format("RTPSourceStream %s buffer is full.", this.hashCode()));
                nbDiscardedFull++;
                q.dropOldest();
            }

            Buffer newBuffer = (Buffer)buffer.clone();
            newBuffer.setFlags(newBuffer.getFlags() | Buffer.FLAG_NO_DROP);

            q.add(newBuffer);
        }
    }

    /**
     * Stop the stream.
     *
     *TODO should be synchronized?
     */
    public void close()
    {
        Log.info(String.format("close() RTPSourceStream %s", this.hashCode()));

        if (readTimer == null)
        {
            Log.warning(String.format("RTPSourceStream %s already closed", this.hashCode()));
        }
        else
        {
            Log.info(String.format("Ending thread for RTPSourceStream %s",
                                   this.hashCode()));
            readTimer.cancel();
            readTimer = null;

            adjusterTimer.cancel();
            adjusterTimer = null;

            printStats();
            stop();
        }
    }

    /**
     * Starts the stream (which starts a thread to move buffers around)
     */
    public void connect()
    {
        Log.info(String.format("connect() RTPSourceStream %s", this.hashCode()));
        createThreads();
    }

    private void createThreads()
    {
        if (readThread == null)
        {
            // Create a thread that will start now and then run every 20ms.
            // This thread will queue up additional tasks if execution takes
            // longer than 20ms. They may be executed in a burst.
            readThread = new TimerTask(){@Override public void run(){packetAvailable();}};
            readTimer.scheduleAtFixedRate(readThread, 0, PACKETIZATION_INTERVAL);
        }

        if (adjusterThread == null)
        {
            // Create a thread that will run every 500ms. Delay the start
            // since we don't want to adjust till we have some data.
            adjusterThread = new TimerTask(){@Override public void run(){adjustDelay();}};
            adjusterTimer.scheduleAtFixedRate(adjusterThread, ADJUSTER_THREAD_INTERVAL, ADJUSTER_THREAD_INTERVAL);
        }
    }

    /**
     * If target delay differs from the average by a big enough delta then
     * add/drop a packet.
     */
    protected void adjustDelay()
    {
        double delay = delayTracker.getAverageDelayInPacketsAndReset();

        if (delay > targetDelayInPackets + requiredDelta)
        {
            //There's on average too much delay so drop a packet.
            Log.warning(String.format("RTPSourceStream %s average delay (%s) " +
            		                  "is greater than target (%s) so " +
            		                  "dropping packet",
                                      this.hashCode(),
                                      delay,
                                      targetDelayInPackets));
            q.dropOldest();
            nbDiscardedShrink++;
        }
        else if (delay < targetDelayInPackets - requiredDelta)
        {
            //There's not enough delay on average insert a packet.
            Log.warning(String.format("RTPSourceStream %s average delay (%s) " +
                                      "is lower than target (%s) so " +
                                      "inserting packet",
                                      this.hashCode(),
                                      delay,
                                      targetDelayInPackets));

            Buffer silenceBuffer = new Buffer();
            silenceBuffer.setFlags(Buffer.FLAG_SILENCE | Buffer.FLAG_NO_FEC);
            q.add(silenceBuffer);
            nbSilenceInserted++;
        }
    }

    @Override
    public Format getFormat()
    {
        return format;
    }

    boolean forceSilenceEnable = false;

    int forceSilencePackets = 5; //Silence to insert
    int forceSilenceGap = 45; //Gap between inserting silence
    long forceSilenceCounter = -forceSilenceGap; //Set to Long.MIN_VALUE to disable...

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
        forceSilenceCounter++;
        if (forceSilenceCounter >= forceSilencePackets){forceSilenceCounter = -forceSilenceGap;}

        if (forceSilenceEnable && forceSilenceCounter > 0)
        {
            buffer.setFlags(Buffer.FLAG_SILENCE | Buffer.FLAG_NO_FEC);
            nbSilenceInserted++;
            updateDelayAverage(q.get());
        }
        else
        {
        //Every time a packet is clocked out of the JB, a rolling average of the
        // difference between the seqno of the packet being read (or if the
        // packet is missing, the seqno of the packet that should be available)
        // and the last sequence number added to the jitter buffer is updated.
        // This approach is resilient to packet loss.
        if (q.isEmpty())
        {
            Log.warning(String.format("Read from RTPSourceStream %s when empty",
                        this.hashCode()));
            buffer.setFlags(Buffer.FLAG_SILENCE | Buffer.FLAG_NO_FEC);
            nbSilenceInserted++;

            delayTracker.updateAverageDelayWithEmptyBuffer();
        }
        else
        {
            //Get the buffer from the jitter buffer. The buffer can be copied
            //as there's no point cloning it since we created it in this class.
            Buffer bufferToCopyFrom = q.get();
            buffer.copy(bufferToCopyFrom);

            updateDelayAverage(bufferToCopyFrom);
        }
        }
    }

    private void updateDelayAverage(Buffer bufferToCopyFrom)
    {
        // Update the delay average. The delay is the difference between
        // this seqNum and the most recently added one to the queue.
        long thisSeqNum =  bufferToCopyFrom.getSequenceNumber(); //TODO Needs to handle loss.

        if (thisSeqNum != Buffer.SEQUENCE_UNKNOWN && q.getLastSeqNoAdded() != Buffer.SEQUENCE_UNKNOWN)
        {
            long delay = q.getLastSeqNoAdded() - thisSeqNum;
            delayTracker.updateAverageDelay(delay);
        }
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

    /**
     * Called every packetization interval.
     */
    public void packetAvailable()
    {
        // When the stream has just been started, it's in prebuffering mode.
        // This stops the stream from signalling to the BufferTransferHandler
        // that there is data available until the jitter buffer is full enough
        // (at which point we exit prebuffering)
        if (prebuffering.get())
        {
            int size = q.getCurrentSize();
            if (size >= maxJitterQueueSize/2)
            {
                // >= is used so that we can wait another packetization
                // interval before we signal the transfer handler.
                Log.info(String.format("RTPSourceStream %s has completed prebuffering", this.hashCode()));
                prebuffering.set(false);
            }
            else
            {
                Log.comment(String.format("RTPSourceStream %s is prebuffering. Size is %s", this.hashCode(), size));
            }
        }
        else
        {
            if (started.get())
            {
                if (handler != null)
                {
                    handler.transferData(this);
                }
            }
            else
            {
                //Do nothing - we'll try again next time we're scheduled
            }
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
     * @param format
     */
    protected void setFormat(Format format)
    {
        this.format = format;
        if (format instanceof VideoFormat)
        {
            //Set the buffer to be much bigger
            Log.info(String.format("RTPSourceStream %s set format to video. Adjusting jitter buffer length", this.hashCode()));
            maxJitterQueueSize = 128;
            q.maxCapacity = 128;
        }
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
        started.set(true);
        prebuffering.set(true);
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

        started.set(false);
        prebuffering.set(false);
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
    public int getSilenceInserted()
    {
        return nbSilenceInserted;
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

    private void printStats()
    {
        String cn = this.getClass().getCanonicalName()+" ";
        Log.info(cn+"Packets dropped because full: " + nbDiscardedFull);
        Log.info(cn+"Packets dropped while shrinking: " + nbDiscardedShrink);
        Log.info(cn+"Packets dropped because they were late: " + nbDiscardedLate);
        Log.info(cn+"Packets dropped in reset(): " + nbDiscardedReset);
        Log.info(cn+"Number of resets(): " + nbTimesReset);
    }
}
