package com.shahzeb.scamera.studio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.shahzeb.scamera.op.CameraMode;
import com.shahzeb.scamera.op.OpCameraUi;
import com.shahzeb.scamera.op.OpFormat;
import com.shahzeb.scamera.op.ui.OpProPanel;
import com.shahzeb.scamera.op.ui.OpChipStrip;
import com.shahzeb.scamera.op.ui.OpTheme;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Small parameter row for Pro Video; the preview and record button remain accessible. */
final class VideoControls {
    interface Host {
        OpticalVideoEngine engine();
        void onGrade(ColorGrade grade);
    }
    private final Activity activity;
    private final OpCameraUi ui;
    private final Host host;
    private final SharedPreferences preferences;
    private final List<Integer> isoValues = new ArrayList<>();
    private final List<Long> shutterValues = new ArrayList<>();
    private OpticalVideoEngine.State state;
    private String capabilities = "";
    private CameraMode mode;
    private ColorGrade grade = ColorGrade.NEUTRAL;
    private AlertDialog colorDialog;
    private boolean restorePending;

    VideoControls(Activity activity, OpCameraUi ui, SharedPreferences preferences, Host host) {
        this.activity = activity; this.ui = ui; this.preferences = preferences; this.host = host;
        ui.chips.setListener(chip -> {
            switch(chip.id) {
                case "natural": applyGrade(ColorGrade.NEUTRAL); break;
                case "cinema": applyGrade(ColorGrade.CINEMA); break;
                case "soft": applyGrade(ColorGrade.SOFT); break;
                case "mono": applyGrade(ColorGrade.MONO); break;
                default: showColor(); break;
            }
        });
        ui.proPanel.setListener(new OpProPanel.Listener() {
            @Override public void onProValueChanged(OpProPanel.Param p, int index) { change(p, index); }
            @Override public void onProAutoChanged(OpProPanel.Param p, boolean auto) {
                OpticalVideoEngine engine = host.engine();
                if( engine == null || state == null ) return;
                if( !auto ) { change(p, p.index); return; }
                switch(p.key) {
                    case "iso": case "shutter": engine.setAutoExposure(); break;
                    case "wb": engine.setWhiteBalance(0); break;
                    case "focus": engine.setAutoFocus(); break;
                    case "ev": engine.setExposureCompensation(0); break;
                }
            }
        });
    }

    void setMode(CameraMode next) {
        remember();
        mode = next;
        restorePending = next != CameraMode.VIDEO && preferences.contains(captureKey() + "manual");
        ui.proPanel.select(null);
        ui.proPanel.setVisibility(next == CameraMode.PRO_VIDEO ? View.VISIBLE : View.GONE);
        ui.chips.setVisibility(next == CameraMode.FILM ? View.VISIBLE : View.GONE);
        ui.chips.setChips(Arrays.asList(new OpChipStrip.Chip("natural", "Natural", "look", false),
                new OpChipStrip.Chip("cinema", "Cinema", "look", false), new OpChipStrip.Chip("soft", "Soft", "look", false),
                new OpChipStrip.Chip("mono", "B&W", "look", false), new OpChipStrip.Chip("adjust", "Adjust", null, false)));
        ColorGrade fallback = next == CameraMode.FILM ? ColorGrade.CINEMA : ColorGrade.NEUTRAL;
        String key = "s_grade_" + next.name() + "_";
        grade = new ColorGrade(preferences.getFloat(key + "exposure", fallback.exposureEv),
                preferences.getFloat(key + "contrast", fallback.contrast), preferences.getFloat(key + "saturation", fallback.saturation),
                preferences.getFloat(key + "warmth", fallback.warmth), preferences.getFloat(key + "tint", fallback.tint));
        host.onGrade(grade);
        refreshLook();
    }

    void toggle() {
        boolean visible = ui.proPanel.getVisibility() == View.VISIBLE;
        ui.proPanel.select(null);
        ui.proPanel.setVisibility(visible ? View.GONE : View.VISIBLE);
    }

