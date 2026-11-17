package com.shahzeb.scamera.op;

import android.content.Intent;

/** The camera selection shared by the classic and Studio activities. IDs are native
 *  Camera2 strings, never indices into an activity's cached camera list. */
public final class CameraSelection {
    private static final String CAMERA = "op_selected_camera";
    private static final String PHYSICAL = "op_selected_physical_camera";
    private static final String FRONT = "op_selected_front";
    private static final String ZOOM = "op_selected_zoom";

    public final String cameraId;
    public final String physicalId;
    public final boolean front;
    public final float zoom;

    public CameraSelection(String cameraId, String physicalId, boolean front, float zoom) {
        this.cameraId = cameraId;
        this.physicalId = physicalId;
        this.front = front;
        this.zoom = Float.isNaN(zoom) || Float.isInfinite(zoom) || zoom <= 0f ? 1f : zoom;
    }

    public void writeTo(Intent intent) {
        intent.putExtra(CAMERA, cameraId);
        intent.putExtra(PHYSICAL, physicalId);
        intent.putExtra(FRONT, front);
        intent.putExtra(ZOOM, zoom);
    }

    public static CameraSelection readFrom(Intent intent) {
        if( intent == null || !intent.hasExtra(FRONT) )
            return null;
        return new CameraSelection(intent.getStringExtra(CAMERA), intent.getStringExtra(PHYSICAL),
                intent.getBooleanExtra(FRONT, false), intent.getFloatExtra(ZOOM, 1f));
    }
}
