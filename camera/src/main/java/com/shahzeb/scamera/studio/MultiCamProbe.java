package com.shahzeb.scamera.studio;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import com.shahzeb.scamera.op.CameraInventory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Actively tests which camera combinations this phone allows an app to stream at the same time:
 *  (1) physical streams of each logical multi-camera, (2) separately opened camera devices.
 *  Results are reported as text for the Camera info screen. */
public class MultiCamProbe {
    public interface Callback {
        void onDone(String result);
    }

    private final Context context;
    private final Callback callback;
    private final StringBuilder out = new StringBuilder();

    public MultiCamProbe(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    public void run() {
        new Thread(() -> {
            try {
                runTests();
            }
            catch(Throwable t) {
                out.append("Probe crashed: ").append(t).append('\n');
            }
            callback.onDone(out.toString());
        }, "MultiCamProbe").start();
    }

    private static class Stream {
        String deviceId;
        String physicalId; // may be null
        ImageReader reader;
        final AtomicInteger frames = new AtomicInteger();
    }

    private void runTests() throws InterruptedException {
        CameraInventory inv = CameraInventory.get(context);
        // 1. logical cameras with physical streams
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ) {
            for(CameraInventory.Lens logical : inv.logicals) {
                if( logical.physicalIds.length < 2 )
                    continue;
                List<Stream> streams = new ArrayList<>();
                for(String pid : logical.physicalIds) {
                    Stream s = new Stream();
                    s.deviceId = logical.id;
                    s.physicalId = pid;
                    streams.add(s);
                }
                out.append("Logical ").append(logical.id).append(" physical streams ").append(Arrays.toString(logical.physicalIds)).append(": ");
                out.append(test(streams)).append('\n');
            }
        }
        // 2. separately opened devices
        List<CameraInventory.Lens> back = new ArrayList<>();
        CameraInventory.Lens front = null;
        for(CameraInventory.Lens l : inv.sensors) {
            if( l.directId == null || l.hidden )
                continue;
            if( l.isBack() )
                back.add(l);
            else if( l.isFront() && front == null )
                front = l;
        }
        if( front != null && !back.isEmpty() ) {
            CameraInventory.Lens b = inv.defaultLens(android.hardware.camera2.CameraMetadata.LENS_FACING_BACK);
            if( b == null || b.directId == null )
                b = back.get(0);
            out.append("Devices ").append(b.directId).append(" (back) + ").append(front.directId).append(" (front): ");
            out.append(test(devices(b.directId, front.directId))).append('\n');
        }
        for(int i=0;i<back.size();i++) {
            for(int j=i+1;j<back.size();j++) {
                out.append("Devices ").append(back.get(i).directId).append(" + ").append(back.get(j).directId).append(" (back): ");
                out.append(test(devices(back.get(i).directId, back.get(j).directId))).append('\n');
            }
        }
        if( back.size() >= 3 ) {
            String [] ids = new String[back.size()];
            for(int i=0;i<ids.length;i++)
                ids[i] = back.get(i).directId;
            out.append("Devices ").append(Arrays.toString(ids)).append(" (all back): ");
            out.append(test(devices(ids))).append('\n');
        }
        if( out.length() == 0 )
            out.append("No multi-camera combinations to test (only one camera visible to apps).\n");
    }

    private static List<Stream> devices(String... ids) {
        List<Stream> list = new ArrayList<>();
        for(String id : ids) {
            Stream s = new Stream();
            s.deviceId = id;
            list.add(s);
        }
        return list;
    }

