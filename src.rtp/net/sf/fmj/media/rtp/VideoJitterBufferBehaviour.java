// VideoJitterBufferBehaviour.java
// (C) COPYRIGHT METASWITCH NETWORKS 2013
package net.sf.fmj.media.rtp;

import java.util.concurrent.locks.*;

import javax.media.*;
import javax.media.protocol.*;

import net.sf.fmj.media.rtp.util.*;
import net.sf.fmj.media.util.*;

public class VideoJitterBufferBehaviour implements JitterBufferBehaviour
{
    private int minVideoPackets = 16;
    private int maxVideoPackets = 256;

    private final Lock videoLock = new ReentrantLock();
    private final Condition videoCondition = videoLock.newCondition();
    private volatile boolean videoDataAvailable = false;
    private JitterBuffer q;
    private BufferTransferHandler handler;
    private RTPSourceStream stream;
    private JitterBufferStats stats;
    private RTPMediaThread videoThread;
    private volatile boolean running;



    public VideoJitterBufferBehaviour(JitterBuffer q, RTPSourceStream stream, JitterBufferStats stats)
    {
        q.maxCapacity = 128;
        this.q = q;
        this.stream = stream;
        this.stats = stats;
    }

    public void transferVideoData()
    {
        if (q.getCurrentSize() > minVideoPackets && handler != null)
        {
                handler.transferData(stream);
        }
    }

    @Override
    public void preAdd(Buffer xiBuffer)
    {
            videoLock.lock();
            try
            {
                videoDataAvailable = true;
                videoCondition.signalAll();
            }
            finally
            {
                videoLock.unlock();
            }
    }


    @Override
    public void handleFull()
    {
            while(q.getCurrentSize() > minVideoPackets)
            {
                stats.incrementDiscardedFull();
                q.dropOldest();
            }

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
        if (videoThread == null)
        {
        //TODO create actual media thread.
        videoThread = new RTPMediaThread("Video Buffer Transfer")
        {
            @Override
            public void run()
            {
                while (running)
                {
                    videoLock.lock();

                    try
                    {
                        while (! videoDataAvailable)
                        {
                            videoCondition.awaitUninterruptibly();
                        }
                    }
                    finally
                    {
                        videoLock.unlock();
                    }

                    transferVideoData();
                }
            }
        };
        videoThread.setPriority(MediaThread.getVideoPriority());
        videoThread.start();
        }
    }

    @Override
    public void stop()
    {
        running = false;
    }

    @Override
    public void setTransferHandler(BufferTransferHandler handler)
    {
        this.handler = handler;
    }
}
