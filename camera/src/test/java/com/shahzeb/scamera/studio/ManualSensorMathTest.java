package com.shahzeb.scamera.studio;

import org.junit.Test;
import static org.junit.Assert.*;

public class ManualSensorMathTest {
    @Test public void exposureIntersectsRouteAndLensThenCapsShutterAtFrameInterval() {
        ManualSensorMath.ExposureLimits limits = new ManualSensorMath.ExposureLimits(
                50, 6400, 100, 3200, 1000, 1_000_000_000L,
                2000, 100_000_000L, 1_000_000_000L, 1_000_000_000L, 60);
        assertTrue(limits.supported);
        assertEquals(100, limits.minIso);
        assertEquals(3200, limits.maxIso);
        assertEquals(2000, limits.minTimeNs);
        assertEquals(16_666_666L, limits.maxTimeNs);
        assertEquals(16_666_666L, limits.clampTime(40_000_000L, 0));
        assertEquals(3200, limits.clampIso(6400, 0));
    }

    @Test public void emptyExposureIntersectionDoesNotInventAnEnabledManualRange() {
        assertFalse(new ManualSensorMath.ExposureLimits(50, 6400, 100, 3200,
                20_000_000L, 1_000_000_000L, 1000, 1_000_000_000L,
                1_000_000_000L, 1_000_000_000L, 60).supported);
        assertFalse(new ManualSensorMath.ExposureLimits(50, 6400, 100, 3200,
                1000, 1_000_000_000L, 1000, 1_000_000_000L,
                10_000_000L, 1_000_000_000L, 30).supported);
    }

    @Test public void enteringManualPreservesMeasuredPartnerAndClampsItOnHandoff() {
        ManualSensorMath.ExposureLimits limits = new ManualSensorMath.ExposureLimits(
                100, 1600, 50, 3200, 1000, 1_000_000_000L,
                1000, 1_000_000_000L, 1_000_000_000L, 1_000_000_000L, 30);
        assertEquals(800, limits.clampIso(0, 800));
        assertEquals(1600, limits.clampIso(0, 6400));
        assertEquals(8_000_000L, limits.clampTime(0, 8_000_000L));
        assertEquals(100, limits.clampIso(0, 0));
        assertEquals(16_666_666L, limits.clampTime(0, 0));
    }

    @Test public void evIsQuantizedInRealStopsAndCannotExceedTheAcceptedRange() {
        assertEquals(3, ManualSensorMath.evSteps(1f, 1f/3f, -6, 6));
        assertEquals(-6, ManualSensorMath.evSteps(-10f, 1f/3f, -6, 6));
        assertEquals(0, ManualSensorMath.evSteps(Float.NaN, 1f/3f, -6, 6));
    }

    @Test public void kelvinGainsAreFiniteLegalAndCoolerLightRequiresMoreRedCorrection() {
        float[] warm = ManualSensorMath.whiteBalanceGains(2000);
        float[] cool = ManualSensorMath.whiteBalanceGains(10000);
        assertTrue(cool[0] > warm[0]);
        assertTrue(warm[3] > cool[3]);
        for( int kelvin : new int[]{0, 2000, 3200, 5600, 10000, 50000} ) {
            float[] gains = ManualSensorMath.whiteBalanceGains(kelvin);
            assertEquals(gains[1], gains[2], 0f);
            for( float gain : gains ) assertTrue(gain >= 1f && gain <= 4f);
        }
    }
}
