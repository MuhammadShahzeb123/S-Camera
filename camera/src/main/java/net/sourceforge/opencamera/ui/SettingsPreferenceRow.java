package net.sourceforge.opencamera.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;

import net.sourceforge.opencamera.op.ui.OpTheme;

/** Tints the framework-created preference widgets without replacing their binding or listeners. */
public class SettingsPreferenceRow extends LinearLayout {
    private static final int[][] CONTROL_STATES = {
            {-android.R.attr.state_enabled},
            {android.R.attr.state_checked},
            {}
    };
    private static final ColorStateList BUTTON_COLORS = new ColorStateList(CONTROL_STATES,
            new int[]{0xFF555963, OpTheme.ACCENT, 0xFFA6ABB5});
    private static final ColorStateList THUMB_COLORS = new ColorStateList(CONTROL_STATES,
            new int[]{0xFF62666F, OpTheme.ACCENT, 0xFFA6ABB5});
    private static final ColorStateList TRACK_COLORS = new ColorStateList(CONTROL_STATES,
            new int[]{0xFF34363D, 0x66EB0029, 0xFF484B53});

    public SettingsPreferenceRow(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Preference inflates its widget after this row, so wait until the entire row is attached.
        tintWidgets(findViewById(android.R.id.widget_frame));
    }

    private static void tintWidgets(View view) {
        if( view instanceof Switch ) {
            Switch toggle = (Switch)view;
            toggle.setThumbTintList(THUMB_COLORS);
            toggle.setTrackTintList(TRACK_COLORS);
        }
        else if( view instanceof CompoundButton ) {
            ((CompoundButton)view).setButtonTintList(BUTTON_COLORS);
        }
        if( view instanceof ViewGroup ) {
            ViewGroup group = (ViewGroup)view;
            for(int i = 0; i < group.getChildCount(); i++) {
                tintWidgets(group.getChildAt(i));
            }
        }
    }
}
