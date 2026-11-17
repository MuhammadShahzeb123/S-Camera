package com.shahzeb.scamera.studio;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/** Device-independent zoom and stream policy for the optical video pipeline. */
public final class OpticalVideoPolicy {
    private OpticalVideoPolicy() { }

    public static final class Lens {
        public final String id;
        public final float nativeZoom;
        public final float maxDigital;
        public Lens(String id, float nativeZoom, float maxDigital) {
            this.id = id;
            this.nativeZoom = nativeZoom;
            this.maxDigital = maxDigital;
        }
    }

    public static Lens select(List<Lens> lenses, float zoom) {
        Lens shortest = null, best = null;
        for( Lens lens : lenses ) {
            if( shortest == null || lens.nativeZoom < shortest.nativeZoom ) shortest = lens;
            // A wider lens must remain active until the requested zoom reaches this lens's
            // native factor: below 1x is ultra-wide; below 3.3x is still main on the OnePlus.
            if( lens.nativeZoom <= zoom && (best == null || lens.nativeZoom > best.nativeZoom) ) best = lens;
        }
        return best == null ? shortest : best;
    }

    public static float maxZoom(List<Lens> lenses) {
        float max = 1f;
        for( Lens lens : lenses ) max = Math.max(max, lens.nativeZoom * Math.max(1f, lens.maxDigital));
        return max;
    }

    public static float digitalZoom(Lens lens, float globalZoom) {
        return lens == null ? 1f : Math.max(1f, globalZoom / lens.nativeZoom);
    }

    public static boolean supportsFrameDuration(long minNs, int fps) {
        if( fps <= 0 ) return false;
        // 29.97/59.94 and nanosecond rounding are normal for an advertised 30/60 fps.
        return minNs <= 0 || minNs <= Math.ceil(1_000_000_000.0 / fps) * 1.005;
    }

    public static boolean supportsFps(int[][] ranges, int fps) {
        if( ranges == null || fps <= 0 ) return false;
        for( int[] range : ranges )
            if( range != null && range.length == 2 && range[0] <= fps && range[1] >= fps ) return true;
        return false;
    }

    /** Only a received first frame can commit a requested optical lens. */
    public static final class ZoomState {
        private final List<Lens> lenses;
        private float desiredZoom;
        private Lens active;
        private float activeZoom;
        public ZoomState(List<Lens> lenses, float initialZoom) {
            if( lenses == null || lenses.isEmpty() ) throw new IllegalArgumentException("No lenses");
            this.lenses = Collections.unmodifiableList(new ArrayList<>(lenses));
            request(initialZoom);
            activeZoom = desiredZoom;
        }
        public void request(float zoom) {
            float min = select(lenses, 0f).nativeZoom;
            if( Float.isNaN(zoom) || Float.isInfinite(zoom) ) zoom = min;
            desiredZoom = Math.max(min, Math.min(maxZoom(lenses), zoom));
        }
        public Lens desiredLens() { return select(lenses, desiredZoom); }
        public float desiredZoom() { return desiredZoom; }
        public Lens activeLens() { return active; }
        public float activeZoom() { return activeZoom; }
        public boolean commit(Lens firstFrameLens) {
            if( firstFrameLens == null || !desiredLens().id.equals(firstFrameLens.id) ) return false;
            active = firstFrameLens;
            activeZoom = desiredZoom;
            return true;
        }
        public boolean rollback(Lens failedLens) {
            if( active == null || failedLens == null || !desiredLens().id.equals(failedLens.id) ) return false;
            desiredZoom = activeZoom;
            return true;
        }
    }
}
