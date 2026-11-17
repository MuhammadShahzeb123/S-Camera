package net.sourceforge.opencamera.studio;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class OpticalVideoMathTest {
    private static final float EPSILON = 0.00001f;
    private static final float[] BACK_90 = {
            0, -1, 0, 0, -1, 0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1
    };

    @Test
    public void telephotoAtTenTimesUsesCenteredDigitalCropAfterOpticalSelection() {
        // A 3.3x lens needs a 0.33-wide crop for 10x total zoom.
        assertArrayEquals(new float[]{0.335f, 0.665f, 0.335f, 0.335f,
                        0.665f, 0.665f, 0.665f, 0.335f},
                OpticalVideoMath.textureCoordinates(1920, 1080, 1080, 1920,
                        BACK_90, 10.0f / 3.3f), EPSILON);
    }

    @Test
    public void aspectFillAndDigitalCropStayInDisplayedAxes() {
        // Portrait camera fills a square by cropping its height, then zooms 2x.
        assertArrayEquals(new float[]{0.359375f, 0.75f, 0.359375f, 0.25f,
                        0.640625f, 0.75f, 0.640625f, 0.25f},
                OpticalVideoMath.textureCoordinates(1920, 1080, 1000, 1000,
                        BACK_90, 2.0f), EPSILON);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nonFiniteZoomIsRejectedBeforeItReachesGl() {
        OpticalVideoMath.textureCoordinates(1920, 1080, 1080, 1920, BACK_90, Float.NaN);
    }

    @Test
    public void frameClockKeepsTheOriginalTimelineThroughCameraGaps() {
        OpticalVideoMath.FrameClock clock = new OpticalVideoMath.FrameClock(30, 9_000_000_000L);
        assertEquals(9_000_000_000L, clock.takeFrame(9_000_000_000L));
        assertEquals(9_033_333_333L, clock.nextFrameTime());
        assertEquals(9_033_333_333L, clock.takeFrame(9_034_000_000L));
        // A stalled GL thread drops overdue slots, without compressing real time.
        assertEquals(11_100_000_000L, clock.takeFrame(11_101_000_000L));
        assertEquals(11_133_333_333L, clock.nextFrameTime());
    }

    @Test
    public void fractionalFrameIntervalsDoNotAccumulateRoundingDrift() {
        for( int fps : new int[]{24, 25, 29, 30, 60} ) {
            OpticalVideoMath.FrameClock clock = new OpticalVideoMath.FrameClock(fps, 0);
            for( int i = 0; i < fps * 60; i++ )
                clock.takeFrame(clock.nextFrameTime());
            assertEquals("one minute at " + fps + " FPS", 60_000_000_000L, clock.nextFrameTime());
        }
    }

    @Test
    public void frameClockAllowsNegativeNanoTimeOrigins() {
        OpticalVideoMath.FrameClock clock = new OpticalVideoMath.FrameClock(60, -2_000_000_000L);
        assertEquals(-2_000_000_000L, clock.takeFrame(-2_000_000_000L));
        assertEquals(-1_983_333_334L, clock.nextFrameTime());
    }

    @Test(expected = IllegalArgumentException.class)
    public void zeroFrameRateIsRejected() {
        new OpticalVideoMath.FrameClock(0, 100L);
    }
}
