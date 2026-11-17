package net.sourceforge.opencamera.studio;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import net.sourceforge.opencamera.op.CameraInventory;
import net.sourceforge.opencamera.op.CameraMode;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Opens every lens a studio mode asked for and keeps their frames on one clock.
 *  Tries a logical camera's physical streams first (hardware sync on the OnePlus 9 Pro when the
 *  HAL exposes it), then separate CameraDevices, then smaller subsets when the HAL refuses. */
public final class StudioEngine {
    public interface Ui {
        void onOpening();
        void onOpened(LensPlanner.Plan plan, int openedCount, String detail);
        void onFailed(String message);
        void onSaved(String message, Bitmap thumb);
        void onRecording(boolean recording);
        void onAf(boolean focused);
    }

    public static final class Status {
        public final boolean ready;
        public final int opened;
        public final int live;
        public final long skewNs;
        public final boolean recording;
        public final boolean paused;
        public final long recordMs;
        public final boolean longExposure;
        public final float longProgress;
        public final String line;

        Status(boolean ready, int opened, int live, long skewNs, boolean recording, boolean paused,
               long recordMs, boolean longExposure, float longProgress, String line) {
            this.ready = ready;
            this.opened = opened;
            this.live = live;
            this.skewNs = skewNs;
            this.recording = recording;
            this.paused = paused;
            this.recordMs = recordMs;
            this.longExposure = longExposure;
            this.longProgress = longProgress;
            this.line = line;
        }
    }

    private enum OpenResult { OK, OPEN_REJECTED, SESSION_REJECTED }

    private static final long SYNC_TOLERANCE_NS = 16_000_000L;

    private final Context context;
    private final Ui ui;
    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private final HandlerThread camThread;
    private final Handler camHandler;
    private final ExecutorService opener;
    private final ExecutorService saveExec;

    private GLSurfaceView glView;
    private GlCompositor gl;
    private CameraMode mode = CameraMode.MULTI_CAM;
    private volatile boolean resumed;
    private volatile boolean ready;
    private volatile int generation;
    private volatile List<DeviceGroup> groups = Collections.emptyList();
    private volatile List<CamPipe> pipes = Collections.emptyList();
    private volatile LensPlanner.Plan plan;
    private volatile int primary;
    private volatile int deviceOrientation;
    private volatile boolean audioGranted;
    private volatile boolean recording;
    private volatile boolean paused;
    private volatile long recordStartMs;
    private volatile boolean longRunning;
    private volatile long longStartMs;
    private volatile int exposureMs = 2000;
    private volatile int flash; // 0 off, 1 auto, 2 on, 3 torch
    private volatile float zoom = 1.0f;
    private volatile float evNorm;
    private volatile boolean aeLock;
    private volatile boolean focusHeld;
    private volatile float focusNx = 0.5f;
    private volatile float focusNy = 0.5f;
    private volatile boolean wantFront;
    private volatile GlCompositor.Layout layout = GlCompositor.Layout.PIP;
    private volatile int filmLook;
    private volatile boolean portraitStrong;
    private volatile String statusLine = "";
    private long skewSum;
    private int skewCount;
    private int afPosted = -1;

    public StudioEngine(Context context, Ui ui) {
        this.context = context.getApplicationContext();
        this.ui = ui;
        camThread = new HandlerThread("StudioCam");
        camThread.start();
        camHandler = new Handler(camThread.getLooper());
        opener = Executors.newSingleThreadExecutor(r -> new Thread(r, "StudioOpen"));
        saveExec = Executors.newSingleThreadExecutor(r -> new Thread(r, "StudioSave"));
    }

    public void attach(GLSurfaceView view, GlCompositor compositor) {
        this.glView = view;
        this.gl = compositor;
    }

    public void setMode(CameraMode mode) {
        if( mode != null )
            this.mode = mode;
    }

    public CameraMode getMode() {
        return mode;
    }

    public void setWantFront(boolean wantFront) {
        this.wantFront = wantFront;
    }

    public boolean wantFront() {
        return wantFront;
    }

    public LensPlanner.LensRef getSelectedLens() {
        List<CamPipe> current = pipes;
        int index = primary;
        return current.isEmpty() ? null : current.get(Math.max(0, Math.min(index, current.size() - 1))).lens;
    }

    public float getZoom() {
        return zoom;
    }

    public void restoreSelection(boolean front, float zoom) {
        wantFront = front;
        this.zoom = zoom > 0.0f ? zoom : 1.0f;
    }

    public void setLayout(GlCompositor.Layout layout) {
        if( layout == null )
            return;
        this.layout = layout;
        if( gl != null )
            gl.setLayout(layout);
    }

    public void setFilmLook(int look) {
        filmLook = look;
        if( gl != null )
            gl.setFilmLook(look);
    }

    public void setPortraitStrong(boolean strong) {
        portraitStrong = strong;
        if( gl != null )
            gl.setPortraitBlur(strong ? 0.028f : 0.012f);
    }

    public void setExposureSeconds(int seconds) {
        exposureMs = Math.max(1, Math.min(seconds, 30)) * 1000;
    }

    public void setAudioGranted(boolean granted) {
        audioGranted = granted;
    }

    public void setDeviceOrientation(int orientationEventDegrees) {
        deviceOrientation = orientationEventDegrees;
    }

    public void setZoom(float z, boolean settle) {
        camHandler.post(() -> applyZoom(z, settle));
    }

    public void setPrimary(int index) {
        camHandler.post(() -> {
            if( index < 0 || index >= pipes.size() || index == primary )
                return;
            primary = index;
            if( gl != null )
                gl.setPrimary(index);
            zoom = pipes.get(index).lens.zoomFactor;
            applyRepeatingSafe();
            postUi(() -> ui.onOpened(plan, pipes.size(), statusLine));
        });
    }

    public void tapFocus(float nx, float ny) {
        focusNx = nx;
        focusNy = ny;
        focusHeld = true;
        camHandler.post(() -> {
            applyRepeatingSafe();
            triggerFocus();
        });
    }

    public void setEvNorm(float ev) {
        evNorm = Math.max(-1.0f, Math.min(1.0f, ev));
        camHandler.post(this::applyRepeatingSafe);
    }

    public void setAeLock(boolean lock) {
        aeLock = lock;
        camHandler.post(this::applyRepeatingSafe);
    }

    /** @return the flash mode after cycling: 0 off, 1 auto, 2 on, 3 torch. */
    public int cycleFlash() {
        if( mode != null && mode.isVideo )
            flash = flash == 3 ? 0 : 3;
        else
            flash = (flash + 1) % 4;
        camHandler.post(this::applyRepeatingSafe);
        return flash;
    }