    void refresh(OpticalVideoEngine.State value) {
        state = value;
        if( restorePending && value.ready && !value.busy ) {
            restorePending = false;
            restore();
        }
        String signature = value.cameraId + ":" + value.physicalId + ":" + value.fps + ":" + value.minIso + ":" + value.maxIso
                + ":" + value.minExposureTimeNs + ":" + value.maxExposureTimeNs + ":" + value.maxFocusDiopters
                + ":" + value.supportsManualExposure + ":" + value.supportsManualFocus + ":" + value.supportsManualWhiteBalance
                + ":" + value.minEv + ":" + value.maxEv;
        if( !signature.equals(capabilities) ) { capabilities = signature; build(); }
        OpProPanel.Param iso = ui.proPanel.getParam("iso"), shutter = ui.proPanel.getParam("shutter");
        iso.isAuto = shutter.isAuto = !value.manualExposure;
        iso.autoText = value.supportsManualExposure ? "A " + value.iso : "—";
        shutter.autoText = value.supportsManualExposure ? "A " + OpFormat.shutter(value.exposureTimeNs) : "—";
        iso.index = nearest(isoValues, value.iso);
        shutter.index = nearest(shutterValues, value.exposureTimeNs);
        iso.manualText = Integer.toString(value.iso);
        shutter.manualText = OpFormat.shutter(value.exposureTimeNs);
        OpProPanel.Param wb = ui.proPanel.getParam("wb");
        wb.isAuto = value.whiteBalanceKelvin == 0;
        wb.index = Math.max(0, Math.min(80, Math.round((value.whiteBalanceKelvin - 2000) / 100f)));
        OpProPanel.Param focus = ui.proPanel.getParam("focus");
        focus.isAuto = !value.manualFocus;
        focus.autoText = "AF";
        focus.index = value.maxFocusDiopters <= 0 ? 0 : Math.round(value.focusDiopters / value.maxFocusDiopters * 100);
        focus.manualText = value.focusDiopters == 0 ? "∞" : String.format(Locale.US, "%.2fm", 1 / value.focusDiopters);
        OpProPanel.Param ev = ui.proPanel.getParam("ev");
        ev.enabled = !value.manualExposure && value.maxEv > value.minEv;
        ev.isAuto = Math.abs(value.exposureCompensation) < .01f;
        ev.index = Math.max(0, Math.min(ev.values.size() - 1, Math.round((value.exposureCompensation - value.minEv) * 6)));
        ev.manualText = String.format(Locale.US, "%+.1f", value.exposureCompensation);
        ui.proPanel.refresh();
    }

    private void build() {
        List<OpProPanel.Param> params = new ArrayList<>();
        OpProPanel.Param iso = new OpProPanel.Param("iso", "ISO");
        iso.enabled = state.supportsManualExposure;
        isoValues.clear();
        if( iso.enabled ) {
            addIso(state.minIso);
            for(int n : new int[]{50,64,80,100,125,160,200,250,320,400,500,640,800,1000,1250,1600,2000,2500,3200,4000,5000,6400,8000,10000,12800})
                if( n > state.minIso && n < state.maxIso ) addIso(n);
            if( state.maxIso > state.minIso ) addIso(state.maxIso);
        }
        for(int n : isoValues) iso.values.add(Integer.toString(n));
        params.add(iso);
        OpProPanel.Param shutter = new OpProPanel.Param("shutter", "SHUTTER");
        shutter.enabled = state.supportsManualExposure;
        shutterValues.clear();
        if( shutter.enabled ) {
            shutterValues.add(state.minExposureTimeNs);
            for(int denominator : new int[]{32000,16000,8000,4000,2000,1000,500,250,125,120,100,60,50,48,30,25,24,15}) {
                long ns = 1_000_000_000L / denominator;
                if( ns > state.minExposureTimeNs && ns < state.maxExposureTimeNs ) shutterValues.add(ns);
            }
            if( state.maxExposureTimeNs > state.minExposureTimeNs ) shutterValues.add(state.maxExposureTimeNs);
        }
        for(long n : shutterValues) shutter.values.add(OpFormat.shutter(n));
        params.add(shutter);
        OpProPanel.Param wb = new OpProPanel.Param("wb", "WB");
        wb.enabled = state.supportsManualWhiteBalance;
        for(int n = 2000; n <= 10000; n += 100) wb.values.add(n + "K");
        wb.majorEvery = 10; params.add(wb);
        OpProPanel.Param focus = new OpProPanel.Param("focus", "FOCUS");
        focus.enabled = state.supportsManualFocus;
        for(int n = 0; n <= 100; n++) focus.values.add(n == 0 ? "∞" : String.format(Locale.US, "%.2fm", 100f / (n * Math.max(.01f, state.maxFocusDiopters))));
        focus.majorEvery = 10; params.add(focus);
        OpProPanel.Param ev = new OpProPanel.Param("ev", "EV");
        for(float n = state.minEv; n <= state.maxEv + .01f; n += 1f / 6f) ev.values.add(String.format(Locale.US, "%+.1f", n));
        ev.autoText = "0.0"; ev.majorEvery = 6; params.add(ev);
        ui.proPanel.setParams(params);
    }

