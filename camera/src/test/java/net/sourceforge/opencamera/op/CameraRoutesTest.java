package net.sourceforge.opencamera.op;

import net.sourceforge.opencamera.studio.OpticalVideoPolicy;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class CameraRoutesTest {
    private static CameraInventory.Lens lens(String id, float nativeZoom, String... parents) {
        CameraInventory.Lens lens = new CameraInventory.Lens();
        lens.id = id;
        lens.directId = id;
        lens.zoomFactor = nativeZoom;
        lens.maxDigitalZoom = 10f;
        lens.logicalParents.addAll(Arrays.asList(parents));
        return lens;
    }

    @Test public void telephotoUsesItsOwnAdvertisedCameraBeforeItsLogicalParent() {
        CameraInventory.Lens tele = lens("3", 3.3f, "0");
        List<CameraRoutes.Route> routes = CameraRoutes.forLens(tele, null);
        assertEquals(2, routes.size());
        assertRoute("3", null, routes.get(0));
        assertRoute("0", "3", routes.get(1));
    }

    @Test public void explicitPhotoPhysicalSelectionIsRetainedWhenItIsValid() {
        CameraInventory.Lens tele = lens("3", 3.3f, "0", "9");
        CameraSelection selectedInPhoto = new CameraSelection("9", "3", false, 3.3f);
        List<CameraRoutes.Route> routes = CameraRoutes.forLens(tele, selectedInPhoto);
        assertEquals(3, routes.size());
        assertRoute("9", "3", routes.get(0));
        assertRoute("3", null, routes.get(1));
        assertRoute("0", "3", routes.get(2));
    }

    @Test public void aMainCameraSelectionCannotReplaceTheTelephotoRoute() {
        List<CameraRoutes.Route> routes = CameraRoutes.forLens(lens("3", 3.3f, "0"),
                new CameraSelection("0", null, false, 1f));
        assertRoute("3", null, routes.get(0));
        assertRoute("0", "3", routes.get(1));
    }

    @Test public void physicalOnlyLensKeepsItsAdvertisedParentAndDoesNotOpenHiddenId() {
        CameraInventory.Lens tele = lens("3", 3.3f, "0");
        tele.hidden = true;
        List<CameraRoutes.Route> routes = CameraRoutes.forLens(tele,
                new CameraSelection("3", null, false, 3.3f));
        assertEquals(1, routes.size());
        assertRoute("0", "3", routes.get(0));
        tele.logicalParents.clear();
        assertTrue(CameraRoutes.forLens(tele, null).isEmpty());
    }

    @Test public void zoomingAcrossAllThreeLensesResolvesTheActualCameraIdsInBothDirections() {
        CameraInventory.Lens uw = lens("2", .6f, "0");
        CameraInventory.Lens main = lens("0", 1f);
        CameraInventory.Lens tele = lens("3", 3.3f, "0");
        List<CameraInventory.Lens> inventory = Arrays.asList(main, tele, uw);
        List<OpticalVideoPolicy.Lens> policy = Arrays.asList(
                new OpticalVideoPolicy.Lens("0", 1f, 10f),
                new OpticalVideoPolicy.Lens("3", 3.3f, 10f),
                new OpticalVideoPolicy.Lens("2", .6f, 10f));
        float[] zooms = {.6f, .9f, 1f, 3.2f, 3.3f, 4f, 10f, 3.3f, 3.2f, 1f, .9f, .6f};
        String[] expected = {"2", "2", "0", "0", "3", "3", "3", "3", "0", "0", "2", "2"};
        CameraSelection previous = new CameraSelection("0", null, false, 1f);
        for( int i = 0; i < zooms.length; i++ ) {
            String selectedId = OpticalVideoPolicy.select(policy, zooms[i]).id;
            CameraRoutes.Route selected = null;
            for( CameraInventory.Lens candidate : inventory ) {
                if( candidate.id.equals(selectedId) ) selected = CameraRoutes.forLens(candidate, previous).get(0);
            }
            assertNotNull(selected);
            assertRoute(expected[i], null, selected);
            previous = new CameraSelection(selected.cameraId, selected.physicalId, false, zooms[i]);
        }
    }

    private static void assertRoute(String cameraId, String physicalId, CameraRoutes.Route actual) {
        assertEquals("CameraManager.openCamera ID", cameraId, actual.cameraId);
        assertEquals("OutputConfiguration physical ID", physicalId, actual.physicalId);
    }
}
