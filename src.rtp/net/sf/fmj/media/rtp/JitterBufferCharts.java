// JitterBufferCharts.java
// (C) COPYRIGHT METASWITCH NETWORKS 2013

package net.sf.fmj.media.rtp;

import info.monitorenter.gui.chart.*;
import info.monitorenter.gui.chart.traces.*;

import java.awt.*;

import net.sf.fmj.media.protocol.rtp.*;

public class JitterBufferCharts
{
    private DataSource datasource;
    public JitterBufferCharts(DataSource datasource)
    {
        this.datasource = datasource;
    }
    
    public JitterBufferCharts()
    {
    }

    public static Chart2D chart            = null;
    public static int     datapointsToKeep = 400;
    //    private ITrace2D intrace = null;
    //    private ITrace2D outtrace = null;
    private ITrace2D      sizetrace        = null;

    //    private long lastArrivalTimeNanos = System.nanoTime();
    //    private long lastDepartureTimeNanos = System.nanoTime();

    private boolean shouldChart()
    {
        if (sizetrace != null)
        {
            return true;
        }

        if (chart != null)
        {
            //          intrace = new Trace2DLtd(datapointsToKeep, String.valueOf(datasource.getSSRC() + " IN Delta (ms)"));
            //          intrace.setColor(Color.red);
            //
            //          outtrace = new Trace2DLtd(datapointsToKeep, String.valueOf(datasource.getSSRC() + " OUT Delta (ms)"));
            //          outtrace.setColor(Color.green);

            sizetrace = new Trace2DLtd(datapointsToKeep,
//                                       String.valueOf(datasource.getSSRC() +
                                                      "JB Size");
            sizetrace.setColor(Color.black);

            //          chart.addTrace(intrace);
            //          chart.addTrace(outtrace);
            chart.addTrace(sizetrace);

            return true;
        }

        return false;
    }

    public void updateSize(int size)
    {
    	if (shouldChart())
    	{
         sizetrace.addPoint(System.nanoTime(), size);
    	}
    }

    //  if (shouldChart())
    //  {
    //      long timeNow = System.nanoTime();
    ////    outtrace.addPoint(timeNow, (timeNow - lastDepartureTimeNanos)/1000000);
    //    sizetrace.addPoint(timeNow,q.getCurrentSize());
    //      lastDepartureTimeNanos = timeNow;
    //  }


}
