package com.shahzeb.scamera.op.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** "More" modes sheet: grid of extra modes (Pro Video, Film, Dual-View, Slow-mo, ...). */
public class OpMoreSheet extends FrameLayout {
    public interface Listener {
        void onTileChosen(String id);
        void onSheetDismissed();
    }

    private final LinearLayout panel;
    private final GridLayout grid;
    private Listener listener;
    private boolean showing;

    public OpMoreSheet(Context context) {
        this(context, null);
    }

    public OpMoreSheet(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBackgroundColor(0x99000000);
        setClickable(true);
        setOnClickListener(v -> dismiss());
        setVisibility(View.GONE);

        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClickable(true); // don't dismiss when tapping the panel itself
        GradientDrawable bg = new GradientDrawable();
        float r = OpTheme.dpf(context, 22);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bg.setColor(0xF2141414);
        panel.setBackground(bg);
        int pad = OpTheme.dp(context, 16);
        panel.setPadding(pad, pad, pad, OpTheme.dp(context, 28));

        View handle = new View(context);
        GradientDrawable hb = new GradientDrawable();
        hb.setCornerRadius(OpTheme.dpf(context, 2));
        hb.setColor(0x66FFFFFF);
        handle.setBackground(hb);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(OpTheme.dp(context, 36), OpTheme.dp(context, 4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = OpTheme.dp(context, 14);
        panel.addView(handle, hlp);

        TextView title = new TextView(context);
        title.setText("More modes");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setPadding(OpTheme.dp(context, 6), 0, 0, OpTheme.dp(context, 10));
        panel.addView(title);

        grid = new GridLayout(context);
        grid.setColumnCount(4);
        panel.addView(grid, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LayoutParams plp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        addView(panel, plp);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void clearTiles() {
        grid.removeAllViews();
    }

    public void addTile(final String id, int iconRes, String label, boolean selected) {
        Context context = getContext();
        LinearLayout tile = new LinearLayout(context);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(0, OpTheme.dp(context, 8), 0, OpTheme.dp(context, 8));
        FrameLayout iconFrame = new FrameLayout(context);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(selected ? OpTheme.HIGHLIGHT : 0xFF2A2A2A);
        iconFrame.setBackground(circle);
        ImageView icon = new ImageView(context);
        icon.setImageResource(iconRes);
        icon.setColorFilter(selected ? Color.BLACK : Color.WHITE);
        int is = OpTheme.dp(context, 26);
        iconFrame.addView(icon, new LayoutParams(is, is, Gravity.CENTER));
        int cs = OpTheme.dp(context, 54);
        tile.addView(iconFrame, new LinearLayout.LayoutParams(cs, cs));
        TextView tv = new TextView(context);
        tv.setText(label);
        tv.setTextColor(selected ? OpTheme.HIGHLIGHT : Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setGravity(Gravity.CENTER);
        tv.setMaxLines(2);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = OpTheme.dp(context, 6);
        tile.addView(tv, tlp);
        tile.setOnClickListener(v -> {
            if( listener != null )
                listener.onTileChosen(id);
        });
        GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
        glp.width = 0;
        glp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1.0f);
        grid.addView(tile, glp);
    }

    public boolean isShowing() {
        return showing;
    }

    public void show() {
        if( showing )
            return;
        showing = true;
        setVisibility(View.VISIBLE);
        setAlpha(0.0f);
        animate().alpha(1.0f).setDuration(160).start();
        panel.setTranslationY(OpTheme.dp(getContext(), 300));
        panel.animate().translationY(0).setInterpolator(new DecelerateInterpolator()).setDuration(240).start();
    }

    public void dismiss() {
        if( !showing )
            return;
        showing = false;
        animate().alpha(0.0f).setDuration(160).withEndAction(() -> setVisibility(View.GONE)).start();
        if( listener != null )
            listener.onSheetDismissed();
    }
}
