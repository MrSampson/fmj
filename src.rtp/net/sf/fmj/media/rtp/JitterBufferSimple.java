
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
    /**
     * The underlying datastore for the jitter buffer.
     *
     * It allows concurrent access, has blocking semantics and stores it's
     * objects in a priority order.
     */
    final private PriorityBlockingQueue<Buffer> q;

    /**
     * The max capacity of the jitter buffer in packets.
     */
    public int               maxCapacity;
    private final AtomicLong lastSeqNoReturned = new AtomicLong(Buffer.SEQUENCE_UNKNOWN);
    private final AtomicLong lastSeqNoAdded    = new AtomicLong(Buffer.SEQUENCE_UNKNOWN);

    /**
     * Creates a new JB
     *
     * @param maxCapacity The most packets that the queue will hold.
     */
    public JitterBufferSimple(int maxCapacity)
    {
        this.maxCapacity = maxCapacity;

        //The packets are stored in order of sequence number.
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
     * Add a buffer to the JB
     *
     * @param buffer The buffer to add.
     *
     * TODO - Race in the dupe detection code. But it's best effort so that's fine.
     */
    public void add(Buffer buffer)
    {
        boolean success = false;
        long seqNo = buffer.getSequenceNumber();

        if (seqNo == lastSeqNoAdded.get())
        {
            Log.warning(String.format("Dropping duplicate packet from jitter buffer with seqNo %s", seqNo));
        }
        else
        {
            success = q.offer(buffer);

            if (!success)
            {
                // TODO While we're using an unbounded queue, it's actually
                // impossible to hit this.
                Log.warning("Failed to add a buffer to jitter buffer. This is usually because it is full.");
            }
            else
            {
                if (lastSeqNoAdded.get() == Buffer.SEQUENCE_UNKNOWN)
                {
                    lastSeqNoAdded.set(seqNo);
                }
                else if (seqNo != Buffer.SEQUENCE_UNKNOWN &&
                        compareSeqNos(seqNo, lastSeqNoAdded.get()) > 0)
                {
                    // The seqNo we've just added is later than the last added one
                    // so update it.
                    lastSeqNoAdded.set(seqNo);
                }
            }
        }
    }

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
                //No need to do anything, we're in a while loop.
            }
        }

        long seqNo = retVal.getSequenceNumber();
        if (seqNo != Buffer.SEQUENCE_UNKNOWN)
        {
          lastSeqNoReturned.set(seqNo);
        }

        return retVal;
    }

    /**
     * Drop the oldest packet in the JB.
     */
    public void dropOldest()
    {
        //Remove the oldest element from the queue.
        Buffer buf = q.poll();
        if (buf != null)
        {
            Log.warning(String.format("Dropped oldest packet in jitter buffer with seqNo %s", buf.getSequenceNumber()));
        }

        //And update the next oldest element to say that it shouldn't get FECed
        buf = q.peek();

        if (buf != null)
        {
            buf.setFlags(buf.getFlags() | Buffer.FLAG_NO_FEC);
            Log.warning(String.format("Setting NO_FEC on packet in jitter buffer with seqNo %s", buf.getSequenceNumber()));
        }
    }

    /**
     * Removes all packets from the jitter buffer.
     */
    public void reset()
    {
        //TODO should this also clear all state?
        q.clear();
    }

    /**
     * @return The maximum capacity of the jitter buffer.
     */
    public int getMaxCapacity()
    {
        return maxCapacity;
    }

    /**
     * @return The current number of packets in the jitter buffer.
     */
    public int getCurrentSize()
    {
        return q.size();
    }

    /**
     * @return the last sequence number that the jitter buffer has returned.
     */
    public long getLastSeqNoReturned()
    {
        return lastSeqNoReturned.get();
    }

    /**
     * @return the last sequence number that was added to the jitter buffer.
     */
    public long getLastSeqNoAdded()
    {
        return lastSeqNoAdded.get();
    }

    /**
     * @return True when the JB is full.
     */
    public boolean isFull()
    {
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
     * @return The number of packets that can be stored in the jitter buffer
     * before it's full.
     */
    private int remainingCapacity()
    {
     return maxCapacity - q.size();
    }

    /**
     * Checks whether there's any point adding a packet to the jitter buffer.
     *
     * @param buffer The packet to check.
     * @return True when a more recent packet has already been returned by the
     * jitter buffer. False otherwise.
     */
    public boolean theShipHasSailed(Buffer buffer)
    {
        if (lastSeqNoReturned.get() == -1)
        {
            return false;
        }

        long incomingSeqNo = buffer.getSequenceNumber();
        long latestAcceptableSeqNo = lastSeqNoReturned.get();

        int result = compareSeqNos(incomingSeqNo, latestAcceptableSeqNo);

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
