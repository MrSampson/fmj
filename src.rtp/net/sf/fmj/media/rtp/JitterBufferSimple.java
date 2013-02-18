package net.sf.fmj.media.rtp;

import java.util.concurrent.*;

import javax.media.*;

public class JitterBufferSimple {

	final private LinkedBlockingQueue<Buffer> q;
	final private int maxCapacity;

	public JitterBufferSimple(int maxCapacity) {
		this.maxCapacity = maxCapacity;
		q = new LinkedBlockingQueue<Buffer>(maxCapacity);
	}

	public boolean isFull() {
		return q.remainingCapacity() == 0;
	}

	public boolean isEmpty() {
		return q.size() == 0;
	}

	public void dropOldest() {
		q.poll();
	}

	public void add(Buffer freeBuffer) {
		q.offer(freeBuffer);
		//TODO we're ignoring the return code here but we should log it.
	}

	/**
	 *  Blocking until data is available.
	 * @return
	 */
	public Buffer get() {
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

	public int getMaxSize() {
		return maxCapacity;
	}

	public int getCurrentSize() {
		return q.size();
	}

	public void reset() {
		q.clear();
	}
}
