package com.shahzeb.scamera.op;

import android.app.Activity;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.shahzeb.scamera.R;
import com.shahzeb.scamera.op.ui.OpChipStrip;
import com.shahzeb.scamera.op.ui.OpFocusRing;
import com.shahzeb.scamera.op.ui.OpGalleryButton;
import com.shahzeb.scamera.op.ui.OpHistogramView;
import com.shahzeb.scamera.op.ui.OpModeCarousel;
import com.shahzeb.scamera.op.ui.OpMoreSheet;
import com.shahzeb.scamera.op.ui.OpOrientationHelper;
import com.shahzeb.scamera.op.ui.OpProPanel;
import com.shahzeb.scamera.op.ui.OpShutterButton;
import com.shahzeb.scamera.op.ui.OpTopBar;
import com.shahzeb.scamera.op.ui.OpZoomBar;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Controller for the OnePlus style camera overlay (op_camera_ui.xml). Shared between the classic
 *  Open Camera engine (MainActivity) and the Studio engine (StudioActivity).
 */
public class OpCameraUi {
    public interface Host {
        void onModeSelected(CameraMode mode);
        void onShutterClick();
        boolean onShutterLongClick();
        void onGalleryClick();
        void onSwitchCameraClick();
        void onPauseClick();
        void onSnapshotClick();
        void onMoreTile(String id);
        void onZoomStop(int index, float zoom);
        void onZoomDrag(float zoom);
        void onZoomDragEnd(float zoom);
    }

    public static final String TILE_DIAGNOSTICS = "tile_diagnostics";
    public static final String TILE_SETTINGS = "tile_settings";

    private final Activity activity;
    private final Host host;
    public final View root;
    public final View topArea;
    public final View bottomArea;
    public final OpTopBar topBar;
    public final OpModeCarousel carousel;
    public final OpShutterButton shutter;
    public final OpZoomBar zoomBar;
    public final OpGalleryButton gallery;
    public final ImageButton switchButton;
    public final ImageButton pauseButton;
    public final ImageButton snapshotButton;
    public final OpProPanel proPanel;
    public final OpChipStrip chips;
    public final OpFocusRing focusRing;
    public final OpMoreSheet moreSheet;
    public final OpHistogramView histogram;
    public final TextView recBadge;
    public final TextView toast;
    public final TextView infoText;
    public final OpOrientationHelper orientation;

    private CameraMode mode = CameraMode.PHOTO;
    private boolean recording;
    private boolean busy;
    private final Runnable hideToast;
    private final int moreIndex;

