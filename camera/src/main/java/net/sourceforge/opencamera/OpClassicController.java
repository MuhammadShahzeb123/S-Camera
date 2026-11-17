package net.sourceforge.opencamera;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraExtensionCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.media.CamcorderProfile;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import net.sourceforge.opencamera.cameracontroller.CameraController;
import net.sourceforge.opencamera.op.CameraInventory;
import net.sourceforge.opencamera.op.CameraMode;
import net.sourceforge.opencamera.op.CameraSelection;
import net.sourceforge.opencamera.op.CameraRoutes;
import net.sourceforge.opencamera.op.OpCameraUi;
import net.sourceforge.opencamera.op.OpFormat;
import net.sourceforge.opencamera.op.ui.OpChipStrip;
import net.sourceforge.opencamera.op.ui.OpProPanel;
import net.sourceforge.opencamera.op.ui.OpShutterButton;
import net.sourceforge.opencamera.op.ui.OpTheme;
import net.sourceforge.opencamera.op.ui.OpZoomBar;
import net.sourceforge.opencamera.preview.Preview;
import net.sourceforge.opencamera.preview.VideoProfile;
import net.sourceforge.opencamera.studio.OpticalVideoActivity;
import net.sourceforge.opencamera.studio.StudioActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Drives Open Camera's classic engine (Preview / CameraController2) from the OnePlus style UI.
 *  Classic modes: Photo, Night, Pro, Video, Pro Video, Slow-mo, Time-lapse, Panorama.
 *  Studio modes (Portrait, Multi-Cam, Dual-View, Film, Long Exposure) are handed to StudioActivity.
 */
public class OpClassicController implements OpCameraUi.Host {
    private static final String TAG = "OpClassic";

    public static final String EXTRA_MODE = "net.sourceforge.opencamera.OP_MODE";
    public static final String EXTRA_OPEN_SETTINGS = "net.sourceforge.opencamera.OP_OPEN_SETTINGS";
    public static final String EXTRA_RETURN_MODE = "net.sourceforge.opencamera.OP_RETURN_MODE";
    static final String PREF_MODE = "op_classic_mode";
    static final String PREF_HDR = "op_photo_hdr";
    static final String PREF_RATIO = "op_photo_ratio";
    static final String PREF_SLOWMO_RATE = "op_slowmo_rate";
    static final String PREF_TIMELAPSE_RATE = "op_timelapse_rate";
    static final String PREF_PROVIDEO_LOG = "op_provideo_log";
    static final String PREF_NIGHT_LOWLIGHT = "op_night_lowlight";
    static final String PREF_DEFAULTS = "op_defaults_v1";

    private final MainActivity main;
    private final SharedPreferences prefs;
    private OpCameraUi ui;
    private CameraMode mode = CameraMode.PHOTO;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean resumed;
    private boolean openSettingsOnce;
    private boolean launchingOpticalVideo;
    private String lastOpticalDecision;
    private boolean refreshOpticalProfile;
    private int[] incomingOpticalProfile;
    private VideoProfile opticalSettingsBaseline;

    // lens / zoom state
    private List<CameraInventory.Lens> lenses = new ArrayList<>();
    private CameraInventory.Lens currentLens;
    private boolean logicalZoomMode; // stops map to zoom ratios on a logical camera
    private float [] stops = {1.0f};
    private float pendingZoomRatio = -1.0f; // digital zoom ratio to apply after the camera reopens

    // pro mode
    private final List<Integer> isoValues = new ArrayList<>();
    private final List<Long> shutterValues = new ArrayList<>();
    private final List<Integer> wbValues = new ArrayList<>();
    private final List<Float> focusValues = new ArrayList<>();
    private final List<Integer> evValues = new ArrayList<>();
    private boolean proPanelBuilt;

    // gestures
    private float swipeDownX, swipeDownY;
    private boolean swipeTracking;
    private boolean swipeConsumed;
    private boolean evDragging;
    private float evDragStartY;
    private int evDragStartValue;

    OpClassicController(MainActivity main) {
        this.main = main;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(main);
    }

    private Preview preview() {
        return main.getPreview();
    }

    private MyApplicationInterface app() {
        return main.getApplicationInterface();
    }

