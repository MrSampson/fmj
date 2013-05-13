package net.sf.fmj.utility.charting;

import info.monitorenter.gui.chart.IAxis;
import info.monitorenter.gui.chart.ITrace2D;
import info.monitorenter.gui.chart.traces.Trace2DLtd;

import java.awt.Color;

public class TraceDescription {

	ITrace2D trace = null;
	long lastArrivalTimeNanos;
	IAxis<?> xAxis = null;
	IAxis<?> yAxis = null;

	public TraceDescription(int datapointsToKeep, String name, Color color,
			IAxis<?> xAxis, IAxis<?> yAxis) {
		super();
		this.xAxis = xAxis;
		this.yAxis = yAxis;
		lastArrivalTimeNanos = System.nanoTime();
		trace = new Trace2DLtd(datapointsToKeep, name);
		trace.setColor(color);
	}
}