    private void addIso(int value) { isoValues.add(value); }
    private static int nearest(List<? extends Number> values, long wanted) {
        int best = 0; double distance = Double.MAX_VALUE;
        for(int i = 0; i < values.size(); i++) {
            double next = Math.abs(values.get(i).doubleValue() - wanted);
            if( next < distance ) { best = i; distance = next; }
        }
        return best;
    }

    private void change(OpProPanel.Param p, int index) {
        OpticalVideoEngine engine = host.engine();
        if( engine == null || state == null || !p.enabled ) return;
        switch(p.key) {
            case "iso":
                if( !isoValues.isEmpty() ) engine.setManualExposure(isoValues.get(Math.min(index, isoValues.size() - 1)), 0);
                break;
            case "shutter":
                if( !shutterValues.isEmpty() ) engine.setManualExposure(0, shutterValues.get(Math.min(index, shutterValues.size() - 1)));
                break;
            case "wb": engine.setWhiteBalance(2000 + index * 100); break;
            case "focus": engine.setManualFocus(state.maxFocusDiopters * index / 100f); break;
            case "ev": engine.setExposureCompensation(state.minEv + index / 6f); break;
        }
    }

    ColorGrade grade() { return grade; }
    void close() {
        remember();
        restorePending = mode != CameraMode.VIDEO && preferences.contains(captureKey() + "manual");
        if( colorDialog != null ) colorDialog.dismiss();
    }

    private String captureKey() { return "s_capture_" + mode.name() + "_"; }

    private void remember() {
        if( mode == null || mode == CameraMode.VIDEO || state == null || !state.ready || state.busy ) return;
        String key = captureKey();
        preferences.edit().putBoolean(key + "manual", state.manualExposure).putInt(key + "iso", state.iso)
                .putLong(key + "shutter", state.exposureTimeNs).putBoolean(key + "manualFocus", state.manualFocus)
                .putFloat(key + "focus", state.focusDiopters).putInt(key + "wb", state.whiteBalanceKelvin)
                .putFloat(key + "ev", state.exposureCompensation).apply();
    }

    private void restore() {
        OpticalVideoEngine engine = host.engine();
        if( engine == null ) return;
        String key = captureKey();
        if( preferences.getBoolean(key + "manual", false) && state.supportsManualExposure )
            engine.setManualExposure(preferences.getInt(key + "iso", 100), preferences.getLong(key + "shutter", 0));
        else {
            engine.setAutoExposure();
            engine.setExposureCompensation(preferences.getFloat(key + "ev", 0));
        }
        if( preferences.getBoolean(key + "manualFocus", false) && state.supportsManualFocus )
            engine.setManualFocus(preferences.getFloat(key + "focus", 0));
        else engine.setAutoFocus();
        int kelvin = preferences.getInt(key + "wb", 0);
        engine.setWhiteBalance(state.supportsManualWhiteBalance ? kelvin : 0);
    }

