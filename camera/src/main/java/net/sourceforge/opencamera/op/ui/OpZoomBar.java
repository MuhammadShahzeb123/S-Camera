package net.sourceforge.opencamera.op.ui;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.Locale;

/** OnePlus style lens / zoom selector: ( .6  [1x]  3.3 ).
 *  Tapping a pill jumps to that lens; dragging horizontally turns the bar into a zoom ruler.
 */
public class OpZoomBar extends View {
    public interface Listener {
        void onStopTapped(int index, float zoom);
        void onZoomDragged(float zoom);
        void onZoomDragEnded(float zoom);
    }

    private float [] stops = {1.0f};
    private float zoom = 1.0f;
    private float minZoom = 1.0f;
    private float maxZoom = 10.0f;
    private Listener listener;
    private boolean dialMode;
    private float dialAlpha;
    private ValueAnimator dialAnim;
    private float downX, downY;
    private float dragStartLogZoom;
    private boolean dragging;
    private final int touchSlop;
    private final float density;
    private float lastHapticZoom = -1;

    private final Paint capsulePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint indicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private static final float CIRCLE_DP = 36.0f;
    private static final float SPACING_DP = 8.0f;
    private static final float PAD_DP = 4.0f;

    public OpZoomBar(Context context) {
        this(context, null);
    }

    public OpZoomBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        float sd = getResources().getDisplayMetrics().scaledDensity;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        capsulePaint.setColor(0x47000000);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(12.0f * sd);
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tickPaint.setColor(Color.WHITE);
        tickPaint.setStrokeWidth(1.2f * density);
        indicatorPaint.setColor(OpTheme.HIGHLIGHT);
        indicatorPaint.setStrokeWidth(2.0f * density);
        setHapticFeedbackEnabled(true);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setStops(float [] new_stops) {
        if( new_stops == null || new_stops.length == 0 )
            new_stops = new float[]{1.0f};
        this.stops = new_stops.clone();
        requestLayout();
        invalidate();
    }

    public float [] getStops() {
        return stops;
    }

    public void setRange(float min, float max) {
        this.minZoom = min;
        this.maxZoom = Math.max(min + 0.01f, max);
        invalidate();
    }

    public void setZoom(float zoom) {
        if( dragging )
            return; // don't fight the user's finger
        this.zoom = zoom;
        invalidate();
    }

    public float getZoom() {
        return zoom;
    }

    private int activeStopIndex() {
        int best = 0;
        for(int i=0;i<stops.length;i++) {
            if( zoom + 0.02f >= stops[i] )
                best = i;
        }
        return best;
    }

    public static String formatStop(float s) {
        if( Math.abs(s - Math.round(s)) < 0.05f )
            return String.valueOf(Math.round(s));
        String t = String.format(Locale.US, "%.1f", s);
        if( t.startsWith("0") )
            t = t.substring(1);
        return t;
    }

    public static String formatZoom(float z) {
        if( z < 10.0f ) {
            String t = String.format(Locale.US, "%.1f", z);
            if( t.endsWith(".0") )
                t = t.substring(0, t.length()-2);
            return t + "x";
        }
        return Math.round(z) + "x";
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int h = (int)((CIRCLE_DP + 2*PAD_DP + 8) * density);
        setMeasuredDimension(width, resolveSize(h, heightMeasureSpec));
    }

    private float capsuleWidth() {
        return (stops.length * CIRCLE_DP + (stops.length - 1) * SPACING_DP + 2 * PAD_DP) * density;
    }

    private float circleCenterX(int i) {
        float left = getWidth()/2.0f - capsuleWidth()/2.0f + PAD_DP * density;
        return left + (i * (CIRCLE_DP + SPACING_DP) + CIRCLE_DP/2.0f) * density;
    }

