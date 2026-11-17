package com.shahzeb.scamera.op.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Row of quick setting icons at the top of the camera screen (flash, HDR, ratio, timer, ...). */
public class OpTopBar extends LinearLayout {
    private final Map<String, FrameLayout> items = new LinkedHashMap<>();

    public OpTopBar(Context context) {
        this(context, null);
    }

    public OpTopBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int pad = OpTheme.dp(context, 8);
        setPadding(pad, 0, pad, 0);
    }

    public void clear() {
        removeAllViews();
        items.clear();
    }

    private FrameLayout makeCell(String id, View.OnClickListener listener) {
        FrameLayout cell = new FrameLayout(getContext());
        int size = OpTheme.dp(getContext(), 44);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        cell.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), null, mask));
        cell.setOnClickListener(listener);
        cell.setTag(id);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, size, 1.0f);
        addView(cell, lp);
        items.put(id, cell);
        return cell;
    }

    public View addIcon(String id, int iconRes, String description, View.OnClickListener listener) {
        FrameLayout cell = makeCell(id, listener);
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(iconRes);
        iv.setColorFilter(Color.WHITE);
        iv.setContentDescription(description);
        int s = OpTheme.dp(getContext(), 24);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s, s, Gravity.CENTER);
        cell.addView(iv, lp);
        return cell;
    }

    public View addText(String id, String text, String description, View.OnClickListener listener) {
        FrameLayout cell = makeCell(id, listener);
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setContentDescription(description);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(OpTheme.dp(getContext(), 4));
        bg.setStroke(OpTheme.dp(getContext(), 1.3f), Color.WHITE);
        tv.setBackground(bg);
        int ph = OpTheme.dp(getContext(), 5);
        tv.setPadding(ph, OpTheme.dp(getContext(), 1), ph, OpTheme.dp(getContext(), 1));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        cell.addView(tv, lp);
        return cell;
    }

    public void setIcon(String id, int iconRes) {
        FrameLayout cell = items.get(id);
        if( cell != null && cell.getChildAt(0) instanceof ImageView )
            ((ImageView)cell.getChildAt(0)).setImageResource(iconRes);
    }

    public void setText(String id, String text) {
        FrameLayout cell = items.get(id);
        if( cell != null && cell.getChildAt(0) instanceof TextView )
            ((TextView)cell.getChildAt(0)).setText(text);
    }

    /** Active items are tinted with the highlight colour (e.g. HDR on, flash on). */
    public void setActive(String id, boolean active) {
        FrameLayout cell = items.get(id);
        if( cell == null )
            return;
        View child = cell.getChildAt(0);
        int color = active ? OpTheme.HIGHLIGHT : Color.WHITE;
        if( child instanceof ImageView ) {
            ((ImageView)child).setColorFilter(color);
        }
        else if( child instanceof TextView ) {
            TextView tv = (TextView)child;
            tv.setTextColor(color);
            if( tv.getBackground() instanceof GradientDrawable )
                ((GradientDrawable)tv.getBackground()).setStroke(OpTheme.dp(getContext(), 1.3f), color);
        }
    }

    public void setItemVisible(String id, boolean visible) {
        FrameLayout cell = items.get(id);
        if( cell != null )
            cell.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public View getItem(String id) {
        return items.get(id);
    }

    public List<View> getRotatableViews() {
        List<View> views = new ArrayList<>();
        for(FrameLayout cell : items.values()) {
            if( cell.getChildCount() > 0 )
                views.add(cell.getChildAt(0));
        }
        return views;
    }
}
