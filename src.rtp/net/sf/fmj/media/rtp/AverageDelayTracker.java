// AverageDelayTracker.java
// (C) COPYRIGHT METASWITCH NETWORKS 2013
package net.sf.fmj.media.rtp;

public class AverageDelayTracker
{

    long packetCount = 0;
    long delaySum = 0;

    //TODO Thread safety and atomic operations...

    public AverageDelayTracker()
    {
    }

    void updateAverageDelayWithEmptyBuffer()
    {
        //Simply update the total packet count seen. The delay from JB is 0
        // (since it was empty) so don't increment packets seen.
        packetCount++;
    }

    public double getAverageDelayInPacketsAndReset()
    {
        double delay = (double)delaySum / (double)packetCount;
        delaySum = 0;
        packetCount = 0;

        return delay;
    }

    public void updateAverageDelay(long delay)
    {
        //TODO input sanitization?
        packetCount++;
        delaySum += delay;
    }
}
