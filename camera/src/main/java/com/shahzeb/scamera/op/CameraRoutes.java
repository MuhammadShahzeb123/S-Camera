package com.shahzeb.scamera.op;

import java.util.ArrayList;
import java.util.List;

/** Camera IDs passed to CameraManager and, when needed, OutputConfiguration. */
public final class CameraRoutes {
    private CameraRoutes() { }

    public static final class Route {
        public final String cameraId, physicalId;
        public Route(String cameraId, String physicalId) {
            this.cameraId = cameraId;
            this.physicalId = physicalId;
        }
    }

    public static List<Route> forLens(CameraInventory.Lens lens, CameraSelection preferred) {
        List<Route> routes = new ArrayList<>();
        // Match Photo's working route: open an advertised sensor directly before trying
        // a physical stream through a logical parent. These are distinct HAL paths.
        if( lens.directId != null && !lens.hidden ) routes.add(new Route(lens.directId, null));
        for( String parent : lens.logicalParents ) routes.add(new Route(parent, lens.id));
        // A selection passed from Photo may explicitly use a different physical parent.
        // Only prefer a pair advertised for THIS lens; never pin all zoom to the old camera.
        if( preferred != null ) {
            for( int i = 0; i < routes.size(); i++ ) {
                Route route = routes.get(i);
                if( route.cameraId.equals(preferred.cameraId) &&
                        (route.physicalId == null ? preferred.physicalId == null : route.physicalId.equals(preferred.physicalId)) ) {
                    routes.remove(i);
                    routes.add(0, route);
                    break;
                }
            }
        }
        return routes;
    }
}
