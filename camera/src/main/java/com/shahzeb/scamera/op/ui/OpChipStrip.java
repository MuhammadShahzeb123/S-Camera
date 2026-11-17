package com.shahzeb.scamera.op.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Horizontally scrolling row of option chips used for mode specific settings
 *  (e.g. long exposure style/duration, film looks, multi-cam lens selection).
 *  Chips with the same group act as radio buttons; chips with group null are toggles or actions.
 */
public class OpChipStrip extends HorizontalScrollView {
    public static class Chip {
        public final String id;
        public final String label;
        public final String group; // null for action/toggle chips
        public boolean selected;
        public boolean enabled = true;

        public Chip(String id, String label, String group, boolean selected) {
            this.id = id;
            this.label = label;
            this.group = group;
            this.selected = selected;
        }
    }

    public interface Listener {
        void onChipClicked(Chip chip);
    }

    private final LinearLayout row;
    private final List<Chip> chips = new ArrayList<>();
    private final List<TextView> views = new ArrayList<>();
    private Listener listener;

    public OpChipStrip(Context context) {
        this(context, null);
    }

    public OpChipStrip(Context context, AttributeSet attrs) {
        super(context, attrs);
        setHorizontalScrollBarEnabled(false);
        setFillViewport(true);
        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        int p = OpTheme.dp(context, 10);
        row.setPadding(p, 0, p, 0);
        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setChips(List<Chip> new_chips) {
        chips.clear();
        chips.addAll(new_chips);
        row.removeAllViews();
        views.clear();
        Context context = getContext();
        for(final Chip chip : chips) {
            if( chip.id.startsWith("|") ) {
                // separator
                View sep = new View(context);
                sep.setBackgroundColor(0x40FFFFFF);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(OpTheme.dp(context, 1), OpTheme.dp(context, 18));
                slp.leftMargin = OpTheme.dp(context, 6);
                slp.rightMargin = OpTheme.dp(context, 6);
                row.addView(sep, slp);
                views.add(null);
                continue;
            }
            TextView tv = new TextView(context);
            tv.setText(chip.label);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            tv.setGravity(Gravity.CENTER);
            tv.setSingleLine(true);
            int ph = OpTheme.dp(context, 12);
            tv.setPadding(ph, 0, ph, 0);
            tv.setOnClickListener(v -> {
                if( !chip.enabled )
                    return;
                if( chip.group != null ) {
                    for(Chip c : chips) {
                        if( chip.group.equals(c.group) )
                            c.selected = c == chip;
                    }
                }
                updateViews();
                if( listener != null )
                    listener.onChipClicked(chip);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, OpTheme.dp(context, 30));
            lp.leftMargin = OpTheme.dp(context, 3);
            lp.rightMargin = OpTheme.dp(context, 3);
            row.addView(tv, lp);
            views.add(tv);
        }
        updateViews();
    }

    public void setChipSelected(String id, boolean selected) {
        for(Chip c : chips) {
            if( c.id.equals(id) ) {
                if( selected && c.group != null ) {
                    for(Chip o : chips) {
                        if( c.group.equals(o.group) )
                            o.selected = false;
                    }
                }
                c.selected = selected;
            }
        }
        updateViews();
    }

    public Chip getChip(String id) {
        for(Chip c : chips) {
            if( c.id.equals(id) )
                return c;
        }
        return null;
    }

    public boolean isEmpty() {
        return chips.isEmpty();
    }

    private void updateViews() {
        for(int i=0;i<chips.size();i++) {
            TextView tv = views.get(i);
            if( tv == null )
                continue;
            Chip c = chips.get(i);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(OpTheme.dpf(getContext(), 15));
            if( c.selected ) {
                bg.setColor(0xF2FFFFFF);
                tv.setTextColor(Color.BLACK);
            }
            else {
                bg.setColor(0x66000000);
                bg.setStroke(OpTheme.dp(getContext(), 1), 0x55FFFFFF);
                tv.setTextColor(c.enabled ? Color.WHITE : 0x66FFFFFF);
            }
            tv.setBackground(bg);
        }
    }
}
