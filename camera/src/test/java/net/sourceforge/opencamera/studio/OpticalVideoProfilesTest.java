package net.sourceforge.opencamera.studio;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/** Relevant Camera2 data copied from the user's OnePlus LE2121 / Android 14 report. */
public class OpticalVideoProfilesTest {
    private static final int[][] MAIN = {{10, 10}, {15, 15}, {24, 24}, {4, 30}, {7, 30}, {15, 30}, {30, 30}};
    private static final int[][] UW = {{10, 10}, {15, 15}, {4, 24}, {24, 24}, {4, 30}, {7, 30}, {15, 30}, {30, 30}};
    private static final int[][] TELE = {{10, 10}, {15, 15}, {24, 24}, {4, 29}, {29, 29}};

    @Test public void reportedTelephotoMustNotBeDiscardedByNormalVideoCandidates() {
        boolean compatible = false;
        for( OpticalVideoEngine.Profile p : OpticalVideoEngine.candidateProfiles() )
            compatible |= reportedCameraSupports(TELE, 34_482_758L, false, p);
        assertTrue("Camera 3 supports normal video below 30 FPS; keep its 3.3x stop", compatible);
    }

    @Test public void reportedThreeLensArrayMustHaveASharedVideoProfile() {
        boolean common = false;
        for( OpticalVideoEngine.Profile p : OpticalVideoEngine.candidateProfiles() ) {
            common |= reportedCameraSupports(MAIN, 33_333_333L, true, p)
                    && reportedCameraSupports(UW, 33_333_333L, true, p)
                    && reportedCameraSupports(TELE, 34_482_758L, false, p);
        }
        assertTrue("Do not reduce the array to UW/main just to retain 30 FPS", common);
    }

    @Test public void reportedFpsEndpointsRetain29WithoutPretendingItIs30Or60() {
        List<OpticalVideoEngine.Profile> common = reportedCommonProfiles();
        assertTrue(common.contains(new OpticalVideoEngine.Profile(1920, 1080, 29)));
        assertTrue(common.contains(new OpticalVideoEngine.Profile(1920, 1080, 24)));
        for( OpticalVideoEngine.Profile p : common ) {
            assertTrue("telephoto has no 4K stream: " + p, p.width <= 1920);
            assertTrue("telephoto cannot sustain 30 or 60 FPS: " + p, p.fps <= 29);
        }
        assertFalse(OpticalVideoPolicy.supportsFrameDuration(34_482_758L, 30));
        assertTrue(OpticalVideoPolicy.supportsFrameDuration(34_482_758L, 29));
    }

    @Test public void unsupportedFourKFallsBackWhenOnlySharedProfilesAreAvailable() {
        assertEquals(new OpticalVideoEngine.Profile(1920, 1080, 29), OpticalVideoEngine.chooseProfile(
                new OpticalVideoEngine.Profile(3840, 2160, 30), reportedCommonProfiles()));
        // Every lens's own compatibility probe uses the same candidate universe as
        // the common-profile check, so camera 3 and its zoom stop are retained.
        List<OpticalVideoPolicy.Lens> retained = new ArrayList<>();
        int[][][] ranges = {UW, MAIN, TELE};
        String[] ids = {"2", "0", "3"};
        float[] zooms = {.6f, 1f, 3.3f};
        for( int i = 0; i < ranges.length; i++ ) {
            for( OpticalVideoEngine.Profile p : reportedCandidates() ) {
                if( reportedCameraSupports(ranges[i], i == 2 ? 34_482_758L : 33_333_333L, i != 2, p) ) {
                    retained.add(new OpticalVideoPolicy.Lens(ids[i], zooms[i], 10f));
                    break;
                }
            }
        }
        assertEquals(3, retained.size());
        assertEquals("2", OpticalVideoPolicy.select(retained, .99f).id);
        assertEquals("0", OpticalVideoPolicy.select(retained, 3.2f).id);
        assertEquals("3", OpticalVideoPolicy.select(retained, 3.3f).id);
        assertEquals("3", OpticalVideoPolicy.select(retained, 10f).id);
        assertEquals(33f, OpticalVideoPolicy.maxZoom(retained), .0001f);
    }

