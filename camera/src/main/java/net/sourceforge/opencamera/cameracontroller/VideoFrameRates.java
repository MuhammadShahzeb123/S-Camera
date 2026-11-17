package net.sourceforge.opencamera.cameracontroller;

import java.util.ArrayList;
import java.util.List;

/** Camera2 video throughput conversion, independent of Android for regression testing. */
public final class VideoFrameRates {
    private VideoFrameRates() { }

    public static List<int[]> forDuration(List<int[]> aeRanges, long minFrameDurationNs) {
        List<int[]> result = new ArrayList<>();
        if( aeRanges == null )
            return result;
        // HAL durations are rounded to whole nanoseconds: 16,666,667 ns is
        // nominally 60 FPS, not 59. An unknown duration is bounded by AE support.
        int max = minFrameDurationNs > 0
                ? (int)Math.min(Integer.MAX_VALUE, Math.round(1_000_000_000.0 / minFrameDurationNs))
                : Integer.MAX_VALUE;
        for(int[] range : aeRanges) {
            int upper = Math.min(range[1], max);
            if( range[0] <= upper )
                result.add(new int[]{range[0], upper});
        }
        return result;
    }
}