    private void setDialMode(boolean on) {
        if( on == dialMode )
            return;
        dialMode = on;
        if( dialAnim != null )
            dialAnim.cancel();
        dialAnim = ValueAnimator.ofFloat(dialAlpha, on ? 1.0f : 0.0f);
        dialAnim.setDuration(180);
        dialAnim.addUpdateListener(a -> {
            dialAlpha = (float)a.getAnimatedValue();
            invalidate();
        });
        dialAnim.start();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if( !isEnabled() )
            return false;
        switch( event.getActionMasked() ) {
            case MotionEvent.ACTION_DOWN: {
                downX = event.getX();
                downY = event.getY();
                dragging = false;
                // only start if touch is near the capsule, or we're in dial mode
                float cw = capsuleWidth();
                boolean inCapsule = Math.abs(downX - getWidth()/2.0f) < cw/2.0f + 16 * density;
                return inCapsule;
            }
            case MotionEvent.ACTION_MOVE: {
                float dx = event.getX() - downX;
                if( !dragging && Math.abs(dx) > touchSlop * 1.5f && Math.abs(dx) > Math.abs(event.getY() - downY) ) {
                    dragging = true;
                    dragStartLogZoom = (float)Math.log(zoom);
                    downX = event.getX();
                    dx = 0;
                    setDialMode(true);
                    if( getParent() != null )
                        getParent().requestDisallowInterceptTouchEvent(true);
                }
                if( dragging ) {
                    double range = Math.log(maxZoom) - Math.log(minZoom);
                    double perPixel = range / (getWidth() * 0.9);
                    double lz = dragStartLogZoom - dx * perPixel;
                    float z = (float)Math.exp(lz);
                    z = Math.max(minZoom, Math.min(maxZoom, z));
                    // haptic tick at stops
                    for(float s : stops) {
                        if( (lastHapticZoom - s) * (z - s) < 0 ) {
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        }
                    }
                    lastHapticZoom = z;
                    zoom = z;
                    invalidate();
                    if( listener != null )
                        listener.onZoomDragged(z);
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if( dragging ) {
                    dragging = false;
                    postDelayed(() -> setDialMode(false), 900);
                    if( listener != null )
                        listener.onZoomDragEnded(zoom);
                }
                else {
                    // tap
                    float x = event.getX();
                    for(int i=0;i<stops.length;i++) {
                        if( Math.abs(x - circleCenterX(i)) < (CIRCLE_DP/2.0f + SPACING_DP/2.0f) * density ) {
                            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                            zoom = stops[i];
                            invalidate();
                            if( listener != null )
                                listener.onStopTapped(i, stops[i]);
                            break;
                        }
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                if( dragging ) {
                    dragging = false;
                    setDialMode(false);
                    if( listener != null )
                        listener.onZoomDragEnded(zoom);
                }
                return true;
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cy = getHeight() / 2.0f;
        float pillsAlpha = 1.0f - dialAlpha;
        if( pillsAlpha > 0.01f ) {
            float cw = capsuleWidth();
            float ch = (CIRCLE_DP + 2*PAD_DP) * density;
            rect.set(getWidth()/2.0f - cw/2.0f, cy - ch/2.0f, getWidth()/2.0f + cw/2.0f, cy + ch/2.0f);
            capsulePaint.setAlpha((int)(0x47 * pillsAlpha));
            if( stops.length > 1 )
                canvas.drawRoundRect(rect, ch/2.0f, ch/2.0f, capsulePaint);
            int active = activeStopIndex();
            float r = CIRCLE_DP/2.0f * density;
            for(int i=0;i<stops.length;i++) {
                float x = circleCenterX(i);
                boolean isActive = i == active;
                String label;
                if( isActive ) {
                    circlePaint.setColor(Color.argb((int)(235*pillsAlpha), 255, 255, 255));
                    canvas.drawCircle(x, cy, r, circlePaint);
                    textPaint.setColor(Color.argb((int)(255*pillsAlpha), 0, 0, 0));
                    label = formatZoom(zoom);
                    textPaint.setTextSize(11.5f * getResources().getDisplayMetrics().scaledDensity);
                }
                else {
                    circlePaint.setColor(Color.argb((int)(110*pillsAlpha), 0, 0, 0));
                    canvas.drawCircle(x, cy, r * 0.82f, circlePaint);
                    textPaint.setColor(Color.argb((int)(255*pillsAlpha), 255, 255, 255));
                    label = formatStop(stops[i]);
                    textPaint.setTextSize(11.0f * getResources().getDisplayMetrics().scaledDensity);
                }
                float baseline = cy - (textPaint.descent() + textPaint.ascent())/2.0f;
                canvas.drawText(label, x, baseline, textPaint);
            }
        }
        if( dialAlpha > 0.01f ) {
            drawDial(canvas, cy);
        }
    }

    private void drawDial(Canvas canvas, float cy) {
        int a = (int)(dialAlpha * 255);
        float w = getWidth();
        rect.set(8 * density, cy - 20 * density, w - 8 * density, cy + 20 * density);
        capsulePaint.setAlpha((int)(0x8C * dialAlpha));
        canvas.drawRoundRect(rect, 20 * density, 20 * density, capsulePaint);
        double range = Math.log(maxZoom) - Math.log(minZoom);
        double pxPerLog = (w * 0.9) / range;
        double lz = Math.log(zoom);
        float cx = w / 2.0f;
        // ticks every 0.1x below 2x, 0.5x up to 10x, 1x beyond
        tickPaint.setAlpha(a);
        canvas.save();
        canvas.clipRect(rect);
        for(float z = (float)Math.floor(minZoom * 10) / 10.0f; z <= maxZoom + 0.001f; ) {
            float x = (float)(cx + (Math.log(Math.max(z, minZoom)) - lz) * pxPerLog);
            boolean major = false;
            for(float s : stops) {
                if( Math.abs(s - z) < 0.049f ) {
                    major = true;
                    break;
                }
            }
            if( Math.abs(z - Math.round(z)) < 0.01f && z >= 1.0f )
                major = true;
            float len = (major ? 9 : 5) * density;
            canvas.drawLine(x, cy - len, x, cy + len * 0.2f, tickPaint);
            if( major ) {
                textPaint.setColor(Color.argb(a, 255, 255, 255));
                textPaint.setTextSize(9.5f * getResources().getDisplayMetrics().scaledDensity);
                canvas.drawText(formatStop(z), x, cy + 15 * density, textPaint);
            }
            if( z < 2.0f )
                z = Math.round((z + 0.1f) * 10) / 10.0f;
            else if( z < 10.0f )
                z += 0.5f;
            else
                z += 1.0f;
        }
        canvas.restore();
        indicatorPaint.setAlpha(a);
        canvas.drawLine(cx, cy - 16 * density, cx, cy + 4 * density, indicatorPaint);
        // value bubble
        textPaint.setColor(Color.argb(a, 0xFF, 0xC5, 0x2E));
        textPaint.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
        canvas.drawText(formatZoom(zoom), cx, cy - 24 * density, textPaint);
    }
}
