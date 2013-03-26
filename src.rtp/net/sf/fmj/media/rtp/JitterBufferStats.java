// JitterBufferStats.java
// (C) COPYRIGHT METASWITCH NETWORKS 2013

package net.sf.fmj.media.rtp;

import java.awt.*;
import java.util.concurrent.atomic.*;

import javax.media.control.*;

import net.sf.fmj.media.*;

public class JitterBufferStats implements PacketQueueControl
{
    //TODO - be a bit more consistent about my use of atomic and non-atomic
    private int          nbDiscardedFull   = 0;
    private int          nbDiscardedShrink = 0;
    private int          nbDiscardedLate   = 0;
    private int          nbDiscardedReset  = 0;
    private int          nbTimesReset      = 0;
    private int          nbSilenceInserted = 0;
    AtomicInteger        totalPackets      = new AtomicInteger(0);

    private JitterBuffer q;

    public JitterBufferStats(JitterBuffer q)
    {
        this.q = q;
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

    void printStats()
    {
        String cn = this.getClass().getCanonicalName() + " ";
        Log.info(cn + "Packets dropped because full: " + nbDiscardedFull);
        Log.info(cn + "Packets dropped while shrinking: " + nbDiscardedShrink);
        Log.info(cn + "Packets dropped because they were late: " +
                 nbDiscardedLate);
        Log.info(cn + "Packets dropped in reset(): " + nbDiscardedReset);
        Log.info(cn + "Number of resets(): " + nbTimesReset);
    }

    ////////////////////////////////////////////////////////////////////////////
    // Getters for STATS - All public
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
        return nbDiscardedFull + nbDiscardedLate + nbDiscardedReset +
               nbDiscardedShrink;
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
        return q.getCurrentSize();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getCurrentSizePackets()
    {
        return q.maxCapacity;
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

    ////////////////////////////////////////////////////////////////////////////
    // Setters for STATS - All package private
    ////////////////////////////////////////////////////////////////////////////
    void incrementTotalPackets()
    {
        totalPackets.incrementAndGet();
    }

    void incrementDiscardedLate()
    {
        nbDiscardedLate++;
    }

    void incrementTimesReset()
    {
        nbTimesReset++;
    }

    void incrementDiscardedReset(int incrementBy)
    {
        nbDiscardedReset += incrementBy;
    }

    void incrementDiscardedFull()
    {
        nbDiscardedFull++;
    }

    void incrementDiscardedShrink()
    {
        nbDiscardedShrink++;
    }

    void incrementSilenceInserted()
    {
        nbSilenceInserted++;
    }
}