    private void applyGrade(ColorGrade value) {
        grade = value;
        String key = "s_grade_" + mode.name() + "_";
        preferences.edit().putFloat(key + "exposure", value.exposureEv).putFloat(key + "contrast", value.contrast)
                .putFloat(key + "saturation", value.saturation).putFloat(key + "warmth", value.warmth).putFloat(key + "tint", value.tint).apply();
        host.onGrade(value);
        refreshLook();
    }

    private void refreshLook() {
        ColorGrade[] looks = {ColorGrade.NEUTRAL, ColorGrade.CINEMA, ColorGrade.SOFT, ColorGrade.MONO};
        String[] ids = {"natural", "cinema", "soft", "mono"};
        for(int i = 0; i < looks.length; i++) {
            ColorGrade look = looks[i];
            ui.chips.setChipSelected(ids[i], grade.exposureEv == look.exposureEv && grade.contrast == look.contrast
                    && grade.saturation == look.saturation && grade.warmth == look.warmth && grade.tint == look.tint);
        }
    }

    void showColor() {
        if( colorDialog != null && colorDialog.isShowing() ) return;
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = OpTheme.dp(activity, 18); content.setPadding(padding, padding, padding, padding);
        TextView explanation = new TextView(activity);
        explanation.setText("Applied to your preview and saved video.");
        content.addView(explanation);
        LinearLayout looks = new LinearLayout(activity);
        looks.setOrientation(LinearLayout.HORIZONTAL);
        content.addView(looks);
        for(String name : Arrays.asList("Natural", "Cinema", "Soft", "B&W")) {
            TextView button = new TextView(activity); button.setText(name); button.setTextColor(Color.WHITE);
            button.setGravity(android.view.Gravity.CENTER); button.setPadding(4, padding, 4, padding);
            button.setContentDescription(name + " video look");
            looks.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
            button.setOnClickListener(v -> {
                applyGrade(name.equals("Cinema") ? ColorGrade.CINEMA : name.equals("Soft") ? ColorGrade.SOFT : name.equals("B&W") ? ColorGrade.MONO : ColorGrade.NEUTRAL);
                colorDialog.dismiss(); showColor();
            });
        }
        float[] values = {grade.exposureEv, grade.contrast, grade.saturation, grade.warmth, grade.tint};
        String[] names = {"Brightness (stops)", "Contrast", "Saturation", "Warmth", "Tint"};
        float[] low = {-3, 0, 0, -1, -1}, high = {3, 2, 2, 1, 1};
        for(int i = 0; i < values.length; i++) {
            final int index = i;
            TextView label = new TextView(activity);
            label.setText(String.format(Locale.US, "%s  %.2f", names[i], values[i])); content.addView(label);
            SeekBar slider = new SeekBar(activity); slider.setMax(200);
            slider.setProgress(Math.round((values[i] - low[i]) / (high[i] - low[i]) * 200));
            slider.setContentDescription(names[i]); content.addView(slider);
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    if( !fromUser ) return;
                    values[index] = low[index] + progress / 200f * (high[index] - low[index]);
                    label.setText(String.format(Locale.US, "%s  %.2f", names[index], values[index]));
                    applyGrade(new ColorGrade(values[0], values[1], values[2], values[3], values[4]));
                }
                @Override public void onStartTrackingTouch(SeekBar bar) { }
                @Override public void onStopTrackingTouch(SeekBar bar) { }
            });
        }
        ScrollView scroll = new ScrollView(activity); scroll.addView(content);
        colorDialog = new AlertDialog.Builder(activity).setTitle("Video color").setView(scroll)
                .setNeutralButton("Reset", (dialog, which) -> { applyGrade(ColorGrade.NEUTRAL); showColorAfterDismiss(); })
                .setPositiveButton("Done", null).create();
        colorDialog.show();
    }

    private void showColorAfterDismiss() { ui.root.post(this::showColor); }
}
