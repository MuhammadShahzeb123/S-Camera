package net.sourceforge.opencamera.studio;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import net.sourceforge.opencamera.PreferenceKeys;
import net.sourceforge.opencamera.op.CameraInventory;
import net.sourceforge.opencamera.op.CameraRoutes;
import net.sourceforge.opencamera.op.CameraSelection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One recorder and audio stream, with sequential Camera2 inputs feeding a persistent GL encoder.
 * All camera/recorder transitions run on the camera handler; callbacks to the activity run on main.
 * The last displayed texture and confirmed zoom survive until the replacement supplies a frame.
 */
public final class OpticalVideoEngine {
    private static final long FIRST_FRAME_TIMEOUT_MS = 8_000;

    public interface Listener {
        void onState(State state);
        void onSaved(Uri uri, Bitmap thumbnail);
        void onError(String message);
    }

    public static final class Profile {
        /** Camera input dimensions. The renderer encodes portrait height x width. */
        public final int width, height, fps;
        public Profile(int width, int height, int fps) {
            this.width = width;
            this.height = height;
            this.fps = fps;
        }
        @Override public boolean equals(Object other) {
            if( !(other instanceof Profile) ) return false;
            Profile p = (Profile)other;
            return width == p.width && height == p.height && fps == p.fps;
        }
        @Override public int hashCode() { return (width * 31 + height) * 31 + fps; }
        @Override public String toString() { return height + "p " + fps + " fps"; }
    }

    public static final class State {
        public final boolean ready, busy, recording, paused, front, torch;
        public final boolean supportsStabilization, supportsTorch, stabilization;
        public final float zoom, minZoom, maxZoom, digitalZoom;
        public final float[] stops;
        public final int width, height, fps;
        public final long recordMs;
        public final String lensLabel, cameraId, physicalId;
        public final List<Profile> profiles;
        private State(OpticalVideoEngine engine) {
            CameraInventory.Lens active = engine.activeLens;
            ready = !engine.closing && engine.slot != null && engine.slot.live && !engine.slot.closing
                    && engine.slot.profile.equals(engine.profile);
            busy = engine.busy || engine.closing;
            recording = engine.recorderStarted;
            paused = engine.paused;
            front = active == null ? engine.front : active.isFront();
            torch = engine.torch;
            supportsTorch = active != null && Boolean.TRUE.equals(active.characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
            supportsStabilization = active != null && engine.activeRoute != null
                    && has(active.characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES), CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                    && has(engine.activeRoute.characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES), CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
            stabilization = supportsStabilization && engine.preferences.getBoolean(PreferenceKeys.VideoStabilizationPreferenceKey, false);
            zoom = engine.zoom == null ? 1f : engine.zoom.activeLens() == null && active != null ? engine.lastGoodZoom : engine.zoom.activeZoom();
            digitalZoom = active == null ? 1f : Math.max(1f, zoom / active.zoomFactor);
            minZoom = engine.choices.isEmpty() ? 1f : engine.choices.get(0).policy.nativeZoom;
            maxZoom = OpticalVideoPolicy.maxZoom(engine.policyLenses());
            stops = new float[engine.choices.size()];
            for( int i = 0; i < stops.length; i++ ) stops[i] = engine.choices.get(i).policy.nativeZoom;
            Profile profile = engine.profile;
            width = profile == null ? 1920 : profile.width;
            height = profile == null ? 1080 : profile.height;
            fps = profile == null ? 30 : profile.fps;
            recordMs = engine.recordDuration();
            lensLabel = active == null ? "Opening camera" : active.shortLabel();
            cameraId = engine.activeRoute == null ? null : engine.activeRoute.openId;
            physicalId = engine.activeRoute == null ? null : engine.activeRoute.physicalId;
            profiles = Collections.unmodifiableList(new ArrayList<>(engine.profiles));
        }
    }

    private static final class Route {
        final String openId, physicalId;
        final CameraCharacteristics characteristics;
        Route(String openId, String physicalId, CameraCharacteristics characteristics) {
            this.openId = openId;
            this.physicalId = physicalId;
            this.characteristics = characteristics;
        }
        @Override public String toString() { return openId + (physicalId == null ? "" : "/physical=" + physicalId); }
    }

    private static final class Choice {
        final CameraInventory.Lens lens;
        final OpticalVideoPolicy.Lens policy;
        final List<Route> routes = new ArrayList<>();
        Choice(CameraInventory.Lens lens) {
            this.lens = lens;
            policy = new OpticalVideoPolicy.Lens(lens.id, lens.zoomFactor, lens.maxDigitalZoom);
        }
    }

    private static final class Slot {
        final Choice choice;
        final Route route;
        final Profile profile;
        final int routeIndex;
        OpticalVideoRenderer.Input input;
        CameraDevice device;
        CameraCaptureSession session;
        Runnable afterClose;
        Runnable timeout;
        Runnable focusReset;
        float[] focusPoint;
        boolean opening, closing, closed, live, failed, captureLogged;
        Slot(Choice choice, Route route, Profile profile, int routeIndex) {
            this.choice = choice;
            this.route = route;
            this.profile = profile;
            this.routeIndex = routeIndex;
        }
    }

    private final Context context;
    private final OpticalVideoRenderer renderer;
    private final Listener listener;
    private final CameraManager manager;
    private final SharedPreferences preferences;
    private final List<CameraInventory.Lens> inventoryOverride;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("OpticalVideoCamera");
    private final Handler handler;
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor();
    private final List<Runnable> closeCallbacks = new ArrayList<>();
    private final List<Choice> choices = new ArrayList<>();
    private final List<Profile> profiles = new ArrayList<>();
    private Map<Profile, List<Choice>> profileChoices = Collections.emptyMap();
    private CameraInventory inventory;
    private CameraSelection initialSelection;
    private OpticalVideoPolicy.ZoomState zoom;
    private CameraInventory.Lens activeLens;
    private Route activeRoute;
    private Profile profile, lastGoodProfile;
    private float lastGoodZoom = 1f;
    private boolean lastGoodFront;
    private Slot slot;
    private boolean front, busy, torch, paused, recovering, initialized, resourcesClosed;
    private volatile boolean closing;
    private volatile State state;
    private float evNorm;
    private MediaRecorder recorder;
    private Surface recorderSurface;
    private StudioSaver.VideoTarget videoTarget;
    private boolean recorderStarted;
    private long recordStartMs, pausedAtMs, pausedDurationMs;

