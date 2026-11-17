package net.sourceforge.opencamera.studio;

/** Maps between the upright preview and a camera sensor.
 *  Angles are clockwise degrees in a y-down coordinate system, which is how camera
 *  sensor pixels and view pixels are both laid out. */
public final class Orient {
    private Orient() {
    }

    /** Clockwise rotation that turns a sensor image into an upright view image.
     *  {@code deviceDegrees} is the display rotation from {@link #toDisplayDegrees}. */
    public static int displayRotation(int sensorOrientation, int deviceDegrees, boolean front) {
        int sensor = ((sensorOrientation % 360) + 360) % 360;
        int device = ((deviceDegrees % 360) + 360) % 360;
        if( front )
            return (sensor + device) % 360;
        return (sensor - device + 360) % 360;
    }

    /** JPEG_ORIENTATION (also the video orientation hint). The capture buffer is not mirrored,
     *  unlike the front preview, so do not reverse the final sensor rotation. */
    public static int jpegOrientation(int sensorOrientation, int deviceDegrees, boolean front) {
        return displayRotation(sensorOrientation, deviceDegrees, front);
    }

    /** OrientationEventListener reports clockwise-from-portrait. Camera2 wants the display rotation,
     *  which is the other way. */
    public static int toDisplayDegrees(int orientationEventDegrees) {
        int d = ((orientationEventDegrees % 360) + 360) % 360;
        return (360 - d) % 360;
    }

    /** Sampling coordinates for TL, TR, BL, BR vertices, after a view-space crop.
     *  A null transform is an ordinary GL texture (e.g. the long-exposure framebuffer).
     *  Camera textures supply SurfaceTexture.getTransformMatrix(), which already includes
     *  sensor rotation, the producer crop, and the front-camera preview mirror. */
    public static float [] textureCoordinates(float [] transform, float u0, float v0, float u1, float v1) {
        // View vertices are top-first, but traditional GL texture coordinates start at
        // the bottom-left. Flip that ordering before applying the producer transform.
        float [] uv = {u0, v1, u1, v1, u0, v0, u1, v0};
        if( transform != null ) {
            for(int i = 0; i < uv.length; i += 2) {
                float u = uv[i];
                float v = uv[i + 1];
                uv[i] = transform[0] * u + transform[4] * v + transform[12];
                uv[i + 1] = transform[1] * u + transform[5] * v + transform[13];
            }
        }
        return uv;
    }

    /** Normalised view-space crop, accounting for the producer's orientation and crop. */
    public static float [] previewCrop(int bufferWidth, int bufferHeight, float paneAspect, float [] transform) {
        float camW = bufferWidth;
        float camH = bufferHeight;
        if( transform != null ) {
            // The transformed U/V basis vectors measure the displayed source edges in pixels.
            // This handles quarter-turns, mirrors and producer crops without guessing an angle.
            camW = (float)Math.hypot(transform[0] * bufferWidth, transform[1] * bufferHeight);
            camH = (float)Math.hypot(transform[4] * bufferWidth, transform[5] * bufferHeight);
        }
        if( camW <= 0.0f || camH <= 0.0f || paneAspect <= 0.0f )
            return new float[]{0.0f, 0.0f, 1.0f, 1.0f};
        float camAspect = camW / camH;
        float u0 = 0.0f, v0 = 0.0f, u1 = 1.0f, v1 = 1.0f;
        if( camAspect > paneAspect * 1.01f ) {
            float visible = paneAspect / camAspect;
            u0 = (1.0f - visible) / 2.0f;
            u1 = 1.0f - u0;
        }
        else if( paneAspect > camAspect * 1.01f ) {
            float visible = camAspect / paneAspect;
            v0 = (1.0f - visible) / 2.0f;
            v1 = 1.0f - v0;
        }
        return new float[]{u0, v0, u1, v1};
    }

    /** Inverse of {@link #displayRotation}: a normalised view point (y down) back into sensor space.
     *  Front previews are mirrored, so pass the mirrored view point through {@code front}. */
    public static float [] viewToSensor(float nx, float ny, int displayRotationDeg, boolean front) {
        float x = clamp01(nx);
        float y = clamp01(ny);
        if( front )
            x = 1.0f - x;
        return inverseRotate(x, y, ((displayRotationDeg % 360) + 360) % 360);
    }

    /** Undo a clockwise rotation of a y-down image. */
    public static float [] inverseRotate(float x, float y, int clockwiseDeg) {
        switch( clockwiseDeg ) {
            case 90:
                return new float[]{y, 1.0f - x};
            case 180:
                return new float[]{1.0f - x, 1.0f - y};
            case 270:
                return new float[]{1.0f - y, x};
            default:
                return new float[]{x, y};
        }
    }

    private static float clamp01(float v) {
        if( v < 0.0f )
            return 0.0f;
        if( v > 1.0f )
            return 1.0f;
        return v;
    }
}
