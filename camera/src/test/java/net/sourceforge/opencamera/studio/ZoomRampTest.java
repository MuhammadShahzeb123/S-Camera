package net.sourceforge.opencamera.studio;

import org.junit.Test;
import static org.junit.Assert.*;

public class ZoomRampTest {
    @Test public void tapVisitsIntermediateZoomsWithoutOvershooting() {
        ZoomRamp ramp = new ZoomRamp();
        ramp.to(3.3f, 0);
        assertEquals(1f, ramp.value(0), 0f);
        float previous = 1f;
        for( int ms = 16; ms < 600; ms += 16 ) {
            float zoom = ramp.value(ms * 1_000_000L);
            assertTrue(zoom >= previous && zoom <= 3.3f);
            assertTrue("no sudden zoom step", zoom - previous < .3f);
            previous = zoom;
        }
        assertEquals(3.3f, previous, .0001f);
        assertFalse(ramp.isRunning(600_000_000L));
    }

    @Test public void reversalStartsAtTheDisplayedZoom() {
        ZoomRamp ramp = new ZoomRamp();
        ramp.to(10f, 0);
        float displayed = ramp.value(120_000_000L);
        assertTrue(displayed > 1f && displayed < 10f);
        ramp.to(.6f, 120_000_000L);
        assertEquals(displayed, ramp.value(120_000_000L), .00001f);
        assertTrue(ramp.value(160_000_000L) < displayed);
        assertEquals(.6f, ramp.value(800_000_000L), .00001f);
    }

    @Test public void repeatedTargetDoesNotRestartTheRamp() {
        ZoomRamp ramp = new ZoomRamp();
        ramp.to(3.3f, 0);
        for( int ms = 16; ms <= 600; ms += 16 ) ramp.to(3.3f, ms * 1_000_000L);
        assertEquals(3.3f, ramp.value(600_000_000L), .00001f);
        assertFalse(ramp.isRunning(600_000_000L));
    }

    @Test public void equalRatiosHaveEqualPerceptualSpeed() {
        ZoomRamp wide = new ZoomRamp(), tele = new ZoomRamp();
        tele.reset(3.3f);
        wide.to(2f, 0);
        tele.to(6.6f, 0);
        assertEquals(wide.value(100_000_000L), tele.value(100_000_000L) / 3.3f, .00001f);
        assertTrue(wide.value(100_000_000L) > 1f && wide.value(100_000_000L) < 2f);
    }

    @Test public void smallDragChangesSettlePromptly() {
        ZoomRamp ramp = new ZoomRamp();
        ramp.to(1.05f, 0);
        assertTrue(ramp.value(30_000_000L) > 1f && ramp.value(30_000_000L) < 1.05f);
        assertEquals(1.05f, ramp.value(160_000_000L), .00001f);
    }

    @Test public void continuousPinchTracksTheFingerBeforeRelease() {
        ZoomRamp ramp = new ZoomRamp();
        for( int ms = 0; ms <= 1000; ms += 10 ) {
            float finger = (float)Math.pow(3.3, ms / 1000.0);
            ramp.to(finger, ms * 1_000_000L);
            assertTrue("zoom must not overshoot the finger", ramp.value(ms * 1_000_000L) <= finger + .0001f);
        }
        assertTrue("continuous input must not defer most of the zoom until release", ramp.value(1_000_000_000L) >= 2.8f);
        assertEquals(3.3f, ramp.value(1_600_000_000L), .0001f);
    }

    @Test public void changingTargetsPreservesMotionWithoutOvershoot() {
        ZoomRamp ramp = new ZoomRamp();
        ramp.to(3.3f, 0);
        float before = ramp.value(199_000_000L);
        float current = ramp.value(200_000_000L);
        ramp.to(4f, 200_000_000L);
        assertEquals(current, ramp.value(200_000_000L), .00001f);
        float after = ramp.value(201_000_000L);
        assertEquals("do not stop on each new gesture event", current - before, after - current, .0002f);
        float previous = after;
        for( int ms = 210; ms <= 800; ms += 10 ) {
            float zoom = ramp.value(ms * 1_000_000L);
            assertTrue(zoom >= previous && zoom <= 4f);
            previous = zoom;
        }
    }

    @Test public void invalidZoomCannotPoisonTheAnimation() {
        for( float invalid : new float[]{0f, -1f, Float.NaN, Float.POSITIVE_INFINITY} ) {
            ZoomRamp ramp = new ZoomRamp();
            try { ramp.to(invalid, 0); fail("accepted invalid zoom " + invalid); }
            catch(IllegalArgumentException expected) { }
            assertEquals(1f, ramp.value(0), 0f);
        }
    }
}