    public int flashMode() {
        return flash;
    }

    public boolean flashAvailable() {
        for(CamPipe pipe : pipes) {
            if( pipe.hasFlash )
                return true;
        }
        return false;
    }

    public void start() {
        resumed = true;
        if( gl != null && gl.isReady() )
            requestOpen();
    }

    public void onGlReady() {
        if( resumed )
            requestOpen();
    }

    public void stopBlocking() {
        resumed = false;
        ++generation; // abort an in-flight open before the devices are torn down
        try {
            opener.submit(() -> {
                if( recording )
                    stopRecording(false);
                destroy(groups);
            }).get(8, TimeUnit.SECONDS);
        }
        catch(Exception e) {
            StudioLog.e("stop failed", e);
        }
    }

    public void release() {
        stopBlocking();
        opener.shutdownNow();
        saveExec.shutdownNow();
        camThread.quitSafely();
    }

    public boolean isReady() {
        return ready;
    }

    public boolean isRecording() {
        return recording || longRunning;
    }

    public void shutter() {
        opener.execute(this::shutterOnOpener);
    }

    public void pauseToggle() {
        if( Build.VERSION.SDK_INT < Build.VERSION_CODES.N )
            return;
        camHandler.post(() -> {
            if( !recording )
                return;
            paused = !paused;
            for(CamPipe pipe : pipes) {
                if( pipe.recorder == null )
                    continue;
                try {
                    if( paused )
                        pipe.recorder.pause();
                    else
                        pipe.recorder.resume();
                }
                catch(Exception e) {
                    StudioLog.e("pause failed", e);
                }
            }
        });
    }

    public void snapshot() {
        opener.execute(this::takeStills);
    }

    public Status status() {
        List<CamPipe> list = pipes;
        int live = 0;
        long now = SystemClock.elapsedRealtime();
        Map<String, Long> stamps = new LinkedHashMap<>();
        for(CamPipe pipe : list) {
            if( now - pipe.lastFrameWallMs < 700 && pipe.frames > 0 ) {
                live++;
                if( pipe.lastTimestampNs > 0 )
                    stamps.put(pipe.lens.id, pipe.lastTimestampNs);
            }
        }
        long skew = FrameSync.liveSkewNs(stamps, SYNC_TOLERANCE_NS * 4);
        long rec = 0;
        if( recording )
            rec = Math.max(0, SystemClock.elapsedRealtime() - recordStartMs);
        float progress = 0.0f;
        if( longRunning && exposureMs > 0 )
            progress = Math.min(1.0f, (SystemClock.elapsedRealtime() - longStartMs) / (float)exposureMs);
        return new Status(ready, list.size(), live, skew, recording, paused, rec, longRunning, progress, statusLine);
    }

    private void requestOpen() {
        final int gen = ++generation;
        opener.execute(() -> openInternal(gen));
    }

    private void openInternal(int gen) {
        if( !resumed || gen != generation )
            return;
        postUi(ui::onOpening);
        destroy(groups);
        if( gen != generation || gl == null || glView == null )
            return;
        for(int i = 0; i < 40 && !gl.isReady() && gen == generation; i++)
            sleep(50);
        if( !gl.isReady() || gen != generation ) {
            fail("Camera preview was not ready");
            return;
        }
        CameraInventory inv = CameraInventory.get(context);
        List<LensPlanner.LensRef> sensors = new ArrayList<>();
        List<LensPlanner.LensRef> logicals = new ArrayList<>();
        for(CameraInventory.Lens lens : inv.sensors)
            sensors.add(toRef(lens, false));
        for(CameraInventory.Lens lens : inv.logicals)
            logicals.add(toRef(lens, true));
        LensPlanner.Plan next = LensPlanner.plan(mode, sensors, logicals, wantFront, zoom);
        plan = next;
        if( next.cameras.isEmpty() ) {
            fail("No camera is available to this app");
            return;
        }
        zoom = clampZoom(zoom, next);
        pushEffect();
        List<int[]> attempts = LensPlanner.openAttempts(next.cameras.size(), next.primaryIndex);
        String parent = LensPlanner.commonLogicalParent(next.cameras, logicals);
        String lastReason = "the camera HAL rejected the combination";
        for(int a = 0; a < attempts.size(); a++) {
            if( gen != generation )
                return;
            int [] idx = attempts.get(a);
            List<LensPlanner.LensRef> subset = new ArrayList<>();
            for(int i : idx)
                subset.add(next.cameras.get(i));
            boolean jpeg = !mode.isVideo;
            if( parent != null && subset.size() >= 2 ) {
                List<LensPlanner.LensRef> physical = new ArrayList<>();
                boolean covered = true;
                for(LensPlanner.LensRef lens : subset) {
                    if( !logicalHas(logicals, parent, lens.id) ) {
                        covered = false;
                        break;
                    }
                    physical.add(lens.asPhysicalOf(parent));
                }
                if( covered ) {
                    OpenResult r = openSet(physical, jpeg, gen, true);
                    if( r == OpenResult.OK )
                        return;
                    if( r == OpenResult.SESSION_REJECTED && jpeg && openSet(physical, false, gen, true) == OpenResult.OK )
                        return;
                    lastReason = "physical streams of camera " + parent + " were rejected";
                }
            }
            if( gen != generation )
                return;
            OpenResult direct = openSet(subset, jpeg, gen, false);
            if( direct == OpenResult.OK )
                return;
            if( direct == OpenResult.SESSION_REJECTED && jpeg && openSet(subset, false, gen, false) == OpenResult.OK )
                return;
            if( direct == OpenResult.OPEN_REJECTED )
                lastReason = "the phone won't open that many cameras at once";
            else
                lastReason = "the camera rejected that stream combination";
        }
        fail("Couldn't open the cameras. " + lastReason + ". See Camera info for the HAL report.");
    }