    /** Applies one-off defaults so the classic engine looks clean under the OnePlus UI. */
    static void applyDefaults(MainActivity main, boolean supportsCamera2) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(main);
        if( sp.getBoolean(PREF_DEFAULTS, false) )
            return;
        SharedPreferences.Editor e = sp.edit();
        if( supportsCamera2 )
            e.putString(PreferenceKeys.CameraAPIPreferenceKey, "preference_camera_api_camera2");
        e.putBoolean(PreferenceKeys.ShowTimePreferenceKey, false);
        e.putBoolean(PreferenceKeys.ShowFreeMemoryPreferenceKey, false);
        e.putBoolean(PreferenceKeys.ShowBatteryPreferenceKey, false);
        e.putBoolean(PreferenceKeys.ShowZoomPreferenceKey, false);
        e.putBoolean(PreferenceKeys.ShowISOPreferenceKey, false);
        e.putBoolean(PreferenceKeys.ShowCameraIDPreferenceKey, false);
        e.putBoolean(PreferenceKeys.ShowAnglePreferenceKey, false);
        e.putBoolean(PreferenceKeys.ShowGeoDirectionPreferenceKey, false);
        e.putBoolean(PreferenceKeys.ShowWhatsNewPreferenceKey, false);
        e.putBoolean(PreferenceKeys.ThumbnailAnimationPreferenceKey, false);
        e.putBoolean(PreferenceKeys.MultiCamButtonPreferenceKey, true);
        e.putBoolean(PREF_DEFAULTS, true);
        e.apply();
    }

    // ------------------------------------------------------------------------------------------
    // lifecycle

    void onCreate(boolean restoringInstanceState) {
        ViewGroup container = main.findViewById(R.id.op_overlay_container);
        ui = new OpCameraUi(main, container, this);
        CameraMode initial = null;
        Intent intent = main.getIntent();
        if( !restoringInstanceState )
            restoreSelection(CameraSelection.readFrom(intent));
        if( intent != null && intent.hasExtra(EXTRA_MODE) ) {
            initial = CameraMode.fromName(intent.getStringExtra(EXTRA_MODE), null);
            intent.removeExtra(EXTRA_MODE);
        }
        if( intent != null && intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) ) {
            openSettingsOnce = true;
            if( intent.hasExtra(OpticalVideoActivity.EXTRA_WIDTH) )
                incomingOpticalProfile = new int[]{intent.getIntExtra(OpticalVideoActivity.EXTRA_WIDTH, 1920),
                        intent.getIntExtra(OpticalVideoActivity.EXTRA_HEIGHT, 1080),
                        intent.getIntExtra(OpticalVideoActivity.EXTRA_FPS, 30)};
            intent.removeExtra(EXTRA_OPEN_SETTINGS);
        }
        if( initial == null || initial.engine != CameraMode.Engine.CLASSIC ) {
            // start like the OnePlus camera: Photo, unless Open Camera was launched in video mode (e.g. video intent / tile)
            initial = app().isVideoPref() ? CameraMode.VIDEO : CameraMode.PHOTO;
        }
        setupShutterTouch();
        enterMode(initial, true);
    }

    void onNewIntentMode(Intent intent) {
        if( intent != null && intent.hasExtra(EXTRA_MODE) ) {
            CameraMode m = CameraMode.fromName(intent.getStringExtra(EXTRA_MODE), null);
            intent.removeExtra(EXTRA_MODE);
            if( m != null && m.engine == CameraMode.Engine.CLASSIC && m != mode )
                enterMode(m, false);
        }
    }

    void onResume() {
        resumed = true;
        ui.onResume();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        updateInsets();
    }

    void onPause() {
        resumed = false;
        ui.onPause();
        handler.removeCallbacks(ticker);
    }

    boolean handleBackPressed() {
        return ui != null && ui.handleBackPressed();
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if( !resumed )
                return;
            try {
                tick();
            }
            catch(Exception e) {
                Log.e(TAG, "tick failed", e);
            }
            handler.postDelayed(this, 150);
        }
    };

    private void tick() {
        Preview preview = preview();
        if( mode == CameraMode.VIDEO && resumed && !openSettingsOnce && !main.isCameraInBackground()
                && !preview.isOpeningCamera() && preview.getCameraController() != null
                && !preview.isVideoRecording() && shouldUseOpticalVideo() ) {
            launchOpticalVideo();
            return;
        }
        // recording state
        boolean recording = preview.isVideoRecording();
        if( recording != ui.isRecording() ) {
            ui.setRecording(recording, preview.isVideo() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && mode != CameraMode.SLOW_MO && mode != CameraMode.TIME_LAPSE,
                    preview.supportsPhotoVideoRecording() && mode != CameraMode.SLOW_MO && mode != CameraMode.TIME_LAPSE);
            updateTopBarForRecording(recording);
        }
        if( recording ) {
            ui.setRecordingTime(preview.getVideoTime(false), preview.isVideoRecordingPaused());
            ui.setPaused(preview.isVideoRecordingPaused());
        }
        updateShutterStyle();
        updateInsets();
        if( ui.focusRing.isShowing() ) {
            if( preview.isFocusRecentSuccess() )
                ui.focusRing.setState(net.sourceforge.opencamera.op.ui.OpFocusRing.State.FOCUSED);
            else if( preview.isFocusRecentFailure() )
                ui.focusRing.setState(net.sourceforge.opencamera.op.ui.OpFocusRing.State.FAILED);
        }
        // zoom display
        if( currentLens != null && !logicalZoomMode )
            ui.zoomBar.setZoom(currentLens.zoomFactor * preview.getZoomRatio());
        else
            ui.zoomBar.setZoom(preview.getZoomRatio());
        // pro auto values
        if( isProMode() && ui.proPanel.getVisibility() == View.VISIBLE )
            refreshProAutoValues();
        // gallery saving indicator
        ui.gallery.setSaving(app().getImageSaver().getNImagesToSave() > 0);
    }

    // ------------------------------------------------------------------------------------------
    // hooks from Open Camera

    /** Called from MainActivity.cameraSetup() whenever the camera (re)opens. */
    void onCameraSetup() {
        if( ui == null )
            return;
        buildLenses();
        if( pendingZoomRatio > 0.0f ) {
            setDigitalZoomRatio(pendingZoomRatio);
            pendingZoomRatio = -1.0f;
        }
        positionPreview();
        rebuildTopBar();
        buildModePanel();
        updateShutterStyle();
        updateInsets();
        if( openSettingsOnce ) {
            if( incomingOpticalProfile != null ) {
                int[] requested = incomingOpticalProfile;
                incomingOpticalProfile = null;
                VideoProfile current = preview().getVideoProfile();
                if( current.videoFrameWidth != requested[0] || current.videoFrameHeight != requested[1] || current.videoFrameRate != requested[2] ) {
                    for(VideoOption option : videoOptions()) {
                        if( option.width == requested[0] && option.height == requested[1] && option.fps == requested[2] ) {
                            // Let the current setup finish before reopening with the recording screen's profile.
                            handler.post(() -> selectVideoOption(option));
                            return;
                        }
                    }
                }
            }
            opticalSettingsBaseline = preview().getVideoProfile();
            openSettingsOnce = false;
            main.openSettings();
        }
    }

    void onGalleryThumbnail(android.graphics.Bitmap bitmap) {
        if( ui != null )
            ui.setGalleryBitmap(bitmap);
    }

    void onLayoutUI() {
        if( ui != null )
            updateInsets();
    }

    void onFocusTap(float x, float y) {
        if( ui == null )
            return;
        ui.focusRing.showAt(x, y, preview().supportsExposures() && !isManualIso());
    }

    // ------------------------------------------------------------------------------------------
    // layout helpers

    private void updateInsets() {
        int bottom = main.getNavigationGap();
        ui.setInsets(0, bottom);
    }

    /** Positions the preview like the OnePlus camera: 4:3 just below the top bar, 16:9 at the top,
     *  wider ratios full screen. */
    private void positionPreview() {
        View previewContainer = main.findViewById(R.id.preview);
        if( previewContainer == null )
            return;
        ViewGroup.LayoutParams lp = previewContainer.getLayoutParams();
        if( !(lp instanceof RelativeLayout.LayoutParams) )
            return;
        RelativeLayout.LayoutParams rlp = (RelativeLayout.LayoutParams)lp;
        double aspect = preview().getCurrentPreviewAspectRatio(); // >= 1
        View root = main.findViewById(R.id.op_overlay_container);
        int screenW = root.getWidth();
        int screenH = root.getHeight();
        int top;
        if( aspect <= 1.0e-5 || screenW == 0 ) {
            top = -1;
        }
        else {
            int previewH = (int)(screenW * aspect);
            if( aspect < 1.5 ) {
                top = OpTheme.dp(main, 52);
                if( top + previewH > screenH )
                    top = Math.max(0, screenH - previewH);
            }
            else if( previewH >= screenH ) {
                top = -1; // full screen, centre
            }
            else {
                top = 0;
            }
        }
        rlp.removeRule(RelativeLayout.CENTER_IN_PARENT);
        rlp.removeRule(RelativeLayout.ALIGN_PARENT_TOP);
        if( top < 0 ) {
            rlp.addRule(RelativeLayout.CENTER_IN_PARENT);
            rlp.topMargin = 0;
        }
        else {
            rlp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
            rlp.addRule(RelativeLayout.CENTER_HORIZONTAL);
            rlp.topMargin = top;
        }
        previewContainer.setLayoutParams(rlp);
    }

    // ------------------------------------------------------------------------------------------
    // modes

    private boolean isProMode() {
        return mode == CameraMode.PRO || mode == CameraMode.PRO_VIDEO;
    }

    private boolean nightUsesExtension() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && main.supportsCameraExtension(CameraExtensionCharacteristics.EXTENSION_NIGHT);
    }

    private String photoModeFor(CameraMode m) {
        switch( m ) {
            case NIGHT:
                if( nightUsesExtension() )
                    return "preference_photo_mode_x_night";
                return "preference_photo_mode_noise_reduction";
            case PANORAMA:
                return "preference_photo_mode_panorama";
            case PHOTO:
                return prefs.getBoolean(PREF_HDR, false) ? "preference_photo_mode_hdr" : "preference_photo_mode_std";
            default:
                return "preference_photo_mode_std";
        }
    }

    private float captureRateFor(CameraMode m) {
        if( m == CameraMode.SLOW_MO ) {
            List<Float> rates = app().getSupportedVideoCaptureRates();
            float wanted = prefs.getFloat(PREF_SLOWMO_RATE, 0.25f);
            float best = 1.0f;
            for(float r : rates) {
                if( r < 0.99f && Math.abs(r - wanted) < 1.0e-3f )
                    return r;
                if( r < 0.99f && (best >= 0.99f || r < best) )
                    best = r;
            }
            return best;
        }
        if( m == CameraMode.TIME_LAPSE )
            return prefs.getFloat(PREF_TIMELAPSE_RATE, 10.0f);
        return 1.0f;
    }

    private String proPrefix(CameraMode m) {
        return m == CameraMode.PRO_VIDEO ? "op_pv_" : "op_pro_";
    }

    /** Writes Open Camera preferences for the manual controls of the target mode (auto for non-pro modes). */
    private void writeManualPrefs(SharedPreferences.Editor e, CameraMode m) {
        boolean pro = m == CameraMode.PRO || m == CameraMode.PRO_VIDEO;
        String p = proPrefix(m);
        int iso = pro ? prefs.getInt(p + "iso", -1) : -1;
        long shutter = pro ? prefs.getLong(p + "shutter", -1) : -1;
        if( iso > 0 ) {
            e.putString(PreferenceKeys.ISOPreferenceKey, String.valueOf(iso));
            if( shutter > 0 )
                e.putLong(PreferenceKeys.ExposureTimePreferenceKey, shutter);
        }
        else {
            e.putString(PreferenceKeys.ISOPreferenceKey, CameraController.ISO_DEFAULT);
            e.putLong(PreferenceKeys.ExposureTimePreferenceKey, CameraController.EXPOSURE_TIME_DEFAULT);
        }
        int wb = pro ? prefs.getInt(p + "wb", -1) : -1;
        if( wb > 0 ) {
            e.putString(PreferenceKeys.WhiteBalancePreferenceKey, "manual");
            e.putInt(PreferenceKeys.WhiteBalanceTemperaturePreferenceKey, wb);
        }
        else {
            e.putString(PreferenceKeys.WhiteBalancePreferenceKey, CameraController.WHITE_BALANCE_DEFAULT);
        }
        int ev = pro ? prefs.getInt(p + "ev", 0) : 0;
        e.putString(PreferenceKeys.ExposurePreferenceKey, String.valueOf(ev));
        float focus = pro ? prefs.getFloat(p + "focus", -1.0f) : -1.0f;
        int cameraId = app().getCameraIdPref();
        String focusKey = PreferenceKeys.getFocusPreferenceKey(cameraId, m.isVideo);
        if( focus >= 0.0f ) {
            e.putString(focusKey, "focus_mode_manual2");
            e.putFloat(PreferenceKeys.FocusDistancePreferenceKey, focus);
        }
        else {
            String cur = prefs.getString(focusKey, "");
            if( cur.equals("focus_mode_manual2") || cur.isEmpty() )
                e.putString(focusKey, m.isVideo ? "focus_mode_continuous_video" : "focus_mode_continuous_picture");
        }
    }

    private void enterMode(CameraMode m, boolean initial) {
        mode = m;
        prefs.edit().putString(PREF_MODE, m.name()).apply();
        ui.setMode(m);
        SharedPreferences.Editor e = prefs.edit();
        // Preview starts with isVideo() == false, even when its saved setting is true.
        // Persist the requested type unconditionally before asynchronous camera setup reads it.
        e.putBoolean(PreferenceKeys.IsVideoPreferenceKey, m.isVideo);
        e.putString(PreferenceKeys.PhotoModePreferenceKey, photoModeFor(m));
        int cameraId = app().getCameraIdPref();
        String physical = app().getCameraIdSPhysicalPref();
        e.putFloat(PreferenceKeys.getVideoCaptureRatePreferenceKey(cameraId, physical), captureRateFor(m));
        e.putString(PreferenceKeys.VideoLogPreferenceKey, m == CameraMode.PRO_VIDEO ? prefs.getString(PREF_PROVIDEO_LOG, "off") : "off");
        if( m == CameraMode.NIGHT && !nightUsesExtension() )
            app().setNRMode(prefs.getBoolean(PREF_NIGHT_LOWLIGHT, true) ? "preference_nr_mode_low_light" : "preference_nr_mode_normal");
        writeManualPrefs(e, m);
        e.apply();
        app().getDrawPreview().updateSettings();
        proPanelBuilt = false;
        rebuildTopBar();
        buildModePanel();
        ui.setInfoText(null);
        if( initial ) {
            // MainActivity's startup opens the camera with the selected capture type above.
            return;
        }
        if( preview().isVideo() != m.isVideo ) {
            main.clickedSwitchVideo(null);
        }
        else {
            main.updateForSettings(true, "", false, true);
        }
        showModeHint(m);
    }

    private void showModeHint(CameraMode m) {
        switch( m ) {
            case NIGHT:
                ui.showToast(nightUsesExtension() ? "Night: hold steady" : "Night (multi-frame): hold steady");
                break;
            case SLOW_MO: {
                List<Float> rates = app().getSupportedVideoCaptureRates();
                if( rates.isEmpty() || rates.get(0) >= 0.99f ) {
                    ui.showToast("This phone exposes no high frame rates to apps,\nslow-mo will record at normal speed", 3500);
                }
                break;
            }
            case PANORAMA:
                ui.showToast("Tap shutter, then pan slowly");
                break;
            default:
                break;
        }
    }

    @Override
    public void onModeSelected(CameraMode m) {
        Preview preview = preview();
        if( preview.isVideoRecording() || preview.isTakingPhotoOrOnTimer() || app().getGyroSensor().isRecording() ) {
            ui.setMode(mode);
            return;
        }
        if( m.engine == CameraMode.Engine.STUDIO ) {
            launchStudio(m);
            return;
        }
        if( m == CameraMode.VIDEO && shouldUseOpticalVideo() ) {
            launchOpticalVideo();
            return;
        }
        if( m != mode || preview.isVideo() != m.isVideo || app().isVideoPref() != m.isVideo )
            enterMode(m, false);
        else
            ui.setMode(mode);
    }

    private void launchStudio(CameraMode m) {
        Intent intent = new Intent(main, StudioActivity.class);
        intent.putExtra(EXTRA_MODE, m.name());
        intent.putExtra(EXTRA_RETURN_MODE, mode.name());
        CameraInventory inv = CameraInventory.get(main);
        int index = app().getCameraIdPref();
        String id = index >= 0 && index < inv.topLevelIds.size() ? inv.topLevelIds.get(index) : null;
        CameraController controller = preview().getCameraController();
        boolean front = controller != null && controller.getFacing() == CameraController.Facing.FACING_FRONT;
        float zoom = preview().getZoomRatio();
        if( !logicalZoomMode && currentLens != null )
            zoom *= currentLens.zoomFactor;
        new CameraSelection(id, app().getCameraIdSPhysicalPref(), front, zoom).writeTo(intent);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        main.startActivity(intent);
        main.overridePendingTransition(0, 0);
        main.finish();
    }

    /** The ordinary Video screen needs a camera-independent encoder for live lens changes.
     *  Keep external capture intents on the classic path, which owns their result contract. */
    private boolean shouldUseOpticalVideo() {
        if( launchingOpticalVideo ) return false;
        if( !preview().usingCamera2API() ) return logOpticalDecision(false, "Camera2 is not active");
        String action = main.getIntent() != null ? main.getIntent().getAction() : null;
        if( android.provider.MediaStore.ACTION_VIDEO_CAPTURE.equals(action) ||
                android.provider.MediaStore.ACTION_IMAGE_CAPTURE.equals(action) ||
                android.provider.MediaStore.ACTION_IMAGE_CAPTURE_SECURE.equals(action) ||
                android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE.equals(action) )
            return logOpticalDecision(false, "external or secure capture intent: " + action);
        int accessible = 0;
        List<String> ids = new ArrayList<>();
        for(CameraInventory.Lens lens : CameraInventory.get(main).usableLenses(CameraMetadata.LENS_FACING_BACK, false)) {
            if( ocTarget(lens) != null ) { accessible++; ids.add(lens.id + "@" + lens.zoomFactor + "x"); }
        }
        return logOpticalDecision(accessible > 1, "accessible rear lenses=" + ids);
    }

    private boolean logOpticalDecision(boolean optical, String reason) {
        String decision = (optical ? "live optical" : "classic") + "; " + reason;
        if( !decision.equals(lastOpticalDecision) ) {
            lastOpticalDecision = decision;
            net.sourceforge.opencamera.studio.StudioLog.i("Normal Video dispatch: " + decision);
        }
        return optical;
    }

    private void launchOpticalVideo() {
        if( launchingOpticalVideo ) return;
        launchingOpticalVideo = true;
        Intent intent = new Intent(main, OpticalVideoActivity.class);
        CameraInventory inv = CameraInventory.get(main);
        int index = app().getCameraIdPref();
        String id = index >= 0 && index < inv.topLevelIds.size() ? inv.topLevelIds.get(index) : null;
        CameraController controller = preview().getCameraController();
        boolean front = controller != null && controller.getFacing() == CameraController.Facing.FACING_FRONT;
        float zoom = preview().getZoomRatio();
        if( !logicalZoomMode && currentLens != null ) zoom *= currentLens.zoomFactor;
        new CameraSelection(id, app().getCameraIdSPhysicalPref(), front, zoom).writeTo(intent);
        try {
            VideoProfile profile = preview().getVideoProfile();
            if( opticalSettingsBaseline != null )
                refreshOpticalProfile = profile.videoFrameWidth != opticalSettingsBaseline.videoFrameWidth ||
                        profile.videoFrameHeight != opticalSettingsBaseline.videoFrameHeight ||
                        profile.videoFrameRate != opticalSettingsBaseline.videoFrameRate;
            intent.putExtra(OpticalVideoActivity.EXTRA_REFRESH_PROFILE, refreshOpticalProfile);
            intent.putExtra(OpticalVideoActivity.EXTRA_WIDTH, profile.videoFrameWidth);
            intent.putExtra(OpticalVideoActivity.EXTRA_HEIGHT, profile.videoFrameHeight);
            intent.putExtra(OpticalVideoActivity.EXTRA_FPS, profile.videoFrameRate);
        }
        catch(Exception error) { Log.w(TAG, "Video profile will be selected from shared lens capabilities", error); }
        app().setVideoPref(true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        main.startActivity(intent);
        main.overridePendingTransition(0, 0);
        main.finish();
    }

    // ------------------------------------------------------------------------------------------
    // top bar

    private String flashIconLabel(String flash) {
        return flash == null ? "" : flash;
    }

    private void rebuildTopBar() {
        if( ui == null )
            return;
        ui.topBar.clear();
        Preview preview = preview();
        boolean video = mode.isVideo;
        // flash / torch
        if( preview.supportsFlash() && mode != CameraMode.NIGHT && mode != CameraMode.PANORAMA ) {
            ui.topBar.addIcon("flash", flashIcon(preview.getCurrentFlashValue()), "Flash", v -> {
                preview().cycleFlash(true, true);
                updateFlashIcon();
            });
        }
        switch( mode ) {
            case PHOTO:
                if( main.supportsHDR() ) {
                    ui.topBar.addIcon("hdr", prefs.getBoolean(PREF_HDR, false) ? R.drawable.op_ic_hdr_on : R.drawable.op_ic_hdr_off, "HDR", v -> toggleHdr());
                    ui.topBar.setActive("hdr", prefs.getBoolean(PREF_HDR, false));
                }
                addRatioItem();
                addTimerItem();
                break;
            case NIGHT:
                if( !nightUsesExtension() ) {
                    boolean low = prefs.getBoolean(PREF_NIGHT_LOWLIGHT, true);
                    ui.topBar.addIcon("lowlight", R.drawable.op_ic_nights_stay, "Low light boost", v -> toggleNightLowLight());
                    ui.topBar.setActive("lowlight", low);
                }
                addTimerItem();
                break;
            case PRO:
                if( preview.supportsRaw() ) {
                    boolean raw = !prefs.getString(PreferenceKeys.RawPreferenceKey, "preference_raw_no").equals("preference_raw_no");
                    ui.topBar.addIcon("raw", raw ? R.drawable.op_ic_raw_on : R.drawable.op_ic_raw_off, "RAW", v -> toggleRaw());
                    ui.topBar.setActive("raw", raw);
                }
                addRatioItem();
                addTimerItem();
                addAssistItems();
                break;
            case VIDEO:
            case PRO_VIDEO:
                ui.topBar.addText("vq", videoResolutionLabel(), "Resolution", v -> cycleVideoQuality());
                ui.topBar.addText("fps", currentVideoFps() + " FPS", "Frame rate", v -> cycleVideoFrameRate());
                if( preview.supportsVideoStabilization() ) {
                    boolean stab = prefs.getBoolean(PreferenceKeys.VideoStabilizationPreferenceKey, false);
                    ui.topBar.addIcon("stab", R.drawable.op_ic_video_stable, "Stabilization", v -> toggleStabilization());
                    ui.topBar.setActive("stab", stab);
                }
                if( mode == CameraMode.PRO_VIDEO ) {
                    if( preview.supportsTonemapCurve() ) {
                        ui.topBar.addText("log", logLabel(), "Picture profile", v -> cycleLogProfile());
                        ui.topBar.setActive("log", !prefs.getString(PREF_PROVIDEO_LOG, "off").equals("off"));
                    }
                    addAssistItems();
                }
                break;
            case SLOW_MO:
                ui.topBar.addText("rate", slowMoLabel(), "Slow motion rate", v -> cycleSlowMo());
                break;
            case TIME_LAPSE:
                ui.topBar.addText("rate", timeLapseLabel(), "Time-lapse speed", v -> cycleTimeLapse());
                break;
            default:
                break;
        }
        ui.topBar.addIcon("settings", R.drawable.op_ic_settings, "Settings", v -> main.clickedSettings(null));
        updateFlashIcon();
        ui.refreshRotatableViews();
        if( !video )
            ui.topBar.setItemVisible("vq", false);
    }

    private void updateTopBarForRecording(boolean recording) {
        for(String id : new String[]{"hdr", "ratio", "timer", "vq", "fps", "stab", "log", "rate", "raw", "settings", "lowlight"}) {
            View v = ui.topBar.getItem(id);
            if( v != null )
                v.setVisibility(recording ? View.INVISIBLE : View.VISIBLE);
        }
    }

    private int flashIcon(String value) {
        if( value == null )
            return R.drawable.op_ic_flash_off;
        switch( value ) {
            case "flash_auto":
            case "flash_frontscreen_auto":
                return R.drawable.op_ic_flash_auto;
            case "flash_on":
            case "flash_frontscreen_on":
            case "flash_red_eye":
                return R.drawable.op_ic_flash_on;
            case "flash_torch":
            case "flash_frontscreen_torch":
                return R.drawable.op_ic_flashlight_on;
            default:
                return R.drawable.op_ic_flash_off;
        }
    }

    private void updateFlashIcon() {
        String value = preview().getCurrentFlashValue();
        ui.topBar.setIcon("flash", flashIcon(value));
        ui.topBar.setActive("flash", value != null && !value.equals("flash_off"));
    }

    private void addTimerItem() {
        long timer = app().getTimerPref();
        int icon = timer <= 0 ? R.drawable.op_ic_timer_off : timer <= 3000 ? R.drawable.op_ic_timer_3_select : R.drawable.op_ic_timer_10_select;
        ui.topBar.addIcon("timer", icon, "Timer", v -> cycleTimer());
        ui.topBar.setActive("timer", timer > 0);
    }

    private void cycleTimer() {
        long timer = app().getTimerPref();
        String next;
        if( timer <= 0 )
            next = "3";
        else if( timer <= 3000 )
            next = "10";
        else
            next = "0";
        prefs.edit().putString(PreferenceKeys.TimerPreferenceKey, next).apply();
        ui.showToast(next.equals("0") ? "Timer off" : "Timer " + next + "s");
        rebuildTopBar();
    }

    private void addRatioItem() {
        ui.topBar.addText("ratio", ratioLabel(), "Aspect ratio", v -> cycleRatio());
    }

    private String ratioLabel() {
        CameraController.Size size = preview().getCurrentPictureSize();
        if( size == null )
            return "4:3";
        double a = Math.max(size.width, size.height) / (double)Math.min(size.width, size.height);
        if( Math.abs(a - 4.0/3.0) < 0.05 )
            return "4:3";
        if( Math.abs(a - 16.0/9.0) < 0.05 )
            return "16:9";
        if( Math.abs(a - 1.0) < 0.05 )
            return "1:1";
        if( a > 1.9 )
            return "Full";
        return String.format(Locale.US, "%.1f", a);
    }

    private void cycleRatio() {
        List<CameraController.Size> sizes = preview().getSupportedPictureSizes(true);
        if( sizes == null || sizes.isEmpty() )
            return;
        double [] targets = {4.0/3.0, 16.0/9.0, 1.0, -1.0}; // -1 = full screen (widest available >= 1.9)
        String [] names = {"4:3", "16:9", "1:1", "Full"};
        String current = ratioLabel();
        int start = 0;
        for(int i=0;i<names.length;i++) {
            if( names[i].equals(current) )
                start = i;
        }
        for(int k=1;k<=names.length;k++) {
            int idx = (start + k) % names.length;
            CameraController.Size best = null;
            for(CameraController.Size s : sizes) {
                double a = Math.max(s.width, s.height) / (double)Math.min(s.width, s.height);
                boolean match = targets[idx] < 0 ? a > 1.9 : Math.abs(a - targets[idx]) < 0.03;
                if( match && (best == null || (long)s.width * s.height > (long)best.width * best.height) )
                    best = s;
            }
            if( best != null ) {
                app().setCameraResolutionPref(best.width, best.height);
                prefs.edit().putString(PREF_RATIO, names[idx]).apply();
                ui.showToast(names[idx] + "  ·  " + Preview.getMPString(best.width, best.height));
                main.updateForSettings(true, "", false, true);
                return;
            }
        }
    }

    private void toggleHdr() {
        boolean hdr = !prefs.getBoolean(PREF_HDR, false);
        prefs.edit().putBoolean(PREF_HDR, hdr).putString(PreferenceKeys.PhotoModePreferenceKey, hdr ? "preference_photo_mode_hdr" : "preference_photo_mode_std").apply();
        app().getDrawPreview().updateSettings();
        ui.showToast(hdr ? "HDR on" : "HDR off");
        main.updateForSettings(true, "", false, true);
        rebuildTopBar();
    }

    private void toggleNightLowLight() {
        boolean low = !prefs.getBoolean(PREF_NIGHT_LOWLIGHT, true);
        prefs.edit().putBoolean(PREF_NIGHT_LOWLIGHT, low).apply();
        app().setNRMode(low ? "preference_nr_mode_low_light" : "preference_nr_mode_normal");
        ui.showToast(low ? "Low light boost on" : "Low light boost off");
        main.updateForSettings(true, "", false, true);
        rebuildTopBar();
    }

    private void toggleRaw() {
        boolean raw = prefs.getString(PreferenceKeys.RawPreferenceKey, "preference_raw_no").equals("preference_raw_no");
        prefs.edit().putString(PreferenceKeys.RawPreferenceKey, raw ? "preference_raw_yes" : "preference_raw_no").apply();
        if( raw && !prefs.contains(PreferenceKeys.RawInfoPreferenceKey) )
            prefs.edit().putBoolean(PreferenceKeys.RawInfoPreferenceKey, true).apply();
        ui.showToast(raw ? "RAW + JPEG" : "JPEG");
        main.updateForSettings(true, "", false, true);
        rebuildTopBar();
    }

    private void addAssistItems() {
        if( !main.supportsPreviewBitmaps() )
            return;
        boolean hist = !prefs.getString(PreferenceKeys.HistogramPreferenceKey, "preference_histogram_off").equals("preference_histogram_off");
        ui.topBar.addIcon("hist", R.drawable.op_ic_bar_chart, "Histogram", v -> {
            boolean on = prefs.getString(PreferenceKeys.HistogramPreferenceKey, "preference_histogram_off").equals("preference_histogram_off");
            prefs.edit().putString(PreferenceKeys.HistogramPreferenceKey, on ? "preference_histogram_rgb" : "preference_histogram_off").apply();
            app().getDrawPreview().updateSettings();
            ui.topBar.setActive("hist", on);
        });
        ui.topBar.setActive("hist", hist);
        boolean peak = app().getFocusPeakingPref();
        ui.topBar.addIcon("peak", R.drawable.op_ic_filter_center_focus, "Focus peaking", v -> {
            boolean on = !app().getFocusPeakingPref();
            prefs.edit().putString(PreferenceKeys.FocusPeakingPreferenceKey, on ? "preference_focus_peaking_on" : "preference_focus_peaking_off").apply();
            app().getDrawPreview().updateSettings();
            ui.topBar.setActive("peak", on);
            ui.showToast(on ? "Focus peaking on" : "Focus peaking off");
        });
        ui.topBar.setActive("peak", peak);
        boolean zebra = !prefs.getString(PreferenceKeys.ZebraStripesPreferenceKey, "0").equals("0");
        ui.topBar.addIcon("zebra", R.drawable.op_ic_gradient, "Zebra stripes", v -> {
            boolean on = prefs.getString(PreferenceKeys.ZebraStripesPreferenceKey, "0").equals("0");
            prefs.edit().putString(PreferenceKeys.ZebraStripesPreferenceKey, on ? "95" : "0").apply();
            app().getDrawPreview().updateSettings();
            ui.topBar.setActive("zebra", on);
            ui.showToast(on ? "Zebras on (95%)" : "Zebras off");
        });
        ui.topBar.setActive("zebra", zebra);
    }

    // --- video quality

    private static class VideoOption {
        final String quality;
        final int width, height, fps;

        VideoOption(String quality, int width, int height, int fps) {
            this.quality = quality;
            this.width = width;
            this.height = height;
            this.fps = fps;
        }

        String label() {
            return resolutionLabel() + " " + fps;
        }

        String resolutionLabel() {
            String res;
            if( width >= 3840 )
                res = "4K";
            else if( height >= 1080 || width >= 1920 )
                res = "1080P";
            else if( height >= 720 )
                res = "720P";
            else
                res = height + "P";
            return res;
        }
    }

    private List<VideoOption> videoOptions() {
        List<VideoOption> options = new ArrayList<>();
        Preview preview = preview();
        if( preview.getVideoQualityHander() == null || preview.getVideoQualityHander().getSupportedVideoQuality() == null )
            return options;
        int [][] wanted = {{3840, 2160}, {1920, 1080}, {1280, 720}};
        List<CameraController.Size> sizes = preview.getVideoQualityHander().getSupportedVideoSizes();
        for(int fps : new int[]{30, 60}) {
            if( preview.usingCamera2API() && !preview.getVideoQualityHander().videoSupportsFrameRate(fps) )
                continue;
            List<String> qualities = preview.getVideoQualityHander().getSupportedVideoQuality();
            for(int [] w : wanted) {
                // getSupportedVideoQuality only filters on some high-speed-capable
                // devices. Check every size explicitly for normal Camera2 30/60 FPS.
                if( preview.usingCamera2API() && (sizes == null || CameraController.CameraFeatures.findSize(
                        sizes, new CameraController.Size(w[0], w[1]), fps, false) == null) )
                    continue;
                for(String q : qualities) {
                    CamcorderProfile profile;
                    try {
                        profile = preview.getCamcorderProfile(q);
                    }
                    catch(Exception e) {
                        continue;
                    }
                    if( profile != null && profile.videoFrameWidth == w[0] && profile.videoFrameHeight == w[1] ) {
                        options.add(new VideoOption(q, w[0], w[1], fps));
                        break;
                    }
                }
            }
        }
        return options;
    }

    private String videoQualityLabel() {
        Preview preview = preview();
        int f = currentVideoFps();
        try {
            String q = preview.getVideoQualityHander().getCurrentVideoQuality();
            if( q != null ) {
                CamcorderProfile profile = preview.getCamcorderProfile(q);
                if( profile != null )
                    return new VideoOption(q, profile.videoFrameWidth, profile.videoFrameHeight, f).label();
            }
        }
        catch(Exception e) {
            Log.e(TAG, "videoQualityLabel failed", e);
        }
        return "HD " + f;
    }

    private int currentVideoFps() {
        try {
            int fps = preview().getVideoProfile().videoFrameRate;
            return fps > 0 ? fps : 30;
        }
        catch(RuntimeException e) {
            return 30;
        }
    }

    private String videoResolutionLabel() {
        String label = videoQualityLabel();
        return label.substring(0, label.lastIndexOf(' '));
    }

    private void cycleVideoQuality() {
        List<VideoOption> options = videoOptions();
        int fps = currentVideoFps();
        List<VideoOption> matching = new ArrayList<>();
        for(VideoOption option : options) {
            if( option.fps == fps )
                matching.add(option);
        }
        if( !matching.isEmpty() )
            options = matching;
        if( options.isEmpty() )
            return;
        String current = videoQualityLabel();
        int idx = -1;
        for(int i=0;i<options.size();i++) {
            if( options.get(i).label().equals(current) )
                idx = i;
        }
        VideoOption next = options.get((idx + 1) % options.size());
        selectVideoOption(next);
    }

    private void cycleVideoFrameRate() {
        if( preview().isVideoRecording() || preview().isOpeningCamera() )
            return;
        int fps = currentVideoFps() == 60 ? 30 : 60;
        VideoOption best = null;
        String resolution = videoResolutionLabel();
        for(VideoOption option : videoOptions()) {
            if( option.fps != fps )
                continue;
            if( best == null )
                best = option;
            if( option.resolutionLabel().equals(resolution) ) {
                best = option;
                break;
            }
        }
        if( best == null ) {
            ui.showToast("Camera2 does not expose " + fps + " FPS for this lens. Try the main 1x camera.", 3500);
            return;
        }
        selectVideoOption(best);
    }

    private void selectVideoOption(VideoOption next) {
        if( preview().isVideoRecording() || preview().isOpeningCamera() )
            return;
        int cameraId = preview().getCameraId();
        String physical = app().getCameraIdSPhysicalPref();
        prefs.edit()
                .putString(PreferenceKeys.getVideoFPSPreferenceKey(cameraId, physical), String.valueOf(next.fps))
                .putString(PreferenceKeys.getVideoQualityPreferenceKey(cameraId, physical, false), next.quality)
                .apply();
        ui.showToast(next.label() + " fps");
        main.updateForSettings(true, "", false, true);
        ui.topBar.setText("vq", next.resolutionLabel());
        ui.topBar.setText("fps", next.fps + " FPS");
    }

    private void toggleStabilization() {
        boolean stab = !prefs.getBoolean(PreferenceKeys.VideoStabilizationPreferenceKey, false);
        prefs.edit().putBoolean(PreferenceKeys.VideoStabilizationPreferenceKey, stab).apply();
        ui.showToast(stab ? "Stabilization on" : "Stabilization off");
        main.updateForSettings(true, "", false, true);
        ui.topBar.setActive("stab", stab);
    }

    private static final String [] LOG_VALUES = {"off", "fine", "medium", "strong", "jtlog"};
    private static final String [] LOG_LABELS = {"STD", "FLAT", "LOG", "LOG+", "JTLOG"};

    private String logLabel() {
        String v = prefs.getString(PREF_PROVIDEO_LOG, "off");
        for(int i=0;i<LOG_VALUES.length;i++) {
            if( LOG_VALUES[i].equals(v) )
                return LOG_LABELS[i];
        }
        return "STD";
    }

    private void cycleLogProfile() {
        String v = prefs.getString(PREF_PROVIDEO_LOG, "off");
        int idx = 0;
        for(int i=0;i<LOG_VALUES.length;i++) {
            if( LOG_VALUES[i].equals(v) )
                idx = i;
        }
        String next = LOG_VALUES[(idx + 1) % LOG_VALUES.length];
        prefs.edit().putString(PREF_PROVIDEO_LOG, next).putString(PreferenceKeys.VideoLogPreferenceKey, next).apply();
        ui.showToast("Profile: " + logLabel());
        main.updateForSettings(true, "", false, true);
        rebuildTopBar();
    }

    private String slowMoLabel() {
        float rate = captureRateFor(CameraMode.SLOW_MO);
        if( rate >= 0.99f )
            return "1x";
        return Math.round(1.0f / rate) + "x SLOW";
    }

    private void cycleSlowMo() {
        List<Float> rates = new ArrayList<>();
        for(float r : app().getSupportedVideoCaptureRates()) {
            if( r < 0.99f )
                rates.add(r);
        }
        if( rates.isEmpty() ) {
            ui.showToast("No high frame rates available on this camera", 2500);
            return;
        }
        float cur = captureRateFor(CameraMode.SLOW_MO);
        int idx = 0;
        for(int i=0;i<rates.size();i++) {
            if( Math.abs(rates.get(i) - cur) < 1.0e-3f )
                idx = i;
        }
        float next = rates.get((idx + 1) % rates.size());
        prefs.edit().putFloat(PREF_SLOWMO_RATE, next)
                .putFloat(PreferenceKeys.getVideoCaptureRatePreferenceKey(preview().getCameraId(), app().getCameraIdSPhysicalPref()), next).apply();
        ui.showToast(Math.round(1.0f / next) + "x slow motion");
        main.updateForSettings(true, "", false, true);
        ui.topBar.setText("rate", slowMoLabel());
    }

    private static final float [] LAPSE_RATES = {2.0f, 5.0f, 10.0f, 30.0f, 60.0f, 120.0f, 240.0f};

    private String timeLapseLabel() {
        return Math.round(captureRateFor(CameraMode.TIME_LAPSE)) + "x";
    }

    private void cycleTimeLapse() {
        float cur = captureRateFor(CameraMode.TIME_LAPSE);
        int idx = 0;
        for(int i=0;i<LAPSE_RATES.length;i++) {
            if( Math.abs(LAPSE_RATES[i] - cur) < 1.0e-3f )
                idx = i;
        }
        float next = LAPSE_RATES[(idx + 1) % LAPSE_RATES.length];
        prefs.edit().putFloat(PREF_TIMELAPSE_RATE, next)
                .putFloat(PreferenceKeys.getVideoCaptureRatePreferenceKey(preview().getCameraId(), app().getCameraIdSPhysicalPref()), next).apply();
        ui.showToast("Time-lapse " + Math.round(next) + "x");
        main.updateForSettings(true, "", false, true);
        ui.topBar.setText("rate", timeLapseLabel());
    }

    // ------------------------------------------------------------------------------------------
    // mode panel (pro dials, chips)

    private void buildModePanel() {
        if( ui == null )
            return;
        if( isProMode() && preview().getCameraController() != null ) {
            buildProPanel();
            ui.setProPanelVisible(true);
        }
        else {
            ui.setProPanelVisible(false);
        }
        ui.setChips(null, null);
    }

    private boolean isManualIso() {
        CameraController cc = preview().getCameraController();
        return cc != null && cc.isManualISO();
    }

    private static int nearestIndexInt(List<Integer> list, int value) {
        int best = 0;
        for(int i=0;i<list.size();i++) {
            if( Math.abs(list.get(i) - value) < Math.abs(list.get(best) - value) )
                best = i;
        }
        return best;
    }

    private static int nearestIndexLong(List<Long> list, long value) {
        int best = 0;
        double lv = Math.log(Math.max(1, value));
        for(int i=0;i<list.size();i++) {
            if( Math.abs(Math.log(list.get(i)) - lv) < Math.abs(Math.log(list.get(best)) - lv) )
                best = i;
        }
        return best;
    }

    private static int nearestIndexFloat(List<Float> list, float value) {
        int best = 0;
        for(int i=0;i<list.size();i++) {
            if( Math.abs(list.get(i) - value) < Math.abs(list.get(best) - value) )
                best = i;
        }
        return best;
    }

    private void buildProPanel() {
        Preview preview = preview();
        CameraController cc = preview.getCameraController();
        List<OpProPanel.Param> params = new ArrayList<>();
        String p = proPrefix(mode);

        // WB
        OpProPanel.Param wb = new OpProPanel.Param("wb", "WB");
        wbValues.clear();
        if( preview.supportsWhiteBalanceTemperature() ) {
            wbValues.addAll(OpFormat.wbLadder(preview.getMinimumWhiteBalanceTemperature(), preview.getMaximumWhiteBalanceTemperature()));
            for(int k : wbValues)
                wb.values.add(k + "K");
            wb.majorEvery = 10;
            int saved = prefs.getInt(p + "wb", -1);
            wb.isAuto = saved <= 0;
            wb.index = nearestIndexInt(wbValues, saved > 0 ? saved : 5000);
        }
        else {
            wb.enabled = false;
        }
        params.add(wb);

        // AF
        OpProPanel.Param af = new OpProPanel.Param("af", "AF");
        focusValues.clear();
        List<String> focusModes = preview.getSupportedFocusValues();
        float minFocus = preview.getMinimumFocusDistance();
        if( focusModes != null && focusModes.contains("focus_mode_manual2") && minFocus > 0.0f ) {
            int n = 60;
            for(int i=0;i<n;i++) {
                float t = i / (float)(n - 1);
                float d = minFocus * t * t; // finer steps near infinity
                focusValues.add(d);
                af.values.add(OpFormat.focus(d));
            }
            af.majorEvery = 10;
            float saved = prefs.getFloat(p + "focus", -1.0f);
            af.isAuto = saved < 0.0f;
            af.index = nearestIndexFloat(focusValues, saved >= 0 ? saved : 0.0f);
        }
        else {
            af.enabled = false;
        }
        params.add(af);

        // ISO
        OpProPanel.Param iso = new OpProPanel.Param("iso", "ISO");
        isoValues.clear();
        if( preview.supportsISORange() ) {
            isoValues.addAll(OpFormat.isoLadder(preview.getMinimumISO(), preview.getMaximumISO()));
            for(int v : isoValues)
                iso.values.add(String.valueOf(v));
            iso.majorEvery = 3;
            boolean manual = cc != null && cc.isManualISO();
            iso.isAuto = !manual;
            iso.index = nearestIndexInt(isoValues, manual ? cc.getISO() : 400);
        }
        else {
            iso.enabled = false;
        }
        params.add(iso);

        // Shutter
        OpProPanel.Param s = new OpProPanel.Param("s", "S");
        shutterValues.clear();
        if( preview.supportsExposureTime() ) {
            long max = preview.getMaximumExposureTime();
            if( mode == CameraMode.PRO_VIDEO )
                max = Math.min(max, 1000000000L / 30);
            shutterValues.addAll(OpFormat.shutterLadder(preview.getMinimumExposureTime(), max));
            for(long v : shutterValues)
                s.values.add(OpFormat.shutter(v));
            s.majorEvery = 3;
            boolean manual = cc != null && cc.isManualISO();
            s.isAuto = !manual;
            s.index = nearestIndexLong(shutterValues, manual ? cc.getExposureTime() : 1000000000L / 60);
        }
        else {
            s.enabled = false;
        }
        params.add(s);

        // EV
        OpProPanel.Param ev = new OpProPanel.Param("ev", "EV");
        evValues.clear();
        if( preview.supportsExposures() ) {
            float step = cc != null ? exposureStep() : 1.0f / 3.0f;
            for(int i=preview.getMinimumExposure();i<=preview.getMaximumExposure();i++) {
                evValues.add(i);
                ev.values.add(OpFormat.ev(i * step));
            }
            ev.majorEvery = Math.max(1, Math.round(1.0f / step));
            int cur = preview.getCurrentExposure();
            ev.hasAuto = true;
            ev.isAuto = cur == 0;
            ev.index = evValues.indexOf(cur);
            if( ev.index < 0 )
                ev.index = evValues.indexOf(0);
            ev.autoText = "0";
            ev.enabled = !(cc != null && cc.isManualISO());
        }
        else {
            ev.enabled = false;
        }
        params.add(ev);

        ui.proPanel.setParams(params);
        ui.proPanel.setListener(proListener);
        proPanelBuilt = true;
        refreshProAutoValues();
    }

    private float exposureStep() {
        try {
            CameraController cc = preview().getCameraController();
            if( cc != null )
                return cc.getCameraFeatures().exposure_step;
        }
        catch(Exception e) {
            Log.e(TAG, "exposureStep failed", e);
        }
        return 1.0f / 3.0f;
    }

    private void refreshProAutoValues() {
        if( !proPanelBuilt )
            return;
        CameraController cc = preview().getCameraController();
        if( cc == null )
            return;
        OpProPanel.Param iso = ui.proPanel.getParam("iso");
        if( iso != null && iso.isAuto )
            iso.autoText = cc.captureResultHasIso() ? "A " + cc.captureResultIso() : "AUTO";
        OpProPanel.Param s = ui.proPanel.getParam("s");
        if( s != null && s.isAuto )
            s.autoText = cc.captureResultHasExposureTime() ? "A " + OpFormat.shutter(cc.captureResultExposureTime()) : "AUTO";
        OpProPanel.Param wb = ui.proPanel.getParam("wb");
        if( wb != null && wb.isAuto )
            wb.autoText = cc.captureResultHasWhiteBalanceTemperature() ? "A " + cc.captureResultWhiteBalanceTemperature() + "K" : "AUTO";
        OpProPanel.Param af = ui.proPanel.getParam("af");
        if( af != null && af.isAuto )
            af.autoText = "AF";
        OpProPanel.Param ev = ui.proPanel.getParam("ev");
        if( ev != null )
            ev.enabled = !cc.isManualISO();
        ui.proPanel.refresh();
    }

    private final OpProPanel.Listener proListener = new OpProPanel.Listener() {
        @Override
        public void onProValueChanged(OpProPanel.Param param, int index) {
            CameraController cc = preview().getCameraController();
            if( cc == null )
                return;
            String p = proPrefix(mode);
            switch( param.key ) {
                case "iso": {
                    int iso = isoValues.get(index);
                    long t = currentOrCapturedExposureTime(cc);
                    setManualExposure(cc, iso, t);
                    break;
                }
                case "s": {
                    long t = shutterValues.get(index);
                    int iso = currentOrCapturedIso(cc);
                    setManualExposure(cc, iso, t);
                    break;
                }
                case "wb": {
                    int k = wbValues.get(index);
                    cc.setWhiteBalance("manual");
                    cc.setWhiteBalanceTemperature(k);
                    app().setWhiteBalancePref("manual");
                    app().setWhiteBalanceTemperaturePref(k);
                    prefs.edit().putInt(p + "wb", k).apply();
                    break;
                }
                case "af": {
                    float d = focusValues.get(index);
                    if( !"focus_mode_manual2".equals(preview().getCurrentFocusValue()) )
                        preview().updateFocus("focus_mode_manual2", true, false);
                    preview().setFocusDistance(d, false, false);
                    prefs.edit().putFloat(p + "focus", d).apply();
                    if( main.supportsPreviewBitmaps() && !app().getFocusPeakingPref() )
                        ui.showToast("Tip: enable focus peaking in the top bar", 1500);
                    break;
                }
                case "ev": {
                    int ev = evValues.get(index);
                    cc.setExposureCompensation(ev);
                    app().setExposureCompensationPref(ev);
                    prefs.edit().putInt(p + "ev", ev).apply();
                    param.isAuto = ev == 0;
                    break;
                }
            }
            ui.proPanel.refresh();
        }

        @Override
        public void onProAutoChanged(OpProPanel.Param param, boolean auto) {
            CameraController cc = preview().getCameraController();
            if( cc == null )
                return;
            String p = proPrefix(mode);
            switch( param.key ) {
                case "iso":
                case "s": {
                    OpProPanel.Param iso = ui.proPanel.getParam("iso");
                    OpProPanel.Param s = ui.proPanel.getParam("s");
                    if( auto ) {
                        cc.setManualISO(false, 0);
                        app().setISOPref(CameraController.ISO_DEFAULT);
                        app().setExposureTimePref(CameraController.EXPOSURE_TIME_DEFAULT);
                        prefs.edit().putInt(p + "iso", -1).putLong(p + "shutter", -1).apply();
                        if( iso != null )
                            iso.isAuto = true;
                        if( s != null )
                            s.isAuto = true;
                    }
                    else {
                        int isoValue = currentOrCapturedIso(cc);
                        long t = currentOrCapturedExposureTime(cc);
                        setManualExposure(cc, isoValue, t);
                        if( iso != null ) {
                            iso.isAuto = false;
                            iso.index = nearestIndexInt(isoValues, isoValue);
                        }
                        if( s != null ) {
                            s.isAuto = false;
                            s.index = nearestIndexLong(shutterValues, t);
                        }
                    }
                    break;
                }
                case "wb":
                    if( auto ) {
                        cc.setWhiteBalance(CameraController.WHITE_BALANCE_DEFAULT);
                        app().setWhiteBalancePref(CameraController.WHITE_BALANCE_DEFAULT);
                        prefs.edit().putInt(p + "wb", -1).apply();
                    }
                    else {
                        int k = cc.captureResultHasWhiteBalanceTemperature() ? cc.captureResultWhiteBalanceTemperature() : 5000;
                        k = wbValues.isEmpty() ? k : wbValues.get(nearestIndexInt(wbValues, k));
                        cc.setWhiteBalance("manual");
                        cc.setWhiteBalanceTemperature(k);
                        app().setWhiteBalancePref("manual");
                        app().setWhiteBalanceTemperaturePref(k);
                        prefs.edit().putInt(p + "wb", k).apply();
                        param.index = nearestIndexInt(wbValues, k);
                    }
                    break;
                case "af":
                    if( auto ) {
                        preview().updateFocus(mode.isVideo ? "focus_mode_continuous_video" : "focus_mode_continuous_picture", true, true);
                        prefs.edit().putFloat(p + "focus", -1.0f).apply();
                    }
                    else {
                        float d = cc.captureResultHasFocusDistance() ? cc.captureResultFocusDistance() : 0.0f;
                        preview().updateFocus("focus_mode_manual2", true, false);
                        preview().setFocusDistance(d, false, false);
                        prefs.edit().putFloat(p + "focus", d).apply();
                        param.index = nearestIndexFloat(focusValues, d);
                    }
                    break;
                case "ev":
                    if( auto ) {
                        cc.setExposureCompensation(0);
                        app().setExposureCompensationPref(0);
                        prefs.edit().putInt(p + "ev", 0).apply();
                        param.index = evValues.indexOf(0);
                    }
                    break;
            }
            // re-select so the dial shows the new index
            ui.proPanel.select(param.key);
        }
    };

    private int currentOrCapturedIso(CameraController cc) {
        if( cc.isManualISO() )
            return cc.getISO();
        if( cc.captureResultHasIso() )
            return cc.captureResultIso();
        return 400;
    }

    private long currentOrCapturedExposureTime(CameraController cc) {
        if( cc.isManualISO() )
            return cc.getExposureTime();
        if( cc.captureResultHasExposureTime() )
            return cc.captureResultExposureTime();
        return 1000000000L / 60;
    }

    private void setManualExposure(CameraController cc, int iso, long exposureTime) {
        cc.setExposureTime(exposureTime);
        if( !cc.isManualISO() )
            cc.setManualISO(true, iso);
        else
            cc.setISO(iso);
        app().setISOPref(String.valueOf(iso));
        app().setExposureTimePref(exposureTime);
        prefs.edit().putInt(proPrefix(mode) + "iso", iso).putLong(proPrefix(mode) + "shutter", exposureTime).apply();
    }

    // ------------------------------------------------------------------------------------------
    // lenses & zoom

    private void restoreSelection(CameraSelection selection) {
        if( selection == null || !preview().usingCamera2API() )
            return;
        CameraInventory inv = CameraInventory.get(main);
        CameraInventory.Lens lens = selection.physicalId != null ? inv.findAny(selection.physicalId) : null;
        if( lens == null && selection.cameraId != null )
            lens = inv.findAny(selection.cameraId);
        Object[] target = lens != null ? ocTarget(lens) : null;
        if( target == null ) {
            // If a Studio-only/hidden lens is unavailable to the classic engine,
            // keep the facing and select the closest accessible optical stop.
            for(CameraInventory.Lens candidate : inv.usableLenses(selection.front ?
                    CameraMetadata.LENS_FACING_FRONT : CameraMetadata.LENS_FACING_BACK, false)) {
                Object[] candidateTarget = ocTarget(candidate);
                if( candidateTarget != null && (target == null || candidate.zoomFactor <= selection.zoom + .02f) ) {
                    lens = candidate;
                    target = candidateTarget;
                }
            }
        }
        if( target != null ) {
            app().setCameraIdPref((Integer)target[0], (String)target[1]);
            pendingZoomRatio = selection.zoom / Math.max(.01f, lens.zoomFactor);
        }
    }

    private void buildLenses() {
        CameraController cc = preview().getCameraController();
        if( cc == null )
            return;
        CameraInventory inv = CameraInventory.get(main);
        int facing;
        switch( cc.getFacing() ) {
            case FACING_FRONT:
                facing = CameraMetadata.LENS_FACING_FRONT;
                break;
            case FACING_BACK:
                facing = CameraMetadata.LENS_FACING_BACK;
                break;
            default:
                facing = CameraMetadata.LENS_FACING_EXTERNAL;
                break;
        }
        int cameraId = app().getCameraIdPref();
        String physical = app().getCameraIdSPhysicalPref();
        List<CameraInventory.Lens> usable = inv.usableLenses(facing, false);
        // the currently open camera
        CameraInventory.Lens open = null;
        if( physical != null )
            open = inv.findAny(physical);
        if( open == null && cameraId >= 0 && cameraId < inv.topLevelIds.size() )
            open = inv.findAny(inv.topLevelIds.get(cameraId));

        logicalZoomMode = false;
        if( open != null && open.isLogical && physical == null && preview().getMinZoomRatio() < 0.95f ) {
            // logical camera that switches lenses itself (e.g. Pixel): use zoom ratios
            logicalZoomMode = true;
            List<Float> s = new ArrayList<>();
            s.add(roundStop(preview().getMinZoomRatio()));
            s.add(1.0f);
            for(CameraInventory.Lens l : usable) {
                if( l.zoomFactor > 1.5f && l.zoomFactor <= preview().getMaxZoomRatio() )
                    s.add(roundStop(l.zoomFactor));
            }
            stops = toArray(s);
            currentLens = open;
            lenses = usable;
            ui.zoomBar.setStops(stops);
            ui.zoomBar.setRange(preview().getMinZoomRatio(), Math.min(preview().getMaxZoomRatio(), 30.0f));
            ui.setZoomVisible(preview().supportsZoom());
            return;
        }
        lenses = new ArrayList<>();
        for(CameraInventory.Lens l : usable) {
            if( ocTarget(l) != null )
                lenses.add(l);
        }
        currentLens = open;
        if( currentLens == null || currentLens.isLogical ) {
            // logical camera used directly: treat as the 1x lens
            for(CameraInventory.Lens l : lenses) {
                if( Math.abs(l.zoomFactor - 1.0f) < 0.15f )
                    currentLens = l;
            }
        }
        List<Float> s = new ArrayList<>();
        for(CameraInventory.Lens l : lenses)
            s.add(roundStop(l.zoomFactor));
        if( s.isEmpty() )
            s.add(1.0f);
        stops = toArray(s);
        ui.zoomBar.setStops(stops);
        float lensZoom = currentLens != null ? currentLens.zoomFactor : 1.0f;
        float minZ = lenses.isEmpty() ? lensZoom : Math.min(lensZoom, lenses.get(0).zoomFactor);
        float maxZ = lensZoom * Math.max(1.0f, preview().getMaxZoomRatio());
        if( !lenses.isEmpty() )
            maxZ = Math.max(maxZ, lenses.get(lenses.size()-1).zoomFactor * 2.0f);
        ui.zoomBar.setRange(minZ, Math.min(maxZ, 30.0f));
        ui.setZoomVisible(lenses.size() > 1 || preview().supportsZoom());
    }

    private static float roundStop(float z) {
        return Math.round(z * 10.0f) / 10.0f;
    }

    private static float [] toArray(List<Float> list) {
        float [] a = new float[list.size()];
        for(int i=0;i<a.length;i++)
            a[i] = list.get(i);
        return a;
    }

    /** How to open this lens in Open Camera: [cameraIndex, physicalId or null], or null if not possible. */
    private Object [] ocTarget(CameraInventory.Lens lens) {
        CameraInventory inv = CameraInventory.get(main);
        for(CameraRoutes.Route route : CameraRoutes.forLens(lens, null)) {
            int idx = inv.topLevelIds.indexOf(route.cameraId);
            if( idx >= 0 && (route.physicalId == null || preview().usingCamera2API()) )
                return new Object[]{idx, route.physicalId};
        }
        return null;
    }

    private void switchToLens(CameraInventory.Lens lens, float digitalZoom) {
        Object [] target = ocTarget(lens);
        if( target == null )
            return;
        int idx = (Integer)target[0];
        String physical = (String)target[1];
        int curIdx = app().getCameraIdPref();
        String curPhysical = app().getCameraIdSPhysicalPref();
        boolean same = idx == curIdx && (physical == null ? curPhysical == null : physical.equals(curPhysical));
        if( same ) {
            setDigitalZoomRatio(digitalZoom);
            return;
        }
        if( preview().isOpeningCamera() || !preview().canSwitchCamera() )
            return;
        pendingZoomRatio = digitalZoom;
        ui.showToast(lens.longLabel(), 1000);
        main.userSwitchToCamera(idx, physical);
    }

    private void setDigitalZoomRatio(float ratio) {
        Preview preview = preview();
        if( !preview.supportsZoom() )
            return;
        int best = 0;
        float bestDiff = Float.MAX_VALUE;
        for(int i=0;i<=preview.getMaxZoom();i++) {
            float d = Math.abs(preview.getZoomRatio(i) - ratio);
            if( d < bestDiff ) {
                bestDiff = d;
                best = i;
            }
        }
        preview.zoomTo(best, false, false);
    }

    @Override
    public void onZoomStop(int index, float zoom) {
        if( logicalZoomMode ) {
            setDigitalZoomRatio(zoom);
            return;
        }
        if( index >= 0 && index < lenses.size() )
            switchToLens(lenses.get(index), 1.0f);
    }

    @Override
    public void onZoomDrag(float zoom) {
        if( logicalZoomMode ) {
            setDigitalZoomRatio(zoom);
            return;
        }
        if( currentLens == null )
            return;
        float ratio = zoom / currentLens.zoomFactor;
        if( ratio >= 1.0f )
            setDigitalZoomRatio(ratio);
    }

    @Override
    public void onZoomDragEnd(float zoom) {
        if( logicalZoomMode || currentLens == null || lenses.isEmpty() )
            return;
        // pick the longest lens whose native zoom is <= requested zoom
        CameraInventory.Lens target = lenses.get(0);
        for(CameraInventory.Lens l : lenses) {
            if( l.zoomFactor <= zoom + 0.01f )
                target = l;
        }
        float ratio = Math.max(1.0f, zoom / target.zoomFactor);
        switchToLens(target, ratio);
    }

    // ------------------------------------------------------------------------------------------
    // shutter etc.

    @SuppressLint("ClickableViewAccessibility")
    private void setupShutterTouch() {
        ui.shutter.setOnTouchListener((v, event) -> {
            if( event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL )
                main.takePhotoButtonLongClickCancelled();
            return false;
        });
    }

    private void updateShutterStyle() {
        Preview preview = preview();
        OpShutterButton.Style style;
        if( preview.isVideoRecording() ) {
            style = OpShutterButton.Style.RECORDING;
        }
        else if( preview.isVideo() ) {
            style = OpShutterButton.Style.VIDEO;
        }
        else if( preview.isOnTimer() ) {
            style = OpShutterButton.Style.PROGRESS;
        }
        else if( preview.isTakingPhoto() && (mode == CameraMode.NIGHT || app().getPhotoMode() == MyApplicationInterface.PhotoMode.HDR) ) {
            style = OpShutterButton.Style.BUSY;
        }
        else {
            style = OpShutterButton.Style.PHOTO;
        }
        ui.shutter.setStyle(style);
        if( style == OpShutterButton.Style.PROGRESS ) {
            long remaining = preview.getTimerEndTime() - System.currentTimeMillis();
            long total = Math.max(1, app().getTimerPref());
            ui.shutter.setProgress(1.0f - remaining / (float)total);
            ui.shutter.setCenterText(String.valueOf(Math.max(1, (remaining + 999) / 1000)));
        }
        else {
            ui.shutter.setCenterText(null);
        }
        boolean panoramaRecording = app().getGyroSensor().isRecording();
        ui.setBusy((preview.isTakingPhoto() && !preview.isVideo() && mode == CameraMode.NIGHT) || panoramaRecording);
    }

    @Override
    public void onShutterClick() {
        if( ui.moreSheet.isShowing() || !captureModeReady() )
            return;
        main.takePicture(false);
    }

    private boolean captureModeReady() {
        Preview preview = preview();
        if( preview.isOpeningCamera() || preview.getCameraController() == null )
            return false;
        if( preview.isVideo() != mode.isVideo || app().isVideoPref() != mode.isVideo ) {
            // Repair a stale engine before interpreting a photo shutter as record/stop.
            enterMode(mode, false);
            return false;
        }
        return true;
    }

    @Override
    public boolean onShutterLongClick() {
        if( !captureModeReady() )
            return true;
        return main.longClickedTakePhoto();
    }

    @Override
    public void onGalleryClick() {
        main.clickedGallery(null);
    }

    @Override
    public void onSwitchCameraClick() {
        if( preview().isVideoRecording() )
            return;
        ui.switchButton.animate().rotationBy(180).setDuration(300).start();
        main.clickedSwitchCamera(null);
    }

    @Override
    public void onPauseClick() {
        main.clickedPauseVideo(null);
    }

    @Override
    public void onSnapshotClick() {
        main.clickedTakePhotoVideoSnapshot(null);
    }

    @Override
    public void onMoreTile(String id) {
        if( OpCameraUi.TILE_SETTINGS.equals(id) ) {
            main.clickedSettings(null);
        }
        else if( OpCameraUi.TILE_DIAGNOSTICS.equals(id) ) {
            main.startActivity(new Intent(main, net.sourceforge.opencamera.studio.DiagnosticsActivity.class));
        }
    }

    // ------------------------------------------------------------------------------------------
    // touch: swipe to change mode, vertical drag after focus tap to change exposure

    /** @return true if the event was consumed. */
    boolean dispatchTouchEvent(MotionEvent ev) {
        if( ui == null || ui.moreSheet.isShowing() )
            return false;
        int action = ev.getActionMasked();
        float density = main.getResources().getDisplayMetrics().density;
        switch( action ) {
            case MotionEvent.ACTION_DOWN: {
                swipeDownX = ev.getX();
                swipeDownY = ev.getY();
                swipeConsumed = false;
                evDragging = false;
                // only track swipes that start over the preview area (not on top/bottom controls)
                int[] loc = new int[2];
                ui.bottomArea.getLocationOnScreen(loc);
                int bottomTop = loc[1];
                ui.topArea.getLocationOnScreen(loc);
                int topBottom = loc[1] + ui.topArea.getHeight();
                swipeTracking = ev.getRawY() > topBottom && ev.getRawY() < bottomTop;
                if( swipeTracking && ui.focusRing.isShowing() && preview().supportsExposures() && !isManualIso() ) {
                    evDragStartY = ev.getY();
                    evDragStartValue = preview().getCurrentExposure();
                }
                return false;
            }
            case MotionEvent.ACTION_MOVE: {
                if( !swipeTracking || swipeConsumed || ev.getPointerCount() > 1 )
                    return swipeConsumed;
                float dx = ev.getX() - swipeDownX;
                float dy = ev.getY() - swipeDownY;
                if( ui.focusRing.isShowing() && preview().supportsExposures() && !isManualIso() && Math.abs(dy) > 24 * density && Math.abs(dy) > 2 * Math.abs(dx) ) {
                    evDragging = true;
                }
                if( evDragging ) {
                    int range = preview().getMaximumExposure() - preview().getMinimumExposure();
                    float perPixel = range / (300.0f * density);
                    int value = Math.round(evDragStartValue - (ev.getY() - evDragStartY) * perPixel);
                    value = Math.max(preview().getMinimumExposure(), Math.min(preview().getMaximumExposure(), value));
                    CameraController cc = preview().getCameraController();
                    if( cc != null && value != preview().getCurrentExposure() ) {
                        cc.setExposureCompensation(value);
                        app().setExposureCompensationPref(value);
                    }
                    float norm = value >= 0 ? value / (float)Math.max(1, preview().getMaximumExposure()) : -value / (float)Math.min(-1, preview().getMinimumExposure());
                    ui.focusRing.setExposure(norm);
                    return false;
                }
                if( Math.abs(dx) > 90 * density && Math.abs(dx) > 2.2f * Math.abs(dy) ) {
                    swipeConsumed = true;
                    swipeMode(dx < 0 ? 1 : -1);
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean consumed = swipeConsumed;
                swipeTracking = false;
                swipeConsumed = false;
                if( action == MotionEvent.ACTION_UP && !consumed && !evDragging && ev.getPointerCount() == 1 ) {
                    float dist = (float)Math.hypot(ev.getX() - swipeDownX, ev.getY() - swipeDownY);
                    if( dist < ViewConfiguration.get(main).getScaledTouchSlop() * 2 && isOverPreview(ev) ) {
                        onFocusTap(ev.getX(), ev.getY());
                    }
                }
                evDragging = false;
                return consumed;
            }
        }
        return false;
    }

    private boolean isOverPreview(MotionEvent ev) {
        View pv = preview().getView();
        int [] loc = new int[2];
        pv.getLocationOnScreen(loc);
        float x = ev.getRawX(), y = ev.getRawY();
        if( x < loc[0] || x > loc[0] + pv.getWidth() || y < loc[1] || y > loc[1] + pv.getHeight() )
            return false;
        ui.bottomArea.getLocationOnScreen(loc);
        if( y > loc[1] + ui.bottomArea.getPaddingTop() + (ui.proPanel.getVisibility() == View.VISIBLE ? 0 : ui.zoomBar.getTop()) )
            return false;
        ui.topArea.getLocationOnScreen(loc);
        return !(y < loc[1] + ui.topArea.getHeight());
    }

    private void swipeMode(int direction) {
        if( preview().isVideoRecording() || preview().isTakingPhotoOrOnTimer() )
            return;
        int idx = CameraMode.carouselIndexOf(mode);
        if( idx < 0 ) {
            // a "more" mode: swiping right goes back to the last carousel entry
            idx = CameraMode.CAROUSEL.length;
        }
        int next = idx + direction;
        if( next < 0 || next >= CameraMode.CAROUSEL.length )
            return;
        onModeSelected(CameraMode.CAROUSEL[next]);
    }
}
