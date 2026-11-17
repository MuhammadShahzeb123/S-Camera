package com.shahzeb.scamera.op.ui;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.List;

/** Horizontal ruler dial used by Pro mode (ISO / shutter / WB / focus / EV), similar to the
 *  OnePlus Pro mode scale. Values are discrete labels; every "majorEvery" values a labelled
 *  major tick is drawn. The value under the centre indicator is the selected value.
 */
public class OpRulerDial extends View {
    public interface Listener {
        void onValueChanged(int index, boolean fromUser);
        void onScrollFinished(int index);
    }

    private final List<String> labels = new ArrayList<>();
    private int majorEvery = 1;
    private int index = 0;
    private float scroll = 0.0f; // in pixels: index * spacing
    private final float spacing;
    private final float density;
    private final OverScroller scroller;
    private final GestureDetector gestureDetector;
    private boolean flinging;
    private ValueAnimator snapAnim;
    private Listener listener;

    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint majorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint indicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public OpRulerDial(Context context) {
        this(context, null);
    }

    public OpRulerDial(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        spacing = 11.0f * density;
        tickPaint.setColor(0x99FFFFFF);
        tickPaint.setStrokeWidth(1.2f * density);
        majorPaint.setColor(Color.WHITE);
        majorPaint.setStrokeWidth(1.6f * density);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(10.5f * getResources().getDisplayMetrics().scaledDensity);
        indicatorPaint.setColor(OpTheme.HIGHLIGHT);
        indicatorPaint.setStrokeWidth(2.4f * density);
        indicatorPaint.setStrokeCap(Paint.Cap.ROUND);
        scroller = new OverScroller(context, new DecelerateInterpolator());
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                cancelAnims();
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if( getParent() != null )
                    getParent().requestDisallowInterceptTouchEvent(true);
                setScroll(scroll + distanceX, true);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if( labels.isEmpty() )
                    return false;
                flinging = true;
                scroller.fling((int)scroll, 0, (int)(-velocityX * 0.8f), 0, 0, (int)((labels.size()-1) * spacing), 0, 0);
                postInvalidateOnAnimation();
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                float dx = e.getX() - getWidth() / 2.0f;
                int target = Math.round((scroll + dx) / spacing);
                target = Math.max(0, Math.min(labels.size()-1, target));
                snapTo(target);
                return true;
            }
        });
        setHapticFeedbackEnabled(true);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setValues(List<String> values, int majorEvery, int selected) {
        cancelAnims();
        labels.clear();
        labels.addAll(values);
        this.majorEvery = Math.max(1, majorEvery);
        this.index = Math.max(0, Math.min(values.size()-1, selected));
        this.scroll = index * spacing;
        invalidate();
    }

    public void setIndex(int new_index) {
        if( labels.isEmpty() )
            return;
        cancelAnims();
        index = Math.max(0, Math.min(labels.size()-1, new_index));
        scroll = index * spacing;
        invalidate();
    }

    public int getIndex() {
        return index;
    }

    public int getCount() {
        return labels.size();
    }

    private void cancelAnims() {
        if( snapAnim != null ) {
            snapAnim.cancel();
            snapAnim = null;
        }
        if( !scroller.isFinished() )
            scroller.forceFinished(true);
        flinging = false;
    }

    private void setScroll(float value, boolean fromUser) {
        if( labels.isEmpty() )
            return;
        float max = (labels.size()-1) * spacing;
        scroll = Math.max(0.0f, Math.min(max, value));
        int new_index = Math.round(scroll / spacing);
        if( new_index != index ) {
            index = new_index;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            if( listener != null )
                listener.onValueChanged(index, fromUser);
        }
        invalidate();
    }

    private void snapTo(int target) {
        if( snapAnim != null )
            snapAnim.cancel();
        snapAnim = ValueAnimator.ofFloat(scroll, target * spacing);
        snapAnim.setDuration(150);
        snapAnim.addUpdateListener(a -> setScroll((float)a.getAnimatedValue(), true));
        snapAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                cancelled = true;
            }
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                snapAnim = null;
                if( !cancelled && listener != null )
                    listener.onScrollFinished(index);
            }
        });
        snapAnim.start();
    }

    @Override
    public void computeScroll() {
        super.computeScroll();
        if( flinging ) {
            if( scroller.computeScrollOffset() ) {
                setScroll(scroller.getCurrX(), true);
                postInvalidateOnAnimation();
            }
            else {
                flinging = false;
                snapTo(Math.round(scroll / spacing));
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if( !isEnabled() || labels.isEmpty() )
            return false;
        boolean r = gestureDetector.onTouchEvent(event);
        int action = event.getActionMasked();
        if( (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && !flinging && snapAnim == null ) {
            snapTo(Math.round(scroll / spacing));
        }
        return r || true;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize((int)(52 * density), heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if( labels.isEmpty() )
            return;
        float cx = getWidth() / 2.0f;
        float tickBase = getHeight() * 0.42f;
        int first = Math.max(0, (int)((scroll - cx) / spacing) - 1);
        int last = Math.min(labels.size()-1, (int)((scroll + cx) / spacing) + 1);
        for(int i=first;i<=last;i++) {
            float x = cx + i * spacing - scroll;
            float fade = Math.max(0.15f, 1.0f - Math.abs(x - cx) / cx);
            boolean major = (i % majorEvery) == 0 || i == labels.size()-1;
            if( major ) {
                majorPaint.setAlpha((int)(255 * fade));
                canvas.drawLine(x, tickBase - 10 * density, x, tickBase + 6 * density, majorPaint);
                if( (i % majorEvery) == 0 ) {
                    textPaint.setAlpha((int)(255 * Math.max(0.25f, fade)));
                    canvas.drawText(labels.get(i), x, getHeight() - 6 * density, textPaint);
                }
            }
            else {
                tickPaint.setAlpha((int)(0x99 * fade));
                canvas.drawLine(x, tickBase - 5 * density, x, tickBase + 3 * density, tickPaint);
            }
        }
        canvas.drawLine(cx, tickBase - 16 * density, cx, tickBase + 10 * density, indicatorPaint);
    }
}
