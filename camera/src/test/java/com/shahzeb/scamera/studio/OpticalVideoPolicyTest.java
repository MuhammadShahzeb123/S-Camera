package com.shahzeb.scamera.studio;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class OpticalVideoPolicyTest {
    private final OpticalVideoPolicy.Lens uw = new OpticalVideoPolicy.Lens("uw", .6f, 10f);
    private final OpticalVideoPolicy.Lens main = new OpticalVideoPolicy.Lens("main", 1f, 10f);
    private final OpticalVideoPolicy.Lens tele = new OpticalVideoPolicy.Lens("tele", 3.3f, 10f);
    private final List<OpticalVideoPolicy.Lens> lenses = Arrays.asList(main, tele, uw);

    @Test public void boundariesChooseLongestOpticalLensBeforeDigitalCrop() {
        assertSame(uw, OpticalVideoPolicy.select(lenses, .6f));
        assertSame(uw, OpticalVideoPolicy.select(lenses, .99f));
        assertSame(main, OpticalVideoPolicy.select(lenses, 1f));
        assertSame(main, OpticalVideoPolicy.select(lenses, 3.29f));
        assertSame(tele, OpticalVideoPolicy.select(lenses, 3.3f));
        assertSame(tele, OpticalVideoPolicy.select(lenses, 10f));
        assertEquals(3.030303f, OpticalVideoPolicy.digitalZoom(tele, 10f), .00001f);
    }

    @Test public void exactThresholdsDoNotSwitchBeforeNativeZoom() {
        assertSame(uw, OpticalVideoPolicy.select(lenses, Math.nextDown(1f)));
        assertSame(main, OpticalVideoPolicy.select(lenses, 1f));
        assertSame(main, OpticalVideoPolicy.select(lenses, Math.nextUp(1f)));
        assertSame(main, OpticalVideoPolicy.select(lenses, Math.nextDown(3.3f)));
        assertSame(tele, OpticalVideoPolicy.select(lenses, 3.3f));
        assertSame(tele, OpticalVideoPolicy.select(lenses, Math.nextUp(3.3f)));
    }

    @Test public void continuousZoomInAndOutUsesEveryLensAndRetainsRequestedZoom() {
        OpticalVideoPolicy.ZoomState state = new OpticalVideoPolicy.ZoomState(lenses, .6f);
        // Exercise every hundredth across the fixture's supported range, in both directions.
        for( int direction : new int[]{1, -1} ) {
            for( int step = 0; step <= 3240; step++ ) {
                int hundredths = direction == 1 ? 60 + step : 3300 - step;
                float requested = hundredths / 100f;
                OpticalVideoPolicy.Lens expected = hundredths < 100 ? uw : hundredths < 330 ? main : tele;
                String at = "zoom=" + requested + ", direction=" + direction;
                state.request(requested);
                assertSame(at, expected, state.desiredLens());
                assertTrue(at, state.commit(expected));
                assertSame(at, expected, state.activeLens());
                assertEquals(at, requested, state.activeZoom(), 0f);
                float nativeZoom = hundredths < 100 ? .6f : hundredths < 330 ? 1f : 3.3f;
                assertEquals(at, requested / nativeZoom,
                        OpticalVideoPolicy.digitalZoom(state.activeLens(), state.activeZoom()), .00001f);
            }
        }
    }

    @Test public void globalMaximumIncludesTeleInsteadOfUltrawideSixTimesCap() {
        assertEquals(33f, OpticalVideoPolicy.maxZoom(lenses), .0001f);
    }

    @Test public void aeRangesAndRoundedFrameDurationsMustBothAllowTheRate() {
        assertTrue(OpticalVideoPolicy.supportsFrameDuration(16_666_667L, 60));
        assertTrue(OpticalVideoPolicy.supportsFrameDuration(33_366_667L, 30));
        assertFalse(OpticalVideoPolicy.supportsFrameDuration(33_333_333L, 60));
        assertFalse(OpticalVideoPolicy.supportsFrameDuration(35_000_000L, 30));
        assertTrue(OpticalVideoPolicy.supportsFps(new int[][]{{15, 30}, {30, 60}}, 60));
        assertFalse(OpticalVideoPolicy.supportsFps(new int[][]{{15, 30}}, 60));
        assertFalse(OpticalVideoPolicy.supportsFps(null, 30));
    }

    @Test public void staleFirstFrameCannotOverwriteANewerZoomRequest() {
        OpticalVideoPolicy.ZoomState state = new OpticalVideoPolicy.ZoomState(lenses, .6f);
        assertTrue(state.commit(uw));
        state.request(3.3f);
        state.request(2f);
        assertFalse(state.commit(tele));
        assertSame(uw, state.activeLens());
        assertEquals(.6f, state.activeZoom(), .001f);
        assertTrue(state.commit(main));
        assertEquals(2f, state.activeZoom(), .001f);
    }

    @Test public void newZoomOnTheSamePendingLensUsesTheLatestDigitalRatio() {
        OpticalVideoPolicy.ZoomState state = new OpticalVideoPolicy.ZoomState(lenses, 1f);
        assertTrue(state.commit(main));
        state.request(3.3f);
        state.request(10f);
        assertTrue(state.commit(tele));
        assertEquals(10f, state.activeZoom(), .001f);
    }

    @Test public void failedTargetRollsBackToTheLastConfirmedLensAndZoom() {
        OpticalVideoPolicy.ZoomState state = new OpticalVideoPolicy.ZoomState(lenses, 2f);
        assertTrue(state.commit(main));
        state.request(10f);
        assertTrue(state.rollback(tele));
        assertSame(main, state.desiredLens());
        assertEquals(2f, state.desiredZoom(), .001f);
    }

    @Test public void failureOfAnObsoleteTargetDoesNotEraseTheLatestRequest() {
        OpticalVideoPolicy.ZoomState state = new OpticalVideoPolicy.ZoomState(lenses, 1f);
        assertTrue(state.commit(main));
        state.request(3.3f);
        state.request(.6f);
        assertFalse(state.rollback(tele));
        assertSame(uw, state.desiredLens());
    }
}