    @Test public void supportedPreferencesAndLowerResolutionsArePreserved() {
        List<OpticalVideoEngine.Profile> common = reportedCommonProfiles();
        OpticalVideoEngine.Profile cinema = new OpticalVideoEngine.Profile(1920, 1080, 24);
        assertEquals(cinema, OpticalVideoEngine.chooseProfile(cinema, common));
        assertEquals(new OpticalVideoEngine.Profile(1280, 720, 29), OpticalVideoEngine.chooseProfile(
                new OpticalVideoEngine.Profile(1280, 720, 30), common));
        assertEquals(new OpticalVideoEngine.Profile(1920, 1080, 29), OpticalVideoEngine.chooseProfile(null, common));
    }

    @Test public void fpsControlVisitsSupportedRatesThenReturnsTo29() {
        List<OpticalVideoEngine.Profile> common = reportedCommonProfiles();
        OpticalVideoEngine.Profile first = new OpticalVideoEngine.Profile(1280, 720, 29);
        OpticalVideoEngine.Profile current = first;
        Set<Integer> visited = new HashSet<>();
        for( int i = 0; i < 5; i++ ) {
            assertTrue("must not get stuck toggling unavailable 30/60 FPS", visited.add(current.fps));
            assertEquals(1280, current.width);
            assertEquals(720, current.height);
            assertTrue(common.contains(current));
            current = OpticalVideoEngine.nextFpsProfile(current, common);
        }
        assertEquals(new HashSet<>(Arrays.asList(29, 25, 24, 15, 10)), visited);
        assertEquals(first, current);
    }

    @Test public void fpsControlStillSupports30And60WhenAllLensesSupportThem() {
        OpticalVideoEngine.Profile thirty = new OpticalVideoEngine.Profile(1920, 1080, 30);
        OpticalVideoEngine.Profile sixty = new OpticalVideoEngine.Profile(1920, 1080, 60);
        List<OpticalVideoEngine.Profile> profiles = Arrays.asList(thirty, sixty);
        assertEquals(sixty, OpticalVideoEngine.nextFpsProfile(thirty, profiles));
        assertEquals(thirty, OpticalVideoEngine.nextFpsProfile(sixty, profiles));
        assertEquals(thirty, OpticalVideoEngine.nextFpsProfile(thirty, Arrays.asList(thirty)));
    }

    @Test public void fpsChangeFallsBackToClosestResolutionWhenNecessary() {
        OpticalVideoEngine.Profile fourK = new OpticalVideoEngine.Profile(3840, 2160, 30);
        OpticalVideoEngine.Profile hd = new OpticalVideoEngine.Profile(1920, 1080, 60);
        assertEquals(hd, OpticalVideoEngine.nextFpsProfile(fourK, Arrays.asList(fourK, hd)));
    }

    @Test public void candidateRatesRemainNormalSessionRatesWithoutDuplicates() {
        List<int[][]> ranges = Arrays.asList(MAIN, TELE, new int[][]{{120, 120}, {0, 0}, {30, 15}});
        List<OpticalVideoEngine.Profile> candidates = OpticalVideoEngine.candidateProfiles(ranges);
        assertEquals(candidates.size(), new HashSet<>(candidates).size());
        for( OpticalVideoEngine.Profile p : candidates ) assertTrue(p.fps > 0 && p.fps <= 60);
    }

    @Test public void telephotoDoesNotRemoveMainAndUltraWideFourK30() {
        Map<OpticalVideoEngine.Profile, List<String>> formats = reportedProfiles();
        OpticalVideoEngine.Profile fourK = new OpticalVideoEngine.Profile(3840, 2160, 30);
        assertEquals("retain 4K with its two capable cameras", Arrays.asList("2", "0"), formats.get(fourK));
        assertEquals(fourK, OpticalVideoEngine.chooseProfile(fourK, new ArrayList<>(formats.keySet())));
        assertFalse("the report does not expose normal 4K/60", formats.containsKey(new OpticalVideoEngine.Profile(3840, 2160, 60)));
    }

    @Test public void fullHd30UsesOnlyMainAndUltraWide() {
        assertEquals(Arrays.asList("2", "0"), reportedProfiles().get(new OpticalVideoEngine.Profile(1920, 1080, 30)));
    }

    @Test public void fullHd29RetainsAllThreeLensesAfterFourKSelection() {
        Map<OpticalVideoEngine.Profile, List<String>> formats = reportedProfiles();
        assertEquals(Arrays.asList("2", "0"), formats.get(new OpticalVideoEngine.Profile(3840, 2160, 30)));
        assertEquals("higher formats must not permanently prune telephoto", Arrays.asList("2", "0", "3"),
                formats.get(new OpticalVideoEngine.Profile(1920, 1080, 29)));
        assertEquals(Arrays.asList("2", "0", "3"), formats.get(new OpticalVideoEngine.Profile(1920, 1080, 24)));
    }

