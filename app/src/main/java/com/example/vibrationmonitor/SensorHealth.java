package com.example.vibrationmonitor;

import java.util.ArrayDeque;

/** Diagnostic only: observes sensor timestamps without altering sampling, filtering or RAW storage. */
public final class SensorHealth {
    private final ArrayDeque<Long> window=new ArrayDeque<>();
    private long first=0,last=0,count=0,minGap=Long.MAX_VALUE,maxGap=0,lastDelay=0,nonMonotonic=0;
    public synchronized void reset(){window.clear();first=last=count=maxGap=lastDelay=nonMonotonic=0;minGap=Long.MAX_VALUE;}
    public synchronized void observe(long sensorNs,long arrivalNs){
        if(sensorNs<=0)return;
        if(last!=0&&sensorNs<=last){nonMonotonic++;return;}
        if(last>0){long gap=sensorNs-last;minGap=Math.min(minGap,gap);maxGap=Math.max(maxGap,gap);}
        if(first==0)first=sensorNs;
        last=sensorNs;count++;lastDelay=Math.max(0,arrivalNs-sensorNs);
        window.addLast(sensorNs);
        while(window.size()>2&&sensorNs-window.peekFirst()>5000000000L)window.removeFirst();
        while(window.size()>4096)window.removeFirst();
    }
    public synchronized Snapshot snapshot(long nowNs){
        long windowNs=window.size()>1?window.peekLast()-window.peekFirst():0;
        double hz=windowNs>0?(window.size()-1)*1e9/windowNs:0;
        return new Snapshot(count,hz,first==0?0:(last-first)/1e9,last==0?-1:Math.max(0,(nowNs-last)/1000000L),
            minGap==Long.MAX_VALUE?0:minGap/1e6,maxGap/1e6,lastDelay/1e6,nonMonotonic);
    }
    public static final class Snapshot {
        public final long count,ageMs,nonMonotonic;
        public final double hz,seconds,minGapMs,maxGapMs,deliveryDelayMs;
        Snapshot(long c,double h,double s,long a,double min,double max,double delay,long bad){count=c;hz=h;seconds=s;ageMs=a;minGapMs=min;maxGapMs=max;deliveryDelayMs=delay;nonMonotonic=bad;}
    }
}
