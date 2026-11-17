package com.shahzeb.scamera.studio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Verifies the saved file, not just preview selection: two independent producers,
 *  a held frame between them, one recorder, and increasing video timestamps. */
@RunWith(AndroidJUnit4.class)
public class OpticalVideoRendererInstrumentedTest {
    @Test public void gradeReachesSavedFramesAndZebrasStayOnPreview() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File output = new File(context.getExternalFilesDir(null), "color-grade-test.mp4");
        HandlerThread drain = new HandlerThread("ColorPreviewDrain");
        drain.start();
        AtomicBoolean sawZebra = new AtomicBoolean();
        ImageReader display = ImageReader.newInstance(320, 480, PixelFormat.RGBA_8888, 3);
        display.setOnImageAvailableListener(reader -> {
            try(Image frame = reader.acquireLatestImage()) {
                if( frame == null ) return;
                Image.Plane plane = frame.getPlanes()[0];
                java.nio.ByteBuffer pixels = plane.getBuffer();
                for(int y = 60; y < 90; y++) for(int x = 60; x < 90; x++) {
                    int p = y * plane.getRowStride() + x * plane.getPixelStride();
                    int red = pixels.get(p) & 255, green = pixels.get(p + 1) & 255, blue = pixels.get(p + 2) & 255;
                    if( red > 220 && green > 160 && blue < 100 ) sawZebra.set(true);
                }
            }
        }, new Handler(drain.getLooper()));
        OpticalVideoRenderer renderer = new OpticalVideoRenderer(display.getSurface(), 320, 480);
        MediaRecorder recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(context) : new MediaRecorder();
        boolean started = false;
        try {
            CountDownLatch first = new CountDownLatch(1);
            OpticalVideoRenderer.Input input = renderer.createInput(640, 480, value -> first.countDown());
            paint(input, Color.RED);
            assertTrue(first.await(5, TimeUnit.SECONDS));
            renderer.activate(input, 1f);
            Bitmap neutral = capture(renderer);
            int neutralColor = neutral.getPixel(100, 100);
            assertTrue(Color.red(neutralColor) > 240 && Color.green(neutralColor) < 10);
            neutral.recycle();
            renderer.setColorGrade(ColorGrade.MONO);
            Bitmap mono = capture(renderer);
            assertGray(mono.getPixel(100, 100));
            mono.recycle();
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setVideoSize(320, 480);
            recorder.setVideoFrameRate(24);
            recorder.setVideoEncodingBitRate(1_500_000);
            recorder.setOutputFile(output.getAbsolutePath());
            recorder.prepare(); recorder.start(); started = true;
            renderer.startEncoder(recorder.getSurface(), 320, 480, 24);
            Thread.sleep(1200);
            renderer.setZebras(true);
            paint(input, Color.WHITE);
            Thread.sleep(1000);
            assertTrue("zebras are visible in preview", sawZebra.get());
            Bitmap still = capture(renderer);
            assertTrue("zebras must not enter a snapshot", Color.blue(still.getPixel(100, 100)) > 240);
            still.recycle();
            renderer.stopEncoder(); recorder.stop(); started = false;
            MediaMetadataRetriever saved = new MediaMetadataRetriever();
            try {
                saved.setDataSource(output.getAbsolutePath());
                Bitmap gradedFrame = Build.VERSION.SDK_INT >= 28 ? saved.getFrameAtIndex(10)
                        : saved.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST);
                assertNotNull(gradedFrame);
                assertGray(gradedFrame.getPixel(100, 100));
                gradedFrame.recycle();
                long duration = Long.parseLong(saved.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                int frameCount = Build.VERSION.SDK_INT >= 28
                        ? Integer.parseInt(saved.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)) : 0;
                Bitmap whiteFrame = Build.VERSION.SDK_INT >= 28 ? saved.getFrameAtIndex(frameCount - 2)
                        : saved.getFrameAtTime((duration - 50) * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                assertNotNull(whiteFrame);
                for(int y = 60; y < 90; y++) for(int x = 60; x < 90; x++)
                    assertTrue("preview zebras must not burn into video: " + Integer.toHexString(whiteFrame.getPixel(x, y)), Color.blue(whiteFrame.getPixel(x, y)) > 220);
                whiteFrame.recycle();
            }
            finally { saved.release(); }
        }
        finally {
            renderer.stopEncoder();
            if( started ) { try { recorder.stop(); } catch(RuntimeException ignored) { } }
            recorder.release(); renderer.close(); display.close();
            drain.quitSafely(); drain.join(2000);
        }
    }

    private static Bitmap capture(OpticalVideoRenderer renderer) throws Exception {
        AtomicReference<Bitmap> bitmap = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        renderer.capture(value -> { bitmap.set(value); ready.countDown(); });
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        assertNotNull(bitmap.get());
        return bitmap.get();
    }

    private static void assertGray(int color) {
        assertTrue("grade must desaturate actual output", Math.abs(Color.red(color) - Color.green(color)) < 8
                && Math.abs(Color.green(color) - Color.blue(color)) < 8);
        assertTrue("graded red retains luminance", Color.red(color) > 20 && Color.red(color) < 100);
    }

    @Test public void oneRecordingSurvivesInputReplacement() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File output = new File(context.getExternalFilesDir(null), "optical-renderer-" + System.currentTimeMillis() + ".mp4");
        HandlerThread drain = new HandlerThread("TestPreviewDrain");
        drain.start();
        ImageReader display = ImageReader.newInstance(320, 480, PixelFormat.RGBA_8888, 3);
        display.setOnImageAvailableListener(reader -> {
            try(Image image = reader.acquireLatestImage()) { /* Drain display buffers. */ }
        }, new Handler(drain.getLooper()));
        OpticalVideoRenderer renderer = new OpticalVideoRenderer(display.getSurface(), 320, 480);
        MediaRecorder recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(context) : new MediaRecorder();
        boolean started = false;
        try {
            CountDownLatch firstRed = new CountDownLatch(1);
            OpticalVideoRenderer.Input red = renderer.createInput(640, 480, input -> firstRed.countDown());
            paint(red, Color.RED);
            assertTrue("first camera input", firstRed.await(5, TimeUnit.SECONDS));
            renderer.activate(red, 1f);
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setVideoSize(320, 480);
            recorder.setVideoFrameRate(24);
            recorder.setVideoEncodingBitRate(1_500_000);
            recorder.setOutputFile(output.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            started = true;
            renderer.startEncoder(recorder.getSurface(), 320, 480, 24);
            Thread.sleep(1100);
            // The old device has closed. Its last frame must remain until the next input is ready.
            renderer.releaseInput(red);
            Thread.sleep(650);
            CountDownLatch firstBlue = new CountDownLatch(1);
            OpticalVideoRenderer.Input blue = renderer.createInput(640, 480, input -> firstBlue.countDown());
            paint(blue, Color.BLUE);
            assertTrue("replacement camera input", firstBlue.await(5, TimeUnit.SECONDS));
            renderer.activate(blue, 10f / 3.3f);
            Thread.sleep(1500);
            renderer.stopEncoder();
            recorder.stop();
            started = false;

            List<Long> timestamps = new ArrayList<>();
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(output.getAbsolutePath());
                assertEquals("one continuous video track", 1, extractor.getTrackCount());
                MediaFormat format = extractor.getTrackFormat(0);
                assertEquals(320, format.getInteger(MediaFormat.KEY_WIDTH));
                assertEquals(480, format.getInteger(MediaFormat.KEY_HEIGHT));
                extractor.selectTrack(0);
                java.util.Set<Long> uniqueTimes = new java.util.HashSet<>();
                int frames = 0;
                while( extractor.getSampleTime() >= 0 ) {
                    long timestamp = extractor.getSampleTime();
                    // Hardware AVC encoders may emit valid B frames in decode order.
                    assertTrue("video must not repeat a presentation timestamp", uniqueTimes.add(timestamp));
                    timestamps.add(timestamp);
                    frames++;
                    extractor.advance();
                }
                assertTrue("frames must continue while the new camera opens", frames >= 35);
            }
            finally { extractor.release(); }
            java.util.Collections.sort(timestamps);
            MediaMetadataRetriever reader = new MediaMetadataRetriever();
            try {
                reader.setDataSource(output.getAbsolutePath());
                long duration = Long.parseLong(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                assertTrue("one file spans both inputs", duration >= 2800);
                Bitmap redFrame = frameAt(reader, 500_000, timestamps);
                Bitmap heldFrame = frameAt(reader, 1_400_000, timestamps);
                Bitmap blueFrame = frameAt(reader, (duration - 200) * 1000, timestamps);
                assertColor(redFrame, true);
                assertColor(heldFrame, true);
                assertColor(blueFrame, false);
                int blendedFrames = 0;
                for( long timestamp : timestamps ) {
                    if( timestamp < 1_200_000 || timestamp > 2_500_000 ) continue;
                    Bitmap frame = frameAt(reader, timestamp, timestamps);
                    assertNotNull(frame);
                    int color = frame.getPixel(frame.getWidth() / 2, frame.getHeight() / 2);
                    assertTrue("handoff must not insert a black frame", Color.red(color) + Color.blue(color) > 150);
                    if( Color.red(color) > 35 && Color.blue(color) > 35 ) blendedFrames++;
                    frame.recycle();
                }
                assertTrue("saved video must blend across several frames instead of cutting", blendedFrames >= 2);
            }
            finally { reader.release(); }
        }
        finally {
            renderer.stopEncoder();
            if( started ) { try { recorder.stop(); } catch(RuntimeException ignored) { } }
            recorder.release();
            renderer.close();
            display.close();
            drain.quitSafely();
            drain.join(2000);
        }
    }

    private static void paint(OpticalVideoRenderer.Input input, int color) {
        Canvas canvas = input.surface.lockCanvas(null);
        canvas.drawColor(color);
        input.surface.unlockCanvasAndPost(canvas);
    }

    private static Bitmap frameAt(MediaMetadataRetriever reader, long time, List<Long> timestamps) {
        if( Build.VERSION.SDK_INT < 28 ) return reader.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST);
        int nearest = 0;
        for(int i = 1; i < timestamps.size(); i++)
            if( Math.abs(timestamps.get(i) - time) < Math.abs(timestamps.get(nearest) - time) ) nearest = i;
        // Indexed retrieval avoids the handset's timestamp-seek implementation,
        // which repeatedly returns the first keyframe for this surface recording.
        return reader.getFrameAtIndex(nearest);
    }

    private static void assertColor(Bitmap frame, boolean red) {
        assertNotNull(frame);
        int color = frame.getPixel(frame.getWidth() / 2, frame.getHeight() / 2);
        assertTrue("saved frame must come from the selected input: " + Integer.toHexString(color),
                red ? Color.red(color) > 170 && Color.blue(color) < 80 : Color.blue(color) > 170 && Color.red(color) < 80);
        frame.recycle();
    }
}
