package com.shahzeb.scamera.studio;

import org.junit.Test;
import static org.junit.Assert.*;

public class ColorGradeTest {
    @Test public void neutralPreservesBlackWhiteAndColor() {
        for(float[] rgb : new float[][]{{0,0,0}, {1,1,1}, {.1f,.5f,.9f}, {1,0,0}})
            assertArrayEquals(rgb, ColorGrade.NEUTRAL.apply(rgb[0], rgb[1], rgb[2]), .00001f);
    }

    @Test public void zeroSaturationProducesEqualChannels() {
        float[] rgb = new ColorGrade(0, 1, 0, 0, 0).apply(.8f, .3f, .1f);
        assertEquals(rgb[0], rgb[1], .00001f);
        assertEquals(rgb[1], rgb[2], .00001f);
    }

    @Test public void exposureUsesStopsAndClipsSafely() {
        assertArrayEquals(new float[]{.4f,.6f,1}, new ColorGrade(1,1,1,0,0).apply(.2f,.3f,.8f), .00001f);
    }

    @Test public void invalidAndExtremeControlsCannotReachShader() {
        ColorGrade grade = new ColorGrade(Float.NaN, Float.POSITIVE_INFINITY, -5, 7, -7);
        assertEquals(0, grade.exposureEv, 0);
        assertEquals(1, grade.contrast, 0);
        assertEquals(0, grade.saturation, 0);
        assertEquals(1, grade.warmth, 0);
        assertEquals(-1, grade.tint, 0);
        for(float c : grade.apply(1, .4f, 0)) assertTrue(c >= 0 && c <= 1);
    }
}
