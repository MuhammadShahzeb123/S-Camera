package net.sourceforge.opencamera.studio;

import net.sourceforge.opencamera.op.CameraMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Decides which lenses a studio mode should open, and in what order to fall back when the
 *  camera HAL refuses a combination. Pure data so it can be tested without a phone. */
public final class LensPlanner {
    public static final int FACING_FRONT = 0;
    public static final int FACING_BACK = 1;

    private LensPlanner() {
    }

    public static final class LensRef {
        public final String id;
        public final String directId;
        public final boolean hidden;
        public final int facing;
        public final float zoomFactor;
        public final boolean mono;
        public final List<String> logicalParents;
        /** When this ref is itself a logical camera, the physical camera ids it contains. */
        public final List<String> physicalIds;
        public final float maxDigitalZoom;
        public final float minZoomRatio;
        public final String label;
        public final int sensorOrientation;
        public final boolean flash;

        public LensRef(String id, String directId, boolean hidden, int facing, float zoomFactor, boolean mono,
                       List<String> logicalParents, List<String> physicalIds, float maxDigitalZoom, String label,
                       int sensorOrientation, boolean flash) {
            this(id, directId, hidden, facing, zoomFactor, mono, logicalParents, physicalIds,
                    maxDigitalZoom, label, sensorOrientation, flash, 1.0f);
        }

        public LensRef(String id, String directId, boolean hidden, int facing, float zoomFactor, boolean mono,
                       List<String> logicalParents, List<String> physicalIds, float maxDigitalZoom, String label,
                       int sensorOrientation, boolean flash, float minZoomRatio) {
            this.id = id;
            this.directId = directId;
            this.hidden = hidden;
            this.facing = facing;
            this.zoomFactor = zoomFactor <= 0.0f ? 1.0f : zoomFactor;
            this.mono = mono;
            this.logicalParents = logicalParents == null ? Collections.<String>emptyList() : logicalParents;
            this.physicalIds = physicalIds == null ? Collections.<String>emptyList() : physicalIds;
            this.maxDigitalZoom = maxDigitalZoom < 1.0f ? 1.0f : maxDigitalZoom;
            this.minZoomRatio = minZoomRatio > 0.0f ? minZoomRatio : 1.0f;
            this.label = label == null ? id : label;
            this.sensorOrientation = sensorOrientation;
            this.flash = flash;
        }

        public boolean isBack() {
            return facing == FACING_BACK;
        }

        public boolean isFront() {
            return facing == FACING_FRONT;
        }

        /** Copy that is opened as a physical stream of {@code parent} instead of its own device. */
        public LensRef asPhysicalOf(String parent) {
            return new LensRef(id, null, false, facing, zoomFactor, mono,
                    Collections.singletonList(parent), physicalIds, maxDigitalZoom, label, sensorOrientation, flash, minZoomRatio);
        }

        public String openId() {
            if( directId != null && !hidden )
                return directId;
            if( !logicalParents.isEmpty() )
                return logicalParents.get(0);
            return null;
        }

        /** Non-null when this lens must be requested with OutputConfiguration.setPhysicalCameraId. */
        public String physicalId() {
            if( directId != null && !hidden )
                return null;
            if( !logicalParents.isEmpty() )
                return id;
            return null;
        }
    }

    public static final class Plan {
        public final List<LensRef> cameras;
        /** All selectable lenses, including those not currently open in a single-camera mode. */
        public final List<LensRef> availableLenses;
        public final int primaryIndex;
        public final float [] stops;
        public final float minZoom;
        public final float maxZoom;
        public final boolean logicalZoom;
        public final String summary;

        Plan(List<LensRef> cameras, List<LensRef> availableLenses, int primaryIndex, float [] stops, float minZoom, float maxZoom, boolean logicalZoom, String summary) {
            this.cameras = cameras;
            this.availableLenses = Collections.unmodifiableList(new ArrayList<>(availableLenses));
            this.primaryIndex = primaryIndex;
            this.stops = stops;
            this.minZoom = minZoom;
            this.maxZoom = maxZoom;
            this.logicalZoom = logicalZoom;
            this.summary = summary;
        }
    }

