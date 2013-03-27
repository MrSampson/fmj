// SimpleJitterBufferBehaviour.java
// (C) COPYRIGHT METASWITCH NETWORKS 2013

package net.sf.fmj.media.rtp;

import javax.media.*;
import javax.media.protocol.*;

import net.sf.fmj.media.rtp.util.*;
import net.sf.fmj.media.util.*;

/**
 * A simple jitter buffer that doesn't actually attempt to store any data.
 */
public class SimpleJitterBufferBehaviour implements JitterBufferBehaviour
{
    private RTPSourceStream       stream;
    private JitterBuffer          q;
    private BufferTransferHandler handler;
    private JitterBufferStats     stats;
    private MediaThread           thread;
    private volatile boolean      running;

    /**
     * cTor
     *
     * @param q      The actual jitter to control the behaviour of.
     * @param stream The stream we're interacting with
     * @param stats  Stats to update
     */
    public SimpleJitterBufferBehaviour(JitterBuffer q,
                                       RTPSourceStream stream,
                                       JitterBufferStats stats)
    {
        this.q = q;
        this.stream = stream;
        this.stats = stats;
        q.maxCapacity = ConfigUtils.getIntConfig("simple.maxPackets", 12);
    }

    @Override
    public void preAdd()
    {
        //Do Nothing
    }

    @Override
    public void handleFull()
    {
        //Reset the whole jitter buffer.
        stats.incrementTimesReset();
        stats.incrementDiscardedReset(q.getCurrentSize());
        q.reset();
    }

    @Override
    public void read(Buffer buffer)
    {
        Buffer bufferToCopyFrom = q.get();
        buffer.copy(bufferToCopyFrom);
    }

    @Override
    public void start()
    {
        running = true;

        if (thread == null)
        {
            thread = new RTPMediaThread("RTPStream")
            {
                @Override
                public void run()
                {
                    while (running)
                    {
                        //Always signal that there's data available.
                        if (handler != null)
                        {
                            handler.transferData(stream);
                        }
                    }
                }
            };
            thread.useControlPriority();
            thread.start();
        }
    }

    @Override
    public void stop()
    {
        // Don't just stop the thread as that is depracated. Instead, signal
        // the thread (which is stuck in a loop) that it should end the loop
        // and come to a natural end.
        running = false;
    }

    @Override
    public void setTransferHandler(BufferTransferHandler handler)
    {
        this.handler = handler;
    }

}
