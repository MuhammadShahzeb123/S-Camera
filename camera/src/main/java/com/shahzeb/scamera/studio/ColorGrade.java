package com.shahzeb.scamera.studio;

/** Display-referred RGB grading. This does not claim RAW, log or additional sensor range. */
public final class ColorGrade {
    public static final ColorGrade NEUTRAL = new ColorGrade(0, 1, 1, 0, 0);
    public static final ColorGrade CINEMA = new ColorGrade(-.1f, 1.12f, .85f, .12f, -.04f);
    public static final ColorGrade SOFT = new ColorGrade(0, .85f, .9f, .03f, 0);
    public static final ColorGrade MONO = new ColorGrade(0, 1.1f, 0, 0, 0);
    public final float exposureEv, contrast, saturation, warmth, tint;

    public ColorGrade(float exposureEv, float contrast, float saturation, float warmth, float tint) {
        this.exposureEv = bounded(exposureEv, -3, 3, 0);
        this.contrast = bounded(contrast, 0, 2, 1);
        this.saturation = bounded(saturation, 0, 2, 1);
        this.warmth = bounded(warmth, -1, 1, 0);
        this.tint = bounded(tint, -1, 1, 0);
    }

    public float gain() { return (float)Math.pow(2, exposureEv); }

    /** CPU reference matching the GPU shader, for deterministic regression coverage. */
    public float[] apply(float red, float green, float blue) {
        float r = ((red * gain() * (1 + warmth * .2f) * (1 + tint * .1f)) - .5f) * contrast + .5f;
        float g = ((green * gain() * (1 - tint * .2f)) - .5f) * contrast + .5f;
        float b = ((blue * gain() * (1 - warmth * .2f) * (1 + tint * .1f)) - .5f) * contrast + .5f;
        float luma = .2126f * r + .7152f * g + .0722f * b;
        return new float[]{clip(luma + (r - luma) * saturation),
                clip(luma + (g - luma) * saturation), clip(luma + (b - luma) * saturation)};
    }

    private static float bounded(float value, float min, float max, float fallback) {
        return Float.isNaN(value) || Float.isInfinite(value) ? fallback : Math.max(min, Math.min(max, value));
    }
    private static float clip(float value) { return Math.max(0, Math.min(1, value)); }
}
