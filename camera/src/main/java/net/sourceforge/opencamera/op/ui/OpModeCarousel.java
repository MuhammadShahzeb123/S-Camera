package net.sourceforge.opencamera.op.ui;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.List;

/** OnePlus style horizontally scrolling mode selector ("NIGHT  VIDEO  PHOTO  PORTRAIT  MORE").
 *  The selected item is always snapped to the horizontal centre.
 */
public class OpModeCarousel extends View {
    public interface Listener {
        /** Called when the user settles on a new item. */
        void onItemSelected(int index);
        /** Called when the user taps the already selected item. */
        void onSelectedItemTapped(int index);
    }

    private final List<String> items = new ArrayList<>();
    private float [] centers = new float[0];
    private float [] widths = new float[0];
    private int selected = 0;
    private float scroll = 0.0f; // content x-coordinate shown at the view centre
    private boolean layoutDirty = true;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final OverScroller scroller;
    private final GestureDetector gestureDetector;
    private ValueAnimator snapAnimator;
    private boolean flinging;
    private boolean touching;
    private final float gap;
    private final float density;
    private Listener listener;
    private boolean enabledForTouch = true;
    private int lastHapticIndex = -1;

    public OpModeCarousel(Context context) {
        this(context, null);
    }

    public OpModeCarousel(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        float scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        gap = 26 * density;
        paint.setTextSize(13.5f * scaledDensity);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setLetterSpacing(0.06f);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setShadowLayer(3 * density, 0, 0, 0x80000000);
        dotPaint.setColor(OpTheme.ACCENT);
        scroller = new OverScroller(context, new DecelerateInterpolator());
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                cancelAnimations();
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if( getParent() != null )
                    getParent().requestDisallowInterceptTouchEvent(true);
                setScroll(scroll + distanceX);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if( items.isEmpty() )
                    return false;
                flinging = true;
                // limit fling so it moves at most a couple of items, it feels more controlled
                float v = Math.max(-2500 * density, Math.min(2500 * density, -velocityX));
                scroller.fling((int)scroll, 0, (int)v, 0, (int)centers[0], (int)centers[centers.length-1], 0, 0);
                postInvalidateOnAnimation();
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                int index = itemAt(e.getX());
                if( index >= 0 ) {
                    if( index == selected ) {
                        if( listener != null )
                            listener.onSelectedItemTapped(index);
                    }
                    else {
                        animateTo(index, true);
                    }
                }
                return true;
            }
        });
        setHapticFeedbackEnabled(true);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setTouchEnabled(boolean enabled) {
        this.enabledForTouch = enabled;
        setAlpha(enabled ? 1.0f : 0.35f);
    }

    public void setItems(List<String> new_items, int new_selected) {
        items.clear();
        items.addAll(new_items);
        selected = Math.max(0, Math.min(new_selected, items.size()-1));
        layoutDirty = true;
        requestLayout();
        invalidate();
    }

    public void setItemLabel(int index, String label) {
        if( index >= 0 && index < items.size() && !items.get(index).equals(label) ) {
            items.set(index, label);
            layoutDirty = true;
            invalidate();
        }
    }

    public int getSelected() {
        return selected;
    }

    /** Programmatically select an item, without notifying the listener. */
    public void setSelected(int index, boolean animate) {
        if( index < 0 || index >= items.size() )
            return;
        cancelAnimations();
        selected = index;
        ensureLayout();
        if( animate ) {
            startSnapAnimation(centers[index], false);
        }
        else {
            scroll = centers[index];
            invalidate();
        }
    }

    private void cancelAnimations() {
        if( snapAnimator != null ) {
            snapAnimator.cancel();
            snapAnimator = null;
        }
        if( !scroller.isFinished() ) {
            scroller.forceFinished(true);
        }
        flinging = false;
    }

    private void ensureLayout() {
        if( !layoutDirty )
            return;
        layoutDirty = false;
        int n = items.size();
        centers = new float[n];
        widths = new float[n];
        float x = 0.0f;
        Typeface normal = paint.getTypeface();
        for(int i=0;i<n;i++) {
            float w = paint.measureText(items.get(i));
            widths[i] = w;
            centers[i] = x + w/2.0f;
            x += w + gap;
        }
        if( n > 0 && !touching && !flinging && (snapAnimator == null) ) {
            scroll = centers[selected];
        }
        paint.setTypeface(normal);
    }

    private void setScroll(float value) {
        ensureLayout();
        if( centers.length == 0 )
            return;
        float min = centers[0] - 40 * density;
        float max = centers[centers.length-1] + 40 * density;
        scroll = Math.max(min, Math.min(max, value));
        int nearest = nearestIndex(scroll);
        if( nearest != lastHapticIndex ) {
            if( lastHapticIndex != -1 && touching )
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            lastHapticIndex = nearest;
        }
        invalidate();
    }

    private int nearestIndex(float x) {
        int best = 0;
        float best_dist = Float.MAX_VALUE;
        for(int i=0;i<centers.length;i++) {
            float d = Math.abs(centers[i] - x);
            if( d < best_dist ) {
                best_dist = d;
                best = i;
            }
        }
        return best;
    }

    private int itemAt(float viewX) {
        ensureLayout();
        float contentX = scroll + (viewX - getWidth()/2.0f);
        for(int i=0;i<centers.length;i++) {
            float half = widths[i]/2.0f + gap/2.0f;
            if( contentX >= centers[i]-half && contentX <= centers[i]+half )
                return i;
        }
        return -1;
    }

    private void animateTo(int index, boolean notify) {
        ensureLayout();
        startSnapAnimation(centers[index], notify);
    }

    private void startSnapAnimation(float target, final boolean notify) {
        if( snapAnimator != null )
            snapAnimator.cancel();
        snapAnimator = ValueAnimator.ofFloat(scroll, target);
        snapAnimator.setDuration(220);
        snapAnimator.setInterpolator(new DecelerateInterpolator());
        snapAnimator.addUpdateListener(animation -> {
            scroll = (float)animation.getAnimatedValue();
            invalidate();
        });
        snapAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                cancelled = true;
            }
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                snapAnimator = null;
                if( !cancelled )
                    settle(notify);
            }
        });
        snapAnimator.start();
    }

    private void settle(boolean notify) {
        int nearest = nearestIndex(scroll);
        scroll = centers[nearest];
        invalidate();
        if( nearest != selected ) {
            selected = nearest;
            if( notify && listener != null ) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                listener.onItemSelected(nearest);
            }
        }
    }

    private void snapToNearest() {
        ensureLayout();
        if( centers.length == 0 )
            return;
        int nearest = nearestIndex(scroll);
        startSnapAnimation(centers[nearest], true);
    }

    @Override
    public void computeScroll() {
        super.computeScroll();
        if( flinging ) {
            if( scroller.computeScrollOffset() ) {
                setScroll(scroller.getCurrX());
                postInvalidateOnAnimation();
            }
            else {
                flinging = false;
                snapToNearest();
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if( !enabledForTouch || items.isEmpty() )
            return false;
        int action = event.getActionMasked();
        if( action == MotionEvent.ACTION_DOWN ) {
            touching = true;
            lastHapticIndex = nearestIndex(scroll);
        }
        boolean handled = gestureDetector.onTouchEvent(event);
        if( action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL ) {
            touching = false;
            if( !flinging && snapAnimator == null ) {
                snapToNearest();
            }
        }
        return handled || true;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int h = (int)(40 * density);
        int height = resolveSize(h, heightMeasureSpec);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        ensureLayout();
        if( items.isEmpty() )
            return;
        float cx = getWidth()/2.0f;
        float baseline = getHeight()/2.0f - (paint.descent() + paint.ascent())/2.0f - 3 * density;
        int nearest = nearestIndex(scroll);
        for(int i=0;i<items.size();i++) {
            float x = cx + (centers[i] - scroll);
            if( x + widths[i] < -gap || x - widths[i] > getWidth() + gap )
                continue;
            float dist = Math.abs(centers[i] - scroll);
            boolean isSel = i == nearest;
            float alpha = isSel ? 1.0f : Math.max(0.35f, 0.72f - dist / (getWidth() * 1.4f));
            paint.setColor(Color.argb((int)(alpha*255), 255, 255, 255));
            paint.setFakeBoldText(isSel);
            canvas.drawText(items.get(i), x, baseline, paint);
        }
        paint.setFakeBoldText(false);
        // selection dot
        canvas.drawCircle(cx, getHeight() - 7 * density, 2.3f * density, dotPaint);
    }
}
