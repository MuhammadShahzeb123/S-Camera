package net.sourceforge.opencamera.op;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Discovers every camera sensor the phone exposes to this app: top level camera IDs, physical
 *  cameras behind logical multi-cameras, and (optionally) hidden IDs that respond to probing.
 *  Works out 35mm equivalent focal lengths so lenses can be labelled like the OnePlus camera
 *  (0.6x ultra wide, 1x main, 3.3x tele).
 */
public class CameraInventory {
    private static final String TAG = "CameraInventory";

    public static class Lens {
        /** Sensor id: top level camera id, or the physical camera id. */
        public String id;
        /** Top level id that can be opened directly, or null if only reachable as a physical camera. */
        public String directId;
        /** Logical camera ids that contain this sensor as a physical camera. */
        public final List<String> logicalParents = new ArrayList<>();
        public boolean isLogical;
        public boolean hidden;
        public int facing;
        public float focalLength;
        public float eqFocal;
        public float zoomFactor = 1.0f;
        public boolean isMono;
        public int sensorOrientation;
        public int hwLevel = -1;
        public float minZoomRatio = 1.0f;
        public float maxZoomRatio = 1.0f;
        public float maxDigitalZoom = 1.0f;
        public String [] physicalIds = new String[0];
        public int syncType = -1;
        /** Index into CameraManager.getCameraIdList() (Open Camera's integer camera id), or -1. */
        public int ocIndex = -1;
        public boolean manualSensor;
        public boolean rawCapable;
        public boolean highSpeedVideo;
        public Range<Long> exposureRange;
        public Range<Integer> isoRange;
        public float minFocusDistance;
        public Range<Integer> [] fpsRanges;
        public Size [] privSizes = new Size[0];
        public CameraCharacteristics characteristics;

        public boolean isBack() {
            return facing == CameraMetadata.LENS_FACING_BACK;
        }

        public boolean isFront() {
            return facing == CameraMetadata.LENS_FACING_FRONT;
        }

        /** Short label used in UI: UW, W, T, Mono, Front... */
        public String shortLabel() {
            if( isLogical )
                return isFront() ? "Front (auto)" : "Auto";
            String base;
            if( isMono )
                base = "Mono";
            else if( zoomFactor < 0.8f )
                base = "UW";
            else if( zoomFactor > 1.5f )
                base = "Tele";
            else
                base = isFront() ? "Front" : "Main";
            if( isFront() && !base.equals("Front") )
                base = "Front " + base;
            return base;
        }

        public String longLabel() {
            String z = OpFormat.zoom(zoomFactor);
            if( isLogical )
                return (isFront() ? "Front" : "Back") + " logical camera (auto lens)";
            String kind;
            if( isMono )
                kind = "Monochrome";
            else if( zoomFactor < 0.8f )
                kind = "Ultra wide";
            else if( zoomFactor > 1.5f )
                kind = "Telephoto";
            else
                kind = isFront() ? "Front" : "Main";
            return kind + " " + z + " (" + Math.round(eqFocal) + "mm)";
        }

        /** Largest SurfaceTexture size with the given aspect ratio that can stream at >= fps. */
        public Size bestPrivSize(double aspect, int maxWidth, int fps) {
            Size best = null;
            StreamConfigurationMap map = characteristics == null ? null : characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            for(Size s : privSizes) {
                if( s.getWidth() > maxWidth )
                    continue;
                double a = s.getWidth() / (double)s.getHeight();
                if( Math.abs(a - aspect) > 0.02 )
                    continue;
                if( map != null && fps > 0 ) {
                    try {
                        long minFrame = map.getOutputMinFrameDuration(android.graphics.SurfaceTexture.class, s);
                        if( minFrame > 0 && minFrame > 1000000000L / fps + 100000L )
                            continue;
                    }
                    catch(Exception ignored) {
                    }
                }
                if( best == null || s.getWidth() * (long)s.getHeight() > best.getWidth() * (long)best.getHeight() )
                    best = s;
            }
            return best;
        }
    }

    public final List<Lens> sensors = new ArrayList<>(); // unique physical sensors (and non-logical top level cameras)
    public final List<Lens> logicals = new ArrayList<>(); // top level logical multi-cameras
    public final List<String> topLevelIds = new ArrayList<>();
    public final List<Set<String>> concurrentSets = new ArrayList<>();
    public final List<String> probeNotes = new ArrayList<>();

