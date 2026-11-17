package com.shahzeb.scamera.op.ui;

import android.content.Context;
import android.view.OrientationEventListener;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Keeps the UI locked in portrait (like the OnePlus camera) while rotating individual icons so
 *  they stay upright when the phone is turned to landscape.
 */
public class OpOrientationHelper {
    public interface Listener {
        void onDeviceOrientationChanged(int degrees);
    }

    private final OrientationEventListener orientationEventListener;
    private final List<View> views = new ArrayList<>();
    private int current = 0; // 0, 90, 180, 270 - clockwise rotation of device from portrait
    private Listener listener;

    public OpOrientationHelper(Context context) {
        orientationEventListener = new OrientationEventListener(context) {
            @Override
            public void onOrientationChanged(int orientation) {
                if( orientation == ORIENTATION_UNKNOWN )
                    return;
                // hysteresis so the UI doesn't flicker between orientations at 45 degrees
                int diff = Math.abs(orientation - current);
                diff = Math.min(diff, 360 - diff);
                if( diff < 55 )
                    return;
                int snapped = ((orientation + 45) / 90 * 90) % 360;
                if( snapped != current ) {
                    current = snapped;
                    applyRotation(true);
                    if( listener != null )
                        listener.onDeviceOrientationChanged(current);
                }
            }
        };
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void enable() {
        if( orientationEventListener.canDetectOrientation() )
            orientationEventListener.enable();
    }

    public void disable() {
        orientationEventListener.disable();
    }

    /** Device rotation in degrees clockwise from natural portrait (0, 90, 180, 270). */
    public int getDeviceOrientation() {
        return current;
    }

    public void clearViews() {
        views.clear();
    }

    public void addView(View view) {
        if( view != null && !views.contains(view) ) {
            views.add(view);
            view.setRotation(uiRotation());
        }
    }

    public void addViews(List<View> list) {
        for(View v : list)
            addView(v);
    }

    /** Rotation to apply to views so they appear upright (counter rotation). */
    private float uiRotation() {
        int r = (360 - current) % 360;
        return r > 180 ? r - 360 : r;
    }

    private void applyRotation(boolean animate) {
        float target = uiRotation();
        for(View v : views) {
            float from = v.getRotation();
            // rotate the short way
            float delta = ((target - from) % 360 + 540) % 360 - 180;
            if( animate )
                v.animate().rotation(from + delta).setDuration(220).start();
            else
                v.setRotation(target);
        }
    }
}
