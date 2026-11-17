package net.sourceforge.opencamera.studio;

import net.sourceforge.opencamera.op.CameraMode;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Planner, timestamp sync and the OnePlus 9 Pro lens labels. No device required. */
public class StudioLogicTest {
    @Test
    public void onePlus9ProLabels() {
        assertEquals(0.6f, LensPlanner.knownZoomFactor("OnePlus", "lemonadep", "LE2125", "2"), 0.001f);
        assertEquals(1.0f, LensPlanner.knownZoomFactor("OnePlus", "lemonadep", "LE2125", "0"), 0.001f);
        assertEquals(3.3f, LensPlanner.knownZoomFactor("OnePlus", "lemonadep", "OnePlus 9 Pro", "3"), 0.001f);
        assertEquals(1.0f, LensPlanner.knownZoomFactor("OnePlus", "lemonadep", "LE2123", "1"), 0.001f);
        assertNull(LensPlanner.knownZoomFactor("OnePlus", "lemonadep", "LE2125", "9"));
        assertNull(LensPlanner.knownZoomFactor("Google", "panther", "Pixel 7", "2"));
    }

    @Test
    public void multiCamPlansAllBackLensesWideFirst() {
        LensPlanner.Plan plan = LensPlanner.plan(CameraMode.MULTI_CAM, onePlus(), Collections.emptyList(), false, 1.0f);
        assertEquals(3, plan.cameras.size());
        assertEquals("2", plan.cameras.get(0).id);
        assertEquals("0", plan.cameras.get(1).id);
        assertEquals("3", plan.cameras.get(2).id);
        assertEquals(1, plan.primaryIndex);
        assertEquals(0.6f, plan.stops[0], 0.001f);
        assertEquals(1.0f, plan.stops[1], 0.001f);
        assertEquals(3.3f, plan.stops[2], 0.001f);
        assertEquals("3", LensPlanner.lensForZoom(plan.cameras, 3.3f).id);
        assertEquals("0", LensPlanner.lensForZoom(plan.cameras, 2.0f).id);
        assertEquals(2.0f, LensPlanner.digitalRatio(plan.cameras.get(1), 2.0f), 0.001f);
        assertEquals(1.0f, LensPlanner.digitalRatio(plan.cameras.get(2), 3.3f), 0.001f);
    }

    @Test
    public void dualViewPairsFrontAndBack() {
        LensPlanner.Plan plan = LensPlanner.plan(CameraMode.DUAL_VIEW, onePlus(), Collections.emptyList(), true, 1.0f);
        assertEquals(2, plan.cameras.size());
        assertTrue(plan.cameras.get(0).isBack());
        assertTrue(plan.cameras.get(1).isFront());
    }

    @Test
    public void openAttemptsKeepThePrimary() {
        List<int[]> attempts = LensPlanner.openAttempts(3, 1);
        assertEquals(4, attempts.size());
        assertArrayEquals(new int[]{1, 2, 0}, attempts.get(0));
        assertArrayEquals(new int[]{1, 2}, attempts.get(1));
        assertArrayEquals(new int[]{1, 0}, attempts.get(2));
        assertArrayEquals(new int[]{1}, attempts.get(3));
        for(int [] attempt : attempts)
            assertEquals(1, attempt[0]);
    }

    @Test
    public void logicalParentCoversTheBackCameras() {
        LensPlanner.LensRef logical = new LensPlanner.LensRef("4", "4", false, LensPlanner.FACING_BACK, 1.0f, false,
                Collections.emptyList(), Arrays.asList("0", "2", "3"), 10.0f, "Auto", 90, true);
        String parent = LensPlanner.commonLogicalParent(onePlusBacks(), Collections.singletonList(logical));
        assertEquals("4", parent);
        assertNull(LensPlanner.commonLogicalParent(onePlusBacks(), Collections.emptyList()));
    }

    @Test
    public void framesAlignInsideTheTolerance() {
        Map<String, List<Long>> series = new LinkedHashMap<>();
        series.put("0", Arrays.asList(0L, 33_000_000L));
        series.put("2", Arrays.asList(2_000_000L, 34_000_000L));
        series.put("3", Arrays.asList(1_000_000L, 40_000_000L));
        List<FrameSync.Group> groups = FrameSync.align(series, "0", 8_000_000L);
        assertEquals(2, groups.size());
        assertTrue(groups.get(0).complete(3));
        assertEquals(2_000_000L, groups.get(0).skewNs);
        assertTrue(groups.get(1).complete(3));
        assertEquals(7_000_000L, groups.get(1).skewNs);

        List<FrameSync.Group> tight = FrameSync.align(series, "0", 5_000_000L);
        assertTrue(tight.get(0).complete(3));
        assertEquals(2, tight.get(1).timestamps.size());
    }

    @Test
    public void liveSkewRejectsAStaleCamera() {
        Map<String, Long> latest = new LinkedHashMap<>();
        latest.put("0", 100L);
        latest.put("2", 5_000_000L);
        latest.put("3", 8_000_000L);
        assertEquals(8_000_000L - 100L, FrameSync.liveSkewNs(latest, 20_000_000L));
        assertEquals(-1L, FrameSync.liveSkewNs(latest, 1_000_000L));
        latest.remove("2");
        latest.remove("3");
        assertEquals(-1L, FrameSync.liveSkewNs(latest, 20_000_000L));
    }

    @Test
    public void orientationRoundTrip() {
        assertEquals(90, Orient.displayRotation(90, 0, false));
        assertEquals(90, Orient.jpegOrientation(90, 0, false));
        assertEquals(90, Orient.jpegOrientation(90, 0, true));
        assertEquals(270, Orient.toDisplayDegrees(90));
        float [] sensor = Orient.inverseRotate(1.0f, 0.5f, 90);
        assertEquals(0.5f, sensor[0], 0.001f);
        assertEquals(0.0f, sensor[1], 0.001f);
        float [] back = Orient.viewToSensor(0.5f, 0.5f, 90, false);
        assertEquals(0.5f, back[0], 0.001f);
        assertEquals(0.5f, back[1], 0.001f);
    }

    @Test
    public void sizePickPrefers16by9UnderTheCap() {
        int [][] sizes = {{4000, 3000}, {1920, 1080}, {1280, 720}, {640, 480}};
        SizePick.Sz preview = SizePick.choose(sizes, 1280, 16.0 / 9.0);
        assertEquals(1280, preview.w);
        assertEquals(720, preview.h);
        SizePick.Sz still = SizePick.choose(sizes, 4000, 4.0 / 3.0);
        assertEquals(4000, still.w);
        assertEquals(3000, still.h);
    }

    private static List<LensPlanner.LensRef> onePlus() {
        List<LensPlanner.LensRef> list = new ArrayList<>(onePlusBacks());
        list.add(lens("1", LensPlanner.FACING_FRONT, 1.0f, "Front"));
        return list;
    }

    private static List<LensPlanner.LensRef> onePlusBacks() {
        return new ArrayList<>(Arrays.asList(
                lens("0", LensPlanner.FACING_BACK, 1.0f, "Main"),
                lens("2", LensPlanner.FACING_BACK, 0.6f, "UW"),
                lens("3", LensPlanner.FACING_BACK, 3.3f, "Tele")
        ));
    }

    private static LensPlanner.LensRef lens(String id, int facing, float zoom, String label) {
        return new LensPlanner.LensRef(id, id, false, facing, zoom, false,
                Collections.emptyList(), Collections.emptyList(), 8.0f, label, facing == LensPlanner.FACING_FRONT ? 270 : 90, true);
    }
}