    public OpCameraUi(Activity activity, ViewGroup container, Host host) {
        this.activity = activity;
        this.host = host;
        root = LayoutInflater.from(activity).inflate(R.layout.op_camera_ui, container, false);
        container.addView(root);
        topArea = root.findViewById(R.id.op_top_area);
        bottomArea = root.findViewById(R.id.op_bottom_area);
        topBar = root.findViewById(R.id.op_top_bar);
        carousel = root.findViewById(R.id.op_mode_carousel);
        shutter = root.findViewById(R.id.op_shutter);
        zoomBar = root.findViewById(R.id.op_zoom_bar);
        gallery = root.findViewById(R.id.op_gallery);
        switchButton = root.findViewById(R.id.op_switch);
        pauseButton = root.findViewById(R.id.op_pause);
        snapshotButton = root.findViewById(R.id.op_snapshot);
        proPanel = root.findViewById(R.id.op_pro_panel);
        chips = root.findViewById(R.id.op_chips);
        focusRing = root.findViewById(R.id.op_focus_ring);
        moreSheet = root.findViewById(R.id.op_more_sheet);
        histogram = root.findViewById(R.id.op_histogram);
        recBadge = root.findViewById(R.id.op_rec_badge);
        toast = root.findViewById(R.id.op_toast);
        infoText = root.findViewById(R.id.op_info_text);
        orientation = new OpOrientationHelper(activity);
        hideToast = () -> toast.animate().alpha(0.0f).setDuration(200).withEndAction(() -> toast.setVisibility(View.GONE)).start();

        List<String> labels = new ArrayList<>();
        for(CameraMode m : CameraMode.CAROUSEL)
            labels.add(m.carouselLabel);
        labels.add("MORE");
        moreIndex = labels.size() - 1;
        carousel.setItems(labels, CameraMode.carouselIndexOf(CameraMode.PHOTO));
        carousel.setListener(new OpModeCarousel.Listener() {
            @Override
            public void onItemSelected(int index) {
                if( index == moreIndex ) {
                    if( isMoreMode(mode) ) {
                        // already in a "more" mode, the slot shows that mode
                        return;
                    }
                    showMoreSheet();
                }
                else {
                    host.onModeSelected(CameraMode.CAROUSEL[index]);
                }
            }

            @Override
            public void onSelectedItemTapped(int index) {
                if( index == moreIndex )
                    showMoreSheet();
            }
        });

        shutter.setOnClickListener(v -> host.onShutterClick());
        shutter.setOnLongClickListener(v -> host.onShutterLongClick());
        gallery.setOnClickListener(v -> host.onGalleryClick());
        switchButton.setOnClickListener(v -> host.onSwitchCameraClick());
        pauseButton.setOnClickListener(v -> host.onPauseClick());
        snapshotButton.setOnClickListener(v -> host.onSnapshotClick());
        zoomBar.setListener(new OpZoomBar.Listener() {
            @Override
            public void onStopTapped(int index, float zoom) {
                host.onZoomStop(index, zoom);
            }

            @Override
            public void onZoomDragged(float zoom) {
                host.onZoomDrag(zoom);
            }

            @Override
            public void onZoomDragEnded(float zoom) {
                host.onZoomDragEnd(zoom);
            }
        });
        moreSheet.setListener(new OpMoreSheet.Listener() {
            @Override
            public void onTileChosen(String id) {
                moreSheet.dismiss();
                CameraMode chosen = CameraMode.fromName(id, null);
                if( chosen != null ) {
                    host.onModeSelected(chosen);
                }
                else {
                    host.onMoreTile(id);
                }
            }

            @Override
            public void onSheetDismissed() {
                // snap carousel back to the active mode
                setMode(mode);
            }
        });

        refreshRotatableViews();
    }

    public static boolean isMoreMode(CameraMode m) {
        return CameraMode.carouselIndexOf(m) < 0;
    }

    public CameraMode getMode() {
        return mode;
    }

    /** Updates the carousel to reflect the active mode (doesn't notify the host). */
    public void setMode(CameraMode new_mode) {
        this.mode = new_mode;
        int index = CameraMode.carouselIndexOf(new_mode);
        if( index < 0 ) {
            carousel.setItemLabel(moreIndex, new_mode.carouselLabel);
            index = moreIndex;
        }
        else {
            carousel.setItemLabel(moreIndex, "MORE");
        }
        carousel.setSelected(index, true);
    }

    public void showMoreSheet() {
        moreSheet.clearTiles();
        for(CameraMode m : CameraMode.MORE) {
            moreSheet.addTile(m.name(), m.iconRes, m.title, m == mode);
        }
        moreSheet.addTile(TILE_DIAGNOSTICS, R.drawable.op_ic_bug_report, "Camera info", false);
        moreSheet.addTile(TILE_SETTINGS, R.drawable.op_ic_settings, "Settings", false);
        moreSheet.show();
    }

    public boolean handleBackPressed() {
        if( moreSheet.isShowing() ) {
            moreSheet.dismiss();
            return true;
        }
        if( proPanel.getVisibility() == View.VISIBLE && proPanel.isDialOpen() ) {
            proPanel.select(null);
            return true;
        }
        return false;
    }

