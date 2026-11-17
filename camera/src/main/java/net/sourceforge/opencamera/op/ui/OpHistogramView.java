package net.sourceforge.opencamera.op.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** Small luminance histogram (Pro modes). Accepts any number of bins. */
public class OpHistogramView extends View {
    private int [] bins;
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    public OpHistogramView(Context context) {
        this(context, null);
    }

    public OpHistogramView(Context context, AttributeSet attrs) {
        super(context, attrs);
        bgPaint.setColor(0x66000000);
        fillPaint.setColor(0xCCFFFFFF);
        setClickable(false);
    }

    public void setHistogram(int [] bins) {
        this.bins = bins;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float r = OpTheme.dpf(getContext(), 6);
        rect.set(0, 0, getWidth(), getHeight());
        canvas.drawRoundRect(rect, r, r, bgPaint);
        if( bins == null || bins.length == 0 )
            return;
        int max = 1;
        for(int b : bins)
            max = Math.max(max, b);
        float pad = OpTheme.dpf(getContext(), 4);
        float w = getWidth() - 2 * pad;
        float h = getHeight() - 2 * pad;
        path.reset();
        path.moveTo(pad, pad + h);
        for(int i=0;i<bins.length;i++) {
            float x = pad + w * i / (float)(bins.length - 1);
            // sqrt scale reads better than linear for camera histograms
            float v = (float)Math.sqrt(bins[i] / (double)max);
            path.lineTo(x, pad + h - v * h);
        }
        path.lineTo(pad + w, pad + h);
        path.close();
        canvas.drawPath(path, fillPaint);
    }
}
