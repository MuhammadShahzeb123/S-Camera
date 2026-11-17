package net.sourceforge.opencamera.op.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Draws the OnePlus style tap-to-focus ring with an exposure "sun" slider beside it.
 *  Purely visual - touch handling (tap / vertical drag for exposure) is done by the activity.
 */
public class OpFocusRing extends View {
    public enum State {
        FOCUSING,
        FOCUSED,
        FAILED
    }

    private float fx, fy;
    private boolean visible;
    private float appear = 0.0f;
    private State state = State.FOCUSING;
    private float exposure = 0.0f; // -1..1
    private boolean showExposure = true;
    private boolean locked;
    private ValueAnimator appearAnim;
    private final Runnable hideRunnable = this::hideNow;

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sunPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    public OpFocusRing(Context context) {
        this(context, null);
    }

    public OpFocusRing(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(1.5f * density);
        ringPaint.setColor(Color.WHITE);
        ringPaint.setShadowLayer(2 * density, 0, 0, 0x80000000);
        sunPaint.setColor(OpTheme.HIGHLIGHT);
        sunPaint.setStrokeWidth(1.5f * density);
        sunPaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setColor(0xCCFFFFFF);
        linePaint.setStrokeWidth(1.2f * density);
        textPaint.setColor(OpTheme.HIGHLIGHT);
        textPaint.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
        textPaint.setTextAlign(Paint.Align.CENTER);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
    }

    public void showAt(float x, float y, boolean showExposure) {
        fx = x;
        fy = y;
        visible = true;
        locked = false;
        state = State.FOCUSING;
        exposure = 0.0f;
        this.showExposure = showExposure;
        if( appearAnim != null )
            appearAnim.cancel();
        appearAnim = ValueAnimator.ofFloat(0.0f, 1.0f);
        appearAnim.setDuration(260);
        appearAnim.setInterpolator(new DecelerateInterpolator());
        appearAnim.addUpdateListener(a -> {
            appear = (float)a.getAnimatedValue();
            invalidate();
        });
        appearAnim.start();
        scheduleHide(3000);
    }

    public void setState(State s) {
        state = s;
        invalidate();
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
        if( locked )
            removeCallbacks(hideRunnable);
        invalidate();
    }

    public boolean isShowing() {
        return visible;
    }

    public float getFocusX() {
        return fx;
    }

    public float getFocusY() {
        return fy;
    }

    /** @param value Normalised exposure, -1..1 */
    public void setExposure(float value) {
        exposure = Math.max(-1.0f, Math.min(1.0f, value));
        scheduleHide(3000);
        invalidate();
    }

    public float getExposure() {
        return exposure;
    }

    public void scheduleHide(long delayMs) {
        removeCallbacks(hideRunnable);
        if( !locked )
            postDelayed(hideRunnable, delayMs);
    }

    public void hideNow() {
        visible = false;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if( !visible )
            return;
        float r = (34 + 14 * (1.0f - appear)) * density;
        int color = state == State.FAILED ? 0xFFFF6B6B : (locked ? OpTheme.HIGHLIGHT : Color.WHITE);
        ringPaint.setColor(color);
        ringPaint.setAlpha((int)(255 * Math.min(1.0f, appear * 1.3f)));
        canvas.drawCircle(fx, fy, r, ringPaint);
        if( state == State.FOCUSED || state == State.FAILED ) {
            canvas.drawCircle(fx, fy, 2.5f * density, ringPaint);
        }
        if( locked ) {
            canvas.drawText("AE/AF LOCK", fx, fy - r - 10 * density, textPaint);
        }
        if( showExposure ) {
            // vertical slider to the right of the ring
            float sx = fx + r + 18 * density;
            if( sx > getWidth() - 20 * density )
                sx = fx - r - 18 * density;
            float half = 52 * density;
            float sunY = fy - exposure * half;
            linePaint.setAlpha((int)(200 * appear));
            if( sunY - 9 * density > fy - half )
                canvas.drawLine(sx, fy - half, sx, sunY - 9 * density, linePaint);
            if( sunY + 9 * density < fy + half )
                canvas.drawLine(sx, sunY + 9 * density, sx, fy + half, linePaint);
            // sun icon
            canvas.drawCircle(sx, sunY, 4.2f * density, sunPaint);
            for(int i=0;i<8;i++) {
                double ang = Math.PI * 2 * i / 8.0;
                float x0 = (float)(sx + Math.cos(ang) * 6.5f * density);
                float y0 = (float)(sunY + Math.sin(ang) * 6.5f * density);
                float x1 = (float)(sx + Math.cos(ang) * 8.8f * density);
                float y1 = (float)(sunY + Math.sin(ang) * 8.8f * density);
                canvas.drawLine(x0, y0, x1, y1, sunPaint);
            }
        }
    }
}
