
package net.sf.fmj.media.rtp;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.media.*;

import net.sf.fmj.media.*;

/**
 * A simple jitter buffer based on existing Java data structures.
 */
public class JitterBufferSimple
{
    final private PriorityBlockingQueue<Buffer> q;
    public int                           maxCapacity;
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
                long buf1SeqNo = buf1.getSequenceNumber();
                long buf2SeqNo = buf2.getSequenceNumber();
                return JitterBufferSimple.compareSeqNos(buf1SeqNo, buf2SeqNo);
            }
        });
    }

    /**
     * Calculate the ordering between two sequence numbers (dealing with
     * wrapping)
     *
     * Return 0  if buf1 == buf2
     *      -ve  if buf1 <  buf2
     *      +ve  if buf1 >  buf2
     *
     *  For example, if buf1 is 50 and buf2 is 53 then return -ve
     *  When wrapping, e.g. buf1 is 65535 and buf2 is 2 then we
     *  still need to return a -ve number (even though buf1 has a
     *  greater magnitude)
     *
     */
    private static int compareSeqNos(long buf1SeqNo, long buf2SeqNo)
    {
        int fullRange = 65536;
        int oneThirdRange = fullRange / 3;
        int twoThirdsRange = fullRange - oneThirdRange;

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

    AtomicLong lastSeqNo = new AtomicLong(-1);

    /**
     * Get a buffer from the jitter buffer, blocking until data is available.
     *
     * It will never return null.

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

        lastSeqNo.set(retVal.getSequenceNumber());
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

    public boolean theShipHasSailed(Buffer buffer)
    {
        if (lastSeqNo.get() == -1)
        {
            return false;
        }

        long incomingSeqNo = buffer.getSequenceNumber();
        long latestAcceptableSeqNo = lastSeqNo.get();

        int result = JitterBufferSimple.compareSeqNos(incomingSeqNo, latestAcceptableSeqNo);
        // A result of 0 indicates the numbers are the same - i.e. a dupe so
        // return true.
        // A positive number indicates that the incomingSeqNo was larger (i.e.
        // later) than the last one we sent. This is good and means we can
        // return false.
        // A negative number indicates the incomingSeqNo was smaller so we
        // should just throw it away, so return true.

        return result <= 0;
    }
}