    public OpticalVideoEngine(Context context, OpticalVideoRenderer renderer, Listener listener) {
        this(context, renderer, listener, null);
    }

    /** Allows an explicitly supplied camera inventory, using the same capability checks and lifecycle. */
    OpticalVideoEngine(Context context, OpticalVideoRenderer renderer, Listener listener, List<CameraInventory.Lens> inventoryOverride) {
        this.context = context.getApplicationContext();
        this.renderer = renderer;
        this.listener = listener;
        manager = (CameraManager)this.context.getSystemService(Context.CAMERA_SERVICE);
        preferences = PreferenceManager.getDefaultSharedPreferences(this.context);
        this.inventoryOverride = inventoryOverride == null ? null : new ArrayList<>(inventoryOverride);
        thread.start();
        handler = new Handler(thread.getLooper());
        state = new State(this);
        renderer.setErrorListener(error -> handler.post(() -> {
            if( resourcesClosed ) return;
            StudioLog.e("Optical renderer failed", error);
            error("Video rendering stopped: " + error.getMessage());
            close();
        }));
    }

    public State getState() { return state; }

    public void start(boolean front, float globalZoom, int preferredW, int preferredH, int preferredFps) {
        start(new CameraSelection(null, null, front, globalZoom), preferredW, preferredH, preferredFps);
    }

    public void start(CameraSelection selection, int preferredW, int preferredH, int preferredFps) {
        post(() -> {
            if( initialized ) return;
            initialized = true;
            initialSelection = selection;
            inventory = CameraInventory.get(context);
            StudioLog.i("Optical start direct-first routing; incoming camera=" + selection.cameraId
                    + "/physical=" + selection.physicalId + ", zoom=" + selection.zoom);
            if( configureFacing(selection.front, selection.zoom, new Profile(preferredW, preferredH, preferredFps), true) ) drive();
        });
    }

    public void setZoom(float globalZoom) {
        post(() -> {
            if( zoom == null ) return;
            String previousTarget = zoom.desiredLens().id;
            zoom.request(globalZoom);
            renderer.setGlobalZoom(zoom.desiredZoom());
            if( !previousTarget.equals(zoom.desiredLens().id) )
                StudioLog.i("Optical zoom boundary " + globalZoom + "x: sensor " + previousTarget + " -> " + zoom.desiredLens().id);
            recovering = false;
            drive();
        });
    }

    public void setProfile(Profile requested) {
        post(() -> {
            if( recorderStarted || busy || requested == null ) return;
            if( !profileChoices.containsKey(requested) ) { error("That resolution and frame rate are not available."); return; }
            if( requested.equals(profile) ) return;
            selectProfile(requested, zoom.desiredZoom());
            drive();
        });
    }

    public void flip() {
        post(() -> {
            if( recorderStarted || busy ) return;
            if( configureFacing(!front, 1f, profile, true) ) drive();
        });
    }

    public void resizePreview(int width, int height) {
        post(() -> renderer.resizePreview(width, height));
    }

    public void record(boolean audioPermission, int orientationEventDegrees) {
        post(() -> startRecorder(audioPermission, orientationEventDegrees));
    }

    public void stopRecording() { post(() -> stopRecorder(true)); }

    public void pauseToggle() {
        post(() -> {
            if( !recorderStarted || recorder == null ) return;
            if( Build.VERSION.SDK_INT < Build.VERSION_CODES.N ) { error("Pausing requires Android 7 or later."); return; }
            try {
                if( paused ) {
                    recorder.resume();
                    pausedDurationMs += SystemClock.elapsedRealtime() - pausedAtMs;
                    paused = false;
                }
                else {
                    recorder.pause();
                    pausedAtMs = SystemClock.elapsedRealtime();
                    paused = true;
                }
                emit();
            }
            catch( RuntimeException e ) { StudioLog.e("Optical pause failed", e); error("Couldn't pause or resume this recording."); }
        });
    }

    public void setEv(float normalized) {
        post(() -> { evNorm = Math.max(-1f, Math.min(1f, normalized)); repeatCurrent(); });
    }

    public void setStabilization(boolean enabled) {
        post(() -> {
            preferences.edit().putBoolean(PreferenceKeys.VideoStabilizationPreferenceKey, enabled).apply();
            repeatCurrent();
            emit();
        });
    }

    public void toggleTorch() {
        post(() -> {
            if( activeLens == null || !Boolean.TRUE.equals(activeLens.characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)) ) {
                error("Torch is unavailable on this lens."); return;
            }
            torch = !torch;
            repeatCurrent();
            emit();
        });
    }

