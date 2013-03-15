
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
    public int                           maxCapacity;
    final private int                           fullRange      = 65536;
    final private int                           oneThirdRange  = fullRange / 3;
    final private int                           twoThirdsRange = fullRange -
                                                                 oneThirdRange;

    /**
     * Creates a new JB
     *
     * @param maxCapacity The most packets that the queue will hold.
     */
    public JitterBufferSimple(int maxCapacity)
    {
        this.maxCapacity = maxCapacity;
        q = new PriorityBlockingQueue<Buffer>(maxCapacity , new Comparator<Buffer>()
        {

            @Override
            public int compare(Buffer buf1, Buffer buf2)
            {
                // This needs to return 0  if buf1 == buf2
                //                    -ve  if buf1 <  buf2
                //                    +ve  if buf1 >  buf2
                // For example, if buf1 is 50 and buf2 is 53 then return -ve
                // When wrapping, e.g. buf1 is 65535 and buf2 is 2 then we
                // still need to return a -ve number (even though buf1 has a
                // greater magnitude)
                //
                // One way to achieve this wrapping behaviour is to add
                // the fullRange of sequenceNumbers if one of them has wrapped.
                long buf1SeqNo = buf1.getSequenceNumber();
                long buf2SeqNo = buf2.getSequenceNumber();

                if (buf1SeqNo < oneThirdRange && buf2SeqNo > twoThirdsRange)
                {
                    buf1SeqNo += fullRange;
                }

                if (buf2SeqNo < oneThirdRange && buf1SeqNo > twoThirdsRange)
                {
                    buf2SeqNo += fullRange;
                }

                int result = (int)(buf1SeqNo - buf2SeqNo);

                return result;
            }
        });
    }

    /**
     * @return True when the JB is full.
     */
    public boolean isFull()
    {
//        return q.remainingCapacity() == 0;
        return remainingCapacity() == 0;
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
        boolean success = false;
        success = q.offer(buffer);

        if (!success)
        {
            Log.warning("Failed to add a buffer to jitter buffer. This is usually because it is full.");
        }
    }

    private int remainingCapacity()
    {
     return maxCapacity - q.size();
//      q.remainingCapacity();
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
