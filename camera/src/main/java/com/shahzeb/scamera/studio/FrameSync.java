package com.shahzeb.scamera.studio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pairs frames from several cameras by sensor timestamp.
 *  Camera2 reports SENSOR_TIMESTAMP in one time base, so concurrent cameras (and physical
 *  streams of a logical camera) can be matched without a separate clock. */
public final class FrameSync {
    private FrameSync() {
    }

    public static final class Group {
        public final Map<String, Long> timestamps;
        public final long skewNs;

        Group(Map<String, Long> timestamps, long skewNs) {
            this.timestamps = timestamps;
            this.skewNs = skewNs;
        }

        public boolean complete(int cameras) {
            return timestamps.size() == cameras;
        }
    }

    /** Greedy match. Each anchor frame from {@code anchorId} pulls the closest unused frame
     *  from every other camera. A camera is left out of the group when nothing falls inside
     *  {@code toleranceNs}. Groups that miss a camera are still returned so the caller can see
     *  the gap; {@link Group#complete} is the strict check. */
    public static List<Group> align(Map<String, List<Long>> series, String anchorId, long toleranceNs) {
        List<Group> groups = new ArrayList<>();
        if( series == null || series.isEmpty() )
            return groups;
        String anchor = anchorId;
        if( anchor == null || !series.containsKey(anchor) ) {
            for(String id : series.keySet()) {
                anchor = id;
                break;
            }
        }
        List<Long> anchors = series.get(anchor);
        if( anchors == null )
            return groups;
        Map<String, Integer> cursor = new LinkedHashMap<>();
        for(String id : series.keySet())
            cursor.put(id, 0);
        for(long t : anchors) {
            Map<String, Long> picked = new LinkedHashMap<>();
            picked.put(anchor, t);
            long min = t;
            long max = t;
            for(Map.Entry<String, List<Long>> e : series.entrySet()) {
                if( e.getKey().equals(anchor) )
                    continue;
                List<Long> list = e.getValue();
                int i = cursor.get(e.getKey());
                int best = -1;
                long bestAbs = Long.MAX_VALUE;
                for(int k = i; k < list.size(); k++) {
                    long d = list.get(k) - t;
                    long abs = Math.abs(d);
                    if( d < -toleranceNs )
                        continue;
                    if( d > toleranceNs && abs > bestAbs )
                        break;
                    if( abs < bestAbs ) {
                        bestAbs = abs;
                        best = k;
                    }
                }
                if( best >= 0 && bestAbs <= toleranceNs ) {
                    long ts = list.get(best);
                    picked.put(e.getKey(), ts);
                    cursor.put(e.getKey(), best + 1);
                    if( ts < min )
                        min = ts;
                    if( ts > max )
                        max = ts;
                }
            }
            groups.add(new Group(picked, max - min));
        }
        return groups;
    }

    /** Skew between the newest sample of each id. Returns -1 when fewer than two ids are present
     *  or when the spread is larger than {@code toleranceNs} (the set is not one moment). */
    public static long liveSkewNs(Map<String, Long> latest, long toleranceNs) {
        if( latest == null || latest.size() < 2 )
            return -1L;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for(Long ts : latest.values()) {
            if( ts == null )
                continue;
            if( ts < min )
                min = ts;
            if( ts > max )
                max = ts;
        }
        if( min == Long.MAX_VALUE )
            return -1L;
        long skew = max - min;
        if( skew > toleranceNs )
            return -1L;
        return skew;
    }
}
