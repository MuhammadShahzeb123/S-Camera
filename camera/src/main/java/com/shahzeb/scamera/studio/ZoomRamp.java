package com.shahzeb.scamera.studio;

/** Time-based zoom interpolation, independent of camera frame timestamps. */
final class ZoomRamp {
    private float start = 1f;
    private float target = 1f;
    private long startedNanos, durationNanos;
    private double initialSlope;

    void reset(float zoom) {
        check(zoom);
        start = target = zoom;
        durationNanos = 0;
        initialSlope = 0;
    }

    void to(float zoom, long nowNanos) {
        check(zoom);
        if( zoom == target ) return;
        double velocity = velocity(nowNanos);
        start = value(nowNanos);
        target = zoom;
        startedNanos = nowNanos;
        // Equal magnification ratios have equal timing; short drags remain responsive.
        double distance = Math.log(target / (double)start);
        double octaves = Math.abs(distance / Math.log(2));
        durationNanos = (long)(Math.min(480, 120 + 180 * octaves) * 1_000_000);
        // Hermite interpolation preserves motion through a stream of gesture updates.
        // Clamp its initial tangent to keep the path monotonic; a reversal starts at rest.
        initialSlope = distance == 0 ? 0 : Math.max(0, Math.min(3, velocity * durationNanos / distance));
    }

    float value(long nowNanos) {
        if( !isRunning(nowNanos) ) return target;
        double t = Math.max(0, (nowNanos - startedNanos) / (double)durationNanos);
        double eased = t * t * (3 - 2 * t) + initialSlope * t * (1 - t) * (1 - t);
        return (float)(start * Math.exp(Math.log(target / (double)start) * eased));
    }

    private double velocity(long nowNanos) {
        if( !isRunning(nowNanos) ) return 0;
        double t = Math.max(0, (nowNanos - startedNanos) / (double)durationNanos);
        double derivative = 6 * t * (1 - t) + initialSlope * (3 * t * t - 4 * t + 1);
        return Math.log(target / (double)start) * derivative / durationNanos;
    }

    boolean isRunning(long nowNanos) {
        return durationNanos > 0 && nowNanos - startedNanos < durationNanos;
    }

    static void check(float zoom) {
        if( Float.isNaN(zoom) || Float.isInfinite(zoom) || zoom <= 0 )
            throw new IllegalArgumentException("Zoom must be finite and positive");
    }
}