    /** Marketing zoom labels for the OnePlus 9 Pro (lemonadep). The HAL often reports the same
     *  focal length for every sensor, so the 0.6 / 1 / 3.3 pills would otherwise collapse. */
    public static Float knownZoomFactor(String manufacturer, String device, String model, String id) {
        if( id == null )
            return null;
        String man = lower(manufacturer);
        String d = lower(device);
        String m = lower(model);
        boolean onePlus = man.contains("oneplus") || man.contains("oplus") || d.contains("lemon") || d.contains("oneplus");
        if( !onePlus )
            return null;
        boolean pro9 = d.contains("lemonadep") || m.contains("le212") || m.contains("9 pro") || m.contains("9pro");
        if( !pro9 )
            return null;
        if( "0".equals(id) )
            return 1.0f;
        if( "1".equals(id) )
            return 1.0f;
        if( "2".equals(id) )
            return 0.6f;
        if( "3".equals(id) )
            return 3.3f;
        return null;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US);
    }

    public static Plan plan(CameraMode mode, List<LensRef> sensors, List<LensRef> logicals, boolean wantFront, float zoom) {
        List<LensRef> backs = usable(sensors, FACING_BACK);
        List<LensRef> fronts = usable(sensors, FACING_FRONT);
        boolean multi = mode == CameraMode.MULTI_CAM || mode == CameraMode.DUAL_VIEW;
        LensRef requestedLogical = matchingLogical(logicals, wantFront ? FACING_FRONT : FACING_BACK);
        List<LensRef> pool = wantFront ? fronts : backs;
        if( pool.isEmpty() && (multi || requestedLogical == null || requestedLogical.openId() == null) )
            pool = wantFront ? backs : fronts;
        List<LensRef> chosen = new ArrayList<>();
        boolean logicalZoom = false;
        if( !multi ) {
            int facing = pool.isEmpty() ? (wantFront ? FACING_FRONT : FACING_BACK) : pool.get(0).facing;
            LensRef logical = matchingLogical(logicals, facing);
            if( logical == null && pool.isEmpty() )
                logical = matchingLogical(logicals, wantFront ? FACING_BACK : FACING_FRONT);
            // A large digital zoom alone does not mean a logical camera can reach the
            // ultra-wide lens. Use automatic switching only when its range covers the pills.
            boolean coversStops = logical != null && (pool.isEmpty() ||
                    (logical.minZoomRatio <= pool.get(0).zoomFactor &&
                     logical.maxDigitalZoom >= pool.get(pool.size() - 1).zoomFactor));
            if( coversStops && logical.openId() != null ) {
                chosen.add(logical);
                logicalZoom = true;
            }
            else if( !pool.isEmpty() ) {
                chosen.add(lensForZoom(pool, zoom));
            }
        }
        else if( mode == CameraMode.DUAL_VIEW ) {
            if( wantFront && !fronts.isEmpty() && !backs.isEmpty() ) {
                chosen.add(lensForZoom(backs, 1.0f));
                chosen.add(lensForZoom(fronts, 1.0f));
            }
            else if( !wantFront && backs.size() >= 2 ) {
                LensRef main = lensForZoom(backs, zoom);
                LensRef other = farthest(backs, main);
                chosen.add(main);
                if( other != null && !other.id.equals(main.id) )
                    chosen.add(other);
            }
            else if( !backs.isEmpty() && !fronts.isEmpty() ) {
                chosen.add(lensForZoom(backs, 1.0f));
                chosen.add(fronts.get(0));
            }
            else if( !pool.isEmpty() ) {
                chosen.add(pool.get(0));
            }
        }
        else {
            // MULTI_CAM: every back lens, ultra-wide first. Front is added only when the user flips.
            if( wantFront && !fronts.isEmpty() ) {
                chosen.add(lensForZoom(fronts, 1.0f));
                if( !backs.isEmpty() )
                    chosen.add(lensForZoom(backs, 1.0f));
            }
            else if( !backs.isEmpty() ) {
                chosen.addAll(backs);
            }
            else if( !fronts.isEmpty() ) {
                chosen.add(fronts.get(0));
            }
        }
        int primary = 0;
        if( !chosen.isEmpty() && !logicalZoom ) {
            primary = indexForZoom(chosen, zoom <= 0.0f ? 1.0f : zoom, wantFront ? FACING_FRONT : FACING_BACK);
        }
        List<LensRef> stopSource = pool.isEmpty() ? chosen : pool;
        if( logicalZoom && !chosen.isEmpty() ) {
            // pills still come from the physical lenses so 0.6 / 1 / 3.3 show up
            List<LensRef> physical = wantFront ? fronts : backs;
            if( !physical.isEmpty() )
                stopSource = physical;
        }
        float [] stops = stopsFor(stopSource);
        float minZ = stops.length > 0 ? stops[0] : 1.0f;
        float maxZ = 8.0f;
        if( logicalZoom && !chosen.isEmpty() )
            maxZ = Math.max(chosen.get(0).maxDigitalZoom, minZ);
        else if( !chosen.isEmpty() )
            maxZ = Math.max(maxZ, chosen.get(chosen.size() - 1).zoomFactor * Math.min(chosen.get(primary).maxDigitalZoom, 8.0f));
        if( maxZ < minZ + 0.05f )
            maxZ = minZ + 0.05f;
        float z = zoom <= 0.0f ? (stops.length > 0 ? nearestStop(stops, 1.0f) : 1.0f) : zoom;
        if( z < minZ )
            z = minZ;
        if( z > maxZ )
            z = maxZ;
        return new Plan(chosen, stopSource, primary, stops, minZ, maxZ, logicalZoom, summary(chosen));
    }

    /** Longest lens whose native zoom is still at or under {@code zoom}. */
    public static LensRef lensForZoom(List<LensRef> lenses, float zoom) {
        if( lenses.isEmpty() )
            return null;
        LensRef smallest = lenses.get(0);
        LensRef best = null;
        for(LensRef lens : lenses) {
            if( lens.zoomFactor < smallest.zoomFactor )
                smallest = lens;
            if( lens.zoomFactor <= zoom + 0.02f && (best == null || lens.zoomFactor > best.zoomFactor) )
                best = lens;
        }
        return best != null ? best : smallest;
    }

    public static int indexForZoom(List<LensRef> lenses, float zoom) {
        return indexForZoom(lenses, zoom, -1);
    }

    /** Equal optical zooms can belong to the front and rear camera in Dual-view. */
    public static int indexForZoom(List<LensRef> lenses, float zoom, int preferredFacing) {
        if( lenses.isEmpty() )
            return -1;
        LensRef want = lensForZoom(lenses, zoom);
        for(int i = 0; i < lenses.size(); i++) {
            LensRef lens = lenses.get(i);
            if( lens.facing == preferredFacing && Math.abs(lens.zoomFactor - want.zoomFactor) < .001f )
                return i;
        }
        for(int i = 0; i < lenses.size(); i++) {
            if( lenses.get(i).id.equals(want.id) )
                return i;
        }
        return 0;
    }

    public static boolean needsLensSwitch(Plan plan, List<LensRef> opened, float zoom, int preferredFacing) {
        if( plan == null || plan.logicalZoom || plan.availableLenses.isEmpty() )
            return false;
        LensRef wanted = plan.availableLenses.get(indexForZoom(plan.availableLenses, zoom, preferredFacing));
        for(LensRef lens : opened) {
            if( lens.id.equals(wanted.id) )
                return false;
        }
        return true;
    }

    public static float digitalRatio(LensRef lens, float zoom) {
        if( lens == null || lens.zoomFactor <= 0.0f )
            return 1.0f;
        float ratio = zoom / lens.zoomFactor;
        if( ratio < 1.0f )
            ratio = 1.0f;
        if( ratio > lens.maxDigitalZoom )
            ratio = lens.maxDigitalZoom;
        return ratio;
    }

    /** Subsets to try, primary first. Full set, then primary paired with each other lens
     *  (tele before ultra-wide), then primary alone. */
    public static List<int[]> openAttempts(int n, int primary) {
        List<int[]> out = new ArrayList<>();
        if( n <= 0 )
            return out;
        if( primary < 0 || primary >= n )
            primary = 0;
        List<Integer> rest = new ArrayList<>();
        for(int i = n - 1; i >= 0; i--) {
            if( i != primary )
                rest.add(i);
        }
        int [] full = new int[1 + rest.size()];
        full[0] = primary;
        for(int i = 0; i < rest.size(); i++)
            full[i + 1] = rest.get(i);
        out.add(full);
        if( rest.size() >= 2 ) {
            for(int r : rest)
                out.add(new int[]{primary, r});
        }
        if( n > 1 )
            out.add(new int[]{primary});
        return out;
    }

    /** Logical camera whose physical ids cover at least two of {@code lenses}, or null. */
    public static String commonLogicalParent(List<LensRef> lenses, List<LensRef> logicals) {
        if( lenses == null || lenses.size() < 2 || logicals == null )
            return null;
        String best = null;
        int bestCount = 1;
        for(LensRef logical : logicals) {
            int count = 0;
            for(LensRef lens : lenses) {
                if( logical.physicalIds.contains(lens.id) )
                    count++;
            }
            if( count > bestCount && logical.openId() != null ) {
                bestCount = count;
                best = logical.openId();
            }
        }
        return best;
    }

    private static LensRef matchingLogical(List<LensRef> logicals, int facing) {
        if( logicals == null )
            return null;
        for(LensRef logical : logicals) {
            if( logical.facing == facing && logical.openId() != null )
                return logical;
        }
        return null;
    }

    private static LensRef farthest(List<LensRef> lenses, LensRef from) {
        LensRef best = null;
        float bestD = -1.0f;
        for(LensRef lens : lenses) {
            float d = Math.abs(lens.zoomFactor - from.zoomFactor);
            if( d > bestD ) {
                bestD = d;
                best = lens;
            }
        }
        return best;
    }

    private static List<LensRef> usable(List<LensRef> sensors, int facing) {
        List<LensRef> list = new ArrayList<>();
        if( sensors == null )
            return list;
        for(LensRef lens : sensors) {
            if( lens.facing != facing || lens.mono )
                continue;
            if( lens.openId() == null )
                continue;
            boolean dup = false;
            for(LensRef have : list) {
                if( Math.abs(have.zoomFactor - lens.zoomFactor) < 0.04f && have.facing == lens.facing )
                    dup = true;
            }
            if( !dup )
                list.add(lens);
        }
        Collections.sort(list, (a, b) -> Float.compare(a.zoomFactor, b.zoomFactor));
        return list;
    }

    private static float [] stopsFor(List<LensRef> lenses) {
        List<Float> stops = new ArrayList<>();
        for(LensRef lens : lenses) {
            float z = Math.round(lens.zoomFactor * 10.0f) / 10.0f;
            boolean dup = false;
            for(float s : stops) {
                if( Math.abs(s - z) < 0.04f )
                    dup = true;
            }
            if( !dup )
                stops.add(z);
        }
        if( stops.isEmpty() )
            stops.add(1.0f);
        Collections.sort(stops);
        float [] out = new float[stops.size()];
        for(int i = 0; i < out.length; i++)
            out[i] = stops.get(i);
        return out;
    }

    private static float nearestStop(float [] stops, float zoom) {
        float best = stops[0];
        float bestD = Math.abs(best - zoom);
        for(float s : stops) {
            float d = Math.abs(s - zoom);
            if( d < bestD ) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    public static String summary(List<LensRef> lenses) {
        if( lenses == null || lenses.isEmpty() )
            return "No camera";
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < lenses.size(); i++) {
            if( i > 0 )
                sb.append(" · ");
            sb.append(lenses.get(i).label);
        }
        return sb.toString();
    }
}
