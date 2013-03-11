
package net.sf.fmj.media.rtp;

import java.util.*;
import java.util.concurrent.*;

import javax.media.*;

import net.sf.fmj.media.*;

/**
 * A simple jitter buffer based on existing Java data structures.
 */
public class JitterBufferSimple
{
    final private PriorityBlockingQueue<Buffer> q;
    final private int                           maxCapacity;

    /**
     * Creates a new JB
     *
     * @param maxCapacity The most packets that the queue will hold.
     */
    public JitterBufferSimple(int maxCapacity)
    {
        this.maxCapacity = maxCapacity;
        q = new PriorityBlockingQueue<Buffer>(maxCapacity, new Comparator<Buffer>()
        {

            @Override
            public int compare(Buffer buf1, Buffer buf2)
            {
                return (int)(buf1.getSequenceNumber() - buf2.getSequenceNumber()); //I haven't thought if this is right.
            }
        });
    }

    /**
     * @return True when the JB is full.
     */
    public boolean isFull()
    {
        return q.remainingCapacity() == 0;
    }

    /**
     * @return True when the JB is empty
     */
    public boolean isEmpty()
    {
        return q.size() == 0;
    }

    /**
     * Drop the oldest packet in the JB.
     */
    public void dropOldest()
    {
        q.poll();
    }

    /**
     * Add a buffer to the JB
     *
     * @param buffer The buffer to add.
     */
    public void add(Buffer buffer)
    {
        boolean success = q.offer(buffer);
        if (!success)
        {
            Log.warning("Failed to add a buffer to jitter buffer. This is usually because it is full.");
        }
    }

    /**
     * Get a buffer from the jitter buffer, blocking until data is available.
     *
     * @return the buffer.
     */
    public Buffer get()
    {
        Buffer retVal = null;

        while (retVal == null)
        {
            try
            {
                retVal = q.take();
            }
            catch (InterruptedException e)
            {
            }
        }
        return retVal;
    }

    public int getMaxSize()
    {
        return maxCapacity;
    }

    public int getCurrentSize()
    {
        return q.size();
    }

    public void reset()
    {
        q.clear();
    }
}
