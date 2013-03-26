// JitterBufferTester.java
// (C) COPYRIGHT METASWITCH NETWORKS 2013
package net.sf.fmj.media.rtp;

import javax.media.*;

public class JitterBufferTester
{
    public static boolean pleaseReset = false;
    boolean forceSilenceEnable = false;

    int forceSilencePackets = 5; //Silence to insert
    int forceSilenceGap = 45; //Gap between inserting silence
    long forceSilenceCounter = -forceSilenceGap; //Set to Long.MIN_VALUE to disable...

    private JitterBuffer q;
    private JitterBufferStats stats;

    public JitterBufferTester(JitterBuffer q, JitterBufferStats stats)
    {
        this.stats = stats;
        this.q = q;
    }

    public boolean silenceInserted(Buffer buffer)
    {

        forceSilenceCounter++;
       if (forceSilenceCounter >= forceSilencePackets){forceSilenceCounter = -forceSilenceGap;}

       if (forceSilenceEnable && forceSilenceCounter > 0)
       {
           buffer.setFlags(Buffer.FLAG_SILENCE | Buffer.FLAG_NO_FEC);
           stats.incrementSilenceInserted();
           return true;
       }

       return false;
    }

}
