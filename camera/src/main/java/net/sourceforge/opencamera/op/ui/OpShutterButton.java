package net.sourceforge.opencamera.op.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** OnePlus style shutter button.
 *  PHOTO: white ring with white disc; VIDEO: white ring with red disc; RECORDING: red rounded
 *  square; BUSY: spinning arc (e.g. night processing); PROGRESS: arc showing progress (long
 *  exposure / timer).
 */
public class OpShutterButton extends View {
    public enum Style {
        PHOTO,
        VIDEO,
        RECORDING,
        BUSY,
        PROGRESS
    }

    private Style style = Style.PHOTO;
    private float morph = 0.0f; // 0 = disc, 1 = rounded square
    private float redness = 0.0f; // 0 = white, 1 = red
    private float progress = 0.0f; // for PROGRESS style, 0..1
    private float pressScale = 1.0f;
    private float spinAngle = 0.0f;
    private ValueAnimator morphAnim;
    private ValueAnimator pressAnim;
    private ValueAnimator spinAnim;
    private String centerText; // e.g. timer countdown "3"

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;

    public OpShutterButton(Context context) {
        this(context, null);
    }

    public OpShutterButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setColor(Color.WHITE);
        ringPaint.setStrokeWidth(4.0f * density);
        fillPaint.setStyle(Paint.Style.FILL);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(4.0f * density);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setColor(OpTheme.HIGHLIGHT);
        textPaint.setColor(Color.BLACK);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(26 * getResources().getDisplayMetrics().scaledDensity);
        textPaint.setFakeBoldText(true);
        setClickable(true);
        setFocusable(true);
        setContentDescription("Shutter");
    }

    public Style getStyle() {
        return style;
    }

    public void setStyle(Style new_style) {
        if( new_style == style )
            return;
        Style old = style;
        style = new_style;
        float targetMorph = new_style == Style.RECORDING ? 1.0f : 0.0f;
        float targetRed = (new_style == Style.VIDEO || new_style == Style.RECORDING) ? 1.0f : 0.0f;
        if( old == Style.PROGRESS || new_style == Style.PROGRESS )
            progress = 0.0f;
        animateMorph(targetMorph, targetRed);
        if( new_style == Style.BUSY )
            startSpin();
        else
            stopSpin();
        invalidate();
    }

    public void setProgress(float progress) {
        this.progress = Math.max(0.0f, Math.min(1.0f, progress));
        invalidate();
    }

    public void setCenterText(String text) {
        this.centerText = text;
        invalidate();
    }

    private void animateMorph(float targetMorph, float targetRed) {
        if( morphAnim != null )
            morphAnim.cancel();
        final float startMorph = morph;
        final float startRed = redness;
        morphAnim = ValueAnimator.ofFloat(0.0f, 1.0f);
        morphAnim.setDuration(220);
        morphAnim.setInterpolator(new DecelerateInterpolator());
        morphAnim.addUpdateListener(a -> {
            float t = (float)a.getAnimatedValue();
            morph = startMorph + (targetMorph - startMorph) * t;
            redness = startRed + (targetRed - startRed) * t;
            invalidate();
        });
        morphAnim.start();
    }

    private void startSpin() {
        if( spinAnim != null )
            return;
        spinAnim = ValueAnimator.ofFloat(0.0f, 360.0f);
        spinAnim.setDuration(900);
        spinAnim.setRepeatCount(ValueAnimator.INFINITE);
        spinAnim.setInterpolator(null);
        spinAnim.addUpdateListener(a -> {
            spinAngle = (float)a.getAnimatedValue();
            invalidate();
        });
        spinAnim.start();
    }

    private void stopSpin() {
        if( spinAnim != null ) {
            spinAnim.cancel();
            spinAnim = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopSpin();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if( !isEnabled() )
            return false;
        switch( event.getActionMasked() ) {
            case MotionEvent.ACTION_DOWN:
                animatePress(0.9f);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                animatePress(1.0f);
                break;
        }
        return super.onTouchEvent(event);
    }

    private void animatePress(float target) {
        if( pressAnim != null )
            pressAnim.cancel();
        pressAnim = ValueAnimator.ofFloat(pressScale, target);
        pressAnim.setDuration(110);
        pressAnim.addUpdateListener(a -> {
            pressScale = (float)a.getAnimatedValue();
            invalidate();
        });
        pressAnim.start();
    }

    private static int lerpColor(int a, int b, float t) {
        int ar = Color.red(a), ag = Color.green(a), ab = Color.blue(a);
        int br = Color.red(b), bg = Color.green(b), bb = Color.blue(b);
        return Color.rgb((int)(ar + (br-ar)*t), (int)(ag + (bg-ag)*t), (int)(ab + (bb-ab)*t));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2.0f;
        float cy = getHeight() / 2.0f;
        float outer = Math.min(getWidth(), getHeight()) / 2.0f - ringPaint.getStrokeWidth();
        canvas.save();
        canvas.scale(pressScale, pressScale, cx, cy);
        ringPaint.setAlpha(isEnabled() ? 255 : 110);
        canvas.drawCircle(cx, cy, outer, ringPaint);

        // inner shape: disc (morph 0) -> rounded square (morph 1)
        float discR = outer - 7.0f * density;
        float squareHalf = outer * 0.42f;
        float half = discR + (squareHalf - discR) * morph;
        float corner = half + (6.0f * density - half) * morph;
        fillPaint.setColor(lerpColor(Color.WHITE, OpTheme.RECORD_RED, redness));
        fillPaint.setAlpha(isEnabled() ? 255 : 110);
        rect.set(cx - half, cy - half, cx + half, cy + half);
        canvas.drawRoundRect(rect, corner, corner, fillPaint);

        if( style == Style.BUSY ) {
            rect.set(cx - outer, cy - outer, cx + outer, cy + outer);
            canvas.drawArc(rect, spinAngle, 90.0f, false, arcPaint);
        }
        else if( style == Style.PROGRESS ) {
            rect.set(cx - outer, cy - outer, cx + outer, cy + outer);
            canvas.drawArc(rect, -90.0f, 360.0f * progress, false, arcPaint);
        }
        if( centerText != null ) {
            float baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2.0f;
            textPaint.setColor(redness > 0.5f ? Color.WHITE : Color.BLACK);
            canvas.drawText(centerText, cx, baseline, textPaint);
        }
        canvas.restore();
    }
}
