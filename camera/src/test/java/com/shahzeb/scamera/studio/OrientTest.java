package com.shahzeb.scamera.studio;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/** The preview uses the camera producer transform; capture follows the phone orientation. */
public class OrientTest {
    private static final float EPSILON = 0.00001f;

    @Test
    public void captureRotationUsesPhoneOrientationWithoutPreviewMirroring() {
        int [] phoneAngles = {0, 90, 180, 270};
        int [] backRotation = {90, 180, 270, 0};
        int [] frontRotation = {270, 180, 90, 0};
        for(int i = 0; i < phoneAngles.length; i++) {
            int displayDegrees = Orient.toDisplayDegrees(phoneAngles[i]);
            assertEquals("back at " + phoneAngles[i], backRotation[i],
                    Orient.jpegOrientation(90, displayDegrees, false));
            assertEquals("front at " + phoneAngles[i], frontRotation[i],
                    Orient.jpegOrientation(270, displayDegrees, true));
        }
    }

    @Test
    public void portraitFocusUnmirrorsBeforeMappingToSensor() {
        assertArrayEquals(new float[]{0.31f, 0.88f},
                Orient.viewToSensor(0.12f, 0.31f, Orient.displayRotation(90, 0, false), false), EPSILON);
        assertArrayEquals(new float[]{0.69f, 0.88f},
                Orient.viewToSensor(0.12f, 0.31f, Orient.displayRotation(270, 0, true), true), EPSILON);
    }

    @Test
    public void framebufferTextureKeepsTopAtVOne() {
        assertArrayEquals(new float[]{0.13f, 0.83f, 0.91f, 0.83f, 0.13f, 0.27f, 0.91f, 0.27f},
                Orient.textureCoordinates(null, 0.13f, 0.27f, 0.91f, 0.83f), EPSILON);
    }

    @Test
    public void cameraCornersApplyProducerRotationAndMirrorExactlyOnce() {
        // Literal SurfaceTexture fixtures include the GL Y flip, sensor rotation, and
        // front-camera mirror. Four distinct corner positions expose double transforms.
        float [][] transforms = {
                matrix(1, 0, 0, -1, 0, 1),       // back, sensor 0
                matrix(0, -1, -1, 0, 1, 1),      // back, sensor 90
                matrix(-1, 0, 0, 1, 1, 0),       // back, sensor 180
                matrix(0, 1, 1, 0, 0, 0),        // back, sensor 270
                matrix(-1, 0, 0, -1, 1, 1),      // front, sensor 0
                matrix(0, 1, -1, 0, 1, 0),       // front, sensor 90
                matrix(1, 0, 0, 1, 0, 0),        // front, sensor 180
                matrix(0, -1, 1, 0, 0, 1)        // front, sensor 270
        };
        float [][] expected = {
                {0, 0, 1, 0, 0, 1, 1, 1},
                {0, 1, 0, 0, 1, 1, 1, 0},
                {1, 1, 0, 1, 1, 0, 0, 0},
                {1, 0, 1, 1, 0, 0, 0, 1},
                {1, 0, 0, 0, 1, 1, 0, 1},
                {0, 0, 0, 1, 1, 0, 1, 1},
                {0, 1, 1, 1, 0, 0, 1, 0},
                {1, 1, 1, 0, 0, 1, 0, 0}
        };
        for(int i = 0; i < transforms.length; i++)
            assertArrayEquals("producer transform " + i, expected[i],
                    Orient.textureCoordinates(transforms[i], 0, 0, 1, 1), EPSILON);
    }

    @Test
    public void viewCropPrecedesRotatedProducerCrop() {
        // Off-centre producer crop plus a quarter-turn; app crop remains in view axes.
        float [] producer = matrix(0, 0.5f, 0.7f, 0, 0.2f, 0.1f);
        assertArrayEquals(new float[]{0.781f, 0.165f, 0.781f, 0.555f,
                        0.389f, 0.165f, 0.389f, 0.555f},
                Orient.textureCoordinates(producer, 0.13f, 0.27f, 0.91f, 0.83f), EPSILON);
    }

    @Test
    public void frontProducerCropIsNotMirroredAgain() {
        float [] producer = matrix(0, -0.5f, 0.7f, 0, 0.2f, 0.7f);
        assertArrayEquals(new float[]{0.781f, 0.635f, 0.781f, 0.245f,
                        0.389f, 0.635f, 0.389f, 0.245f},
                Orient.textureCoordinates(producer, 0.13f, 0.27f, 0.91f, 0.83f), EPSILON);
    }

    @Test
    public void centerCropUsesTheOrientedBufferAspect() {
        float [] back90 = matrix(0, -1, -1, 0, 1, 1);
        float [] front270 = matrix(0, -1, 1, 0, 0, 1);
        assertArrayEquals(new float[]{0, 0, 1, 1},
                Orient.previewCrop(1920, 1080, 9.0f / 16.0f, back90), EPSILON);
        assertArrayEquals(new float[]{0, 0.21875f, 1, 0.78125f},
                Orient.previewCrop(1920, 1080, 1, front270), EPSILON);
        assertArrayEquals(new float[]{0.21875f, 0, 0.78125f, 1},
                Orient.previewCrop(1920, 1080, 1, null), EPSILON);
    }

    @Test
    public void centerCropIncludesProducerCropDimensions() {
        float [] croppedBack90 = matrix(0, -0.5f, -1, 0, 1, 0.75f);
        assertArrayEquals(new float[]{0, 0.25f, 1, 0.75f},
                Orient.previewCrop(1600, 900, 9.0f / 16.0f, croppedBack90), EPSILON);
    }

    private static float [] matrix(float xx, float xy, float yx, float yy, float tx, float ty) {
        return new float[]{xx, xy, 0, 0, yx, yy, 0, 0, 0, 0, 1, 0, tx, ty, 0, 1};
    }
}
