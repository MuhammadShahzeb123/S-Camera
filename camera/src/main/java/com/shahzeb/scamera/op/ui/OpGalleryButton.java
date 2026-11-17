package com.shahzeb.scamera.op.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/** Circular gallery thumbnail with a thin white border; pulses when a new item is saved. */
public class OpGalleryButton extends View {
    private Bitmap bitmap;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix shaderMatrix = new Matrix();
    private float pulse = 1.0f;
    private boolean saving;

    public OpGalleryButton(Context context) {
        this(context, null);
    }

    public OpGalleryButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setColor(Color.WHITE);
        borderPaint.setStrokeWidth(OpTheme.dpf(context, 1.6f));
        emptyPaint.setColor(0x33FFFFFF);
        setClickable(true);
        setContentDescription("Gallery");
    }

    public void setBitmap(Bitmap bitmap) {
        this.bitmap = bitmap;
        if( bitmap != null ) {
            bitmapPaint.setShader(new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        }
        else {
            bitmapPaint.setShader(null);
        }
        animatePulse();
        invalidate();
    }

    public void setSaving(boolean saving) {
        this.saving = saving;
        borderPaint.setColor(saving ? OpTheme.HIGHLIGHT : Color.WHITE);
        invalidate();
    }

    private void animatePulse() {
        ValueAnimator a = ValueAnimator.ofFloat(0.7f, 1.0f);
        a.setDuration(260);
        a.addUpdateListener(an -> {
            pulse = (float)an.getAnimatedValue();
            invalidate();
        });
        a.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2.0f;
        float cy = getHeight() / 2.0f;
        float r = Math.min(getWidth(), getHeight()) / 2.0f - borderPaint.getStrokeWidth();
        canvas.save();
        canvas.scale(pulse, pulse, cx, cy);
        if( bitmap != null && !bitmap.isRecycled() ) {
            float scale = Math.max(2*r / bitmap.getWidth(), 2*r / bitmap.getHeight());
            shaderMatrix.reset();
            shaderMatrix.setScale(scale, scale);
            shaderMatrix.postTranslate(cx - bitmap.getWidth()*scale/2.0f, cy - bitmap.getHeight()*scale/2.0f);
            bitmapPaint.getShader().setLocalMatrix(shaderMatrix);
            canvas.drawCircle(cx, cy, r, bitmapPaint);
        }
        else {
            canvas.drawCircle(cx, cy, r, emptyPaint);
        }
        canvas.drawCircle(cx, cy, r, borderPaint);
        canvas.restore();
    }
}