    /** Recording UI: hides mode carousel / gallery / switch, shows pause + snapshot + timer. */
    public void setRecording(boolean recording, boolean canPause, boolean canSnapshot) {
        this.recording = recording;
        carousel.setVisibility(recording ? View.INVISIBLE : View.VISIBLE);
        gallery.setVisibility(recording ? View.GONE : View.VISIBLE);
        switchButton.setVisibility(recording ? View.GONE : View.VISIBLE);
        pauseButton.setVisibility(recording && canPause ? View.VISIBLE : View.GONE);
        snapshotButton.setVisibility(recording && canSnapshot ? View.VISIBLE : View.GONE);
        recBadge.setVisibility(recording ? View.VISIBLE : View.GONE);
        if( recording )
            setRecordingTime(0, false);
        pauseButton.setImageResource(R.drawable.op_ic_pause);
    }

    public boolean isRecording() {
        return recording;
    }

    public void setPaused(boolean paused) {
        pauseButton.setImageResource(paused ? R.drawable.op_ic_fiber_manual_record : R.drawable.op_ic_pause);
    }

    public void setRecordingTime(long ms, boolean paused) {
        long s = ms / 1000;
        String text;
        if( s >= 3600 )
            text = String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
        else
            text = String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
        if( paused )
            text = "II  " + text;
        recBadge.setText(text);
    }

    /** Blocks mode switching etc. while busy (e.g. long exposure, night capture). */
    public void setBusy(boolean busy) {
        this.busy = busy;
        carousel.setTouchEnabled(!busy);
        switchButton.setEnabled(!busy);
        switchButton.setAlpha(busy ? 0.4f : 1.0f);
        gallery.setEnabled(!busy);
        topBar.setEnabled(!busy);
        topBar.setAlpha(busy ? 0.4f : 1.0f);
    }

    public boolean isBusy() {
        return busy;
    }

    public void showToast(String text) {
        showToast(text, 1600);
    }

    public void showToast(String text, long durationMs) {
        toast.removeCallbacks(hideToast);
        toast.setText(text);
        toast.setAlpha(0.0f);
        toast.setVisibility(View.VISIBLE);
        toast.animate().alpha(1.0f).setDuration(150).start();
        toast.postDelayed(hideToast, durationMs);
    }

    public void setInfoText(String text) {
        if( text == null || text.isEmpty() ) {
            infoText.setVisibility(View.GONE);
        }
        else {
            infoText.setText(text);
            infoText.setVisibility(View.VISIBLE);
        }
    }

    public void setGalleryBitmap(Bitmap bitmap) {
        gallery.setBitmap(bitmap);
    }

    /** Top inset: status bar / cutout. Bottom inset: navigation bar. */
    public void setInsets(int top, int bottom) {
        topArea.setPadding(0, top, 0, 0);
        LinearLayout bottomLayout = (LinearLayout)bottomArea;
        bottomLayout.setPadding(0, bottomLayout.getPaddingTop(), 0, bottom);
    }

    public void setZoomVisible(boolean visible) {
        zoomBar.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }

    public void setChips(List<OpChipStrip.Chip> list, OpChipStrip.Listener listener) {
        if( list == null || list.isEmpty() ) {
            chips.setVisibility(View.GONE);
            chips.setChips(new ArrayList<>());
        }
        else {
            chips.setListener(listener);
            chips.setChips(list);
            chips.setVisibility(View.VISIBLE);
        }
    }

    public void setProPanelVisible(boolean visible) {
        proPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** Registers the top bar icons for rotation with the device orientation; call after building the bar. */
    public void refreshRotatableViews() {
        orientation.clearViews();
        orientation.addView(gallery);
        orientation.addView(switchButton);
        orientation.addView(pauseButton);
        orientation.addView(snapshotButton);
        orientation.addViews(topBar.getRotatableViews());
    }

    public void onResume() {
        orientation.enable();
    }

    public void onPause() {
        orientation.disable();
    }
}
