package net.sourceforge.opencamera.studio;

import net.sourceforge.opencamera.op.CameraMode;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class LensSelectionTest {
    private LensPlanner.LensRef lens(String id, float zoom) {
        return new LensPlanner.LensRef(id, id, false, LensPlanner.FACING_BACK, zoom, false,
                Collections.emptyList(), Collections.emptyList(), 8f, id, 90, true);
    }

    @Test public void primaryFirstOpenOrderDoesNotChangeZoomSelection() {
        List<LensPlanner.LensRef> opened = Arrays.asList(lens("0", 1f), lens("3", 3.3f), lens("2", .6f));
        assertEquals("0", LensPlanner.lensForZoom(opened, 1f).id);
        assertEquals("3", LensPlanner.lensForZoom(opened, 3.3f).id);
        assertEquals("0", LensPlanner.lensForZoom(opened, 2f).id);
        assertEquals("2", LensPlanner.lensForZoom(opened, .5f).id);
    }

    @Test public void emptyCameraListHasNoSelection() {
        assertNull(LensPlanner.lensForZoom(Collections.emptyList(), 1f));
    }

    @Test public void aDigitalOnlyLogicalCameraDoesNotSwallowTheUltrawide() {
        List<LensPlanner.LensRef> sensors = Arrays.asList(lens("0", 1f), lens("2", .6f), lens("3", 3.3f));
        LensPlanner.LensRef logical = new LensPlanner.LensRef("4", "4", false, LensPlanner.FACING_BACK, 1f, false,
                Collections.emptyList(), Arrays.asList("0", "2", "3"), 10f, "Auto", 90, true);
        LensPlanner.Plan plan = LensPlanner.plan(CameraMode.FILM, sensors, Collections.singletonList(logical), false, .6f);
        assertFalse(plan.logicalZoom);
        assertEquals("2", plan.cameras.get(0).id);
        assertEquals("0", LensPlanner.lensForZoom(plan.availableLenses, 1f).id);
        assertEquals("3", LensPlanner.lensForZoom(plan.availableLenses, 3.3f).id);
    }

    @Test public void logicalCameraCanHandleStopsWhenItReportsTheFullRange() {
        List<LensPlanner.LensRef> sensors = Arrays.asList(lens("0", 1f), lens("2", .6f), lens("3", 3.3f));
        LensPlanner.LensRef logical = new LensPlanner.LensRef("4", "4", false, LensPlanner.FACING_BACK, 1f, false,
                Collections.emptyList(), Arrays.asList("0", "2", "3"), 10f, "Auto", 90, true, .5f);
        LensPlanner.Plan plan = LensPlanner.plan(CameraMode.FILM, sensors, Collections.singletonList(logical), false, .6f);
        assertTrue(plan.logicalZoom);
        assertEquals("4", plan.cameras.get(0).id);
    }

    @Test public void dualViewKeepsTheSelectedSelfieCameraPrimary() {
        LensPlanner.LensRef front = new LensPlanner.LensRef("1", "1", false, LensPlanner.FACING_FRONT, 1f, false,
                Collections.emptyList(), Collections.emptyList(), 8f, "Front", 270, false);
        LensPlanner.Plan plan = LensPlanner.plan(CameraMode.DUAL_VIEW,
                Arrays.asList(lens("0", 1f), front), Collections.emptyList(), true, 1f);
        assertEquals("1", plan.cameras.get(plan.primaryIndex).id);
        assertEquals(1, LensPlanner.indexForZoom(plan.cameras, 2f, LensPlanner.FACING_FRONT));
    }

    @Test public void dualViewCanReopenToAnUnopenedUltrawideStop() {
        List<LensPlanner.LensRef> lenses = Arrays.asList(lens("2", .6f), lens("0", 1f), lens("3", 3.3f));
        LensPlanner.Plan mainPlan = LensPlanner.plan(CameraMode.DUAL_VIEW, lenses, Collections.emptyList(), false, 1f);
        assertTrue(LensPlanner.needsLensSwitch(mainPlan, mainPlan.cameras, .6f, LensPlanner.FACING_BACK));
        assertFalse(LensPlanner.needsLensSwitch(mainPlan, mainPlan.cameras, 3.3f, LensPlanner.FACING_BACK));
        LensPlanner.Plan widePlan = LensPlanner.plan(CameraMode.DUAL_VIEW, lenses, Collections.emptyList(), false, .6f);
        assertEquals("2", widePlan.cameras.get(widePlan.primaryIndex).id);
        assertFalse(LensPlanner.needsLensSwitch(widePlan, widePlan.cameras, .6f, LensPlanner.FACING_BACK));
    }

    @Test public void aRearSensorNeverFallsBackToAnOppositeFacingLogicalCamera() {
        LensPlanner.LensRef frontLogical = new LensPlanner.LensRef("1", "1", false, LensPlanner.FACING_FRONT, 1f, false,
                Collections.emptyList(), Arrays.asList("10", "11"), 8f, "Front auto", 270, false);
        LensPlanner.Plan plan = LensPlanner.plan(CameraMode.PORTRAIT, Collections.singletonList(lens("0", 1f)),
                Collections.singletonList(frontLogical), false, 1f);
        assertEquals("0", plan.cameras.get(0).id);
        assertFalse(plan.logicalZoom);
        LensPlanner.Plan frontPlan = LensPlanner.plan(CameraMode.PORTRAIT, Collections.singletonList(lens("0", 1f)),
                Collections.singletonList(frontLogical), true, 1f);
        assertEquals("1", frontPlan.cameras.get(0).id);
        assertTrue(frontPlan.logicalZoom);
        assertEquals(LensPlanner.FACING_FRONT, frontPlan.availableLenses.get(0).facing);
    }
}
