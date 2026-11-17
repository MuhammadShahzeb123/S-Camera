package net.sourceforge.opencamera.studio;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Size;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

import net.sourceforge.opencamera.MainActivity;
import net.sourceforge.opencamera.OpClassicController;
import net.sourceforge.opencamera.R;
import net.sourceforge.opencamera.op.CameraMode;
import net.sourceforge.opencamera.op.CameraSelection;
import net.sourceforge.opencamera.op.OpCameraUi;
import net.sourceforge.opencamera.op.ui.OpChipStrip;
import net.sourceforge.opencamera.op.ui.OpFocusRing;
import net.sourceforge.opencamera.op.ui.OpShutterButton;

import java.util.ArrayList;
import java.util.List;

/** OnePlus-style studio: several lenses previewed and recorded together, matched by sensor timestamp. */
public class StudioActivity extends ComponentActivity implements OpCameraUi.Host, StudioEngine.Ui, GlCompositor.Ready {
    private static final int REQ_PERMS = 41;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private OpCameraUi ui;
    private StudioEngine engine;
    private GLSurfaceView glView;
    private GlCompositor gl;
    private CameraMode mode = CameraMode.MULTI_CAM;
    private CameraMode returnMode = CameraMode.PHOTO;
    private CameraSelection lastSelection;
    private GestureDetector gestures;
    private float focusDownY;
    private boolean evDrag;
    private boolean alive;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if( !alive || ui == null || engine == null )
                return;
            StudioEngine.Status status = engine.status();
            if( status.ready )
                currentSelection();
            ui.zoomBar.setZoom(engine.getZoom());
            if( status.recording && mode != CameraMode.LONG_EXPOSURE )
                ui.setRecordingTime(status.recordMs, status.paused);
            if( status.longExposure ) {
                ui.shutter.setStyle(OpShutterButton.Style.PROGRESS);
                ui.shutter.setProgress(status.longProgress);
            }
            if( status.ready && status.opened > 1 ) {
                String sync;
                if( status.live < 2 )
                    sync = "waiting for the other cameras";
                else if( status.skewNs >= 0 )
                    sync = String.format(java.util.Locale.US, "sync %.0f ms", status.skewNs / 1.0e6);
                else
                    sync = "syncing…";
                ui.setInfoText(status.opened + " cameras · " + sync);
            }
            handler.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        FrameLayout root = new FrameLayout(this);
        glView = new GLSurfaceView(this);
        gl = new GlCompositor(this);
        glView.setEGLContextClientVersion(2);
        glView.setRenderer(gl);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        root.addView(glView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        FrameLayout overlay = new FrameLayout(this);
        root.addView(overlay, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        ui = new OpCameraUi(this, overlay, this);
        engine = new StudioEngine(this, this);
        engine.attach(glView, gl);

        Intent selectionIntent = savedInstanceState != null
                ? new Intent().putExtras(savedInstanceState) : getIntent();
        CameraSelection selection = CameraSelection.readFrom(selectionIntent);
        lastSelection = selection;
        if( selection != null )
            engine.restoreSelection(selection.front, selection.zoom);
        returnMode = CameraMode.fromName(selectionIntent != null ?
                selectionIntent.getStringExtra(OpClassicController.EXTRA_RETURN_MODE) : null, CameraMode.PHOTO);
        if( returnMode.engine != CameraMode.Engine.CLASSIC )
            returnMode = CameraMode.PHOTO;
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBackNavigation();
            }
        });

        String extra = selectionIntent != null ? selectionIntent.getStringExtra(OpClassicController.EXTRA_MODE) : null;
        CameraMode requested = CameraMode.fromName(extra, CameraMode.MULTI_CAM);
        if( requested.engine != CameraMode.Engine.STUDIO )
            requested = CameraMode.MULTI_CAM;
        mode = requested;
        engine.setMode(mode);
        ui.setMode(mode);
        buildChrome();

        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                focusDownY = e.getY();
                evDrag = false;
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if( !ui.focusRing.isShowing() )
                    return false;
                evDrag = true;
                float norm = (focusDownY - e2.getY()) / (220f * getResources().getDisplayMetrics().density);
                norm = Math.max(-1f, Math.min(1f, norm));
                ui.focusRing.setExposure(norm);
                engine.setEvNorm(norm);
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if( evDrag )
                    return true;
                GlCompositor.Pane hit = hitPane(e.getX(), e.getY());
                if( hit != null && hit.index != gl.getPrimary() && hit.w < 0.95f ) {
                    engine.setPrimary(hit.index);
                    ui.showToast("Lens " + (hit.index + 1));
                    return true;
                }
                ui.focusRing.showAt(e.getX(), e.getY(), true);
                float [] norm = paneNorm(e.getX(), e.getY());
                engine.tapFocus(norm[0], norm[1]);
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                engine.setAeLock(true);
                ui.focusRing.setLocked(true);
                ui.showToast("Exposure locked");
            }
        });
        glView.setOnTouchListener((v, event) -> {
            gestures.onTouchEvent(event);
            return true;
        });

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            ui.setInsets(bars.top, bars.bottom);
            return insets;
        });
        ui.orientation.setListener(degrees -> engine.setDeviceOrientation(degrees));
    }

    @Override
    protected void onResume() {
        super.onResume();
        alive = true;
        glView.onResume();
        ui.onResume();
        engine.setDeviceOrientation(ui.orientation.getDeviceOrientation());
        if( hasCameraPermission() ) {
            engine.setAudioGranted(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
            engine.start();
            handler.post(ticker);
            loadThumb();
        }
        else {
            requestNeededPermissions();
        }
    }

    @Override
    protected void onPause() {
        alive = false;
        handler.removeCallbacks(ticker);
        ui.onPause();
        engine.stopBlocking();
        gl.markContextLost();
        glView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if( engine != null )
            engine.release();
        super.onDestroy();
    }

    private void handleBackNavigation() {
        if( ui != null && ui.handleBackPressed() )
            return;
        if( engine != null && engine.isRecording() ) {
            engine.shutter();
            return;
        }
        launchClassic(returnMode, false);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        Intent selection = new Intent();
        currentSelection().writeTo(selection);
        state.putAll(selection.getExtras());
        state.putString(OpClassicController.EXTRA_MODE, mode.name());
        state.putString(OpClassicController.EXTRA_RETURN_MODE, returnMode.name());
    }

    private CameraSelection currentSelection() {
        LensPlanner.LensRef lens = engine.getSelectedLens();
        if( lens == null && lastSelection != null )
            return lastSelection;
        lastSelection = new CameraSelection(lens != null ? lens.openId() : null,
                lens != null ? lens.physicalId() : null,
                lens != null ? lens.isFront() : engine.wantFront(), engine.getZoom());
        return lastSelection;
    }

    private void launchClassic(CameraMode next, boolean settings) {
        Intent intent = new Intent(this, MainActivity.class);
        currentSelection().writeTo(intent);
        intent.putExtra(OpClassicController.EXTRA_MODE, next.name());
        intent.putExtra(OpClassicController.EXTRA_OPEN_SETTINGS, settings);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        // Release Studio's camera before the classic engine attempts to open it.
        engine.stopBlocking();
        startActivity(intent);
        overridePendingTransition(0, 0);
        finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if( keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP ) {
            if( event.getRepeatCount() == 0 )
                onShutterClick();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if( keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP )
            return true;
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String [] permissions, int [] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if( requestCode == REQ_PERMS && hasCameraPermission() && alive ) {
            engine.setAudioGranted(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
            engine.start();
            handler.post(ticker);
        }
        else if( requestCode == REQ_PERMS ) {
            ui.showToast("Camera permission is required", 3000);
        }
    }

    @Override
    public void onGlReady() {
        if( alive && hasCameraPermission() )
            engine.onGlReady();
    }

    @Override
    public void onOpening() {
        ui.setInfoText("Opening cameras…");
    }

    @Override
    public void onOpened(LensPlanner.Plan plan, int openedCount, String detail) {
        if( plan != null ) {
            ui.zoomBar.setStops(plan.stops);
            ui.zoomBar.setRange(plan.minZoom, plan.maxZoom);
            ui.setZoomVisible(true);
        }
        ui.setInfoText(detail);
        ui.showToast(detail, 2200);
        ui.topBar.setItemVisible("flash", engine.flashAvailable());
        if( plan != null && openedCount < plan.cameras.size() )
            ui.showToast("Opened " + openedCount + " of " + plan.cameras.size() + ". This phone's camera HAL blocked the rest.", 3600);
    }

    @Override
    public void onFailed(String message) {
        ui.setInfoText(message);
        ui.showToast(message, 3200);
    }

    @Override
    public void onSaved(String message, Bitmap thumb) {
        if( thumb != null )
            ui.setGalleryBitmap(thumb);
        if( message != null )
            ui.showToast(message, 1800);
    }

    @Override
    public void onRecording(boolean recording) {
        if( mode == CameraMode.LONG_EXPOSURE ) {
            ui.setBusy(recording);
            ui.shutter.setStyle(recording ? OpShutterButton.Style.PROGRESS : OpShutterButton.Style.PHOTO);
            if( !recording )
                ui.shutter.setProgress(0f);
            return;
        }
        ui.setRecording(recording, Build.VERSION.SDK_INT >= Build.VERSION_CODES.N, true);
        ui.shutter.setStyle(recording ? OpShutterButton.Style.RECORDING : (mode.isVideo ? OpShutterButton.Style.VIDEO : OpShutterButton.Style.PHOTO));
    }

    @Override
    public void onAf(boolean focused) {
        ui.focusRing.setState(focused ? OpFocusRing.State.FOCUSED : OpFocusRing.State.FAILED);
    }

    @Override
    public void onModeSelected(CameraMode next) {
        if( engine.isRecording() ) {
            ui.setMode(mode);
            return;
        }
        if( next.engine == CameraMode.Engine.CLASSIC ) {
            launchClassic(next, false);
            return;
        }
        mode = next;
        ui.setMode(mode);
        engine.setMode(mode);
        buildChrome();
        if( alive && hasCameraPermission() )
            engine.start();
    }

    @Override
    public void onShutterClick() {
        if( ui.moreSheet.isShowing() )
            return;
        engine.shutter();
    }

    @Override
    public boolean onShutterLongClick() {
        if( mode.isVideo && !engine.isRecording() ) {
            engine.snapshot();
            return true;
        }
        return true;
    }

    @Override
    public void onGalleryClick() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }
        catch(Exception e) {
            try {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.addCategory(Intent.CATEGORY_APP_GALLERY);
                startActivity(intent);
            }
            catch(Exception ignored) {
                ui.showToast("No gallery app found");
            }
        }
    }

    @Override
    public void onSwitchCameraClick() {
        if( engine.isRecording() )
            return;
        ui.switchButton.animate().rotationBy(180).setDuration(280).start();
        boolean front = !engine.wantFront();
        engine.restoreSelection(front, 1.0f);
        ui.showToast(front ? "Front" : "Back");
        engine.start();
    }

    @Override
    public void onPauseClick() {
        engine.pauseToggle();
        ui.setPaused(engine.status().paused);
    }

    @Override
    public void onSnapshotClick() {
        engine.snapshot();
    }

    @Override
    public void onMoreTile(String id) {
        if( OpCameraUi.TILE_SETTINGS.equals(id) ) {
            launchClassic(CameraMode.PHOTO, true);
        }
        else if( OpCameraUi.TILE_DIAGNOSTICS.equals(id) ) {
            startActivity(new Intent(this, DiagnosticsActivity.class));
        }
    }

    @Override
    public void onZoomStop(int index, float zoom) {
        engine.setZoom(zoom, true);
    }

    @Override
    public void onZoomDrag(float zoom) {
        engine.setZoom(zoom, false);
    }

    @Override
    public void onZoomDragEnd(float zoom) {
        engine.setZoom(zoom, true);
    }

    private void buildChrome() {
        ui.topBar.clear();
        ui.topBar.addIcon("flash", flashIcon(engine.flashMode()), "Flash", v -> {
            int mode = engine.cycleFlash();
            ui.topBar.setIcon("flash", flashIcon(mode));
            ui.topBar.setActive("flash", mode != 0);
            ui.showToast(flashLabel(mode));
        });
        ui.topBar.setActive("flash", engine.flashMode() != 0);
        ui.refreshRotatableViews();
        ui.shutter.setStyle(mode.isVideo ? OpShutterButton.Style.VIDEO : OpShutterButton.Style.PHOTO);
        ui.setRecording(false, false, false);
        ui.setProPanelVisible(false);
        buildChips();
        if( mode == CameraMode.FILM )
            ui.showToast("Film look is on the preview. The saved video is the original picture.");
        else if( mode == CameraMode.MULTI_CAM )
            ui.showToast("Multi-cam records every open lens together");
        else if( mode == CameraMode.DUAL_VIEW )
            ui.showToast("Dual-view records both lenses together");
    }

    private void buildChips() {
        List<OpChipStrip.Chip> chips = new ArrayList<>();
        if( mode == CameraMode.MULTI_CAM || mode == CameraMode.DUAL_VIEW ) {
            chips.add(new OpChipStrip.Chip("layout_pip", "PiP", "layout", true));
            chips.add(new OpChipStrip.Chip("layout_split", "Split", "layout", false));
            if( mode == CameraMode.MULTI_CAM )
                chips.add(new OpChipStrip.Chip("layout_grid", "Grid", "layout", false));
        }
        if( mode == CameraMode.DUAL_VIEW ) {
            chips.add(new OpChipStrip.Chip("pair_lenses", "Two lenses", "pair", !engine.wantFront()));
            chips.add(new OpChipStrip.Chip("pair_front", "Front + back", "pair", engine.wantFront()));
        }
        if( mode == CameraMode.FILM ) {
            chips.add(new OpChipStrip.Chip("film_warm", "Warm", "film", true));
            chips.add(new OpChipStrip.Chip("film_cool", "Cool", "film", false));
            chips.add(new OpChipStrip.Chip("film_mono", "Mono", "film", false));
        }
        if( mode == CameraMode.PORTRAIT ) {
            chips.add(new OpChipStrip.Chip("port_soft", "Soft", "portrait", true));
            chips.add(new OpChipStrip.Chip("port_strong", "Strong", "portrait", false));
        }
        if( mode == CameraMode.LONG_EXPOSURE ) {
            chips.add(new OpChipStrip.Chip("exp_1", "1s", "exp", false));
            chips.add(new OpChipStrip.Chip("exp_2", "2s", "exp", true));
            chips.add(new OpChipStrip.Chip("exp_4", "4s", "exp", false));
            chips.add(new OpChipStrip.Chip("exp_8", "8s", "exp", false));
        }
        ui.setChips(chips, chip -> {
            if( "layout".equals(chip.group) ) {
                if( "layout_split".equals(chip.id) )
                    engine.setLayout(GlCompositor.Layout.SPLIT);
                else if( "layout_grid".equals(chip.id) )
                    engine.setLayout(GlCompositor.Layout.GRID);
                else
                    engine.setLayout(GlCompositor.Layout.PIP);
            }
            else if( "pair".equals(chip.group) ) {
                engine.setWantFront("pair_front".equals(chip.id));
                engine.start();
            }
            else if( "film".equals(chip.group) ) {
                int look = "film_mono".equals(chip.id) ? 2 : ("film_cool".equals(chip.id) ? 1 : 0);
                engine.setFilmLook(look);
            }
            else if( "portrait".equals(chip.group) ) {
                engine.setPortraitStrong("port_strong".equals(chip.id));
            }
            else if( "exp".equals(chip.group) ) {
                int seconds = 2;
                if( "exp_1".equals(chip.id) )
                    seconds = 1;
                else if( "exp_4".equals(chip.id) )
                    seconds = 4;
                else if( "exp_8".equals(chip.id) )
                    seconds = 8;
                engine.setExposureSeconds(seconds);
            }
            selectChip(chip);
        });
    }

    private void selectChip(OpChipStrip.Chip chosen) {
        // OpChipStrip selects within a group itself when the listener fires after its own toggle.
        // Rebuild is unnecessary; the strip handles radio behaviour.
        if( chosen != null && chosen.group == null )
            return;
    }

    private GlCompositor.Pane hitPane(float x, float y) {
        float nx = x / Math.max(1, glView.getWidth());
        float ny = y / Math.max(1, glView.getHeight());
        GlCompositor.Pane found = null;
        for(GlCompositor.Pane pane : gl.copyPanes()) {
            if( pane.contains(nx, ny) )
                found = pane; // later panes are the small windows drawn on top
        }
        return found;
    }

    private float [] paneNorm(float x, float y) {
        float nx = x / Math.max(1f, glView.getWidth());
        float ny = y / Math.max(1f, glView.getHeight());
        for(GlCompositor.Pane pane : gl.copyPanes()) {
            if( pane.index == gl.getPrimary() && pane.contains(nx, ny) && pane.w > 0.01f ) {
                return new float[]{(nx - pane.x) / pane.w, (ny - pane.y) / pane.h};
            }
        }
        return new float[]{nx, ny};
    }

    private void loadThumb() {
        new Thread(() -> {
            Bitmap bitmap = null;
            try {
                Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                android.database.Cursor cursor = getContentResolver().query(uri, new String[]{MediaStore.Images.Media._ID}, null, null, MediaStore.Images.Media.DATE_ADDED + " DESC");
                if( cursor != null ) {
                    try {
                        if( cursor.moveToFirst() ) {
                            long id = cursor.getLong(0);
                            Uri item = Uri.withAppendedPath(uri, String.valueOf(id));
                            if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q )
                                bitmap = getContentResolver().loadThumbnail(item, new Size(200, 200), null);
                        }
                    }
                    finally {
                        cursor.close();
                    }
                }
            }
            catch(Exception ignored) {
            }
            Bitmap shown = bitmap;
            if( shown != null )
                runOnUiThread(() -> ui.setGalleryBitmap(shown));
        }, "StudioThumb").start();
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestNeededPermissions() {
        List<String> need = new ArrayList<>();
        if( !hasCameraPermission() )
            need.add(Manifest.permission.CAMERA);
        if( checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED )
            need.add(Manifest.permission.RECORD_AUDIO);
        if( Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED )
            need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if( !need.isEmpty() )
            requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    private static int flashIcon(int mode) {
        switch( mode ) {
            case 1:
                return R.drawable.op_ic_flash_auto;
            case 2:
                return R.drawable.op_ic_flash_on;
            case 3:
                return R.drawable.op_ic_flashlight_on;
            default:
                return R.drawable.op_ic_flash_off;
        }
    }

    private static String flashLabel(int mode) {
        switch( mode ) {
            case 1:
                return "Flash auto";
            case 2:
                return "Flash on";
            case 3:
                return "Torch";
            default:
                return "Flash off";
        }
    }
}