    @Test public void fourKZoomRemainsOnMainAboveTelephotoBoundary() {
        Map<OpticalVideoEngine.Profile, List<String>> formats = reportedProfiles();
        List<String> ids = formats.get(new OpticalVideoEngine.Profile(3840, 2160, 30));
        assertNotNull("4K must be offered", ids);
        List<OpticalVideoPolicy.Lens> lenses = new ArrayList<>();
        for( String id : ids ) lenses.add(new OpticalVideoPolicy.Lens(id, "2".equals(id) ? .6f : 1f, 10f));
        OpticalVideoPolicy.ZoomState zoom = new OpticalVideoPolicy.ZoomState(lenses, 3.3f);
        assertEquals("0", zoom.desiredLens().id);
        assertEquals(3.3f, OpticalVideoPolicy.digitalZoom(zoom.desiredLens(), zoom.desiredZoom()), .0001f);
        zoom.request(.6f);
        assertEquals("2", zoom.desiredLens().id);
        zoom.request(33f);
        assertEquals(10f, zoom.desiredZoom(), .0001f);
    }

    @Test public void encoderRejectionStillRemovesFourKEvenIfCamerasSupportIt() {
        Map<OpticalVideoEngine.Profile, List<String>> formats = OpticalVideoEngine.supportedProfiles(
                reportedCandidates(), Arrays.asList("2", "0", "3"), OpticalVideoProfilesTest::reportedLensSupports,
                profile -> profile.width <= 1920);
        assertFalse(formats.containsKey(new OpticalVideoEngine.Profile(3840, 2160, 30)));
        assertEquals(Arrays.asList("2", "0"), formats.get(new OpticalVideoEngine.Profile(1920, 1080, 30)));
    }

    @Test public void cameraWithoutAnOpeningRouteDoesNotBlockOtherCameras() {
        Map<OpticalVideoEngine.Profile, List<String>> formats = OpticalVideoEngine.supportedProfiles(
                reportedCandidates(), Arrays.asList("2", "0", "3"),
                (id, profile) -> !"2".equals(id) && reportedLensSupports(id, profile), profile -> true);
        assertEquals(Arrays.asList("0"), formats.get(new OpticalVideoEngine.Profile(3840, 2160, 30)));
        assertEquals(Arrays.asList("0", "3"), formats.get(new OpticalVideoEngine.Profile(1920, 1080, 29)));
    }

    @Test public void formatsWithNoUsableCameraAreNeverOffered() {
        assertTrue(OpticalVideoEngine.supportedProfiles(reportedCandidates(), Arrays.asList("0"),
                (id, profile) -> false, profile -> true).isEmpty());
    }

    private static Map<OpticalVideoEngine.Profile, List<String>> reportedProfiles() {
        return OpticalVideoEngine.supportedProfiles(reportedCandidates(), Arrays.asList("2", "0", "3"),
                OpticalVideoProfilesTest::reportedLensSupports, profile -> true);
    }

    private static boolean reportedLensSupports(String id, OpticalVideoEngine.Profile profile) {
        return reportedCameraSupports("3".equals(id) ? TELE : "2".equals(id) ? UW : MAIN,
                "3".equals(id) ? 34_482_758L : 33_333_333L, !"3".equals(id), profile);
    }

    private static List<OpticalVideoEngine.Profile> reportedCandidates() {
        return OpticalVideoEngine.candidateProfiles(Arrays.asList(MAIN, UW, TELE));
    }

    private static List<OpticalVideoEngine.Profile> reportedCommonProfiles() {
        List<OpticalVideoEngine.Profile> common = new ArrayList<>();
        for( OpticalVideoEngine.Profile p : reportedCandidates() ) {
            if( reportedCameraSupports(MAIN, 33_333_333L, true, p)
                    && reportedCameraSupports(UW, 33_333_333L, true, p)
                    && reportedCameraSupports(TELE, 34_482_758L, false, p) ) common.add(p);
        }
        return common;
    }

    private static boolean reportedCameraSupports(int[][] ranges, long duration, boolean fourK, OpticalVideoEngine.Profile p) {
        boolean size = (fourK && p.width == 3840 && p.height == 2160)
                || (p.width == 1920 && p.height == 1080)
                || (p.width == 1280 && p.height == 720)
                || (p.width == 640 && p.height == 480);
        return size && OpticalVideoPolicy.supportsFps(ranges, p.fps)
                && OpticalVideoPolicy.supportsFrameDuration(duration, p.fps);
    }
}