    public void focus(float nx, float ny) {
        post(() -> {
            Slot current = slot;
            if( current == null || !current.live || current.closing ) return;
            try {
                float ratio = OpticalVideoPolicy.digitalZoom(current.choice.policy, zoom.activeZoom());
                float x = .5f + (nx - .5f) / ratio, y = .5f + (ny - .5f) / ratio;
                current.focusPoint = Orient.viewToSensor(x, y,
                        Orient.displayRotation(current.choice.lens.sensorOrientation, 0, front), front);
                // AUTO and the tap regions must remain on repeating requests after START. A
                // one-shot AUTO request followed by CONTINUOUS_VIDEO immediately cancels the tap.
                current.session.setRepeatingRequest(request(current).build(), null, handler);
                if( supportsAutoFocus(current) ) {
                    CaptureRequest.Builder cancel = request(current);
                    set(cancel, current, CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
                    current.session.capture(cancel.build(), null, handler);
                    CaptureRequest.Builder trigger = request(current);
                    set(trigger, current, CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                    current.session.capture(trigger.build(), null, handler);
                }
                if( current.focusReset != null ) handler.removeCallbacks(current.focusReset);
                current.focusReset = () -> clearFocus(current);
                handler.postDelayed(current.focusReset, 4_000);
            }
            catch( Exception e ) { StudioLog.e("Optical focus failed", e); }
        });
    }

    public void snapshot() { snapshot(0); }

    public void snapshot(int orientationEventDegrees) {
        final int rotation = Orient.toDisplayDegrees(((orientationEventDegrees + 45) / 90 * 90) % 360);
        post(() -> renderer.capture(bitmap -> {
            if( bitmap == null ) { main.post(() -> listener.onError("Couldn't capture a video snapshot.")); return; }
            try {
                saveExecutor.execute(() -> {
                    Bitmap oriented = bitmap;
                    if( rotation != 0 ) {
                        Matrix matrix = new Matrix();
                        matrix.postRotate(rotation);
                        oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                        if( oriented != bitmap ) bitmap.recycle();
                    }
                    Uri uri = StudioSaver.saveBitmap(context, oriented, "IMG_" + StudioSaver.stamp() + ".jpg");
                    Bitmap thumbnail = thumbnail(oriented);
                    if( thumbnail != oriented ) oriented.recycle();
                    main.post(() -> { if( uri == null ) listener.onError("Couldn't save the snapshot."); else listener.onSaved(uri, thumbnail); });
                });
            }
            catch( java.util.concurrent.RejectedExecutionException e ) { bitmap.recycle(); }
        }));
    }

    public void close() { close(null); }

    /** Stops and publishes an already-started clip even if a replacement camera is still opening. */
    public void close(Runnable onClosed) {
        synchronized( closeCallbacks ) {
            if( resourcesClosed ) { if( onClosed != null ) main.post(onClosed); return; }
            if( onClosed != null ) closeCallbacks.add(onClosed);
            if( closing ) return;
            closing = true;
        }
        handler.post(() -> {
            stopRecorder(true);
            busy = true;
            emit();
            if( slot == null ) finishClose();
            else closeSlot(slot, this::finishClose);
        });
    }

    private void post(Runnable work) {
        if( closing ) return;
        handler.post(() -> {
            if( closing ) return;
            try { work.run(); }
            catch( RuntimeException e ) {
                StudioLog.e("Optical operation failed", e);
                error("Camera operation failed: " + e.getMessage());
            }
        });
    }

    private boolean configureFacing(boolean requestedFront, float initialZoom, Profile preferred, boolean reportFallback) {
        int facing = requestedFront ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
        List<Choice> found = new ArrayList<>();
        List<CameraInventory.Lens> available = inventoryOverride == null ? inventory.usableLenses(facing, false) : inventoryOverride;
        for( CameraInventory.Lens lens : available ) {
            if( lens.facing != facing || lens.isMono ) continue;
            if( lens.characteristics == null || lens.zoomFactor <= 0 ) continue;
            Choice choice = new Choice(lens);
            for( CameraRoutes.Route route : CameraRoutes.forLens(lens, initialSelection) ) {
                if( route.physicalId != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.P ) continue;
                try {
                    CameraCharacteristics characteristics = route.physicalId == null ? lens.characteristics
                            : manager.getCameraCharacteristics(route.cameraId);
                    choice.routes.add(new Route(route.cameraId, route.physicalId, characteristics));
                }
                catch( CameraAccessException | IllegalArgumentException e ) { StudioLog.e("Optical route unavailable " + route.cameraId, e); }
            }
            found.add(choice);
        }
        // Gather rates before filtering lenses. Otherwise a 29 FPS telephoto is removed by
        // a 30/60-only probe, and the remaining cameras incorrectly determine the whole UI.
        List<CameraCharacteristics> cameras = new ArrayList<>();
        for( Choice choice : found ) {
            cameras.add(choice.lens.characteristics);
            for( Route route : choice.routes ) cameras.add(route.characteristics);
        }
        List<Profile> candidates = candidateProfilesForCameras(preferred == null ? 30 : preferred.fps,
                cameras.toArray(new CameraCharacteristics[0]));
        for( Iterator<Choice> it = found.iterator(); it.hasNext(); ) {
            Choice choice = it.next();
            boolean compatible = supportsAny(choice, candidates);
            StudioLog.i("Optical lens " + choice.lens.id + " " + choice.lens.shortLabel() + " " + choice.lens.zoomFactor
                    + "x, routes=" + choice.routes + ", video-compatible=" + compatible);
            if( !compatible ) it.remove();
        }
        if( found.isEmpty() ) { error("No compatible " + (requestedFront ? "front" : "rear") + " video camera is exposed by this device."); return false; }
        Map<Profile, List<Choice>> availableProfiles = supportedProfiles(candidates, found,
                (choice, candidate) -> routeFor(choice, candidate, 0) >= 0,
                this::encoderSupports);
        if( availableProfiles.isEmpty() ) { error("No camera exposes a video format supported by the encoder."); return false; }
        profileChoices = availableProfiles;
        profiles.clear(); profiles.addAll(availableProfiles.keySet());
        front = requestedFront;
        torch = false;
        selectProfile(chooseProfile(preferred, profiles), initialZoom);
        if( reportFallback && preferred != null && !preferred.equals(profile) )
            error(preferred + " is not available. Using " + profile + ".");
        StudioLog.i("Optical profiles " + profiles + "; lenses=" + choices.size() + "; chosen=" + profile);
        emit();
        return true;
    }

    private void selectProfile(Profile requested, float globalZoom) {
        // Retain the full catalog so returning to 1080p/29 brings telephoto back.
        // Only these lenses may feed a recording with the selected fixed format.
        choices.clear();
        choices.addAll(profileChoices.get(requested));
        profile = requested;
        zoom = new OpticalVideoPolicy.ZoomState(policyLenses(), globalZoom);
        renderer.setGlobalZoom(zoom.desiredZoom());
        StringBuilder available = new StringBuilder();
        for( Choice choice : choices ) {
            if( available.length() > 0 ) available.append(", ");
            available.append(choice.lens.id).append(':').append(choice.lens.shortLabel());
        }
        StudioLog.i("Optical selected " + profile + "; available lenses=" + available);
    }

    public static List<Profile> candidateProfiles() {
        return candidateProfiles(Collections.emptyList());
    }

    interface LensProfileCheck<T> { boolean supports(T lens, Profile profile); }
    interface EncoderProfileCheck { boolean supports(Profile profile); }

    /** Offer a format if the encoder and at least one camera route support it.
     *  Store its eligible lenses instead of restricting every format to the weakest lens. */
    static <T> Map<Profile, List<T>> supportedProfiles(List<Profile> candidates, List<T> lenses,
                                                     LensProfileCheck<T> cameraCheck,
                                                     EncoderProfileCheck encoderCheck) {
        Map<Profile, List<T>> result = new LinkedHashMap<>();
        for( Profile candidate : candidates ) {
            List<T> supported = new ArrayList<>();
            for( T lens : lenses ) if( cameraCheck.supports(lens, candidate) ) supported.add(lens);
            if( !supported.isEmpty() && encoderCheck.supports(candidate) )
                result.put(candidate, Collections.unmodifiableList(supported));
        }
        return result;
    }

    /** Use sensor AND opening-route metadata, for the engine and the diagnostic report. */
    public static List<Profile> candidateProfilesForCameras(CameraCharacteristics... cameras) {
        return candidateProfilesForCameras(0, cameras);
    }

    public static List<Profile> candidateProfilesForCameras(int preferredFps, CameraCharacteristics... cameras) {
        List<int[][]> advertised = new ArrayList<>();
        // Retain a saved rate when switching to cameras with broader AE ranges, even if
        // it is no longer an endpoint. It must still pass all the capability checks.
        advertised.add(new int[][]{{preferredFps, preferredFps}});
        for( CameraCharacteristics camera : cameras ) {
            if( camera == null ) continue;
            Range<Integer>[] ranges = camera.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if( ranges == null ) continue;
            int[][] values = new int[ranges.length][2];
            for( int i = 0; i < ranges.length; i++ ) {
                values[i][0] = ranges[i].getLower();
                values[i][1] = ranges[i].getUpper();
            }
            advertised.add(values);
        }
        return candidateProfiles(advertised);
    }

    /** Candidates still require exact AE, size, frame-duration, route and encoder checks.
     *  AE upper endpoints preserve nonstandard rates such as 29 without rounding them up.
     *  This is the normal-session pipeline; rates above 60 belong to high-speed recording. */
    static List<Profile> candidateProfiles(List<int[][]> advertised) {
        Set<Integer> rates = new TreeSet<>(Collections.reverseOrder());
        Collections.addAll(rates, 24, 25, 30, 50, 60);
        for( int[][] ranges : advertised ) {
            if( ranges == null ) continue;
            for( int[] range : ranges ) {
                if( range != null && range.length == 2 && range[0] > 0 && range[0] <= range[1]
                        && range[1] <= 60 ) rates.add(range[1]);
            }
        }
        List<Profile> result = new ArrayList<>();
        for( int[] size : new int[][]{{3840, 2160}, {1920, 1080}, {1280, 720}, {640, 480}} )
            for( int fps : rates ) result.add(new Profile(size[0], size[1], fps));
        return result;
    }

    /** Preserve resolution where possible, then use the nearest frame rate at or below
     *  the preference. Compatible lens subsets allow main/UW to retain 2160p/30. */
    static Profile chooseProfile(Profile preferred, List<Profile> profiles) {
        if( profiles.isEmpty() ) throw new IllegalArgumentException("No supported video profiles");
        if( preferred == null ) preferred = new Profile(1920, 1080, 30);
        long targetArea = (long)preferred.width * preferred.height;
        Profile resolution = profiles.get(0);
        for( Profile p : profiles ) {
            long area = (long)p.width * p.height;
            long bestArea = (long)resolution.width * resolution.height;
            if( (area <= targetArea && (bestArea > targetArea || area > bestArea))
                    || (area > targetArea && bestArea > targetArea && area < bestArea) ) resolution = p;
        }
        Profile best = null;
        for( Profile p : profiles ) {
            if( p.width != resolution.width || p.height != resolution.height ) continue;
            if( best == null || (p.fps <= preferred.fps && (best.fps > preferred.fps || p.fps > best.fps))
                    || (p.fps > preferred.fps && best.fps > preferred.fps && p.fps < best.fps) ) best = p;
        }
        return best;
    }

    /** Cycle only frame rates that survived all lens/route/encoder checks. */
    static Profile nextFpsProfile(Profile current, List<Profile> profiles) {
        Set<Integer> unique = new TreeSet<>(Collections.reverseOrder());
        for( Profile p : profiles ) unique.add(p.fps);
        List<Integer> rates = new ArrayList<>(unique);
        int next = rates.get((rates.indexOf(current.fps) + 1) % rates.size());
        List<Profile> options = new ArrayList<>();
        for( Profile p : profiles ) if( p.fps == next ) options.add(p);
        return chooseProfile(new Profile(current.width, current.height, next), options);
    }

    private boolean supportsAny(Choice choice, List<Profile> candidates) {
        for( Profile candidate : candidates ) if( routeFor(choice, candidate, 0) >= 0 ) return true;
        return false;
    }

    private static int routeFor(Choice choice, Profile profile, int first) {
        for( int i = first; i < choice.routes.size(); i++ ) {
            Route route = choice.routes.get(i);
            if( supports(choice.lens.characteristics, profile) && supports(route.characteristics, profile) ) return i;
        }
        return -1;
    }

    private static boolean supports(CameraCharacteristics c, Profile p) {
        return profileRejection(c, p) == null;
    }

    /** The eligibility check used by Video and by both diagnostic report entry points.
     *  Null means this individual camera's metadata permits the profile. */
    public static String profileRejection(CameraCharacteristics c, Profile p) {
        if( c == null ) return "missing camera characteristics";
        if( fpsRange(c, p.fps) == null ) return "AE ranges do not include " + p.fps + " FPS";
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if( map == null ) return "missing stream configuration map";
        Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
        if( sizes == null ) return "no SurfaceTexture output sizes";
        for( Size size : sizes ) {
            if( size.getWidth() != p.width || size.getHeight() != p.height ) continue;
            try {
                long duration = map.getOutputMinFrameDuration(SurfaceTexture.class, size);
                return OpticalVideoPolicy.supportsFrameDuration(duration, p.fps) ? null
                        : "minimum frame duration " + duration + " ns is too slow for " + p.fps + " FPS";
            }
            catch( IllegalArgumentException e ) { return "frame duration query rejected: " + e.getMessage(); }
        }
        return "SurfaceTexture size " + p.width + "x" + p.height + " is not advertised";
    }

    private boolean encoderSupports(Profile p) {
        String mime = hevc() ? "video/hevc" : "video/avc";
        try {
            for( MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos() ) {
                if( !info.isEncoder() ) continue;
                if( !Arrays.asList(info.getSupportedTypes()).contains(mime) ) continue;
                MediaCodecInfo.CodecCapabilities capabilities = info.getCapabilitiesForType(mime);
                if( !has(capabilities.colorFormats, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) ) continue;
                MediaCodecInfo.VideoCapabilities video = capabilities.getVideoCapabilities();
                if( video != null && video.areSizeAndRateSupported(p.height, p.width, p.fps) ) return true;
            }
        }
        catch( RuntimeException e ) { StudioLog.e("Optical encoder capability query failed", e); }
        return false;
    }

    private List<OpticalVideoPolicy.Lens> policyLenses() {
        List<OpticalVideoPolicy.Lens> result = new ArrayList<>();
        for( Choice choice : choices ) result.add(choice.policy);
        return result;
    }

    private Choice desiredChoice() {
        if( zoom == null ) return null;
        for( Choice choice : choices ) if( choice.policy.id.equals(zoom.desiredLens().id) ) return choice;
        return null;
    }

    private void drive() {
        if( closing || profile == null ) return;
        Choice desired = desiredChoice();
        if( desired == null ) return;
        if( slot != null ) {
            if( slot.closing || slot.opening || (!slot.live && !slot.failed) ) { busy = true; emit(); return; }
            if( slot.live && slot.choice.lens.id.equals(desired.lens.id) && slot.profile.equals(profile) ) {
                if( zoom.commit(desired.policy) ) {
                    renderer.setGlobalZoom(zoom.activeZoom());
                    lastGoodZoom = zoom.activeZoom();
                }
                busy = false;
                emit();
                return;
            }
            busy = true;
            emit();
            closeSlot(slot, this::drive);
            return;
        }
        int route = routeFor(desired, profile, 0);
        if( route < 0 ) { error("This lens cannot use the recording's fixed format."); return; }
        open(desired, route);
    }

    private void open(Choice choice, int routeIndex) {
        if( closing ) return;
        Slot next = new Slot(choice, choice.routes.get(routeIndex), profile, routeIndex);
        slot = next;
        busy = true;
        emit();
        StudioLog.i("Optical open " + choice.lens.shortLabel() + " " + next.route + " " + profile + "; recording=" + recorderStarted);
        try {
            next.input = renderer.createInput(profile.width, profile.height, input -> handler.post(() -> firstFrame(next, input)));
            if( closing ) { closeSlot(next, this::finishClose); return; }
            next.opening = true;
            next.timeout = () -> failSlot(next, "Timed out waiting for a frame from " + choice.lens.shortLabel(), null);
            handler.postDelayed(next.timeout, FIRST_FRAME_TIMEOUT_MS);
            if( context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED )
                throw new SecurityException("Camera permission is required");
            manager.openCamera(next.route.openId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    next.opening = false;
                    next.device = camera;
                    if( closing || next.closing || slot != next ) { camera.close(); return; }
                    configure(next);
                }
                @Override public void onDisconnected(CameraDevice camera) {
                    next.opening = false;
                    next.device = camera;
                    if( next.closing || closing ) camera.close();
                    else failSlot(next, "Camera disconnected while switching lenses", null);
                }
                @Override public void onError(CameraDevice camera, int code) {
                    next.opening = false;
                    next.device = camera;
                    if( next.closing || closing ) camera.close();
                    else failSlot(next, "Camera " + next.route.openId + " could not open (" + code + ")", null);
                }
                @Override public void onClosed(CameraDevice camera) {
                    next.device = null;
                    next.opening = false;
                    slotClosed(next);
                }
            }, handler);
        }
        catch( Exception e ) {
            next.opening = false;
            failSlot(next, "Couldn't open " + choice.lens.shortLabel(), e);
        }
    }

