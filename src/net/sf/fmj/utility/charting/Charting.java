package net.sf.fmj.utility.charting;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JFrame;

import info.monitorenter.gui.chart.Chart2D;
import info.monitorenter.gui.chart.IAxis;
import info.monitorenter.gui.chart.IAxis.AxisTitle;
import info.monitorenter.gui.chart.IAxisScalePolicy;
import info.monitorenter.gui.chart.ITrace2D;
import info.monitorenter.gui.chart.axis.AxisLinear;
import info.monitorenter.gui.chart.rangepolicies.RangePolicyMinimumViewport;
import info.monitorenter.gui.chart.traces.Trace2DLtd;
import info.monitorenter.util.Range;

public class Charting 
{
	public static Chart2D chart = null;
	private static boolean initPerformed = false;

	private static List<TraceDescription> traces = new ArrayList<TraceDescription>();
	
	private static boolean chartingEnabled()
	{
		return (chart != null);
	}
	
	private synchronized static boolean shouldChart()
	{
		boolean retVal = false;
		if (initPerformed)
		{
			retVal = true;
		}
		else if (chartingEnabled())
		{
			initializeTraces();
			initPerformed = true;
			return true;
		}
		else if (!chartingEnabled() && initPerformed)
		{
			//Charting has been turned off - uninit
			initPerformed = false;
		}

		return retVal;
	}
	static TraceDescription wasapiDeltaTrace;
	static TraceDescription wasapiBytesWrittenTrace;
	static TraceDescription jbSizeInPacketsTrace;
	static TraceDescription jbCapacityInPacketsTrace;

	private static void initializeTraces() 
	{
        AxisLinear<IAxisScalePolicy> yAxisMs = new AxisLinear<IAxisScalePolicy>();
        chart.setAxisYLeft(yAxisMs,0);
        yAxisMs.setRangePolicy(new RangePolicyMinimumViewport(new Range(0.0,40.0)));
        yAxisMs.setAxisTitle(new AxisTitle("ms"));
        
		AxisLinear<IAxisScalePolicy> yAxisBytes = new AxisLinear<IAxisScalePolicy>();
		chart.setAxisYRight(yAxisBytes,0);
        yAxisBytes.setRangePolicy(new RangePolicyMinimumViewport(new Range(0.0,2000.0)));
        yAxisBytes.setAxisTitle(new AxisTitle("bytes"));
        
//        AxisLinear<IAxisScalePolicy> yAxisPackets = new AxisLinear<IAxisScalePolicy>();
//		chart.addAxisYRight(yAxisPackets);
//		yAxisPackets.setRangePolicy(new RangePolicyMinimumViewport(new Range(0.0,40.0)));
//        yAxisPackets.setAxisTitle(new AxisTitle("packets"));
        
		wasapiDeltaTrace = new TraceDescription(400, "Wasapi - Interarrival Time (ms)", Color.BLUE, chart.getAxisX(), yAxisMs);
		wasapiBytesWrittenTrace = new TraceDescription(400, "Wasapi - Bytes Written", Color.RED, chart.getAxisX(), yAxisBytes);
		jbSizeInPacketsTrace= new TraceDescription(400, "Jitter Buffer Size", Color.BLACK, chart.getAxisX(), yAxisMs);
		jbCapacityInPacketsTrace= new TraceDescription(400, "Jitter Buffer Capacity", Color.DARK_GRAY, chart.getAxisX(), yAxisMs);
		
		traces.add(wasapiDeltaTrace);
		traces.add(wasapiBytesWrittenTrace);
		traces.add(jbSizeInPacketsTrace);
		traces.add(jbCapacityInPacketsTrace);
		
        for (TraceDescription aTrace : traces)
        {
        	chart.addTrace(aTrace.trace, aTrace.xAxis, aTrace.yAxis);
        }
	}

	public static void wroteToWasapi(int bytesWritten)
	{
		if (shouldChart())
		{
			long timeNow = System.nanoTime();
			wasapiDeltaTrace.trace.addPoint(timeNow, (timeNow - wasapiDeltaTrace.lastArrivalTimeNanos)/1000000);
			wasapiBytesWrittenTrace.trace.addPoint(timeNow, bytesWritten);

			wasapiDeltaTrace.lastArrivalTimeNanos = timeNow;
		}
	}
	
	public static void jbQueueSizeChanged(int jbSize, int capacity)
	{
		if (shouldChart())
		{
			long timestamp = System.nanoTime();
			jbSizeInPacketsTrace.trace.addPoint(timestamp, jbSize);
			jbCapacityInPacketsTrace.trace.addPoint(timestamp, capacity);
		}
	}

	public static void createChart(JFrame chartFrame) {
        chart = new Chart2D();
        chartFrame.getContentPane().add(chart);
	}
}
