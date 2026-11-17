package com.shahzeb.scamera.studio;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.camera2.CameraMetadata;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.preference.PreferenceManager;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import com.shahzeb.scamera.MainActivity;
import com.shahzeb.scamera.PreferenceKeys;
import com.shahzeb.scamera.op.CameraInventory;
import com.shahzeb.scamera.op.CameraSelection;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Uses the emulator's actual rear/front devices as a synthetic three-lens rear array.
 *  Ultra-wide/main share a real device but have separate test-only sensor identities.
 *  This exercises device close/open during one real recording, not OnePlus optics. */
@RunWith(AndroidJUnit4.class)
public class OpticalVideoEngineInstrumentedTest {
    @Rule public GrantPermissionRule permissions = GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);

    @Test public void switchesCameraDevicesInsideOneAudioVideoFile() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(PreferenceKeys.IsVideoPreferenceKey, false)
                .putBoolean(PreferenceKeys.RecordAudioPreferenceKey, true)
                .putString(PreferenceKeys.VideoFormatPreferenceKey, "preference_video_output_format_default")
                .putString(PreferenceKeys.VideoBitratePreferenceKey, "default").commit();
        CameraInventory inventory = CameraInventory.get(context);
        CameraInventory.Lens back = inventory.defaultLens(CameraMetadata.LENS_FACING_BACK);
        CameraInventory.Lens front = inventory.defaultLens(CameraMetadata.LENS_FACING_FRONT);
        assumeTrue("two real camera devices are needed", back != null && front != null && back.directId != null && front.directId != null);
        CameraInventory.Lens wide = simulatedLens(back, .6f);
        CameraInventory.Lens mainLens = simulatedLens(back, 1f);
        mainLens.id = "test-main";
        CameraInventory.Lens tele = simulatedLens(front, 3.3f);
        // A parent alternative must not displace this advertised direct sensor. The
        // emulator has no real logical rear array; this is deliberately test-only metadata.
        tele.logicalParents.add(back.directId);
        Intent intent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("test_project", true);
        try(ActivityScenario<MainActivity> activity = ActivityScenario.launch(intent)) {
            AtomicReference<MainActivity> host = new AtomicReference<>();
            activity.onActivity(host::set);
            waitFor(() -> host.get().getPreview().getCameraController() != null, "foreground camera initialization", 20_000);
            activity.onActivity(value -> value.getPreview().onPause());
            waitFor(() -> host.get().getPreview().getCameraController() == null, "classic camera release", 10_000);

            HandlerThread drain = new HandlerThread("OpticalEnginePreviewDrain"); drain.start();
            ImageReader display = ImageReader.newInstance(320, 480, PixelFormat.RGBA_8888, 3);
            display.setOnImageAvailableListener(reader -> {
                try(Image image = reader.acquireLatestImage()) { }
            }, new Handler(drain.getLooper()));
            OpticalVideoRenderer renderer = new OpticalVideoRenderer(display.getSurface(), 320, 480);
            AtomicReference<OpticalVideoEngine.State> state = new AtomicReference<>();
            AtomicReference<Uri> saved = new AtomicReference<>();
            Queue<String> errors = new ConcurrentLinkedQueue<>();
            OpticalVideoEngine engine = new OpticalVideoEngine(context, renderer, new OpticalVideoEngine.Listener() {
                @Override public void onState(OpticalVideoEngine.State value) { state.set(value); }
                @Override public void onSaved(Uri uri, Bitmap thumbnail) {
                    assertNull("handoffs must not create a separate clip", saved.get());
                    saved.set(uri);
                }
                @Override public void onError(String message) { errors.add(message); }
            }, Arrays.asList(wide, mainLens, tele));
            try {
                engine.start(new CameraSelection(mainLens.directId, null, false, 1f), 640, 480, 29);
                waitFor(() -> state.get() != null && state.get().ready && !state.get().busy, "first lens", 20_000);
                assertEquals(mainLens.directId, state.get().cameraId);
                assertEquals("Main", state.get().lensLabel);
                assertEquals(.6f, state.get().minZoom, .0001f);
                assertEquals("record at the telephoto-compatible rate", 29, state.get().fps);
                assertArrayEquals(new float[]{.6f, 1f, 3.3f}, state.get().stops, .0001f);
                // Rebuild the eligible lens set when a format changes, then restore it.
                // This must close the previous camera before reopening at the new size.
                OpticalVideoEngine.Profile initialProfile = new OpticalVideoEngine.Profile(640, 480, 29);
                OpticalVideoEngine.Profile alternate = null;
                for( OpticalVideoEngine.Profile option : state.get().profiles ) {
                    if( !option.equals(initialProfile) ) { alternate = option; break; }
                }
                assertNotNull("a second supported format is needed", alternate);
                OpticalVideoEngine.Profile changedProfile = alternate;
                engine.setProfile(changedProfile);
                waitFor(() -> state.get().ready && !state.get().busy && state.get().width == changedProfile.width
                                && state.get().height == changedProfile.height && state.get().fps == changedProfile.fps,
                        "format change reopens a compatible lens: " + errors, 20_000);
                engine.setProfile(initialProfile);
                waitFor(() -> state.get().ready && !state.get().busy && state.get().width == 640
                                && state.get().height == 480 && state.get().fps == 29,
                        "restore original format: " + errors, 20_000);
                assertArrayEquals("all original lens stops return", new float[]{.6f, 1f, 3.3f}, state.get().stops, .0001f);
                engine.record(true, 0);
                waitFor(() -> state.get().recording, "recording start: " + errors, 10_000);
                Thread.sleep(1100);
                engine.setZoom(3.3f);
                waitFor(() -> state.get().ready && !state.get().busy && tele.directId.equals(state.get().cameraId)
                                && Math.abs(state.get().zoom - 3.3f) < .0001f,
                        "tele input handoff: " + errors, 20_000);
                assertNull("open telephoto directly, like Photo; do not substitute its parent", state.get().physicalId);
                assertEquals("Tele", state.get().lensLabel);
                assertTrue("encoder continues across the device switch", state.get().recording);
                assertEquals(29, state.get().fps);
                engine.setProfile(changedProfile);
                assertEquals(1f, state.get().digitalZoom, .0001f);
                assertNull("switching cannot publish/end the recording", saved.get());
                Thread.sleep(1100);
                engine.setZoom(6.6f);
                waitFor(() -> Math.abs(state.get().zoom - 6.6f) < .0001f, "digital crop on telephoto", 5000);
                assertEquals("recording format is fixed despite setProfile", 29, state.get().fps);
                assertEquals(640, state.get().width);
                assertEquals(480, state.get().height);
                assertEquals(tele.directId, state.get().cameraId);
                assertEquals(2f, state.get().digitalZoom, .0001f);
                engine.setZoom(3.2f);
                waitFor(() -> state.get().ready && !state.get().busy && "Main".equals(state.get().lensLabel)
                                && Math.abs(state.get().zoom - 3.2f) < .0001f,
                        "main below the telephoto threshold: " + errors, 20_000);
                assertEquals(mainLens.directId, state.get().cameraId);
                assertTrue(state.get().recording);
                engine.setZoom(.9f);
                waitFor(() -> state.get().ready && !state.get().busy && "UW".equals(state.get().lensLabel)
                                && Math.abs(state.get().zoom - .9f) < .0001f,
                        "ultra-wide below 1x: " + errors, 20_000);
                assertEquals(wide.directId, state.get().cameraId);
                assertEquals(1.5f, state.get().digitalZoom, .0001f);
                assertTrue(state.get().recording);
                engine.setZoom(1f);
                waitFor(() -> state.get().ready && !state.get().busy && "Main".equals(state.get().lensLabel)
                                && Math.abs(state.get().zoom - 1f) < .0001f,
                        "main exactly at 1x: " + errors, 20_000);
                engine.pauseToggle();
                waitFor(() -> state.get().paused, "recording pause", 5000);
                engine.setZoom(10f);
                waitFor(() -> state.get().ready && !state.get().busy && tele.directId.equals(state.get().cameraId),
                        "camera change while paused", 20_000);
                assertTrue(state.get().paused);
                engine.pauseToggle();
                waitFor(() -> !state.get().paused, "recording resume", 5000);
                Thread.sleep(1100);
                engine.stopRecording();
                waitFor(() -> saved.get() != null, "saved recording: " + errors, 15_000);
                assertTrue("no handoff/recorder errors: " + errors, errors.isEmpty());
                assertTracks(context, saved.get());
                android.util.Log.i("OpticalVideoTest", "One-file camera handoff saved " + saved.get());
            }
            finally {
                CountDownLatch closed = new CountDownLatch(1);
                engine.close(closed::countDown);
                assertTrue("camera and encoder cleanup", closed.await(15, TimeUnit.SECONDS));
                display.close(); drain.quitSafely(); drain.join(2000);
            }
        }
    }

    private static CameraInventory.Lens simulatedLens(CameraInventory.Lens real, float nativeZoom) {
        CameraInventory.Lens copy = new CameraInventory.Lens();
        copy.id = real.id; copy.directId = real.directId; copy.characteristics = real.characteristics;
        copy.facing = CameraMetadata.LENS_FACING_BACK; copy.zoomFactor = nativeZoom;
        copy.maxDigitalZoom = Math.max(4f, real.maxDigitalZoom); copy.sensorOrientation = real.sensorOrientation;
        return copy;
    }

    private static void waitFor(BooleanSupplier condition, String description, long timeoutMs) throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + timeoutMs;
        while( !condition.getAsBoolean() && android.os.SystemClock.elapsedRealtime() < until ) Thread.sleep(50);
        assertTrue(description + "\n" + StudioLog.dump(), condition.getAsBoolean());
    }

    private static void assertTracks(Context context, Uri uri) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, uri, null);
            assertEquals("one video and one uninterrupted audio track", 2, extractor.getTrackCount());
            long audioEnd = 0, videoEnd = 0;
            for(int track = 0; track < extractor.getTrackCount(); track++) {
                MediaFormat format = extractor.getTrackFormat(track);
                boolean video = format.getString(MediaFormat.KEY_MIME).startsWith("video/");
                extractor.selectTrack(track);
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                long previous = -1;
                int samples = 0;
                while( extractor.getSampleTime() >= 0 ) {
                    long time = extractor.getSampleTime();
                    assertTrue("monotonic " + format.getString(MediaFormat.KEY_MIME) + " timestamps", time > previous);
                    previous = time; samples++; extractor.advance();
                }
                assertTrue("continuous track contains multiple seconds", samples > 30);
                if( video ) videoEnd = previous; else audioEnd = previous;
                extractor.unselectTrack(track);
            }
            assertTrue("audio/video duration difference: " + Math.abs(audioEnd - videoEnd), Math.abs(audioEnd - videoEnd) < 750_000);
        }
        finally { extractor.release(); }
    }
}
