package com.shahzeb.scamera.op.ui;

import android.content.Context;

/** Shared colours and helpers for the OnePlus style camera UI. */
public final class OpTheme {
    private OpTheme() {
    }

    /** OnePlus red accent. */
    public static final int ACCENT = 0xFFEB0029;
    /** Highlight colour used for active values (pro mode dials, active toggles). */
    public static final int HIGHLIGHT = 0xFFFFC52E;
    public static final int RECORD_RED = 0xFFFF3B30;
    public static final int SCRIM = 0x8C000000;
    public static final int PANEL = 0xB3000000;
    public static final int CHIP_BG = 0x59000000;
    public static final int TEXT_DIM = 0x99FFFFFF;

    public static int dp(Context context, float dp) {
        return (int)(dp * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static float dpf(Context context, float dp) {
        return dp * context.getResources().getDisplayMetrics().density;
    }
}