    private OpenResult openSet(List<LensPlanner.LensRef> lenses, boolean jpeg, int gen, boolean logical) {
        if( gen != generation )
            return OpenResult.OPEN_REJECTED;
        List<DeviceGroup> built = new ArrayList<>();
        try {
            CameraManager manager = (CameraManager)context.getSystemService(Context.CAMERA_SERVICE);
            Map<String, DeviceGroup> byId = new LinkedHashMap<>();
            int maxPreview = lenses.size() >= 3 ? 1280 : (lenses.size() == 2 ? 1600 : 1920);
            int maxJpeg = lenses.size() > 1 ? 1920 : 4000;
            int glIndex = 0;
            for(LensPlanner.LensRef lens : lenses) {
                String openId = lens.openId();
                if( openId == null )
                    throw new OpenFail(OpenResult.OPEN_REJECTED, "lens " + lens.id + " cannot be opened");
                DeviceGroup group = byId.get(openId);
                if( group == null ) {
                    group = new DeviceGroup(openId);
                    byId.put(openId, group);
                    built.add(group);
                }
                CamPipe pipe = new CamPipe(lens);
                pipe.glIndex = glIndex++;
                fillPipe(manager, pipe, maxPreview, maxJpeg);
                Surface surface = gl.addStream(glView, pipe.previewW, pipe.previewH);
                if( surface == null || !surface.isValid() )
                    throw new OpenFail(OpenResult.SESSION_REJECTED, "preview surface");
                pipe.previewSurface = surface;
                if( jpeg ) {
                    pipe.jpegReader = ImageReader.newInstance(pipe.jpegW, pipe.jpegH, ImageFormat.JPEG, 2);
                    pipe.jpegSurface = pipe.jpegReader.getSurface();
                    final CamPipe target = pipe;
                    pipe.jpegReader.setOnImageAvailableListener(reader -> onJpeg(target, reader), camHandler);
                }
                group.pipes.add(pipe);
            }
            if( gen != generation )
                throw new OpenFail(OpenResult.OPEN_REJECTED, "cancelled");
            for(DeviceGroup group : built) {
                OpenResult opened = openDevice(manager, group, gen);
                if( opened != OpenResult.OK )
                    throw new OpenFail(opened, "open " + group.openId);
                if( logical )
                    readSync(manager, group);
            }
            for(DeviceGroup group : built) {
                OpenResult configured = configure(group, false, gen);
                if( configured != OpenResult.OK )
                    throw new OpenFail(configured, "session " + group.openId);
            }
            if( gen != generation )
                throw new OpenFail(OpenResult.OPEN_REJECTED, "cancelled");
            publish(built, lenses, logical, jpeg, gen);
            return OpenResult.OK;
        }
        catch(OpenFail fail) {
            StudioLog.i("Open attempt failed (" + (logical ? "logical" : "direct") + ", jpeg=" + jpeg + "): " + fail.reason);
            destroy(built);
            return fail.result;
        }
        catch(Throwable t) {
            StudioLog.e("Open attempt crashed", t);
            destroy(built);
            return OpenResult.SESSION_REJECTED;
        }
    }

    private void publish(List<DeviceGroup> built, List<LensPlanner.LensRef> lenses, boolean logical, boolean jpeg, int gen) throws OpenFail {
        CountDownLatch latch = new CountDownLatch(1);
        String [] error = {null};
        camHandler.post(() -> {
            try {
                if( gen != generation ) {
                    error[0] = "cancelled";
                    return;
                }
                groups = built;
                List<CamPipe> flat = new ArrayList<>();
                for(DeviceGroup group : built)
                    flat.addAll(group.pipes);
                pipes = flat;
                primary = 0;
                for(int i = 0; i < flat.size(); i++) {
                    if( Math.abs(flat.get(i).lens.zoomFactor - zoom) < 0.08f || i == 0 )
                        primary = i;
                }
                // prefer the lens the zoom pills would select
                int want = LensPlanner.indexForZoom(flatLenses(flat), zoom,
                        wantFront ? LensPlanner.FACING_FRONT : LensPlanner.FACING_BACK);
                if( want >= 0 )
                    primary = want;
                ready = true;
                focusHeld = false;
                if( gl != null ) {
                    gl.setPrimary(primary);
                    gl.setLayout(flat.size() > 1 ? layout : GlCompositor.Layout.SINGLE);
                }
                applyRepeating();
                String detail = describe(flat, logical, jpeg);
                statusLine = detail;
                StudioLog.i(detail);
                LensPlanner.Plan shown = plan;
                postUi(() -> ui.onOpened(shown, flat.size(), detail));
            }
            catch(Exception e) {
                error[0] = e.getMessage();
                StudioLog.e("publish failed", e);
            }
            finally {
                latch.countDown();
            }
        });
        await(latch, 3);
        if( error[0] != null )
            throw new OpenFail(OpenResult.SESSION_REJECTED, error[0]);
    }

    private static List<LensPlanner.LensRef> flatLenses(List<CamPipe> flat) {
        List<LensPlanner.LensRef> list = new ArrayList<>();
        for(CamPipe pipe : flat)
            list.add(pipe.lens);
        return list;
    }

    private String describe(List<CamPipe> flat, boolean logical, boolean jpeg) {
        int wanted = plan == null ? flat.size() : plan.cameras.size();
        StringBuilder sb = new StringBuilder();
        sb.append(flat.size());
        if( wanted > flat.size() )
            sb.append(" of ").append(wanted);
        sb.append(flat.size() == 1 ? " camera" : " cameras");
        sb.append(" · ");
        for(int i = 0; i < flat.size(); i++) {
            if( i > 0 )
                sb.append(" + ");
            sb.append(flat.get(i).lens.label);
        }
        if( logical ) {
            String sync = null;
            for(DeviceGroup group : groups) {
                if( group.syncLabel() != null )
                    sync = group.syncLabel();
            }
            sb.append(sync != null ? " · sync " + sync : " · synced");
        }
        else if( flat.size() > 1 ) {
            sb.append(" · timestamp sync");
        }
        if( !jpeg && mode != null && !mode.isVideo )
            sb.append(" · preview stills");
        return sb.toString();
    }

