package net.sourceforge.opencamera.test;

import net.sourceforge.opencamera.cameracontroller.VideoFrameRates;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class VideoFrameRatesTest {
    @Test public void nanosecondRoundingDoesNotHide60Fps() {
        List<int[]> rates = VideoFrameRates.forDuration(Collections.singletonList(new int[]{15, 60}), 16_666_667L);
        assertArrayEquals(new int[]{15, 60}, rates.get(0));
    }

    @Test public void slowerOutputRestricts60FpsToSupportedResolutions() {
        List<int[]> rates = VideoFrameRates.forDuration(Collections.singletonList(new int[]{15, 60}), 33_333_334L);
        assertArrayEquals(new int[]{15, 30}, rates.get(0));
    }

    @Test public void fastOutputDoesNotInventAeSupport() {
        List<int[]> rates = VideoFrameRates.forDuration(Collections.singletonList(new int[]{15, 30}), 16_666_667L);
        assertArrayEquals(new int[]{15, 30}, rates.get(0));
    }

    @Test public void unknownDurationStillRequiresAnAdvertisedRange() {
        assertArrayEquals(new int[]{15, 30}, VideoFrameRates.forDuration(
                Collections.singletonList(new int[]{15, 30}), 0L).get(0));
        assertTrue(VideoFrameRates.forDuration(Collections.emptyList(), 0L).isEmpty());
    }

    @Test public void disjointAeRangesRemainDisjoint() {
        List<int[]> rates = VideoFrameRates.forDuration(Arrays.asList(new int[]{15, 15}, new int[]{30, 30}, new int[]{60, 60}), 16_666_667L);
        assertEquals(3, rates.size());
        assertArrayEquals(new int[]{15, 15}, rates.get(0));
        assertArrayEquals(new int[]{30, 30}, rates.get(1));
        assertArrayEquals(new int[]{60, 60}, rates.get(2));
    }
}
