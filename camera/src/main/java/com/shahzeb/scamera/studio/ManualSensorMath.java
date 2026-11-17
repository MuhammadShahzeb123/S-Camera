package com.shahzeb.scamera.studio;

/** Android-independent validation of manual video sensor values. */
final class ManualSensorMath {
    static final class ExposureLimits {
        final boolean supported;
        final int minIso, maxIso;
        final long minTimeNs, maxTimeNs, frameDurationNs;
        ExposureLimits(int lensMinIso, int lensMaxIso, int routeMinIso, int routeMaxIso,
                       long lensMinTime, long lensMaxTime, long routeMinTime, long routeMaxTime,
                       long lensMaxFrame, long routeMaxFrame, int fps) {
            frameDurationNs = fps > 0 ? 1_000_000_000L / fps : 0;
            minIso = Math.max(lensMinIso, routeMinIso);
            maxIso = Math.min(lensMaxIso, routeMaxIso);
            minTimeNs = Math.max(lensMinTime, routeMinTime);
            maxTimeNs = Math.min(frameDurationNs, Math.min(lensMaxTime, routeMaxTime));
            supported = minIso > 0 && maxIso >= minIso && minTimeNs > 0 && maxTimeNs >= minTimeNs
                    && frameDurationNs > 0 && frameDurationNs <= Math.min(lensMaxFrame, routeMaxFrame);
        }
        int clampIso(int requested, int measured) {
            return Math.max(minIso, Math.min(maxIso, requested > 0 ? requested : measured > 0 ? measured : 100));
        }
        long clampTime(long requested, long measured) {
            return Math.max(minTimeNs, Math.min(maxTimeNs, requested > 0 ? requested
                    : measured > 0 ? measured : frameDurationNs / 2));
        }
    }
    static int evSteps(float ev, float step, int min, int max) {
        if( Float.isNaN(ev) || Float.isInfinite(ev) || !(step > 0) ) return 0;
        return Math.max(min, Math.min(max, Math.round(ev / step)));
    }

    /** Approximate black-body illuminant correction; Camera2 guarantees gains in [1,4]. */
    static float[] whiteBalanceGains(int kelvin) {
        double t = Math.max(2000, Math.min(10000, kelvin)) / 100.0;
        double red = t <= 66 ? 255 : 329.698727446 * Math.pow(t - 60, -0.1332047592);
        double green = t <= 66 ? 99.4708025861 * Math.log(t) - 161.1195681661
                : 288.1221695283 * Math.pow(t - 60, -0.0755148492);
        double blue = t >= 66 ? 255 : 138.5177312231 * Math.log(t - 10) - 305.0447927307;
        double scale = Math.max(red, Math.max(green, blue));
        float r = (float)Math.max(1, Math.min(4, scale / Math.max(1, red)));
        float g = (float)Math.max(1, Math.min(4, scale / Math.max(1, green)));
        float b = (float)Math.max(1, Math.min(4, scale / Math.max(1, blue)));
        return new float[]{r, g, g, b};
    }
}
