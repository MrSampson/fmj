package net.sf.fmj.media.rtp;

import java.util.*;
import java.util.concurrent.atomic.*;

import javax.media.*;
import javax.media.protocol.*;

import net.sf.fmj.media.*;

/**
 * Jitter buffer for audio
 */
public class AudioJitterBufferBehaviour implements JitterBufferBehaviour
{
    @SuppressWarnings("javadoc")
    public int  maxQueueSize           = ConfigUtils.getIntConfig("audio.maxQueueSize", 10);
    private int targetDelayInPackets   = ConfigUtils.getIntConfig("audio.targetDelayInPackets", maxQueueSize / 2);
    private int adjusterThreadInterval = ConfigUtils.getIntConfig("audio.adjusterThreadInterval", 1000);
    private int packetizationInterval  = ConfigUtils.getIntConfig("audio.packetizationInterval", 20);
    private int requiredDelta          = ConfigUtils.getIntConfig("audio.requiredDelta", 2);

    private AtomicBoolean         prebuffering       = new AtomicBoolean();

    private TimerTask             readThread         = null;
    private TimerTask             adjusterThread     = null;
    private Timer                 readTimer          = new Timer();
    private Timer                 adjusterTimer      = new Timer();

    private AverageDelayTracker delayTracker = new AverageDelayTracker();
    private JitterBuffer q;
    private RTPSourceStream stream;
    private JitterBufferStats stats;
    private BufferTransferHandler handler;
    private JitterBufferTester tester;

    /**
     * cTor
     *
     * @param q      The actual jitter to control the behaviour of.
     * @param stream The stream we're interacting with
     * @param stats  Stats to update
     */
    public AudioJitterBufferBehaviour(JitterBuffer q, RTPSourceStream stream, JitterBufferStats stats)
    {
        this.q = q;
        this.stream = stream;
        this.stats = stats;

        q.maxCapacity = maxQueueSize;
        tester = new JitterBufferTester(q, stats);
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
            stats.incrementDiscardedShrink();
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
            stats.incrementSilenceInserted();
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
            if (size >= maxQueueSize / 2)
            {
                // >= is used so that we can wait another packetization
                // interval before we signal the transfer handler.
                Log.info(String
                        .format("RTPSourceStream %s has completed prebuffering",
                                this.hashCode()));
                prebuffering.set(false);
            }
            else
            {
                Log.comment(String
                        .format("RTPSourceStream %s is prebuffering. Size is %s",
                                this.hashCode(),
                                size));
            }
        }
        else
        {
            if (handler != null)
            {
                handler.transferData(stream);
            }
        }
    }

    @Override
    public void handleFull()
    {
      stats.incrementDiscardedFull();
      q.dropOldest();
    }

    @Override
    public void read(Buffer buffer)
    {
        if (tester.silenceInserted(buffer))
        {
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
                Log.warning(String
                        .format("Read from RTPSourceStream %s when empty",
                                this.hashCode()));
                buffer.setFlags(Buffer.FLAG_SILENCE | Buffer.FLAG_NO_FEC);
                stats.incrementSilenceInserted();

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

        if (JitterBufferTester.pleaseReset)
        {
            JitterBufferTester.pleaseReset = false;
            q.reset();
        }
    }
    @Override
    public void start()
    {
        prebuffering.set(true);
        if (readThread == null)
        {
            // Create a thread that will start now and then run every 20ms.
            // This thread will queue up additional tasks if execution takes
            // longer than 20ms. They may be executed in a burst.
            readThread = new TimerTask()
            {
                @Override
                public void run()
                {
                    packetAvailable();
                }
            };
            readTimer.scheduleAtFixedRate(readThread, 0, packetizationInterval);
        }

        if (adjusterThread == null)
        {
            // Create a thread that will run every 500ms. Delay the start
            // since we don't want to adjust till we have some data.
            adjusterThread = new TimerTask()
            {
                @Override
                public void run()
                {
                    adjustDelay();
                }
            };
            adjusterTimer.scheduleAtFixedRate(adjusterThread,
                                              adjusterThreadInterval,
                                              adjusterThreadInterval);
        }
    }

    @Override
    public void stop()
    {
        prebuffering.set(false);

        // Stop the various timer threads that are running.
        if (readTimer != null)
        {
            readTimer.cancel();
            readTimer = null;
        }

        if (adjusterTimer != null)
        {
            adjusterTimer.cancel();
            adjusterTimer = null;
        }
    }

    @Override
    public void preAdd()
    {
        //Nothing required
    }

    @Override
    public void setTransferHandler(BufferTransferHandler handler)
    {
        this.handler = handler;
    }
}
