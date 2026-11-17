package net.sourceforge.opencamera.studio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import net.sourceforge.opencamera.MainActivity;
import net.sourceforge.opencamera.OpClassicController;
import net.sourceforge.opencamera.PreferenceKeys;
import net.sourceforge.opencamera.R;
import net.sourceforge.opencamera.op.CameraMode;
import net.sourceforge.opencamera.op.CameraSelection;
import net.sourceforge.opencamera.op.OpCameraUi;
import net.sourceforge.opencamera.op.ui.OpShutterButton;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Normal Video's single-file recording surface. Camera inputs can change without
 *  changing this activity, the encoder, the audio stream, or the output file. */
public final class OpticalVideoActivity extends ComponentActivity implements OpCameraUi.Host {
    public static final String EXTRA_WIDTH = "op_video_width";
    public static final String EXTRA_HEIGHT = "op_video_height";
    public static final String EXTRA_FPS = "op_video_fps";
    public static final String EXTRA_REFRESH_PROFILE = "op_video_refresh_profile";
    private static final String PREF_WIDTH = "op_optical_video_width";
    private static final String PREF_HEIGHT = "op_optical_video_height";
    public static final String PREF_FPS = "op_optical_video_fps";
    private static final int CAMERA_PERMISSION = 40;
    private static final int AUDIO_PERMISSION = 41;

    private final ExecutorService initializer = Executors.newSingleThreadExecutor();
    private final Map<SurfaceTexture, TextureLease> textures = new IdentityHashMap<>();
    private OpCameraUi ui;
    private TextureView preview;
    private FrameLayout root;
    private SharedPreferences preferences;
    private Owner owner;
    private OpticalVideoEngine.State state;
    private CameraSelection selection = new CameraSelection(null, null, false, 1f);
    private boolean resumed;
    private boolean leaving;
    private boolean pendingAudioStart;
    private int closing;
    private int preferredWidth = 1920, preferredHeight = 1080, preferredFps = 30;
    private Uri lastSaved;
    private Runnable afterClose;
    private float ev;
    private boolean volumeUpHeld, volumeDownHeld;
    private boolean multiTouchSeen;
    private float focusX = .5f, focusY = .5f;

    private static final class TextureLease {
        final SurfaceTexture texture;
        int users;
        boolean destroyed;
        TextureLease(SurfaceTexture texture) { this.texture = texture; }
    }

    private static final class Owner {
        final TextureLease lease;
        final Surface surface;
        OpticalVideoEngine engine;
        boolean stopping;
        boolean finished;
        Owner(TextureLease lease) {
            this.lease = lease;
            ++lease.users;
            surface = new Surface(lease.texture);
        }
    }

