package com.shahzeb.scamera.op.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** OnePlus style "Pro" control panel: a row of parameters (WB, AF, ISO, S, EV); selecting one
 *  opens a ruler dial to adjust it, with an "A" button to return it to auto.
 */
public class OpProPanel extends LinearLayout {
    public static class Param {
        public final String key;
        public final String label;
        public final List<String> values = new ArrayList<>();
        public int majorEvery = 1;
        public boolean hasAuto = true;
        public boolean isAuto = true;
        public int index = 0;
        /** Text to show for the value while in auto mode (e.g. live ISO). */
        public String autoText = "AUTO";
        /** Accepted value may fall between the dial's selectable presets. */
        public String manualText;
        public boolean enabled = true;

        public Param(String key, String label) {
            this.key = key;
            this.label = label;
        }

        String displayValue() {
            if( isAuto || values.isEmpty() )
                return autoText;
            if( manualText != null ) return manualText;
            return values.get(Math.max(0, Math.min(values.size()-1, index)));
        }
    }

    public interface Listener {
        void onProValueChanged(Param param, int index);
        void onProAutoChanged(Param param, boolean auto);
    }

    private final List<Param> params = new ArrayList<>();
    private final List<TextView> valueViews = new ArrayList<>();
    private final List<TextView> labelViews = new ArrayList<>();
    private final LinearLayout cellsRow;
    private final FrameLayout dialRow;
    private final OpRulerDial dial;
    private final TextView autoButton;
    private Param selected;
    private Listener listener;

    public OpProPanel(Context context) {
        this(context, null);
    }

    public OpProPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        setClickable(true); // swallow touches so they don't go to the preview

        dialRow = new FrameLayout(context);
        dialRow.setVisibility(View.GONE);
        dial = new OpRulerDial(context);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, OpTheme.dp(context, 52));
        dlp.leftMargin = OpTheme.dp(context, 52);
        dialRow.addView(dial, dlp);
        autoButton = new TextView(context);
        autoButton.setText("A");
        autoButton.setGravity(Gravity.CENTER);
        autoButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        autoButton.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable autoBg = new GradientDrawable();
        autoBg.setShape(GradientDrawable.OVAL);
        autoBg.setStroke(OpTheme.dp(context, 1.5f), Color.WHITE);
        autoButton.setBackground(autoBg);
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(OpTheme.dp(context, 34), OpTheme.dp(context, 34));
        alp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        alp.leftMargin = OpTheme.dp(context, 12);
        dialRow.addView(autoButton, alp);
        addView(dialRow, new LayoutParams(LayoutParams.MATCH_PARENT, OpTheme.dp(context, 56)));

        cellsRow = new LinearLayout(context);
        cellsRow.setOrientation(HORIZONTAL);
        cellsRow.setGravity(Gravity.CENTER);
        addView(cellsRow, new LayoutParams(LayoutParams.MATCH_PARENT, OpTheme.dp(context, 48)));

        dial.setListener(new OpRulerDial.Listener() {
            @Override
            public void onValueChanged(int index, boolean fromUser) {
                if( selected == null || !fromUser )
                    return;
                selected.index = index;
                selected.manualText = null;
                if( selected.isAuto ) {
                    selected.isAuto = false;
                    if( listener != null )
                        listener.onProAutoChanged(selected, false);
                }
                updateCells();
                if( listener != null )
                    listener.onProValueChanged(selected, index);
            }

            @Override
            public void onScrollFinished(int index) {
            }
        });
        autoButton.setOnClickListener(v -> {
            if( selected == null || !selected.hasAuto )
                return;
            boolean newAuto = !selected.isAuto;
            selected.isAuto = newAuto;
            updateAutoButton();
            updateCells();
            if( listener != null )
                listener.onProAutoChanged(selected, newAuto);
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setParams(List<Param> new_params) {
        String selectedKey = selected != null ? selected.key : null;
        params.clear();
        params.addAll(new_params);
        cellsRow.removeAllViews();
        valueViews.clear();
        labelViews.clear();
        Context context = getContext();
        selected = null;
        for(final Param p : params) {
            LinearLayout cell = new LinearLayout(context);
            cell.setOrientation(VERTICAL);
            cell.setGravity(Gravity.CENTER);
            TextView label = new TextView(context);
            label.setText(p.label);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            label.setTextColor(OpTheme.TEXT_DIM);
            label.setGravity(Gravity.CENTER);
            TextView value = new TextView(context);
            value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            value.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            value.setGravity(Gravity.CENTER);
            value.setSingleLine(true);
            cell.addView(label);
            cell.addView(value);
            cell.setOnClickListener(v -> select(p.key.equals(selected != null ? selected.key : null) ? null : p.key));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1.0f);
            cellsRow.addView(cell, lp);
            valueViews.add(value);
            labelViews.add(label);
            if( p.key.equals(selectedKey) )
                selected = p;
        }
        if( selected != null )
            openDialFor(selected);
        else
            dialRow.setVisibility(View.GONE);
        updateCells();
    }

    public Param getParam(String key) {
        for(Param p : params) {
            if( p.key.equals(key) )
                return p;
        }
        return null;
    }

    /** Refresh texts after the host changed a param's values (e.g. live auto values). */
    public void refresh() {
        if( selected != null && !selected.enabled ) select(null);
        updateCells();
    }

    public void select(String key) {
        selected = key == null ? null : getParam(key);
        if( selected == null || !selected.enabled ) {
            selected = null;
            dialRow.setVisibility(View.GONE);
        }
        else {
            openDialFor(selected);
        }
        updateCells();
    }

    private void openDialFor(Param p) {
        dialRow.setVisibility(View.VISIBLE);
        dial.setValues(p.values, p.majorEvery, p.index);
        updateAutoButton();
    }

    private void updateAutoButton() {
        if( selected == null )
            return;
        autoButton.setVisibility(selected.hasAuto ? View.VISIBLE : View.INVISIBLE);
        GradientDrawable bg = (GradientDrawable)autoButton.getBackground();
        if( selected.isAuto ) {
            bg.setColor(OpTheme.HIGHLIGHT);
            bg.setStroke(OpTheme.dp(getContext(), 1.5f), OpTheme.HIGHLIGHT);
            autoButton.setTextColor(Color.BLACK);
        }
        else {
            bg.setColor(Color.TRANSPARENT);
            bg.setStroke(OpTheme.dp(getContext(), 1.5f), Color.WHITE);
            autoButton.setTextColor(Color.WHITE);
        }
    }

    private void updateCells() {
        for(int i=0;i<params.size();i++) {
            Param p = params.get(i);
            TextView v = valueViews.get(i);
            v.setText(p.displayValue());
            View cell = (View)v.getParent();
            cell.setEnabled(p.enabled);
            cell.setContentDescription(p.label + ": " + p.displayValue() + (p.enabled ? "" : " unavailable"));
            boolean isSel = p == selected;
            int color = !p.enabled ? 0x55FFFFFF : isSel ? OpTheme.HIGHLIGHT : (p.isAuto ? Color.WHITE : OpTheme.HIGHLIGHT);
            v.setTextColor(color);
            labelViews.get(i).setTextColor(isSel ? OpTheme.HIGHLIGHT : OpTheme.TEXT_DIM);
        }
        updateAutoButton();
    }

    public boolean isDialOpen() {
        return dialRow.getVisibility() == View.VISIBLE;
    }
}
