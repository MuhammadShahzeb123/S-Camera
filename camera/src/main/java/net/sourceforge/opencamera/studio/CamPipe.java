package net.sourceforge.opencamera.studio;

import android.graphics.Rect;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Range;
import android.view.Surface;

import java.util.ArrayList;
import java.util.List;

/** One sensor stream inside a studio session. The {@link CameraDevice} is owned by the group,
 *  because several physical cameras can share one logical device. */
final class CamPipe {
    final LensPlanner.LensRef lens;
    CameraCharacteristics characteristics;
    Rect activeArray;
    float maxDigital = 8.0f;
    Range<Float> zoomRange;
    Range<Integer> evRange;
    int [] aeModes = new int[0];
    int [] afModes = new int[0];
    int [] oisModes = new int[0];
    Range<Integer> [] fpsRanges = new Range[0];
    int previewW = 1280;
    int previewH = 720;
    int jpegW = 1920;
    int jpegH = 1080;
    Surface previewSurface;
    ImageReader jpegReader;
    Surface jpegSurface;
    MediaRecorder recorder;
    Surface recorderSurface;
    StudioSaver.VideoTarget video;
    long recordStartNs;

    volatile long lastTimestampNs;
    volatile long lastFrameWallMs;
    volatile int afState = -1;
    volatile int frames;
    /** Index of this pipe's preview texture inside {@link GlCompositor}. */
    int glIndex;
    /** Camera2 SENSOR_ORIENTATION, degrees clockwise. */
    int sensorOrientation;
    boolean hasFlash;
    final java.util.ArrayDeque<String> jpegNames = new java.util.ArrayDeque<>();

    CamPipe(LensPlanner.LensRef lens) {
        this.lens = lens;
    }

    boolean front() {
        return lens.isFront();
    }

    void closeRecorder() {
        if( recorder != null ) {
            try {
                recorder.reset();
            }
            catch(Exception ignored) {
            }
            try {
                recorder.release();
            }
            catch(Exception ignored) {
            }
            recorder = null;
        }
        recorderSurface = null;
    }

    void closeOutputs() {
        closeRecorder();
        if( jpegReader != null ) {
            try {
                jpegReader.close();
            }
            catch(Exception ignored) {
            }
            jpegReader = null;
            jpegSurface = null;
        }
        // preview Surface is owned by GlCompositor
        previewSurface = null;
    }
}

/** Cameras opened as one CameraDevice. A logical multi-camera has several pipes. */
final class DeviceGroup {
    final String openId;
    final List<CamPipe> pipes = new ArrayList<>();
    CameraDevice device;
    CameraCaptureSession session;
    int syncType = -1;

    DeviceGroup(String openId) {
        this.openId = openId;
    }

    void closeDevice() {
        if( session != null ) {
            try {
                session.stopRepeating();
            }
            catch(Exception ignored) {
            }
            try {
                session.abortCaptures();
            }
            catch(Exception ignored) {
            }
            try {
                session.close();
            }
            catch(Exception ignored) {
            }
            session = null;
        }
        if( device != null ) {
            try {
                device.close();
            }
            catch(Exception ignored) {
            }
            device = null;
        }
    }

    String syncLabel() {
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ) {
            if( syncType == CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_CALIBRATED )
                return "calibrated";
            if( syncType == CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_APPROXIMATE )
                return "approximate";
        }
        return null;
    }
}
