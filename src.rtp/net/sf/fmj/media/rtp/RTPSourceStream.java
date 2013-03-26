package net.sf.fmj.media.rtp;

import java.util.concurrent.atomic.*;

import javax.media.*;
import javax.media.control.*;
import javax.media.format.*;
import javax.media.protocol.*;

import net.sf.fmj.media.*;
import net.sf.fmj.media.protocol.*;
import net.sf.fmj.media.protocol.rtp.DataSource;

/**
 * The RTPSource stream is passed packets, buffers them in a jitter buffer and
 * passes them onto a BufferTransferHandler<p>
 *
 * It can be in one of a number of states
 * <ul>
 * <li>Newly created/Closed - No thread running.</li>
 * <li>Started/Stopped - controls the started flag.</li>
 * <ul><li>The thread only does something when started=true</li>
 *     <li>Items are only added to the queue if started=true</li>
 * </ul>
 * <li>Connected - The thread is started</li>
 * </ul>
 * <p>
 * When the RTPSourceStream isn't started, packets can still be added to it.
 */
public class RTPSourceStream
    extends BasicSourceStream
    implements PushBufferStream
{
    private Format                format             = null;
    private AtomicBoolean         started            = new AtomicBoolean();

    public JitterBuffer     q;

    private JitterBufferBehaviour behaviour;
    private JitterBufferStats stats;
    private JitterBufferCharts charts;

    /**
     * cTor
     *
     * @param datasource The datasource that this stream is the source for.
     */
    public RTPSourceStream(DataSource datasource)
    {
        datasource.setSourceStream(this);

        Log.info(String.format("Creating RTPSourceStream %s for datasource %s (SSRC=%s)",
                               this.hashCode(),
                               datasource.hashCode(),
                               datasource.getSSRC()));

        q = new JitterBuffer(1);
        behaviour = new SimpleJitterBufferBehaviour(q, this);
        stats = new JitterBufferStats(q);
        charts = new JitterBufferCharts(datasource);
    }

    /**
     * Adds <tt>buffer</tt> to the queue.
     *
     * In case the queue is full: if <tt>buffer</tt>'s sequence number comes
     * before the sequence numbers of the <tt>Buffer</tt>s in the queue, nothing
     * is done.
     *
     * @param buffer the buffer to add
     */
    public void add(Buffer buffer)
    {
        behaviour.preAdd();
        stats.incrementTotalPackets();

        if (! started.get())
        {
            Log.warning(String.format("RTPSourceStream %s add() called but not started.", this.hashCode()));
        }

        if (q.theShipHasSailed(buffer))
        {
            // The packet is too late, the ship has sailed.
            // Dn't add the packet to the queue. This is equivalent to a
            // packet dropped by the network.
            stats.incrementDiscardedLate();
        }
        else
        {
            if (q.isFull())
            {
                Log.warning(String.format("RTPSourceStream %s buffer is full.", this.hashCode()));
                behaviour.handleFull();
            }

            Buffer newBuffer = (Buffer)buffer.clone();
            newBuffer.setFlags(newBuffer.getFlags() | Buffer.FLAG_NO_DROP);
            q.add(newBuffer);
        }
    }

    /**
     * Pops an element off the queue and copies it to <tt>buffer</tt>. The data
     * and header arrays of <tt>buffer</tt> are reused.
     *
     * @param buffer The <tt>Buffer</tt> object to copy an element of the queue
     * to.
     */
    @Override
    public void read(Buffer buffer)
    {
        if (charts.shouldChart())
        {
            charts.updateSize(q.getCurrentSize());
        }

        behaviour.read(buffer);
    }

    /**
     * Resets the queue, dropping all packets.
     */
    public void reset()
    {
        Log.info(String.format("reset() RTPSourceStream %s", this.hashCode()));
        stats.incrementDiscardedReset(q.getCurrentSize());
        stats.incrementTimesReset();
        q.reset();
    }

    @Override
    public Format getFormat()
    {
        return format;
    }

    /**
     * @param format
     */
    protected void setFormat(Format format)
    {
        this.format = format;
        Log.info(String.format("RTPSourceStream %s set format to %s.", this.hashCode(), format));

        if (format instanceof VideoFormat)
        {
            behaviour = new VideoJitterBufferBehaviour(q, this, stats);
        }
        else if (format instanceof AudioFormat)
        {
            behaviour = new AudioJitterBufferBehaviour(q, this, stats);
        }

        if (started.get())
        {
            behaviour.start();
        }
    }

    @Override
    public void setTransferHandler(BufferTransferHandler buffertransferhandler)
    {
        behaviour.setTransferHandler(buffertransferhandler);
    }

    /**
     * Puts the source stream into the "started" state
     */
    public void start()
    {
        Log.info(String.format("start() RTPSourceStream %s", this.hashCode()));
        started.set(true);
        behaviour.start();
    }

    /**
     * Puts the source stream in the "stop" state.
     *
     * Can be called multiple times.
     */
    public void stop()
    {
        Log.info(String.format("stop() RTPSourceStream %s", this.hashCode()));

        StringBuffer buf = new StringBuffer();
        for(StackTraceElement s : new Throwable().getStackTrace())
        {
          buf.append(s.toString());
          buf.append("\n");
        }

        Log.info(buf.toString());

        if (! started.get())
        {
            behaviour.stop();
        }

        started.set(false);
    }

    /**
     * Connects.<p>
     *
     * This does nothing.<p>
     *
     * We could allocate resources (start theads?) here to speed up the
     * start() method.
     */
    public void connect()
    {
        Log.info(String.format("connect() RTPSourceStream %s", this.hashCode()));
    }

    /**
     * Close the stream.<p>
     *
     * This does nothing<p>
     */
    public void close()
    {
        Log.info(String.format("close() RTPSourceStream %s", this.hashCode()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object getControl(String controlType)
    {
        if (PacketQueueControl.class.getName().equals(controlType))
        {
            return stats;
        }

        return super.getControl(controlType);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object[] getControls()
    {
        Object[] superControls = super.getControls();
        Object[] superControlsAndThis = new Object[superControls.length + 1];
        System.arraycopy(superControls, 0, superControlsAndThis, 0, superControls.length);
        superControlsAndThis[superControls.length] = this;
        return superControlsAndThis;
    }
}