    private void configure(Slot target) {
        try {
            CameraCaptureSession.StateCallback callback = new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession session) {
                    if( closing || target.closing || slot != target ) { session.close(); return; }
                    target.session = session;
                    try { session.setRepeatingRequest(request(target).build(), captureCallback(target), handler); }
                    catch( Exception e ) { failSlot(target, "Couldn't stream " + target.choice.lens.shortLabel(), e); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) {
                    session.close();
                    failSlot(target, "The camera rejected " + target.profile + " on " + target.choice.lens.shortLabel(), null);
                }
            };
            if( target.route.physicalId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ) {
                OutputConfiguration output = new OutputConfiguration(target.input.surface);
                output.setPhysicalCameraId(target.route.physicalId);
                target.device.createCaptureSessionByOutputConfigurations(Collections.singletonList(output), callback, handler);
            }
            else target.device.createCaptureSession(Collections.singletonList(target.input.surface), callback, handler);
        }
        catch( Exception e ) { failSlot(target, "Couldn't configure " + target.choice.lens.shortLabel(), e); }
    }

    private void firstFrame(Slot target, OpticalVideoRenderer.Input input) {
        if( closing || target.closing || target.failed || target.closed || slot != target ) return;
        if( target.timeout != null ) handler.removeCallbacks(target.timeout);
        // A frame for an obsolete request is never shown as the requested optical lens.
        if( !target.profile.equals(profile) || !zoom.commit(target.choice.policy) ) {
            closeSlot(target, this::drive);
            return;
        }
        boolean blend = activeLens != null && activeLens.isFront() == target.choice.lens.isFront()
                && target.profile.equals(lastGoodProfile);
        renderer.activate(input, OpticalVideoPolicy.digitalZoom(target.choice.policy, zoom.activeZoom()),
                target.choice.policy.nativeZoom, blend);
        target.live = true;
        activeLens = target.choice.lens;
        activeRoute = target.route;
        lastGoodProfile = target.profile;
        lastGoodZoom = zoom.activeZoom();
        lastGoodFront = front;
        if( !Boolean.TRUE.equals(activeLens.characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)) ) torch = false;
        recovering = false;
        busy = false;
        StudioLog.i(String.format(Locale.US, "Optical active %s via %s, global=%.3fx digital=%.3fx; encoder continues=%s",
                activeLens.shortLabel(), target.route, zoom.activeZoom(), OpticalVideoPolicy.digitalZoom(target.choice.policy, zoom.activeZoom()), recorderStarted));
        emit();
    }

    /** Record what the HAL actually reports, separately from the requested lens label.
     *  Logical active ID alone does not identify a separately pinned physical output. */
    private CameraCaptureSession.CaptureCallback captureCallback(Slot target) {
        return new CameraCaptureSession.CaptureCallback() {
            @Override public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                                                     TotalCaptureResult result) {
                if( target.captureLogged || target.closing || closing || slot != target ) return;
                target.captureLogged = true;
                try {
                    String active = null;
                    Map<String, ? extends CaptureResult> physical = Collections.emptyMap();
                    if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q )
                        active = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);
                    if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ) {
                        physical = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                                ? result.getPhysicalCameraTotalResults() : result.getPhysicalCameraResults();
                    }
                    CaptureResult sensor = target.route.physicalId == null
                            ? result : physical.get(target.route.physicalId);
                    StudioLog.i("Optical capture sensor=" + target.choice.lens.id + ", route=" + target.route
                            + ", logical-active=" + active + ", physical-results=" + physical.keySet()
                            + ", reported-focal-mm=" + (sensor == null ? null : sensor.get(CaptureResult.LENS_FOCAL_LENGTH)));
                    if( target.route.physicalId != null && sensor == null )
                        StudioLog.e("Optical physical output has no sensor metadata for " + target.route.physicalId);
                }
                catch( RuntimeException e ) { StudioLog.e("Optical capture metadata unavailable", e); }
            }
        };
    }

    private void failSlot(Slot failed, String message, Throwable error) {
        if( failed.closed || failed.failed || failed.closing || resourcesClosed ) return;
        failed.failed = true;
        StudioLog.e("Optical " + message + " route=" + failed.route, error);
        if( failed.opening ) error(message + ". Waiting for the camera to close before recovery.");
        if( failed.timeout != null ) handler.removeCallbacks(failed.timeout);
        closeSlot(failed, () -> {
            if( closing ) { finishClose(); return; }
            Choice desired = desiredChoice();
            if( desired != null && !desired.lens.id.equals(failed.choice.lens.id) ) { drive(); return; }
            int fallback = routeFor(failed.choice, profile, failed.routeIndex + 1);
            if( fallback >= 0 ) { open(failed.choice, fallback); return; }
            error(message + ". " + (lastGoodProfile == null ? "Try another lens or video mode." : "Restoring the previous lens."));
            if( lastGoodProfile != null && !recovering ) {
                recovering = true;
                if( zoom.activeLens() != null ) zoom.rollback(failed.choice.policy);
                else if( !configureFacing(lastGoodFront, lastGoodZoom, lastGoodProfile, false) ) { busy = false; emit(); return; }
                profile = lastGoodProfile;
                zoom.request(lastGoodZoom);
                renderer.setGlobalZoom(zoom.desiredZoom());
                drive();
            }
            else {
                busy = false;
                stopRecorder(true);
                emit();
            }
        });
    }

    private void closeSlot(Slot target, Runnable afterClose) {
        if( target.closed ) { if( afterClose != null ) afterClose.run(); return; }
        // Shutdown supersedes a pending handoff/rollback; no later callback may reopen a camera.
        if( closing || target.afterClose == null ) target.afterClose = afterClose;
        if( target.closing ) return;
        target.closing = true;
        target.live = false;
        if( target.timeout != null ) handler.removeCallbacks(target.timeout);
        if( target.focusReset != null ) handler.removeCallbacks(target.focusReset);
        if( target.session != null ) {
            try { target.session.stopRepeating(); } catch( Exception ignored ) { }
            target.session.close();
            target.session = null;
        }
        if( target.device != null ) target.device.close();
        else if( !target.opening ) slotClosed(target);
        // openCamera is asynchronous: wait for onOpened/onError and then onClosed before reopening.
    }

    private void slotClosed(Slot target) {
        if( target.closed ) return;
        target.closed = true;
        if( target.timeout != null ) handler.removeCallbacks(target.timeout);
        if( target.input != null && !resourcesClosed ) {
            try { renderer.releaseInput(target.input); }
            catch( RuntimeException e ) { StudioLog.e("Optical input release failed", e); }
            target.input = null;
        }
        if( slot == target ) slot = null;
        Runnable completion = target.afterClose;
        target.afterClose = null;
        if( closing ) finishClose();
        else if( completion != null ) completion.run();
    }

    private CaptureRequest.Builder request(Slot target) throws CameraAccessException {
        CaptureRequest.Builder builder;
        if( target.route.physicalId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P )
            builder = target.device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD, Collections.singleton(target.route.physicalId));
        else builder = target.device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        builder.addTarget(target.input.surface);
        CameraCharacteristics c = target.choice.lens.characteristics;
        set(builder, target, CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        set(builder, target, CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        int af = has(c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES), CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                && has(target.route.characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES), CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                ? CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO : CaptureRequest.CONTROL_AF_MODE_OFF;
        if( target.focusPoint != null && supportsAutoFocus(target) ) af = CaptureRequest.CONTROL_AF_MODE_AUTO;
        set(builder, target, CaptureRequest.CONTROL_AF_MODE, af);
        set(builder, target, CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
        if( target.focusPoint != null ) {
            applyFocusRegion(builder, target, target.focusPoint, false);
            applyFocusRegion(builder, target, target.focusPoint, true);
        }
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange(target.route.characteristics, target.profile.fps));
        setPhysical(builder, target, CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange(c, target.profile.fps));
        // Camera requests stay at native 1x. Only the renderer applies the requested digital ratio.
        Rect parentArray = target.route.characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if( parentArray != null ) builder.set(CaptureRequest.SCALER_CROP_REGION, parentArray);
        Rect physicalArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if( physicalArray != null ) setPhysical(builder, target, CaptureRequest.SCALER_CROP_REGION, physicalArray);
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ) {
            Range<Float> range = target.route.characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if( range != null && range.contains(1f) ) builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f);
            setPhysical(builder, target, CaptureRequest.CONTROL_ZOOM_RATIO, 1f);
        }
        boolean flash = torch && Boolean.TRUE.equals(c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
        set(builder, target, CaptureRequest.FLASH_MODE, flash ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
        Range<Integer> ev = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        if( ev != null ) {
            int value = evNorm >= 0 ? Math.round(evNorm * ev.getUpper()) : Math.round(-evNorm * ev.getLower());
            Range<Integer> parentEv = target.route.characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            if( parentEv != null ) builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, parentEv.clamp(value));
            setPhysical(builder, target, CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, value);
        }
        boolean stabilize = preferences.getBoolean(PreferenceKeys.VideoStabilizationPreferenceKey, false);
        int stabilization = stabilize && has(c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES), CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                && has(target.route.characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES), CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                ? CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON : CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF;
        set(builder, target, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, stabilization);
        return builder;
    }

    private static <T> void set(CaptureRequest.Builder builder, Slot target, CaptureRequest.Key<T> key, T value) {
        if( value == null ) return;
        builder.set(key, value);
        setPhysical(builder, target, key, value);
    }

    private static <T> void setPhysical(CaptureRequest.Builder builder, Slot target, CaptureRequest.Key<T> key, T value) {
        if( value == null || target.route.physicalId == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P ) return;
        List<CaptureRequest.Key<?>> keys = target.route.characteristics.getAvailablePhysicalCameraRequestKeys();
        if( keys != null && keys.contains(key) ) builder.setPhysicalCameraKey(key, value, target.route.physicalId);
    }

    private void applyFocusRegion(CaptureRequest.Builder builder, Slot target, float[] sensor, boolean physical) {
        CameraCharacteristics c = physical ? target.choice.lens.characteristics : target.route.characteristics;
        Rect array = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if( array == null ) return;
        int half = Math.max(16, Math.min(array.width(), array.height()) / 15);
        int cx = array.left + Math.round(sensor[0] * array.width());
        int cy = array.top + Math.round(sensor[1] * array.height());
        Rect rect = new Rect(cx - half, cy - half, cx + half, cy + half);
        if( !rect.intersect(array) ) return;
        MeteringRectangle[] areas = {new MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX)};
        Integer af = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF), ae = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
        if( physical ) {
            if( af != null && af > 0 ) setPhysical(builder, target, CaptureRequest.CONTROL_AF_REGIONS, areas);
            if( ae != null && ae > 0 ) setPhysical(builder, target, CaptureRequest.CONTROL_AE_REGIONS, areas);
        }
        else {
            if( af != null && af > 0 ) builder.set(CaptureRequest.CONTROL_AF_REGIONS, areas);
            if( ae != null && ae > 0 ) builder.set(CaptureRequest.CONTROL_AE_REGIONS, areas);
        }
    }

    private void repeatCurrent() {
        if( slot == null || !slot.live || slot.closing ) return;
        try { slot.session.setRepeatingRequest(request(slot).build(), captureCallback(slot), handler); }
        catch( Exception e ) { StudioLog.e("Optical controls rejected", e); error("The camera couldn't apply that control."); }
    }

    private static boolean supportsAutoFocus(Slot target) {
        return has(target.choice.lens.characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES), CaptureRequest.CONTROL_AF_MODE_AUTO)
                && has(target.route.characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES), CaptureRequest.CONTROL_AF_MODE_AUTO);
    }

    private void clearFocus(Slot target) {
        target.focusReset = null;
        target.focusPoint = null;
        if( closing || target.closing || !target.live || slot != target ) return;
        try {
            CaptureRequest.Builder cancel = request(target);
            set(cancel, target, CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
            target.session.capture(cancel.build(), null, handler);
            target.session.setRepeatingRequest(request(target).build(), null, handler);
        }
        catch( Exception e ) { StudioLog.e("Optical focus reset failed", e); }
    }

    private static Range<Integer> fpsRange(CameraCharacteristics c, int fps) {
        Range<Integer>[] ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        Range<Integer> best = null;
        if( ranges == null ) return null;
        for( Range<Integer> range : ranges ) {
            if( !range.contains(fps) ) continue;
            if( best == null || range.getUpper() < best.getUpper()
                    || (range.getUpper().equals(best.getUpper()) && range.getLower() > best.getLower()) ) best = range;
        }
        return best;
    }

    private void startRecorder(boolean audioPermission, int orientationEventDegrees) {
        if( recorder != null || busy || slot == null || !slot.live || closing ) return;
        String format = preferences.getString(PreferenceKeys.VideoFormatPreferenceKey, "preference_video_output_format_default");
        if( !"preference_video_output_format_default".equals(format)
                && !"preference_video_output_format_mpeg4_h264".equals(format)
                && !"preference_video_output_format_mpeg4_hevc".equals(format) ) {
            error("Live optical video supports MP4 with H.264 or HEVC. Select an MP4 format in Video settings.");
            return;
        }
        busy = true;
        emit();
        try {
            videoTarget = StudioSaver.beginVideo(context, "VID_" + StudioSaver.stamp() + "_OPTICAL.mp4");
            if( videoTarget == null ) throw new IllegalStateException("Could not create the video file");
            recorder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? new MediaRecorder(context) : new MediaRecorder();
            boolean audio = audioPermission && preferences.getBoolean(PreferenceKeys.RecordAudioPreferenceKey, true)
                    && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            if( audio ) recorder.setAudioSource(audioSource());
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            if( hevc() ) {
                if( Build.VERSION.SDK_INT < Build.VERSION_CODES.N )
                    throw new IllegalStateException("HEVC recording requires Android 7 or later");
                recorder.setVideoEncoder(MediaRecorder.VideoEncoder.HEVC);
            }
            else recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            if( audio ) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                String channels = preferences.getString(PreferenceKeys.RecordAudioChannelsPreferenceKey, "audio_default");
                recorder.setAudioChannels("audio_stereo".equals(channels) ? 2 : 1);
                recorder.setAudioEncodingBitRate(128_000);
                recorder.setAudioSamplingRate(48_000);
            }
            recorder.setVideoSize(profile.height, profile.width);
            recorder.setVideoFrameRate(profile.fps);
            recorder.setVideoEncodingBitRate(bitrate(profile));
            int rounded = ((orientationEventDegrees + 45) / 90 * 90) % 360;
            // SurfaceTexture/GL already produces upright portrait pixels; do not add sensor rotation again.
            recorder.setOrientationHint(Orient.toDisplayDegrees(rounded));
            if( videoTarget.pfd != null ) recorder.setOutputFile(videoTarget.pfd.getFileDescriptor());
            else recorder.setOutputFile(videoTarget.legacyFile.getAbsolutePath());
            recorder.setOnErrorListener((source, what, extra) -> handler.post(() -> {
                if( source != recorder ) return;
                error("The video recorder stopped (" + what + "/" + extra + ").");
                stopRecorder(true);
            }));
            recorder.prepare();
            recorderSurface = recorder.getSurface();
            if( closing ) { releaseRecorder(false); return; }
            recorder.start();
            recorderStarted = true;
            recordStartMs = SystemClock.elapsedRealtime();
            pausedDurationMs = 0;
            paused = false;
            renderer.startEncoder(recorderSurface, profile.height, profile.width, profile.fps);
            StudioLog.i("Optical recording started: one recorder, " + profile + ", portrait=" + profile.height + "x" + profile.width + ", audio=" + audio);
            handler.post(ticker);
        }
        catch( Exception e ) {
            StudioLog.e("Optical recorder start failed", e);
            try { renderer.stopEncoder(); } catch( RuntimeException ignored ) { }
            releaseRecorder(false);
            error("Couldn't start this video format: " + e.getMessage());
        }
        finally { busy = false; emit(); }
    }

    private void stopRecorder(boolean publish) {
        if( recorder == null ) return;
        handler.removeCallbacks(ticker);
        try { renderer.stopEncoder(); }
        catch( RuntimeException e ) { StudioLog.e("Optical encoder detach failed", e); }
        releaseRecorder(publish);
        emit();
    }

    private void releaseRecorder(boolean publish) {
        boolean valid = false;
        if( recorder != null ) {
            if( recorderStarted ) {
                try { recorder.stop(); valid = true; }
                catch( RuntimeException e ) { StudioLog.e("Optical recording finalization failed", e); if( publish ) error("Recording was too short or could not be saved."); }
            }
            try { recorder.release(); } catch( RuntimeException e ) { StudioLog.e("Optical recorder release failed", e); }
            recorder = null;
        }
        if( recorderSurface != null ) { recorderSurface.release(); recorderSurface = null; }
        recorderStarted = false;
        paused = false;
        if( videoTarget != null ) {
            if( publish && valid ) {
                Uri uri = videoTarget.uri != null ? videoTarget.uri : Uri.fromFile(videoTarget.legacyFile);
                StudioSaver.finishVideo(context, videoTarget);
                main.post(() -> listener.onSaved(uri, null));
                StudioLog.i("Optical recording saved " + uri);
            }
            else StudioSaver.abandonVideo(context, videoTarget);
            videoTarget = null;
        }
    }

    private boolean hevc() {
        return "preference_video_output_format_mpeg4_hevc".equals(preferences.getString(PreferenceKeys.VideoFormatPreferenceKey, "preference_video_output_format_default"));
    }

    private int bitrate(Profile profile) {
        try {
            int chosen = Integer.parseInt(preferences.getString(PreferenceKeys.VideoBitratePreferenceKey, "default"));
            if( chosen > 0 ) return chosen;
        }
        catch( NumberFormatException ignored ) { }
        return (int)Math.max(4_000_000L, Math.min(100_000_000L, profile.width * (long)profile.height * profile.fps / 5));
    }

    private int audioSource() {
        String source = preferences.getString(PreferenceKeys.RecordAudioSourcePreferenceKey, "audio_src_camcorder");
        if( "audio_src_mic".equals(source) ) return MediaRecorder.AudioSource.MIC;
        if( "audio_src_default".equals(source) ) return MediaRecorder.AudioSource.DEFAULT;
        if( "audio_src_voice_communication".equals(source) ) return MediaRecorder.AudioSource.VOICE_COMMUNICATION;
        if( "audio_src_voice_recognition".equals(source) ) return MediaRecorder.AudioSource.VOICE_RECOGNITION;
        if( "audio_src_unprocessed".equals(source) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ) return MediaRecorder.AudioSource.UNPROCESSED;
        return MediaRecorder.AudioSource.CAMCORDER;
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if( !recorderStarted || closing ) return;
            emit();
            handler.postDelayed(this, 250);
        }
    };

    private long recordDuration() {
        if( !recorderStarted ) return 0;
        long end = paused ? pausedAtMs : SystemClock.elapsedRealtime();
        return Math.max(0, end - recordStartMs - pausedDurationMs);
    }

    private void finishClose() {
        if( resourcesClosed || slot != null ) return;
        stopRecorder(true);
        try { renderer.close(); } catch( RuntimeException e ) { StudioLog.e("Optical renderer close failed", e); }
        saveExecutor.shutdown();
        List<Runnable> callbacks;
        synchronized( closeCallbacks ) {
            resourcesClosed = true;
            callbacks = new ArrayList<>(closeCallbacks);
            closeCallbacks.clear();
        }
        busy = false;
        emit();
        for( Runnable callback : callbacks ) main.post(callback);
        thread.quitSafely();
    }

    private void emit() {
        State snapshot = new State(this);
        state = snapshot;
        main.post(() -> listener.onState(snapshot));
    }

    private void error(String message) { main.post(() -> listener.onError(message)); }

    private static boolean has(int[] values, int value) {
        if( values != null ) for( int item : values ) if( item == value ) return true;
        return false;
    }

    private static Bitmap thumbnail(Bitmap bitmap) {
        int width = Math.min(240, bitmap.getWidth());
        int height = Math.max(1, Math.round(bitmap.getHeight() * width / (float)bitmap.getWidth()));
        return Bitmap.createScaledBitmap(bitmap, width, height, true);
    }
}
