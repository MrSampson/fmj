package net.sf.fmj.media.rtp;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.media.*;

public class JitterBufferTester
{
	public static final AtomicInteger videoToDrop = new AtomicInteger(0);
	
	public static final AtomicInteger audioSilenceToInsert = new AtomicInteger(0);
	public static final AtomicInteger audioPacketsToDiscard = new AtomicInteger(0);
	
	public static final AtomicBoolean pleaseReset = new AtomicBoolean(false);
	public static final AtomicBoolean audioSilenceReplaceExisting = new AtomicBoolean(true);
	public static final AtomicBoolean audioDiscardAllowFec = new AtomicBoolean(true);
	public static final AtomicBoolean useSimpleJitterBuffer = new AtomicBoolean(false);
	
    private JitterBuffer q;
    private JitterBufferStats stats;

    public JitterBufferTester(JitterBuffer q, JitterBufferStats stats)
    {
        this.stats = stats;
        this.q = q;
    }
    
	public boolean replacedWithSilence(Buffer buffer) 
	{
		if (audioSilenceToInsert.get() > 0 && audioSilenceReplaceExisting.get())
		{
			audioSilenceToInsert.decrementAndGet();
			buffer.setFlags(Buffer.FLAG_SILENCE | Buffer.FLAG_SKIP_FEC);
//			stats.incrementSilenceInserted();
			return true;
		}
		
		return false;
	}

	public void addPacketsIfRequired() {
		if (!audioSilenceReplaceExisting.get()) {
			while (audioSilenceToInsert.get() > 0) {
				audioSilenceToInsert.decrementAndGet();
				Buffer newBuffer = new Buffer();
				newBuffer.setFlags(newBuffer.getFlags() | Buffer.FLAG_NO_DROP
						| Buffer.FLAG_SILENCE | Buffer.FLAG_SKIP_FEC);
//				stats.incrementSilenceInserted();
//				q.add(newBuffer);
			}
		}
	}

	public void dropPacketIfRequired() {
		if (audioPacketsToDiscard.get() > 0)
		{
			audioPacketsToDiscard.decrementAndGet();
//			q.dropOldest(!audioDiscardAllowFec.get());
//			stats.incrementDiscardedReset(1);
		}
	}
}