    @SuppressLint("MissingPermission")
    private OpenResult openDevice(CameraManager manager, DeviceGroup group, int gen) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        OpenResult [] result = {OpenResult.OPEN_REJECTED};
        try {
            manager.openCamera(group.openId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    group.device = camera;
                    result[0] = OpenResult.OK;
                    latch.countDown();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    StudioLog.e("camera " + camera.getId() + " disconnected");
                    camera.close();
                    if( group.device == camera )
                        group.device = null;
                    result[0] = OpenResult.OPEN_REJECTED;
                    latch.countDown();
                    if( ready )
                        camHandler.post(() -> fail("A camera was closed by the phone"));
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    StudioLog.e("camera " + camera.getId() + " error " + error + MultiCamProbe.errorName(error));
                    camera.close();
                    result[0] = OpenResult.OPEN_REJECTED;
                    latch.countDown();
                }
            }, camHandler);
        }
        catch(SecurityException e) {
            StudioLog.e("camera permission", e);
            return OpenResult.OPEN_REJECTED;
        }
        catch(Exception e) {
            StudioLog.e("openCamera " + group.openId, e);
            return OpenResult.OPEN_REJECTED;
        }
        if( !latch.await(4, TimeUnit.SECONDS) )
            return OpenResult.OPEN_REJECTED;
        if( gen != generation && group.device != null ) {
            group.device.close();
            group.device = null;
            return OpenResult.OPEN_REJECTED;
        }
        return result[0];
    }

    private OpenResult configure(DeviceGroup group, boolean recordingSession, int gen) throws InterruptedException {
        if( group.device == null )
            return OpenResult.OPEN_REJECTED;
        List<Surface> surfaces = new ArrayList<>();
        List<OutputConfiguration> configs = new ArrayList<>();
        for(CamPipe pipe : group.pipes) {
            if( pipe.previewSurface != null ) {
                surfaces.add(pipe.previewSurface);
                if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.N )
                    configs.add(output(pipe.previewSurface, pipe));
            }
            if( recordingSession && pipe.recorderSurface != null ) {
                surfaces.add(pipe.recorderSurface);
                if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.N )
                    configs.add(output(pipe.recorderSurface, pipe));
            }
            else if( !recordingSession && pipe.jpegSurface != null ) {
                surfaces.add(pipe.jpegSurface);
                if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.N )
                    configs.add(output(pipe.jpegSurface, pipe));
            }
        }
        if( surfaces.isEmpty() )
            return OpenResult.SESSION_REJECTED;
        CountDownLatch latch = new CountDownLatch(1);
        OpenResult [] result = {OpenResult.SESSION_REJECTED};
        CameraCaptureSession.StateCallback callback = new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession session) {
                group.session = session;
                result[0] = OpenResult.OK;
                latch.countDown();
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession session) {
                result[0] = OpenResult.SESSION_REJECTED;
                latch.countDown();
            }
        };
        try {
            if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ) {
                group.device.createCaptureSessionByOutputConfigurations(configs, callback, camHandler);
            }
            else {
                group.device.createCaptureSession(surfaces, callback, camHandler);
            }
        }
        catch(Exception e) {
            StudioLog.e("createCaptureSession " + group.openId, e);
            return OpenResult.SESSION_REJECTED;
        }
        if( !latch.await(4, TimeUnit.SECONDS) )
            return OpenResult.SESSION_REJECTED;
        if( gen != generation )
            return OpenResult.OPEN_REJECTED;
        return result[0];
    }

    private static OutputConfiguration output(Surface surface, CamPipe pipe) {
        OutputConfiguration config = new OutputConfiguration(surface);
        if( pipe.lens.physicalId() != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P )
            config.setPhysicalCameraId(pipe.lens.physicalId());
        return config;
    }

    private void applyRepeating() throws CameraAccessException {
        for(DeviceGroup group : groups) {
            if( group.device == null || group.session == null )
                continue;
            int template = recording ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW;
            CaptureRequest.Builder b = group.device.createCaptureRequest(template);
            CamPipe settings = null;
            for(CamPipe pipe : group.pipes) {
                if( pipe.previewSurface != null && pipe.previewSurface.isValid() )
                    b.addTarget(pipe.previewSurface);
                if( recording && pipe.recorderSurface != null )
                    b.addTarget(pipe.recorderSurface);
                if( settings == null || pipes.indexOf(pipe) == primary )
                    settings = pipe;
            }
            if( settings != null )
                fillRequest(settings, b, recording, false);
            group.session.setRepeatingRequest(b.build(), callbackFor(group), camHandler);
        }
    }

    private void applyRepeatingSafe() {
        if( !ready )
            return;
        try {
            applyRepeating();
        }
        catch(Exception e) {
            StudioLog.e("repeating request failed", e);
        }
    }

    private void fillRequest(CamPipe pipe, CaptureRequest.Builder b, boolean video, boolean still) {
        int af = video ? CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO : CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
        if( focusHeld && contains(pipe.afModes, CaptureRequest.CONTROL_AF_MODE_AUTO) )
            af = CaptureRequest.CONTROL_AF_MODE_AUTO;
        trySet(b, CaptureRequest.CONTROL_AF_MODE, af);
        trySet(b, CaptureRequest.CONTROL_AE_LOCK, aeLock);
        applyFlash(pipe, b, video, still);
        applyZoom(pipe, b);
        applyEv(pipe, b);
        applyFps(pipe, b, mode == CameraMode.FILM);
        if( focusHeld )
            applyRegions(pipe, b);
        if( !video && contains(pipe.oisModes, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) )
            trySet(b, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON);
        if( video && pipes.size() == 1 )
            trySet(b, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
    }

    private void applyFlash(CamPipe pipe, CaptureRequest.Builder b, boolean video, boolean still) {
        if( !pipe.hasFlash || flash == 0 ) {
            trySet(b, CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            trySet(b, CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
            return;
        }
        if( flash == 3 ) {
            trySet(b, CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            trySet(b, CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
            return;
        }
        if( video ) {
            trySet(b, CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            trySet(b, CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
            return;
        }
        if( flash == 2 && contains(pipe.aeModes, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH) )
            trySet(b, CaptureRequest.CONTROL_AE_MODE, still ? CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH : CaptureRequest.CONTROL_AE_MODE_ON);
        else if( flash == 1 && contains(pipe.aeModes, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH) )
            trySet(b, CaptureRequest.CONTROL_AE_MODE, still ? CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH : CaptureRequest.CONTROL_AE_MODE_ON);
        else
            trySet(b, CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        if( !still )
            trySet(b, CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
    }

    private void applyZoom(CamPipe pipe, CaptureRequest.Builder b) {
        float ratio = 1.0f;
        if( pipes.isEmpty() || pipe == pipes.get(Math.min(primary, pipes.size() - 1)) )
            ratio = plan != null && plan.logicalZoom ? zoom : LensPlanner.digitalRatio(pipe.lens, zoom);
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && pipe.zoomRange != null ) {
            float z = Math.max(pipe.zoomRange.getLower(), Math.min(pipe.zoomRange.getUpper(), ratio));
            trySet(b, CaptureRequest.CONTROL_ZOOM_RATIO, z);
            return;
        }
        if( pipe.activeArray == null )
            return;
        float max = pipe.maxDigital;
        if( ratio > max )
            ratio = max;
        if( ratio < 1.0f )
            ratio = 1.0f;
        int cw = even(Math.round(pipe.activeArray.width() / ratio));
        int ch = even(Math.round(pipe.activeArray.height() / ratio));
        int x = pipe.activeArray.left + (pipe.activeArray.width() - cw) / 2;
        int y = pipe.activeArray.top + (pipe.activeArray.height() - ch) / 2;
        trySet(b, CaptureRequest.SCALER_CROP_REGION, new Rect(x, y, x + cw, y + ch));
    }

    private void applyEv(CamPipe pipe, CaptureRequest.Builder b) {
        if( pipe.evRange == null )
            return;
        int span = pipe.evRange.getUpper() - pipe.evRange.getLower();
        int value = pipe.evRange.getLower() + Math.round((evNorm + 1.0f) * 0.5f * span);
        value = Math.max(pipe.evRange.getLower(), Math.min(pipe.evRange.getUpper(), value));
        trySet(b, CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, value);
    }

    private void applyFps(CamPipe pipe, CaptureRequest.Builder b, boolean film) {
        Range<Integer> range = chooseFps(pipe.fpsRanges, film);
        if( range != null )
            trySet(b, CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range);
    }

    private void applyRegions(CamPipe pipe, CaptureRequest.Builder b) {
        if( pipe.activeArray == null || pipe.characteristics == null )
            return;
        Integer maxAf = pipe.characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
        if( maxAf == null || maxAf <= 0 )
            return;
        // Studio's window stays portrait even when the capture orientation changes.
        float [] sensor = Orient.viewToSensor(focusNx, focusNy, Orient.displayRotation(pipe.sensorOrientation, 0, pipe.front()), pipe.front());
        Rect crop = pipe.activeArray;
        int cx = crop.left + Math.round(sensor[0] * crop.width());
        int cy = crop.top + Math.round(sensor[1] * crop.height());
        int half = Math.max(32, Math.min(crop.width(), crop.height()) / 10);
        Rect rect = new Rect(cx - half, cy - half, cx + half, cy + half);
        rect.intersect(crop);
        if( rect.width() < 2 || rect.height() < 2 )
            return;
        MeteringRectangle area = new MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX);
        trySet(b, CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{area});
        trySet(b, CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{area});
    }

    private void triggerFocus() {
        if( pipes.isEmpty() )
            return;
        CamPipe pipe = pipes.get(Math.min(primary, pipes.size() - 1));
        DeviceGroup group = groupOf(pipe);
        if( group == null || group.device == null || group.session == null || pipe.previewSurface == null )
            return;
        try {
            CaptureRequest.Builder b = group.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(pipe.previewSurface);
            fillRequest(pipe, b, false, false);
            trySet(b, CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
            group.session.capture(b.build(), callbackFor(group), camHandler);
        }
        catch(Exception e) {
            StudioLog.e("focus trigger failed", e);
        }
    }

    private void applyZoom(float z, boolean settle) {
        if( plan != null ) {
            if( z < plan.minZoom )
                z = plan.minZoom;
            if( z > plan.maxZoom )
                z = plan.maxZoom;
        }
        if( !ready || pipes.isEmpty() )
            return;
        List<LensPlanner.LensRef> opened = flatLenses(pipes);
        int facing = pipes.get(Math.max(0, Math.min(primary, pipes.size() - 1))).lens.facing;
        boolean switchLens = LensPlanner.needsLensSwitch(plan, opened, z, facing);
        if( switchLens && (recording || longRunning) ) {
            // Keep the value aligned with the open lens while its recording is active.
            if( settle )
                postUi(() -> ui.onFailed("Stop recording to switch to this lens"));
            return;
        }
        zoom = z;
        int idx = LensPlanner.indexForZoom(opened, zoom, facing);
        if( idx >= 0 && idx != primary && pipes.size() > 1 ) {
            primary = idx;
            if( gl != null )
                gl.setPrimary(idx);
        }
        applyRepeatingSafe();
        if( settle && switchLens )
            requestOpen();
    }

    private CameraCaptureSession.CaptureCallback callbackFor(DeviceGroup group) {
        return new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                note(group, result);
            }

            @Override
            public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
                StudioLog.e("capture failed on " + group.openId + " reason " + failure.getReason());
            }
        };
    }

    private void note(DeviceGroup group, TotalCaptureResult result) {
        long now = SystemClock.elapsedRealtime();
        boolean anyPhysical = false;
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ) {
            try {
                Map<String, CaptureResult> physical = result.getPhysicalCameraResults();
                if( physical != null && !physical.isEmpty() ) {
                    for(CamPipe pipe : group.pipes) {
                        String pid = pipe.lens.physicalId();
                        if( pid == null )
                            continue;
                        CaptureResult part = physical.get(pid);
                        if( part == null )
                            continue;
                        Long ts = part.get(CaptureResult.SENSOR_TIMESTAMP);
                        if( ts != null ) {
                            pipe.lastTimestampNs = ts;
                            pipe.lastFrameWallMs = now;
                            pipe.frames++;
                            anyPhysical = true;
                        }
                    }
                }
            }
            catch(Exception ignored) {
            }
        }
        Long ts = result.get(CaptureResult.SENSOR_TIMESTAMP);
        if( !anyPhysical && ts != null ) {
            for(CamPipe pipe : group.pipes) {
                pipe.lastTimestampNs = ts;
                pipe.lastFrameWallMs = now;
                pipe.frames++;
            }
        }
        Integer af = result.get(CaptureResult.CONTROL_AF_STATE);
        if( af != null && !pipes.isEmpty() ) {
            CamPipe mainPipe = pipes.get(Math.min(primary, pipes.size() - 1));
            if( group.pipes.contains(mainPipe) ) {
                mainPipe.afState = af;
                int posted = -1;
                if( af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || af == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED )
                    posted = 1;
                else if( af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED || af == CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED )
                    posted = 0;
                if( posted >= 0 && posted != afPosted ) {
                    afPosted = posted;
                    boolean ok = posted == 1;
                    postUi(() -> ui.onAf(ok));
                }
            }
        }
        if( recording && pipes.size() > 1 ) {
            Map<String, Long> stamps = new LinkedHashMap<>();
            for(CamPipe pipe : pipes) {
                if( pipe.lastTimestampNs > 0 )
                    stamps.put(pipe.lens.id, pipe.lastTimestampNs);
            }
            long skew = FrameSync.liveSkewNs(stamps, SYNC_TOLERANCE_NS * 4);
            if( skew >= 0 ) {
                skewSum += skew;
                skewCount++;
            }
        }
    }

    private void pushEffect() {
        if( gl == null )
            return;
        if( mode == CameraMode.FILM )
            gl.setEffect(GlCompositor.Effect.FILM);
        else if( mode == CameraMode.PORTRAIT )
            gl.setEffect(GlCompositor.Effect.PORTRAIT);
        else
            gl.setEffect(GlCompositor.Effect.NONE);
        gl.setFilmLook(filmLook);
        gl.setPortraitBlur(portraitStrong ? 0.028f : 0.012f);
        gl.setLayout(layout);
        if( mode != CameraMode.LONG_EXPOSURE )
            gl.endAccumulation();
    }

    private void shutterOnOpener() {
        if( !ready ) {
            fail("Cameras are still opening");
            return;
        }
        if( mode == CameraMode.LONG_EXPOSURE ) {
            if( longRunning )
                finishLong();
            else
                startLong();
            return;
        }
        if( mode != null && mode.isVideo ) {
            if( recording )
                stopRecording(true);
            else
                startRecording();
            return;
        }
        takeStills();
    }

    private void startLong() {
        if( gl == null )
            return;
        gl.beginAccumulation();
        longRunning = true;
        longStartMs = SystemClock.elapsedRealtime();
        postUi(() -> ui.onRecording(true));
        main.postDelayed(this::finishLong, exposureMs);
    }

    private void finishLong() {
        if( !longRunning )
            return;
        longRunning = false;
        main.removeCallbacks(this::finishLong);
        if( gl == null || glView == null ) {
            postUi(() -> ui.onRecording(false));
            return;
        }
        String name = "IMG_" + StudioSaver.stamp() + "_LONG.jpg";
        gl.capture(glView, main, bitmap -> {
            gl.endAccumulation();
            postUi(() -> ui.onRecording(false));
            if( bitmap == null ) {
                fail("Long exposure produced no image");
                return;
            }
            saveExec.execute(() -> {
                android.net.Uri uri = StudioSaver.saveBitmap(context, bitmap, name);
                Bitmap thumb = scale(bitmap, 220);
                postUi(() -> ui.onSaved(uri != null ? "Saved long exposure" : "Couldn't save the photo", thumb));
            });
        });
    }

    private void startRecording() {
        if( recording || pipes.isEmpty() )
            return;
        final int gen = generation;
        List<CamPipe> targets = new ArrayList<>(pipes);
        String stamp = StudioSaver.stamp();
        int fps = mode == CameraMode.FILM ? 24 : 30;
        boolean any = false;
        for(int i = 0; i < targets.size(); i++) {
            CamPipe pipe = targets.get(i);
            StudioSaver.VideoTarget video = StudioSaver.beginVideo(context, "VID_" + stamp + "_" + safe(pipe.lens.label) + ".mp4");
            if( video == null )
                continue;
            try {
                MediaRecorder recorder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? new MediaRecorder(context) : new MediaRecorder();
                recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                boolean audio = audioGranted && i == primary;
                if( audio )
                    recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                if( audio )
                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                recorder.setVideoSize(pipe.previewW, pipe.previewH);
                recorder.setVideoFrameRate(fps);
                int bits = pipe.previewW >= 1920 ? 12_000_000 : 6_000_000;
                recorder.setVideoEncodingBitRate(bits);
                if( audio ) {
                    recorder.setAudioEncodingBitRate(128_000);
                    recorder.setAudioSamplingRate(44100);
                }
                recorder.setOrientationHint(Orient.jpegOrientation(pipe.sensorOrientation, displayDegrees(), pipe.front()));
                if( video.pfd != null )
                    recorder.setOutputFile(video.pfd.getFileDescriptor());
                else if( video.legacyFile != null )
                    recorder.setOutputFile(video.legacyFile.getAbsolutePath());
                recorder.prepare();
                pipe.recorder = recorder;
                pipe.recorderSurface = recorder.getSurface();
                pipe.video = video;
                any = true;
            }
            catch(Exception e) {
                StudioLog.e("recorder prepare failed for " + pipe.lens.label, e);
                StudioSaver.abandonVideo(context, video);
                pipe.video = null;
            }
        }
        if( !any ) {
            fail("Couldn't start recording");
            return;
        }
        boolean sessionsOk = true;
        for(DeviceGroup group : groups) {
            try {
                if( configure(group, true, gen) != OpenResult.OK )
                    sessionsOk = false;
            }
            catch(Exception e) {
                sessionsOk = false;
                StudioLog.e("record session failed", e);
            }
        }
        if( !sessionsOk || gen != generation ) {
            abandonRecorders(targets);
            restorePreview(gen);
            fail("This combination can't record. Try fewer cameras.");
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder sidecar = new StringBuilder();
        sidecar.append("group=").append(stamp).append('\n');
        camHandler.post(() -> {
            try {
                recording = true;
                paused = false;
                recordStartMs = SystemClock.elapsedRealtime();
                skewSum = 0;
                skewCount = 0;
                for(CamPipe pipe : targets) {
                    if( pipe.recorder == null )
                        continue;
                    pipe.recorder.start();
                    pipe.recordStartNs = System.nanoTime();
                    sidecar.append(pipe.lens.id).append(' ').append(pipe.lens.label)
                            .append(" startNs=").append(pipe.recordStartNs).append('\n');
                }
                applyRepeating();
            }
            catch(Exception e) {
                StudioLog.e("recorder start failed", e);
                recording = false;
            }
            finally {
                latch.countDown();
            }
        });
        await(latch, 3);
        if( !recording ) {
            abandonRecorders(targets);
            restorePreview(gen);
            fail("Recording failed to start");
            return;
        }
        StudioSaver.saveText(context, "VID_" + stamp + "_sync.txt", sidecar.toString());
        postUi(() -> ui.onRecording(true));
    }

    private void stopRecording(boolean restore) {
        if( !recording && !anyRecorder() )
            return;
        List<CamPipe> targets = new ArrayList<>(pipes);
        CountDownLatch latch = new CountDownLatch(1);
        camHandler.post(() -> {
            try {
                for(DeviceGroup group : groups) {
                    if( group.session != null ) {
                        try {
                            group.session.stopRepeating();
                        }
                        catch(Exception ignored) {
                        }
                    }
                }
            }
            finally {
                latch.countDown();
            }
        });
        await(latch, 2);
        long skew = skewCount > 0 ? skewSum / skewCount : -1;
        int saved = 0;
        for(CamPipe pipe : targets) {
            if( pipe.recorder == null )
                continue;
            try {
                pipe.recorder.stop();
                saved++;
            }
            catch(Exception e) {
                StudioLog.e("recorder stop failed", e);
                StudioSaver.abandonVideo(context, pipe.video);
                pipe.video = null;
            }
            if( pipe.video != null ) {
                StudioSaver.finishVideo(context, pipe.video);
                pipe.video = null;
            }
            pipe.closeRecorder();
        }
        recording = false;
        paused = false;
        if( skew >= 0 )
            StudioLog.i(String.format(Locale.US, "record sync average %.1f ms", skew / 1.0e6));
        if( restore )
            restorePreview(generation);
        int count = saved;
        String msg = saved == 0 ? "Recording failed" : ("Saved " + saved + (saved == 1 ? " video" : " videos"));
        if( skew >= 0 )
            msg += String.format(Locale.US, " · sync %.0fms", skew / 1.0e6);
        String text = msg;
        postUi(() -> {
            ui.onRecording(false);
            ui.onSaved(text, null);
        });
    }

    private void restorePreview(int gen) {
        for(DeviceGroup group : groups) {
            try {
                configure(group, false, gen);
            }
            catch(Exception e) {
                StudioLog.e("restore preview failed", e);
            }
        }
        CountDownLatch latch = new CountDownLatch(1);
        camHandler.post(() -> {
            ready = true;
            applyRepeatingSafe();
            latch.countDown();
        });
        await(latch, 2);
    }

    private void abandonRecorders(List<CamPipe> targets) {
        for(CamPipe pipe : targets) {
            StudioSaver.abandonVideo(context, pipe.video);
            pipe.video = null;
            pipe.closeRecorder();
        }
        recording = false;
    }

    private boolean anyRecorder() {
        for(CamPipe pipe : pipes) {
            if( pipe.recorder != null )
                return true;
        }
        return false;
    }

    private void takeStills() {
        List<CamPipe> targets = new ArrayList<>(pipes);
        if( targets.isEmpty() )
            return;
        String stamp = StudioSaver.stamp();
        boolean needGl = mode == CameraMode.PORTRAIT || mode == CameraMode.LONG_EXPOSURE;
        int jpegPipes = 0;
        for(CamPipe pipe : targets) {
            if( pipe.jpegSurface != null )
                jpegPipes++;
        }
        if( jpegPipes == 0 )
            needGl = true;
        AtomicInteger pending = new AtomicInteger((needGl ? 1 : 0) + jpegPipes);
        AtomicInteger saved = new AtomicInteger();
        Runnable done = () -> {
            if( pending.decrementAndGet() == 0 ) {
                int n = saved.get();
                postUi(() -> ui.onSaved(n == 0 ? "Couldn't save" : ("Saved " + n + (n == 1 ? " photo" : " photos")), null));
            }
        };
        if( needGl && gl != null && glView != null ) {
            String name = "IMG_" + stamp + "_" + (mode == CameraMode.PORTRAIT ? "PORTRAIT" : "VIEW") + ".jpg";
            gl.capture(glView, main, bitmap -> saveExec.execute(() -> {
                if( bitmap != null && StudioSaver.saveBitmap(context, bitmap, name) != null ) {
                    saved.incrementAndGet();
                    Bitmap thumb = scale(bitmap, 220);
                    postUi(() -> ui.onSaved(null, thumb));
                }
                done.run();
            }));
        }
        this.stillPending = pending;
        this.stillSaved = saved;
        this.stillDone = done;
        final int gen = generation;
        camHandler.post(() -> {
            if( gen != generation )
                return;
            for(CamPipe pipe : targets) {
                if( pipe.jpegSurface == null || pipe.jpegReader == null )
                    continue;
                DeviceGroup group = groupOf(pipe);
                if( group == null || group.device == null || group.session == null ) {
                    done.run();
                    continue;
                }
                String name = "IMG_" + stamp + "_" + safe(pipe.lens.label) + ".jpg";
                synchronized( pipe.jpegNames ) {
                    pipe.jpegNames.add(name);
                }
                try {
                    CaptureRequest.Builder b = group.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                    b.addTarget(pipe.jpegSurface);
                    fillRequest(pipe, b, false, true);
                    trySet(b, CaptureRequest.JPEG_ORIENTATION, Orient.jpegOrientation(pipe.sensorOrientation, displayDegrees(), pipe.front()));
                    trySet(b, CaptureRequest.JPEG_QUALITY, (byte)92);
                    group.session.capture(b.build(), callbackFor(group), camHandler);
                }
                catch(Exception e) {
                    StudioLog.e("still capture failed", e);
                    done.run();
                }
            }
        });
        if( jpegPipes == 0 && !needGl )
            done.run();
    }

    private AtomicInteger stillPending;
    private AtomicInteger stillSaved;
    private Runnable stillDone;

    private void onJpeg(CamPipe pipe, ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireNextImage();
            if( image == null )
                return;
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte [] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            String name;
            synchronized( pipe.jpegNames ) {
                name = pipe.jpegNames.isEmpty() ? ("IMG_" + StudioSaver.stamp() + ".jpg") : pipe.jpegNames.removeFirst();
            }
            final String fileName = name;
            saveExec.execute(() -> {
                android.net.Uri uri = StudioSaver.saveJpeg(context, bytes, fileName);
                if( uri != null && stillSaved != null )
                    stillSaved.incrementAndGet();
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 8;
                Bitmap thumb = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if( thumb != null )
                    postUi(() -> ui.onSaved(null, thumb));
                if( stillDone != null )
                    stillDone.run();
            });
        }
        catch(Exception e) {
            StudioLog.e("jpeg read failed", e);
            if( stillDone != null )
                stillDone.run();
        }
        finally {
            if( image != null )
                image.close();
        }
    }

    private void destroy(List<DeviceGroup> built) {
        if( built == null || built.isEmpty() ) {
            ready = false;
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        camHandler.post(() -> {
            try {
                if( recording )
                    recording = false;
                for(DeviceGroup group : built)
                    group.closeDevice();
                if( groups == built ) {
                    groups = Collections.emptyList();
                    pipes = Collections.emptyList();
                    ready = false;
                }
            }
            finally {
                latch.countDown();
            }
        });
        await(latch, 2);
        for(DeviceGroup group : built) {
            for(CamPipe pipe : group.pipes) {
                StudioSaver.abandonVideo(context, pipe.video);
                pipe.video = null;
                pipe.closeOutputs();
            }
        }
        if( gl != null && glView != null )
            gl.releaseStreams(glView);
        sleep(120);
    }

    private void fillPipe(CameraManager manager, CamPipe pipe, int maxPreview, int maxJpeg) throws CameraAccessException {
        CameraCharacteristics chars = null;
        String physical = pipe.lens.physicalId();
        if( physical != null ) {
            try {
                chars = manager.getCameraCharacteristics(physical);
            }
            catch(Exception ignored) {
            }
        }
        if( chars == null )
            chars = manager.getCameraCharacteristics(pipe.lens.openId());
        pipe.characteristics = chars;
        Rect active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        pipe.activeArray = active;
        Integer sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
        pipe.sensorOrientation = sensor != null ? sensor : pipe.lens.sensorOrientation;
        Boolean flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        pipe.hasFlash = flash != null && flash;
        pipe.evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        pipe.fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        int [] ae = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
        if( ae != null )
            pipe.aeModes = ae;
        int [] af = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if( af != null )
            pipe.afModes = af;
        int [] ois = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        if( ois != null )
            pipe.oisModes = ois;
        Float digital = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        if( digital != null && digital > 1.0f )
            pipe.maxDigital = digital;
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ) {
            pipe.zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
        }
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if( map != null ) {
            SizePick.Sz preview = SizePick.choose(toInts(map.getOutputSizes(android.graphics.SurfaceTexture.class)), maxPreview, 16.0 / 9.0);
            pipe.previewW = preview.w;
            pipe.previewH = preview.h;
            SizePick.Sz jpg = SizePick.choose(toInts(map.getOutputSizes(ImageFormat.JPEG)), maxJpeg, 16.0 / 9.0);
            pipe.jpegW = jpg.w;
            pipe.jpegH = jpg.h;
        }
    }

    private void readSync(CameraManager manager, DeviceGroup group) {
        if( Build.VERSION.SDK_INT < Build.VERSION_CODES.P )
            return;
        try {
            CameraCharacteristics chars = manager.getCameraCharacteristics(group.openId);
            Integer sync = chars.get(CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE);
            group.syncType = sync != null ? sync : -1;
        }
        catch(Exception ignored) {
        }
    }

    private DeviceGroup groupOf(CamPipe pipe) {
        for(DeviceGroup group : groups) {
            if( group.pipes.contains(pipe) )
                return group;
        }
        return null;
    }

    private int displayDegrees() {
        return Orient.toDisplayDegrees(deviceOrientation);
    }

    private void fail(String message) {
        StudioLog.e(message);
        statusLine = message;
        ready = false;
        postUi(() -> ui.onFailed(message));
    }

    private void postUi(Runnable runnable) {
        main.post(runnable);
    }

    private static void await(CountDownLatch latch, int seconds) {
        try {
            latch.await(seconds, TimeUnit.SECONDS);
        }
        catch(InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        }
        catch(InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static float clampZoom(float zoom, LensPlanner.Plan plan) {
        if( plan == null )
            return zoom <= 0.0f ? 1.0f : zoom;
        if( zoom <= 0.0f )
            zoom = 1.0f;
        if( zoom < plan.minZoom )
            return plan.minZoom;
        if( zoom > plan.maxZoom )
            return plan.maxZoom;
        return zoom;
    }

    private static int even(int v) {
        return v - (v & 1);
    }

    private static boolean contains(int [] values, int want) {
        if( values == null )
            return false;
        for(int v : values) {
            if( v == want )
                return true;
        }
        return false;
    }

    private static <T> void trySet(CaptureRequest.Builder b, CaptureRequest.Key<T> key, T value) {
        try {
            b.set(key, value);
        }
        catch(IllegalArgumentException ignored) {
        }
    }

    private static Range<Integer> chooseFps(Range<Integer> [] ranges, boolean film) {
        if( ranges == null || ranges.length == 0 )
            return null;
        if( film ) {
            Range<Integer> exact = null;
            Range<Integer> contains = null;
            for(Range<Integer> range : ranges) {
                if( range.getLower() == 24 && range.getUpper() == 24 )
                    exact = range;
                if( range.getLower() <= 24 && range.getUpper() >= 24 && (contains == null || range.getUpper() < contains.getUpper()) )
                    contains = range;
            }
            if( exact != null )
                return exact;
            if( contains != null )
                return contains;
        }
        for(Range<Integer> range : ranges) {
            if( range.getLower() == 30 && range.getUpper() == 30 )
                return range;
        }
        for(Range<Integer> range : ranges) {
            if( range.getLower() <= 30 && range.getUpper() >= 30 )
                return range;
        }
        return ranges[0];
    }

    private static int [][] toInts(Size [] sizes) {
        if( sizes == null )
            return new int[0][];
        int [][] out = new int[sizes.length][2];
        for(int i = 0; i < sizes.length; i++) {
            out[i][0] = sizes[i].getWidth();
            out[i][1] = sizes[i].getHeight();
        }
        return out;
    }

    private static String safe(String label) {
        if( label == null )
            return "Cam";
        String s = label.replace(" ", "").replace("/", "");
        if( s.length() > 16 )
            s = s.substring(0, 16);
        return s;
    }

    private static Bitmap scale(Bitmap src, int width) {
        if( src == null || src.getWidth() <= 0 )
            return null;
        int h = Math.max(1, src.getHeight() * width / src.getWidth());
        return Bitmap.createScaledBitmap(src, width, h, true);
    }

    private static LensPlanner.LensRef toRef(CameraInventory.Lens lens, boolean logical) {
        boolean flash = false;
        if( lens.characteristics != null ) {
            Boolean has = lens.characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            flash = has != null && has;
        }
        List<String> parents = new ArrayList<>(lens.logicalParents);
        List<String> physical = logical ? Arrays.asList(lens.physicalIds) : Collections.<String>emptyList();
        return new LensPlanner.LensRef(lens.id, lens.directId, lens.hidden, lens.facing, lens.zoomFactor, lens.isMono,
                parents, physical, Math.max(1.0f, lens.maxZoomRatio), lens.shortLabel(), lens.sensorOrientation, flash, lens.minZoomRatio);
    }

    private static boolean logicalHas(List<LensPlanner.LensRef> logicals, String openId, String physicalId) {
        for(LensPlanner.LensRef logical : logicals) {
            if( openId.equals(logical.openId()) && logical.physicalIds.contains(physicalId) )
                return true;
        }
        return false;
    }

    private static final class OpenFail extends Exception {
        final OpenResult result;
        final String reason;

        OpenFail(OpenResult result, String reason) {
            this.result = result;
            this.reason = reason;
        }
    }
}
