package com.shahzeb.scamera.studio;

/** Picks a camera output size. Width/height are the sensor-buffer pair (usually landscape). */
public final class SizePick {
    private SizePick() {
    }

    public static final class Sz {
        public final int w;
        public final int h;

        public Sz(int w, int h) {
            this.w = w;
            this.h = h;
        }
    }

    /** Largest size at most {@code maxWidth} wide whose aspect is within 0.04 of {@code aspect}.
     *  Falls back to the closest aspect, then to the first size. */
    public static Sz choose(int [][] sizes, int maxWidth, double aspect) {
        if( sizes == null || sizes.length == 0 )
            return new Sz(1280, 720);
        Sz best = null;
        Sz closest = null;
        double closestDiff = Double.MAX_VALUE;
        for(int [] s : sizes) {
            if( s == null || s.length < 2 || s[0] <= 0 || s[1] <= 0 )
                continue;
            double a = s[0] / (double)s[1];
            double diff = Math.abs(a - aspect);
            if( diff < closestDiff ) {
                closestDiff = diff;
                closest = new Sz(s[0], s[1]);
            }
            if( s[0] > maxWidth || diff > 0.04 )
                continue;
            if( best == null || (long)s[0] * s[1] > (long)best.w * best.h )
                best = new Sz(s[0], s[1]);
        }
        if( best != null )
            return best;
        if( closest != null )
            return closest;
        return new Sz(sizes[0][0], sizes[0][1]);
    }
}