    private static CameraInventory cached;

    public static synchronized CameraInventory get(Context context) {
        if( cached == null ) {
            cached = new CameraInventory(context.getApplicationContext(), true);
        }
        return cached;
    }

    public static synchronized void invalidate() {
        cached = null;
    }

    private CameraInventory(Context context, boolean probeHidden) {
        CameraManager manager = (CameraManager)context.getSystemService(Context.CAMERA_SERVICE);
        Map<String, Lens> byId = new LinkedHashMap<>();
        String [] ids;
        try {
            ids = manager.getCameraIdList();
        }
        catch(Throwable e) {
            Log.e(TAG, "getCameraIdList failed", e);
            ids = new String[0];
        }
        topLevelIds.addAll(Arrays.asList(ids));
        for(int i=0;i<ids.length;i++) {
            Lens lens = describe(manager, ids[i]);
            if( lens == null )
                continue;
            lens.directId = ids[i];
            lens.ocIndex = i;
            if( lens.isLogical ) {
                logicals.add(lens);
            }
            else {
                byId.put(lens.id, lens);
            }
        }
        // physical cameras of logical cameras
        for(Lens logical : logicals) {
            for(String pid : logical.physicalIds) {
                Lens existing = byId.get(pid);
                if( existing == null ) {
                    Lens phys = describe(manager, pid);
                    if( phys == null )
                        continue;
                    phys.directId = null;
                    byId.put(pid, phys);
                    existing = phys;
                }
                if( !existing.logicalParents.contains(logical.id) )
                    existing.logicalParents.add(logical.id);
            }
        }
        if( probeHidden ) {
            for(int i=0;i<16;i++) {
                String pid = String.valueOf(i);
                if( byId.containsKey(pid) || topLevelIds.contains(pid) )
                    continue;
                try {
                    Lens hidden = describe(manager, pid);
                    if( hidden != null && !hidden.isLogical ) {
                        hidden.hidden = true;
                        hidden.directId = pid; // may or may not be openable
                        byId.put(pid, hidden);
                        probeNotes.add("Hidden camera id " + pid + " responded to probing");
                    }
                }
                catch(Throwable ignored) {
                }
            }
        }
        sensors.addAll(byId.values());
        computeZoomFactors();
        applyDeviceZoomHints();
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ) {
            try {
                Set<Set<String>> sets = manager.getConcurrentCameraIds();
                concurrentSets.addAll(sets);
            }
            catch(Throwable e) {
                Log.e(TAG, "getConcurrentCameraIds failed", e);
            }
        }
    }

    private static Lens describe(CameraManager manager, String id) {
        CameraCharacteristics c;
        try {
            c = manager.getCameraCharacteristics(id);
        }
        catch(Throwable e) {
            return null;
        }
        Lens lens = new Lens();
        lens.id = id;
        lens.characteristics = c;
        Integer facing = c.get(CameraCharacteristics.LENS_FACING);
        lens.facing = facing != null ? facing : CameraMetadata.LENS_FACING_EXTERNAL;
        float [] fl = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        lens.focalLength = (fl != null && fl.length > 0) ? fl[0] : 4.0f;
        Integer so = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
        lens.sensorOrientation = so != null ? so : 90;
        Integer hw = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        lens.hwLevel = hw != null ? hw : -1;
        int [] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if( caps != null ) {
            for(int cap : caps) {
                if( cap == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P )
                    lens.isLogical = true;
                else if( cap == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR )
                    lens.manualSensor = true;
                else if( cap == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW )
                    lens.rawCapable = true;
                else if( cap == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO )
                    lens.highSpeedVideo = true;
                else if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && cap == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME )
                    lens.isMono = true;
            }
        }
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ) {
            Integer cfa = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
            if( cfa != null && (cfa == CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO || cfa == CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR) )
                lens.isMono = true;
        }
        if( lens.isLogical && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ) {
            Set<String> pids = c.getPhysicalCameraIds();
            lens.physicalIds = pids.toArray(new String[0]);
            Integer sync = c.get(CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE);
            lens.syncType = sync != null ? sync : -1;
            if( lens.physicalIds.length == 0 )
                lens.isLogical = false;
        }
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ) {
            Range<Float> zr = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if( zr != null ) {
                lens.minZoomRatio = zr.getLower();
                lens.maxZoomRatio = zr.getUpper();
            }
        }
        Float maxDigital = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        lens.maxDigitalZoom = maxDigital != null ? maxDigital : 1.0f;
        if( lens.maxZoomRatio <= 1.0f )
            lens.maxZoomRatio = Math.max(1.0f, lens.maxDigitalZoom);
        lens.exposureRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        lens.isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        Float mfd = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        lens.minFocusDistance = mfd != null ? mfd : 0.0f;
        lens.fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if( map != null ) {
            Size [] sizes = map.getOutputSizes(android.graphics.SurfaceTexture.class);
            if( sizes != null )
                lens.privSizes = sizes;
        }
        // 35mm equivalent focal length from the diagonal of the active area
        SizeF phys = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        Size pixels = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        Rect active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if( phys != null && phys.getWidth() > 0 ) {
            float w = phys.getWidth();
            float h = phys.getHeight();
            if( pixels != null && active != null && pixels.getWidth() > 0 && pixels.getHeight() > 0 ) {
                w *= active.width() / (float)pixels.getWidth();
                h *= active.height() / (float)pixels.getHeight();
            }
            double diag = Math.sqrt(w*w + h*h);
            lens.eqFocal = (float)(lens.focalLength * 43.27 / diag);
        }
        else {
            lens.eqFocal = lens.focalLength * 6.0f;
        }
        return lens;
    }

    /** OnePlus 9 Pro often reports one focal length for every sensor, which collapses the
     *  0.6 / 1 / 3.3 zoom pills. Known devices get the labels the OnePlus camera shows. */
    private void applyDeviceZoomHints() {
        int applied = 0;
        for(Lens lens : sensors) {
            Float zoom = net.sourceforge.opencamera.studio.LensPlanner.knownZoomFactor(Build.MANUFACTURER, Build.DEVICE, Build.MODEL, lens.id);
            if( zoom != null ) {
                lens.zoomFactor = zoom;
                applied++;
            }
        }
        for(Lens lens : logicals) {
            Float zoom = net.sourceforge.opencamera.studio.LensPlanner.knownZoomFactor(Build.MANUFACTURER, Build.DEVICE, Build.MODEL, lens.id);
            if( zoom != null ) {
                lens.zoomFactor = zoom;
                applied++;
            }
        }
        if( applied > 0 )
            probeNotes.add("OnePlus 9 Pro lens labels applied to " + applied + " cameras (" + Build.DEVICE + " / " + Build.MODEL + ")");
    }

    private void computeZoomFactors() {
        for(int facing : new int[]{CameraMetadata.LENS_FACING_BACK, CameraMetadata.LENS_FACING_FRONT, CameraMetadata.LENS_FACING_EXTERNAL}) {
            float ref = -1.0f;
            // reference: the first top level camera with this facing (what apps open by default)
            for(String id : topLevelIds) {
                Lens l = findAny(id);
                if( l != null && l.facing == facing ) {
                    if( l.isLogical ) {
                        // logical camera reports the focal length of its default lens
                        ref = l.eqFocal;
                    }
                    else {
                        ref = l.eqFocal;
                    }
                    break;
                }
            }
            if( ref <= 0 ) {
                for(Lens l : sensors) {
                    if( l.facing == facing && !l.isMono ) {
                        ref = l.eqFocal;
                        break;
                    }
                }
            }
            if( ref <= 0 )
                continue;
            for(Lens l : sensors) {
                if( l.facing == facing )
                    l.zoomFactor = l.eqFocal / ref;
            }
            for(Lens l : logicals) {
                if( l.facing == facing )
                    l.zoomFactor = l.eqFocal / ref;
            }
        }
    }

    public Lens findAny(String id) {
        for(Lens l : sensors) {
            if( l.id.equals(id) )
                return l;
        }
        for(Lens l : logicals) {
            if( l.id.equals(id) )
                return l;
        }
        return null;
    }

    /** Unique sensors with the given facing, sorted by zoom factor (ultra wide first). Excludes mono
     *  sensors unless includeMono. */
    public List<Lens> lenses(int facing, boolean includeMono) {
        List<Lens> list = new ArrayList<>();
        for(Lens l : sensors) {
            if( l.facing == facing && (includeMono || !l.isMono) )
                list.add(l);
        }
        Collections.sort(list, (a, b) -> Float.compare(a.zoomFactor, b.zoomFactor));
        return list;
    }

    /** The lens apps open by default for this facing (first top level id with that facing). */
    public Lens defaultLens(int facing) {
        for(String id : topLevelIds) {
            Lens l = findAny(id);
            if( l != null && l.facing == facing )
                return l;
        }
        return null;
    }

    /** Back lenses that can actually be used (directly openable, or physical of an openable logical camera). */
    public List<Lens> usableLenses(int facing, boolean includeMono) {
        List<Lens> list = new ArrayList<>();
        for(Lens l : lenses(facing, includeMono)) {
            if( (l.directId != null && !l.hidden) || !l.logicalParents.isEmpty() )
                list.add(l);
        }
        // de-duplicate lenses with near identical zoom (e.g. same sensor exposed twice under different ids)
        List<Lens> result = new ArrayList<>();
        for(Lens l : list) {
            boolean dup = false;
            for(Lens r : result) {
                if( Math.abs(r.zoomFactor - l.zoomFactor) < 0.04f && r.isMono == l.isMono && Math.abs(r.focalLength - l.focalLength) < 0.05f ) {
                    dup = true;
                    break;
                }
            }
            if( !dup )
                result.add(l);
        }
        return result;
    }

    public boolean isConcurrentSupported(Set<String> ids) {
        for(Set<String> s : concurrentSets) {
            if( s.containsAll(ids) )
                return true;
        }
        return false;
    }

    public String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append("), Android ").append(Build.VERSION.RELEASE)
                .append(" API ").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("Camera ID list: ").append(topLevelIds).append('\n');
        sb.append("Concurrent camera sets: ").append(concurrentSets.isEmpty() ? "none reported" : concurrentSets.toString()).append('\n');
        for(String note : probeNotes)
            sb.append(note).append('\n');
        sb.append('\n');
        for(Lens l : logicals) {
            sb.append("LOGICAL ").append(l.id).append(": ").append(facingName(l.facing))
                    .append(", physical ").append(Arrays.toString(l.physicalIds))
                    .append(", sync ").append(syncName(l.syncType))
                    .append(", zoom ").append(String.format(Locale.US, "%.2f-%.1f", l.minZoomRatio, l.maxZoomRatio))
                    .append(", level ").append(levelName(l.hwLevel)).append('\n');
        }
        for(Lens l : sensors) {
            sb.append(l.hidden ? "HIDDEN " : "").append("SENSOR ").append(l.id)
                    .append(l.directId != null ? " (openable)" : " (physical only)")
                    .append(": ").append(l.longLabel()).append(", ").append(facingName(l.facing))
                    .append(String.format(Locale.US, ", f=%.2fmm", l.focalLength))
                    .append(", orient ").append(l.sensorOrientation)
                    .append(", level ").append(levelName(l.hwLevel));
            if( !l.logicalParents.isEmpty() )
                sb.append(", in logical ").append(l.logicalParents);
            if( l.isoRange != null )
                sb.append(", ISO ").append(l.isoRange);
            if( l.exposureRange != null )
                sb.append(String.format(Locale.US, ", exp %s-%s", OpFormat.shutter(l.exposureRange.getLower()), OpFormat.shutter(l.exposureRange.getUpper())));
            if( l.fpsRanges != null )
                sb.append(", fps ").append(Arrays.toString(l.fpsRanges));
            sb.append(l.manualSensor ? ", manual" : "").append(l.rawCapable ? ", RAW" : "").append(l.highSpeedVideo ? ", high-speed" : "");
            sb.append('\n');
        }
        return sb.toString();
    }

    public static String facingName(int facing) {
        switch( facing ) {
            case CameraMetadata.LENS_FACING_BACK:
                return "back";
            case CameraMetadata.LENS_FACING_FRONT:
                return "front";
            default:
                return "external";
        }
    }

    static String levelName(int level) {
        switch( level ) {
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY:
                return "LEGACY";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED:
                return "LIMITED";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL:
                return "FULL";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3:
                return "LEVEL_3";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL:
                return "EXTERNAL";
            default:
                return "?";
        }
    }

    static String syncName(int sync) {
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ) {
            if( sync == CameraMetadata.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_CALIBRATED )
                return "calibrated";
            if( sync == CameraMetadata.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_APPROXIMATE )
                return "approximate";
        }
        return "unknown";
    }
}