    @SuppressLint("MissingPermission")
    private String test(List<Stream> streams) throws InterruptedException {
        CameraManager manager = (CameraManager)context.getSystemService(Context.CAMERA_SERVICE);
        HandlerThread thread = new HandlerThread("probe");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        Executor executor = handler::post;
        List<String> deviceIds = new ArrayList<>();
        for(Stream s : streams) {
            if( !deviceIds.contains(s.deviceId) )
                deviceIds.add(s.deviceId);
        }
        final List<CameraDevice> opened = new ArrayList<>();
        final List<CameraCaptureSession> sessions = new ArrayList<>();
        final String [] error = {null};
        try {
            for(Stream s : streams) {
                try {
                    s.reader = ImageReader.newInstance(640, 480, ImageFormat.PRIVATE, 3);
                }
                catch(Exception e) {
                    s.reader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 3);
                }
                final Stream fs = s;
                s.reader.setOnImageAvailableListener(reader -> {
                    Image img = reader.acquireLatestImage();
                    if( img != null ) {
                        img.close();
                        fs.frames.incrementAndGet();
                    }
                }, handler);
            }
            // open all devices first (required order for concurrent streaming)
            for(String id : deviceIds) {
                final CountDownLatch latch = new CountDownLatch(1);
                final CameraDevice [] dev = {null};
                manager.openCamera(id, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(CameraDevice camera) {
                        dev[0] = camera;
                        latch.countDown();
                    }

                    @Override
                    public void onDisconnected(CameraDevice camera) {
                        if( error[0] == null )
                            error[0] = "camera " + camera.getId() + " disconnected (evicted - phone doesn't allow this combination)";
                        camera.close();
                        latch.countDown();
                    }

                    @Override
                    public void onError(CameraDevice camera, int code) {
                        if( error[0] == null )
                            error[0] = "camera " + camera.getId() + " error " + code + errorName(code);
                        camera.close();
                        latch.countDown();
                    }
                }, handler);
                if( !latch.await(4, TimeUnit.SECONDS) ) {
                    error[0] = "timeout opening camera " + id;
                }
                if( dev[0] == null || error[0] != null )
                    return "FAILED - " + error[0];
                opened.add(dev[0]);
            }
            // configure sessions
            for(CameraDevice device : opened) {
                List<OutputConfiguration> configs = new ArrayList<>();
                List<Stream> mine = new ArrayList<>();
                for(Stream s : streams) {
                    if( s.deviceId.equals(device.getId()) ) {
                        OutputConfiguration oc = new OutputConfiguration(s.reader.getSurface());
                        if( s.physicalId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P )
                            oc.setPhysicalCameraId(s.physicalId);
                        configs.add(oc);
                        mine.add(s);
                    }
                }
                final CountDownLatch latch = new CountDownLatch(1);
                final CameraCaptureSession [] sess = {null};
                CameraCaptureSession.StateCallback cb = new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession session) {
                        sess[0] = session;
                        latch.countDown();
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession session) {
                        error[0] = "session configuration failed on camera " + device.getId();
                        latch.countDown();
                    }
                };
                if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ) {
                    SessionConfiguration sc = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, configs, executor, cb);
                    if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ) {
                        try {
                            if( !device.isSessionConfigurationSupported(sc) ) {
                                return "FAILED - stream combination reported unsupported by camera " + device.getId();
                            }
                        }
                        catch(UnsupportedOperationException | IllegalArgumentException ignored) {
                            // query not implemented by this HAL - just try
                        }
                    }
                    device.createCaptureSession(sc);
                }
                else {
                    device.createCaptureSessionByOutputConfigurations(configs, cb, handler);
                }
                if( !latch.await(5, TimeUnit.SECONDS) && error[0] == null )
                    error[0] = "timeout configuring session on camera " + device.getId();
                if( sess[0] == null || error[0] != null )
                    return "FAILED - " + error[0];
                sessions.add(sess[0]);
                CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                for(Stream s : mine)
                    b.addTarget(s.reader.getSurface());
                sess[0].setRepeatingRequest(b.build(), null, handler);
            }
            // measure
            Thread.sleep(400);
            for(Stream s : streams)
                s.frames.set(0);
            long t0 = System.nanoTime();
            Thread.sleep(1500);
            double secs = (System.nanoTime() - t0) / 1.0e9;
            if( error[0] != null )
                return "FAILED - " + error[0];
            StringBuilder sb = new StringBuilder();
            boolean allOk = true;
            for(Stream s : streams) {
                double fps = s.frames.get() / secs;
                if( fps < 5 )
                    allOk = false;
                sb.append(String.format(Locale.US, "%s%s=%.0ffps ", s.deviceId, s.physicalId != null ? ("/" + s.physicalId) : "", fps));
            }
            return (allOk ? "OK - " : "PARTIAL - ") + sb.toString().trim();
        }
        catch(Throwable t) {
            return "FAILED - " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        finally {
            for(CameraCaptureSession s : sessions) {
                try {
                    s.close();
                }
                catch(Exception ignored) {
                }
            }
            for(CameraDevice d : opened) {
                try {
                    d.close();
                }
                catch(Exception ignored) {
                }
            }
            Thread.sleep(300);
            for(Stream s : streams) {
                if( s.reader != null )
                    s.reader.close();
            }
            thread.quitSafely();
        }
    }

    static String errorName(int code) {
        switch( code ) {
            case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
                return " (camera in use)";
            case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
                return " (too many cameras open)";
            case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
                return " (disabled by policy)";
            case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
                return " (device error)";
            case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:
                return " (service error)";
            default:
                return "";
        }
    }
}
