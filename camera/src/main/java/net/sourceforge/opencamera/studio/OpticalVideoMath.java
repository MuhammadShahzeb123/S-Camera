package net.sourceforge.opencamera.studio;

/** Pure crop and recorder-clock policy shared by the EGL renderer and unit tests. */
final class OpticalVideoMath {
    private OpticalVideoMath() {
    }

    static float[] textureCoordinates(int inputWidth, int inputHeight, int outputWidth,
                                      int outputHeight, float[] transform, float digitalZoom) {
        checkZoom(digitalZoom);
        if( inputWidth <= 0 || inputHeight <= 0 || outputWidth <= 0 || outputHeight <= 0 )
            throw new IllegalArgumentException("Invalid video dimensions");
        float[] crop = Orient.previewCrop(inputWidth, inputHeight,
                outputWidth / (float)outputHeight, transform);
        float halfWidth = (crop[2] - crop[0]) / (2.0f * digitalZoom);
        float halfHeight = (crop[3] - crop[1]) / (2.0f * digitalZoom);
        return Orient.textureCoordinates(transform, 0.5f - halfWidth, 0.5f - halfHeight,
                0.5f + halfWidth, 0.5f + halfHeight);
    }

    static void checkZoom(float digitalZoom) {
        if( Float.isNaN(digitalZoom) || Float.isInfinite(digitalZoom) || digitalZoom < 1.0f )
            throw new IllegalArgumentException("Digital zoom must be finite and at least 1");
    }

    /** A constant-rate, monotonic clock that never uses an individual camera's timestamp. */
    static final class FrameClock {
        private static final long SECOND = 1_000_000_000L;
        private final int fps;
        private final long originNanos;
        private long nextFrame;

        FrameClock(int fps, long originNanos) {
            if( fps <= 0 || fps > SECOND )
                throw new IllegalArgumentException("Invalid video frame rate: " + fps);
            this.fps = fps;
            this.originNanos = originNanos;
        }

        long nextFrameTime() {
            return originNanos + (nextFrame / fps) * SECOND + (nextFrame % fps) * SECOND / fps;
        }

        /** Consume the newest due frame slot; a delayed renderer must not burst old frames. */
        long takeFrame(long nowNanos) {
            long elapsed = Math.max(0, nowNanos - originNanos);
            long dueFrame = (elapsed / SECOND) * fps
                    + (((elapsed % SECOND) + 1) * fps - 1) / SECOND;
            nextFrame = Math.max(nextFrame, dueFrame);
            long timestamp = nextFrameTime();
            nextFrame++;
            return timestamp;
        }
    }
}
