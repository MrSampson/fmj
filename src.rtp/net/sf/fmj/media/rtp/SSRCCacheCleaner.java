package net.sf.fmj.media.rtp;

import java.util.*;

import javax.media.rtp.*;
import javax.media.rtp.event.*;

import net.sf.fmj.media.Log;
import net.sf.fmj.media.rtp.util.*;

public class SSRCCacheCleaner implements Runnable
{
    private SSRCCache cache;
    private RTPMediaThread thread;
    private static final int DEATHTIME = 0x1b7740;
    private static final int TIMEOUT_MULTIPLIER = 5;
    boolean timeToClean;
    private boolean killed;
    private StreamSynch streamSynch;

    public SSRCCacheCleaner(SSRCCache cache, StreamSynch streamSynch)
    {
        timeToClean = false;
        killed = false;
        this.cache = cache;
        this.streamSynch = streamSynch;
        thread = new RTPMediaThread(this, "SSRC Cache Cleaner");
        thread.useControlPriority();
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void cleannow()
    {
        Log.annotate(this, "Enter cleannow");
        long time = System.currentTimeMillis();
        if (cache.ourssrc == null)
        {
            Log.annotate(this, "Exit right away - ourssrc is null");
            return;
        }

        double reportInterval
            = cache.calcReportInterval(cache.ourssrc.sender, true);
        for (Enumeration elements = cache.cache.elements();
                elements.hasMoreElements();)
        {
            SSRCInfo info = (SSRCInfo) elements.nextElement();
            if (!info.ours)
            {
                if (info.byeReceived)
                {
                    if (time - info.byeTime < 1000L)
                    {
                        try
                        {
                            long sleepTime = (1000L - time) + info.byeTime;
                            // TODO - use this.wait() rather than Thread.sleep()
                            // SGD: Do in 2.8 (too risky to change this in 2.7)
                            Log.annotate(this, "Sleep for " + sleepTime + "ms");
                            Thread.sleep(sleepTime);
                            //this.wait((1000L - time) + info.byeTime);
                        }
                        catch (InterruptedException e)
                        {
                            // TODO: What to do here? By default: as soon as
                            // we're interrupted, go ahead and do what we were
                            // going to do anyway.
                            // Other options: (1) just exit - don't do the
                            // things; (2) use a wait loop instead so we always
                            // wait for the expected time.
                            Log.info("Sleep interrupted");
                        }
                        time = System.currentTimeMillis();
                    }
                    info.byeTime = 0L;
                    info.byeReceived = false;
                    cache.remove(info.ssrc);
                    streamSynch.remove(info.ssrc);
                    boolean byepart = false;
                    RTPSourceInfo sourceInfo = info.sourceInfo;
                    if (sourceInfo != null && sourceInfo.getStreamCount() == 0)
                        byepart = true;
                    ByeEvent evtbye = null;
                    if (info instanceof RecvSSRCInfo)
                        evtbye = new ByeEvent(cache.sm, info.sourceInfo,
                                (ReceiveStream) info, info.byereason, byepart);
                    if (info instanceof PassiveSSRCInfo)
                        evtbye = new ByeEvent(cache.sm, info.sourceInfo, null,
                                info.byereason, byepart);
                    cache.eventhandler.postEvent(evtbye);
                }
                else if (info.lastHeardFrom + reportInterval <= time)
                {
                    InactiveReceiveStreamEvent event = null;
                    if (!info.inactivesent)
                    {
                        boolean laststream = false;
                        RTPSourceInfo si = info.sourceInfo;
                        if (si != null && si.getStreamCount() == 1)
                            laststream = true;
                        if (info instanceof ReceiveStream)
                        {
                            event = new InactiveReceiveStreamEvent(cache.sm,
                                    info.sourceInfo, (ReceiveStream) info,
                                    laststream);
                        } else
                        {
                            reportInterval *= 5D;
                            if (info.lastHeardFrom + reportInterval <= time)
                                event = new InactiveReceiveStreamEvent(
                                        cache.sm, info.sourceInfo, null,
                                        laststream);
                        }
                        if (event != null)
                        {
                            cache.eventhandler.postEvent(event);
                            info.quiet = true;
                            info.inactivesent = true;
                            info.setAlive(false);
                        }
                    }
                    /*
                     * 30 minutes without hearing from an SSRC sounded like an
                     * awful lot so it was reduced to what was considered a more
                     * reasonable value in practical situations.
                     */
                    else if (info.lastHeardFrom + (5 * 1000) <= time)
                    {
                        TimeoutEvent evt = null;
                        cache.remove(info.ssrc);
                        boolean byepart = false;
                        RTPSourceInfo sourceInfo = info.sourceInfo;
                        if (sourceInfo != null
                                && sourceInfo.getStreamCount() == 0)
                            byepart = true;
                        if (info instanceof ReceiveStream)
                            evt = new TimeoutEvent(cache.sm, info.sourceInfo,
                                    (ReceiveStream) info, byepart);
                        else
                            evt = new TimeoutEvent(cache.sm, info.sourceInfo,
                                    null, byepart);
                        cache.eventhandler.postEvent(evt);
                    }
                }
            }
        }

        Log.annotate(this, "Exit cleannow");
    }

    @Override
    public synchronized void run()
    {
        Log.annotate(this, "run");
        try
        {
            do
            {
                while (!timeToClean && !killed)
                    wait();
                if (killed)
                    return;
                cleannow();
                timeToClean = false;
            } while (true);
        } catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public synchronized void setClean()
    {
        Log.annotate(this, "setclean");
        timeToClean = true;
        notifyAll();
    }

    public synchronized void stop()
    {
        Log.annotate(this, "stop");
        killed = true;
        notifyAll();
    }
}