    // GestureDetector delegates an actual tap to preview.performClick(); pinches are cancelled separately.
    @SuppressLint("ClickableViewAccessibility")
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        Intent initial = savedInstanceState == null ? getIntent() : new Intent().putExtras(savedInstanceState);
        CameraSelection supplied = CameraSelection.readFrom(initial);
        if( supplied != null ) selection = supplied;
        boolean useIncomingProfile = savedInstanceState != null || initial.getBooleanExtra(EXTRA_REFRESH_PROFILE, false)
                || !preferences.contains(PREF_WIDTH);
        preferredWidth = useIncomingProfile ? initial.getIntExtra(EXTRA_WIDTH, preferences.getInt(PREF_WIDTH, 1920)) : preferences.getInt(PREF_WIDTH, 1920);
        preferredHeight = useIncomingProfile ? initial.getIntExtra(EXTRA_HEIGHT, preferences.getInt(PREF_HEIGHT, 1080)) : preferences.getInt(PREF_HEIGHT, 1080);
        preferredFps = useIncomingProfile ? initial.getIntExtra(EXTRA_FPS, preferences.getInt(PREF_FPS, 30)) : preferences.getInt(PREF_FPS, 30);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        preview = new TextureView(this) {
            @Override public boolean performClick() {
                super.performClick();
                OpticalVideoEngine engine = engine();
                if( engine != null ) {
                    engine.focus(focusX, focusY);
                    ui.focusRing.showAt(focusX * getWidth() + getLeft(), focusY * getHeight() + getTop(), true);
                }
                return true;
            }
        };
        preview.setContentDescription("Camera preview. Tap to focus; pinch to zoom.");
        preview.setClickable(true);
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout overlay = new FrameLayout(this);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        ui = new OpCameraUi(this, overlay, this);
        ui.setMode(CameraMode.VIDEO);
        ui.shutter.setStyle(OpShutterButton.Style.VIDEO);
        ui.setInfoText("Opening camera…");
        ui.topBar.addIcon("flash", R.drawable.op_ic_flash_off, "Video light", v -> {
            OpticalVideoEngine engine = engine();
            if( engine != null ) engine.toggleTorch();
        });
        ui.topBar.addText("resolution", "1080P", "Resolution", v -> cycleResolution());
        ui.topBar.addText("fps", "30 FPS", "Frame rate", v -> cycleFps());
        ui.topBar.addIcon("stabilization", R.drawable.op_ic_video_stable, "Stabilization", v -> {
            if( !canChangeProfile() || engine() == null ) return;
            boolean enabled = !preferences.getBoolean(PreferenceKeys.VideoStabilizationPreferenceKey, false);
            engine().setStabilization(enabled);
            ui.topBar.setActive("stabilization", enabled);
        });
        ui.topBar.addIcon("settings", R.drawable.op_ic_settings, "Settings", v -> openSettings());
        ui.refreshRotatableViews();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            int reservedTop = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars() |
                    WindowInsetsCompat.Type.displayCutout()).top;
            ui.setInsets(Math.max(bars.top, reservedTop), bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> positionPreview());
        GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent event) {
                focusX = event.getX() / Math.max(1, preview.getWidth());
                focusY = event.getY() / Math.max(1, preview.getHeight());
                preview.performClick();
                return true;
            }
            @Override public boolean onScroll(MotionEvent start, MotionEvent end, float dx, float dy) {
                OpticalVideoEngine engine = engine();
                if( engine == null || start == null || !ui.focusRing.isShowing() ) return false;
                ev = Math.max(-1f, Math.min(1f, (start.getY() - end.getY()) /
                        (220f * getResources().getDisplayMetrics().density)));
                ui.focusRing.setExposure(ev);
                engine.setEv(ev);
                return true;
            }
        });
        ScaleGestureDetector pinch = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private float requested;
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                requested = state == null ? selection.zoom : state.zoom;
                return true;
            }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                if( state != null && engine() != null ) {
                    requested = Math.max(state.minZoom, Math.min(state.maxZoom, requested * detector.getScaleFactor()));
                    engine().setZoom(requested);
                }
                return true;
            }
        });
        preview.setOnTouchListener((view, event) -> {
            if( event.getActionMasked() == MotionEvent.ACTION_DOWN ) multiTouchSeen = false;
            if( event.getPointerCount() > 1 && !multiTouchSeen ) {
                multiTouchSeen = true;
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                gestures.onTouchEvent(cancel);
                cancel.recycle();
            }
            pinch.onTouchEvent(event);
            if( !multiTouchSeen && !pinch.isInProgress() && event.getPointerCount() == 1 ) gestures.onTouchEvent(event);
            return true;
        });
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int w, int h) { maybeStart(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int w, int h) {
                if( owner != null && owner.engine != null ) owner.engine.resizePreview(w, h);
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                TextureLease lease = textures.get(texture);
                if( lease == null ) return true;
                lease.destroyed = true;
                stopOwner();
                releaseTextureIfUnused(lease);
                return false;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) { }
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if( ui.handleBackPressed() ) return;
                if( state != null && state.recording ) { onShutterClick(); return; }
                navigate(CameraMode.PHOTO, false);
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        ui.onResume();
        if( checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED ) maybeStart();
        else requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
    }

    @Override protected void onPause() {
        resumed = false;
        pendingAudioStart = false;
        ui.onPause();
        stopOwner();
        super.onPause();
    }

    @Override protected void onDestroy() {
        stopOwner();
        initializer.shutdown();
        super.onDestroy();
    }

    private OpticalVideoEngine engine() { return owner == null ? null : owner.engine; }

    private void maybeStart() {
        if( !resumed || leaving || owner != null || closing != 0 || !preview.isAvailable() ||
                checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ) return;
        SurfaceTexture texture = preview.getSurfaceTexture();
        TextureLease lease = textures.get(texture);
        if( lease == null ) { lease = new TextureLease(texture); textures.put(texture, lease); }
        final Owner next = new Owner(lease);
        owner = next;
        final int width = Math.max(1, preview.getWidth()), height = Math.max(1, preview.getHeight());
        initializer.execute(() -> {
            OpticalVideoRenderer renderer = null;
            try {
                renderer = new OpticalVideoRenderer(next.surface, width, height);
                final OpticalVideoEngine created = new OpticalVideoEngine(getApplicationContext(), renderer,
                        new OpticalVideoEngine.Listener() {
                            @Override public void onState(OpticalVideoEngine.State value) {
                                if( owner == next && resumed ) showState(value);
                            }
                            @Override public void onSaved(Uri uri, Bitmap thumb) {
                                if( uri != null ) lastSaved = uri;
                                if( !isDestroyed() ) {
                                    if( thumb != null ) ui.setGalleryBitmap(thumb);
                                    if( resumed ) ui.showToast("Saved");
                                }
                            }
                            @Override public void onError(String message) {
                                if( owner == next && resumed ) ui.showToast(message, 4000);
                            }
                        });
                runOnUiThread(() -> {
                    next.engine = created;
                    if( next.stopping || owner != next || !resumed ) created.close(() -> ownerClosed(next));
                    else created.start(selection, preferredWidth, preferredHeight, preferredFps);
                });
            }
            catch(Exception error) {
                if( renderer != null ) {
                    try { renderer.close(); }
                    catch(RuntimeException cleanupError) { StudioLog.e("Video renderer cleanup failed", cleanupError); }
                }
                StudioLog.e("Video renderer initialization failed", error);
                runOnUiThread(() -> {
                    if( owner == next ) {
                        owner = null;
                        ui.setInfoText("Camera could not start");
                        ui.showToast("Couldn't start the video renderer", 4000);
                    }
                    ownerClosed(next);
                });
            }
        });
    }

    private void stopOwner() {
        Owner old = owner;
        if( old == null || old.stopping ) return;
        owner = null;
        old.stopping = true;
        ++closing;
        if( old.engine != null ) old.engine.close(() -> ownerClosed(old));
    }

    private void ownerClosed(Owner old) {
        if( old.finished ) return;
        old.finished = true;
        old.surface.release();
        --old.lease.users;
        releaseTextureIfUnused(old.lease);
        if( old.stopping ) --closing;
        if( closing == 0 && afterClose != null ) {
            Runnable action = afterClose;
            afterClose = null;
            action.run();
        }
        else if( old.stopping ) maybeStart();
    }

    private void releaseTextureIfUnused(TextureLease lease) {
        if( lease.destroyed && lease.users == 0 ) {
            lease.texture.release();
            textures.remove(lease.texture);
        }
    }

    private void showState(OpticalVideoEngine.State value) {
        state = value;
        if( value.cameraId != null )
            selection = new CameraSelection(value.cameraId, value.physicalId, value.front, value.zoom);
        ui.zoomBar.setStops(value.stops);
        ui.zoomBar.setRange(value.minZoom, value.maxZoom);
        ui.zoomBar.setZoom(value.zoom);
        ui.setZoomVisible(true);
        ui.setRecording(value.recording, true, true);
        ui.setPaused(value.paused);
        ui.setRecordingTime(value.recordMs, value.paused);
        ui.setBusy(value.busy);
        ui.shutter.setStyle(value.recording ? OpShutterButton.Style.RECORDING : OpShutterButton.Style.VIDEO);
        ui.setInfoText(value.busy ? "Switching lens…" : value.lensLabel);
        ui.topBar.setText("resolution", resolutionLabel(value.width, value.height));
        ui.topBar.setText("fps", value.fps + " FPS");
        ui.topBar.setIcon("flash", value.torch ? R.drawable.op_ic_flashlight_on : R.drawable.op_ic_flash_off);
        ui.topBar.setActive("flash", value.torch);
        for(String item : new String[]{"resolution", "fps", "settings", "stabilization"})
            ui.topBar.setItemVisible(item, !value.recording);
        ui.topBar.setItemVisible("stabilization", !value.recording && value.supportsStabilization);
        ui.topBar.setItemVisible("flash", value.supportsTorch);
        ui.topBar.setActive("stabilization", value.stabilization);
        if( value.ready && value.width > 0 && value.height > 0 ) {
            preferredWidth = value.width; preferredHeight = value.height; preferredFps = value.fps;
            if( preferences.getInt(PREF_WIDTH, 0) != preferredWidth || preferences.getInt(PREF_HEIGHT, 0) != preferredHeight ||
                    preferences.getInt(PREF_FPS, 0) != preferredFps )
                preferences.edit().putInt(PREF_WIDTH, preferredWidth).putInt(PREF_HEIGHT, preferredHeight)
                        .putInt(PREF_FPS, preferredFps).apply();
            positionPreview();
        }
    }

    private void positionPreview() {
        if( preview == null || root.getWidth() == 0 || preferredHeight <= 0 ) return;
        int height = Math.round(root.getWidth() * (preferredWidth / (float)preferredHeight));
        height = Math.min(root.getHeight(), height);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams)preview.getLayoutParams();
        if( params.height == height && params.gravity == Gravity.CENTER ) return;
        params.height = height;
        params.gravity = Gravity.CENTER;
        preview.setLayoutParams(params);
    }

    private static String resolutionLabel(int width, int height) {
        return width >= 3840 ? "4K" : height + "P";
    }

    private boolean canChangeProfile() { return state != null && state.ready && !state.recording && !state.busy; }

    private void cycleResolution() {
        if( !canChangeProfile() || engine() == null ) return;
        List<OpticalVideoEngine.Profile> options = new ArrayList<>();
        int current = -1;
        for(OpticalVideoEngine.Profile p : state.profiles) {
            if( p.fps == state.fps ) {
                if( p.width == state.width && p.height == state.height ) current = options.size();
                options.add(p);
            }
        }
        if( options.size() < 2 ) { ui.showToast("No other resolution is available at " + state.fps + " FPS"); return; }
        engine().setProfile(options.get((current + 1) % options.size()));
    }

    private void cycleFps() {
        if( !canChangeProfile() || engine() == null ) return;
        OpticalVideoEngine.Profile current = new OpticalVideoEngine.Profile(state.width, state.height, state.fps);
        OpticalVideoEngine.Profile choice = OpticalVideoEngine.nextFpsProfile(current, state.profiles);
        if( choice.equals(current) ) {
            ui.showToast(state.fps + " FPS is the only available frame rate.");
            return;
        }
        engine().setProfile(choice);
    }

    private void navigate(CameraMode next, boolean settings) {
        if( leaving || (state != null && state.recording) ) return;
        leaving = true;
        Intent intent = new Intent(this, next.engine == CameraMode.Engine.STUDIO ? StudioActivity.class : MainActivity.class);
        selection.writeTo(intent);
        intent.putExtra(OpClassicController.EXTRA_MODE, next.name());
        intent.putExtra(OpClassicController.EXTRA_RETURN_MODE, CameraMode.VIDEO.name());
        intent.putExtra(OpClassicController.EXTRA_OPEN_SETTINGS, settings);
        if( settings ) {
            intent.putExtra(EXTRA_WIDTH, preferredWidth);
            intent.putExtra(EXTRA_HEIGHT, preferredHeight);
            intent.putExtra(EXTRA_FPS, preferredFps);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        afterClose = () -> {
            if( isFinishing() || isDestroyed() ) return;
            startActivity(intent);
            overridePendingTransition(0, 0);
            finish();
        };
        stopOwner();
        if( closing == 0 ) { Runnable action = afterClose; afterClose = null; action.run(); }
    }

    private void openSettings() { navigate(CameraMode.VIDEO, true); }
    @Override public void onModeSelected(CameraMode mode) {
        if( mode == CameraMode.VIDEO || (state != null && state.recording) ) { ui.setMode(CameraMode.VIDEO); return; }
        navigate(mode, false);
    }
    @Override public void onShutterClick() {
        OpticalVideoEngine engine = engine();
        if( engine == null || state == null ) return;
        if( state.recording ) { engine.stopRecording(); return; }
        if( !state.ready || state.busy ) return;
        if( preferences.getBoolean(PreferenceKeys.RecordAudioPreferenceKey, true) &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ) {
            pendingAudioStart = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
        }
        else engine.record(preferences.getBoolean(PreferenceKeys.RecordAudioPreferenceKey, true) &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                ui.orientation.getDeviceOrientation());
    }
    @Override public boolean onShutterLongClick() { onShutterClick(); return true; }
    @Override public void onGalleryClick() {
        if( state != null && state.recording ) return;
        Intent intent = new Intent(Intent.ACTION_VIEW);
        if( lastSaved != null ) {
            intent.setDataAndType(lastSaved, getContentResolver().getType(lastSaved));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        else intent.setData(MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        try { startActivity(intent); }
        catch(Exception unavailable) { ui.showToast("No gallery app found"); }
    }
    @Override public void onSwitchCameraClick() { if( engine() != null && canChangeProfile() ) engine().flip(); }
    @Override public void onPauseClick() { if( engine() != null ) engine().pauseToggle(); }
    @Override public void onSnapshotClick() { if( engine() != null ) engine().snapshot(ui.orientation.getDeviceOrientation()); }
    @Override public void onMoreTile(String id) {
        if( OpCameraUi.TILE_SETTINGS.equals(id) ) openSettings();
        else if( OpCameraUi.TILE_DIAGNOSTICS.equals(id) && (state == null || !state.recording) )
            startActivity(new Intent(this, DiagnosticsActivity.class));
    }
    @Override public void onZoomStop(int index, float zoom) { if( engine() != null ) engine().setZoom(zoom); }
    @Override public void onZoomDrag(float zoom) { if( engine() != null ) engine().setZoom(zoom); }
    @Override public void onZoomDragEnd(float zoom) { if( engine() != null ) engine().setZoom(zoom); }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        Intent copy = new Intent(); selection.writeTo(copy); out.putAll(copy.getExtras());
        out.putInt(EXTRA_WIDTH, preferredWidth); out.putInt(EXTRA_HEIGHT, preferredHeight); out.putInt(EXTRA_FPS, preferredFps);
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if( request == CAMERA_PERMISSION ) {
            if( checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED ) maybeStart();
            else ui.showToast("Camera permission is needed to record video");
        }
        if( request == AUDIO_PERMISSION && pendingAudioStart ) {
            pendingAudioStart = false;
            if( engine() != null && resumed ) {
                boolean granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
                if( !granted ) ui.showToast("Recording without audio");
                engine().record(granted && preferences.getBoolean(PreferenceKeys.RecordAudioPreferenceKey, true),
                        ui.orientation.getDeviceOrientation());
            }
        }
    }
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if( keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ) {
            boolean up = keyCode == KeyEvent.KEYCODE_VOLUME_UP;
            if( up ) volumeUpHeld = true; else volumeDownHeld = true;
            String action = preferences.getString(PreferenceKeys.VolumeKeysPreferenceKey, "volume_take_photo");
            switch(action) {
                case "volume_take_photo":
                    if( event.getRepeatCount() == 0 ) {
                        if( !up && state != null && state.recording ) onPauseClick(); else onShutterClick();
                    }
                    return true;
                case "volume_zoom":
                    if( engine() != null && state != null ) engine().setZoom(state.zoom * (up ? 1.1f : 1f / 1.1f));
                    return true;
                case "volume_focus":
                    if( event.getRepeatCount() == 0 && engine() != null ) {
                        if( volumeUpHeld && volumeDownHeld ) onShutterClick(); else engine().focus(.5f, .5f);
                    }
                    return true;
                case "volume_exposure":
                    ev = Math.max(-1f, Math.min(1f, ev + (up ? .1f : -.1f)));
                    if( engine() != null ) engine().setEv(ev);
                    return true;
                case "volume_really_nothing": return true;
                case "volume_auto_stabilise":
                    if( event.getRepeatCount() == 0 ) ui.showToast("Auto-level applies to photos");
                    return true;
                default: return super.onKeyDown(keyCode, event);
            }
        }
        if( keyCode == KeyEvent.KEYCODE_CAMERA && event.getRepeatCount() == 0 ) { onShutterClick(); return true; }
        if( keyCode == KeyEvent.KEYCODE_FOCUS && engine() != null ) { engine().focus(.5f, .5f); return true; }
        if( keyCode == KeyEvent.KEYCODE_MENU ) { openSettings(); return true; }
        return super.onKeyDown(keyCode, event);
    }
    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if( keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ) {
            if( keyCode == KeyEvent.KEYCODE_VOLUME_UP ) volumeUpHeld = false; else volumeDownHeld = false;
            String action = preferences.getString(PreferenceKeys.VolumeKeysPreferenceKey, "volume_take_photo");
            if( !"volume_nothing".equals(action) ) return true;
        }
        return super.onKeyUp(keyCode, event);
    }
}
