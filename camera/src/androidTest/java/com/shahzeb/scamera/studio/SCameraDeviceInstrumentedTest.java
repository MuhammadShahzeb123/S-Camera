package com.shahzeb.scamera.studio;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraMetadata;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import com.shahzeb.scamera.OpClassicController;
import com.shahzeb.scamera.PreferenceKeys;
import com.shahzeb.scamera.op.CameraInventory;
import com.shahzeb.scamera.op.CameraSelection;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/** Acceptance test using the handset's real lens inventory, not simulated lenses. */
@RunWith(AndroidJUnit4.class)
public class SCameraDeviceInstrumentedTest {
    @Rule public GrantPermissionRule permissions = GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);

    @Test public void fourKRecordsMainAndUltraWideWithoutStopping() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CameraInventory.Lens main = CameraInventory.get(context).defaultLens(CameraMetadata.LENS_FACING_BACK);
        assertNotNull(main);
        Intent intent = new Intent(context, OpticalVideoActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(OpClassicController.EXTRA_MODE, "PRO_VIDEO")
                .putExtra(OpticalVideoActivity.EXTRA_REFRESH_PROFILE, true)
                .putExtra(OpticalVideoActivity.EXTRA_WIDTH, 3840).putExtra(OpticalVideoActivity.EXTRA_HEIGHT, 2160)
                .putExtra(OpticalVideoActivity.EXTRA_FPS, 30);
        new CameraSelection(main.directId, null, false, 1f).writeTo(intent);
        try(ActivityScenario<OpticalVideoActivity> scenario = ActivityScenario.launch(intent)) {
            waitFor(() -> state(scenario) != null && state(scenario).ready && !state(scenario).busy, "4K camera", 25_000);
            assertEquals(3840, state(scenario).width); assertEquals(30, state(scenario).fps);
            assertEquals("4K supports main and ultra-wide on this firmware", 2, state(scenario).stops.length);
            OpticalVideoEngine engine = engine(scenario);
            engine.record(true, 0);
            waitFor(() -> state(scenario).recording && state(scenario).recordMs > 700, "4K recording", 10_000);
            for(float stop : state(scenario).stops) {
                engine.selectLens(stop);
                waitFor(() -> state(scenario).ready && !state(scenario).busy && Math.abs(state(scenario).zoom - stop) < .02,
                        "4K physical lens " + stop, 20_000);
                assertTrue(state(scenario).recording); assertNull(saved(scenario));
                Thread.sleep(900);
            }
            engine.stopRecording();
            waitFor(() -> saved(scenario) != null && !state(scenario).recording, "4K saved file", 15_000);
            verifyTracks(context, saved(scenario));
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(context, saved(scenario), null);
                boolean found = false;
                for(int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    if( format.getString(MediaFormat.KEY_MIME).startsWith("video/") ) {
                        assertEquals(2160, format.getInteger(MediaFormat.KEY_WIDTH));
                        assertEquals(3840, format.getInteger(MediaFormat.KEY_HEIGHT)); found = true;
                    }
                }
                assertTrue(found);
            }
            finally { extractor.release(); }
            // Leave the initial format usable with all rear colour lenses.
            engine.selectLens(3.3f);
            waitFor(() -> state(scenario).ready && !state(scenario).busy && state(scenario).fps == 29, "all-lens format restored", 20_000);
        }
    }

    @Test public void proVideoControlsReachSensorsAndRearLensesShareOneFile() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(PreferenceKeys.RecordAudioPreferenceKey, true)
                .putString(PreferenceKeys.VideoFormatPreferenceKey, "preference_video_output_format_default")
                .putString(PreferenceKeys.VideoBitratePreferenceKey, "default").commit();
        CameraInventory.Lens main = CameraInventory.get(context).defaultLens(CameraMetadata.LENS_FACING_BACK);
        assertNotNull(main);
        Intent intent = new Intent(context, OpticalVideoActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(OpClassicController.EXTRA_MODE, "PRO_VIDEO")
                .putExtra(OpticalVideoActivity.EXTRA_REFRESH_PROFILE, true)
                .putExtra(OpticalVideoActivity.EXTRA_WIDTH, 1920).putExtra(OpticalVideoActivity.EXTRA_HEIGHT, 1080)
                .putExtra(OpticalVideoActivity.EXTRA_FPS, 29);
        new CameraSelection(main.directId, null, false, 1f).writeTo(intent);
        try(ActivityScenario<OpticalVideoActivity> scenario = ActivityScenario.launch(intent)) {
            waitFor(() -> state(scenario) != null && state(scenario).ready && !state(scenario).busy, "camera ready", 25_000);
            assertVisibleHeader(scenario);
            OpticalVideoEngine.State initial = state(scenario);
            Log.i("SCameraAcceptance", "Profile " + initial.width + "x" + initial.height + "@" + initial.fps
                    + " actual rear stops=" + java.util.Arrays.toString(initial.stops));
            assertEquals("telephoto-compatible rate", 29, initial.fps);
            assertTrue("OnePlus rear main/ultra-wide/telephoto must remain accessible with new identity", initial.stops.length >= 3);
            assertTrue("main/ultra-wide retain 4K/30", initial.profiles.stream().anyMatch(p -> p.width == 3840 && p.height == 2160 && p.fps == 30));
            assertTrue("main sensor exposes manual ISO and shutter", initial.supportsManualExposure);
            OpticalVideoEngine engine = engine(scenario);
            engine.setProfile(new OpticalVideoEngine.Profile(3840, 2160, 30));
            waitFor(() -> state(scenario).ready && !state(scenario).busy && state(scenario).width == 3840, "4K remains available", 20_000);
            assertTrue("telephoto stays discoverable even at 4K", state(scenario).allStops.length >= 3);
            engine.selectLens(initial.stops[initial.stops.length - 1]);
            waitFor(() -> state(scenario).ready && !state(scenario).busy && state(scenario).width == 1920 && state(scenario).fps == 29,
                    "telephoto button selects its compatible format while idle", 20_000);
            engine.selectLens(1f);
            waitFor(() -> state(scenario).ready && !state(scenario).busy && Math.abs(state(scenario).zoom - 1) < .02, "main before manual test", 20_000);
            int iso = Math.min(initial.maxIso, Math.max(initial.minIso, 200));
            long shutter = Math.min(initial.maxExposureTimeNs, Math.max(initial.minExposureTimeNs, 1_000_000_000L / 120));
            engine.setManualExposure(iso, shutter);
            waitFor(() -> {
                OpticalVideoEngine.State s = state(scenario);
                return s.manualExposure && Math.abs(s.actualIso - iso) <= iso * .12
                        && Math.abs(s.actualExposureTimeNs - shutter) <= shutter * .08;
            }, "requested manual ISO/shutter returned by real sensor", 10_000);
            Log.i("SCameraAcceptance", "Manual sensor readback ISO=" + state(scenario).actualIso + " shutterNs=" + state(scenario).actualExposureTimeNs);
            engine.record(true, 0);
            waitFor(() -> state(scenario).recording && state(scenario).recordMs > 500, "record start", 10_000);
            if( state(scenario).supportsManualWhiteBalance ) engine.setWhiteBalance(4800);
            if( state(scenario).supportsManualFocus ) engine.setManualFocus(state(scenario).maxFocusDiopters / 3);
            Set<String> cameras = new HashSet<>();
            for(float zoom : initial.stops) {
                engine.setZoom(zoom);
                waitFor(() -> {
                    OpticalVideoEngine.State s = state(scenario);
                    return s.ready && !s.busy && Math.abs(s.zoom - zoom) < .02f;
                }, "real rear lens at " + zoom, 20_000);
                OpticalVideoEngine.State current = state(scenario);
                assertTrue("recorder remains active during physical handoff", current.recording);
                assertNull("handoff cannot finish or publish an intermediate video", saved(scenario));
                if( current.supportsManualExposure ) assertTrue("manual intent survives a compatible handoff", current.manualExposure);
                assertTrue(current.exposureTimeNs <= 1_000_000_000L / current.fps);
                cameras.add(current.cameraId + ":" + current.physicalId);
                Log.i("SCameraAcceptance", "Recorded lens=" + current.lensLabel + " camera=" + current.cameraId + " zoom=" + current.zoom + " elapsed=" + current.recordMs);
                Thread.sleep(700);
            }
            assertTrue("three different physical routes were recorded", cameras.size() >= 3);
            engine.pauseToggle(); waitFor(() -> state(scenario).paused, "pause", 5000);
            engine.pauseToggle(); waitFor(() -> !state(scenario).paused, "resume", 5000);
            engine.setZoom(1f);
            waitFor(() -> state(scenario).ready && !state(scenario).busy && Math.abs(state(scenario).zoom - 1) < .02, "return main", 20_000);
            engine.setAutoExposure();
            engine.setExposureCompensation(Math.min(1, state(scenario).maxEv));
            waitFor(() -> !state(scenario).manualExposure, "auto exposure restored", 5000);
            Thread.sleep(800);
            engine.stopRecording();
            waitFor(() -> saved(scenario) != null && !state(scenario).recording, "saved single file", 15_000);
            verifyTracks(context, saved(scenario));
            scenario.onActivity(activity -> activity.onModeSelected(com.shahzeb.scamera.op.CameraMode.FILM));
            assertSame("Cinematic uses the same engine without reopening", engine, engine(scenario));
            scenario.onActivity(activity -> activity.onModeSelected(com.shahzeb.scamera.op.CameraMode.PRO_VIDEO));
            assertSame(engine, engine(scenario));
            engine.setAutoExposure(); engine.setAutoFocus(); engine.setWhiteBalance(0); engine.setExposureCompensation(0);
            waitFor(() -> !state(scenario).manualExposure && Math.abs(state(scenario).exposureCompensation) < .01,
                    "leave manual mode ready with neutral Auto exposure", 5000);
            assertVisibleHeader(scenario);
        }
    }

    private static void verifyTracks(Context context, Uri uri) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, uri, null);
            int video = 0, audio = 0; long videoEnd = 0, audioEnd = 0;
            for(int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if( mime.startsWith("video/") ) video++; else if( mime.startsWith("audio/") ) audio++;
                extractor.selectTrack(i);
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                long previous = -1; int samples = 0;
                Set<Long> uniqueTimes = new HashSet<>();
                while( extractor.getSampleTime() >= 0 ) {
                    long timestamp = extractor.getSampleTime();
                    assertTrue("unique presentation timestamp for " + mime, uniqueTimes.add(timestamp));
                    if( mime.startsWith("audio/") ) assertTrue("increasing audio timestamps", timestamp > previous);
                    // B-frame video samples arrive in decode order, rather than presentation order.
                    previous = Math.max(previous, timestamp); samples++; extractor.advance();
                }
                assertTrue("recording contains real samples", samples > 30);
                if( mime.startsWith("video/") ) videoEnd = previous; else if( mime.startsWith("audio/") ) audioEnd = previous;
                extractor.unselectTrack(i);
            }
            assertEquals(1, video); assertEquals(1, audio);
            Log.i("SCameraAcceptance", "Saved " + uri + " videoEndUs=" + videoEnd + " audioEndUs=" + audioEnd);
            assertTrue("audio/video end times within 200ms", Math.abs(videoEnd - audioEnd) < 200_000);
        }
        finally { extractor.release(); }
    }

    private static void assertVisibleHeader(ActivityScenario<OpticalVideoActivity> scenario) {
        android.graphics.Rect bounds = new android.graphics.Rect();
        AtomicReference<String> description = new AtomicReference<>();
        scenario.onActivity(activity -> {
            try {
                Field f = OpticalVideoActivity.class.getDeclaredField("ui"); f.setAccessible(true);
                com.shahzeb.scamera.op.OpCameraUi ui = (com.shahzeb.scamera.op.OpCameraUi)f.get(activity);
                int[] xy = new int[2]; ui.topBar.getLocationOnScreen(xy);
                bounds.set(xy[0], xy[1], xy[0] + ui.topBar.getWidth(), xy[1] + ui.topBar.getHeight());
                description.set("alpha=" + ui.topBar.getAlpha() + " visible=" + ui.topBar.getVisibility() + " children=" + ui.topBar.getChildCount());
            }
            catch(Exception error) { throw new AssertionError(error); }
        });
        android.graphics.Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(screenshot);
        int bright = 0;
        for(int y = Math.max(0, bounds.top); y < Math.min(screenshot.getHeight(), bounds.bottom); y++)
            for(int x = Math.max(0, bounds.left); x < Math.min(screenshot.getWidth(), bounds.right); x++) {
                int color = screenshot.getPixel(x, y);
                if( android.graphics.Color.red(color) > 150 && android.graphics.Color.green(color) > 150 ) bright++;
            }
        screenshot.recycle();
        assertTrue("Top controls must actually render, " + description.get() + " brightPixels=" + bright, bright > 100);
    }

    private static OpticalVideoEngine.State state(ActivityScenario<OpticalVideoActivity> scenario) { return field(scenario, "state"); }
    private static Uri saved(ActivityScenario<OpticalVideoActivity> scenario) { return field(scenario, "lastSaved"); }
    @SuppressWarnings("unchecked") private static <T> T field(ActivityScenario<OpticalVideoActivity> scenario, String name) {
        AtomicReference<T> value = new AtomicReference<>();
        scenario.onActivity(activity -> {
            try { Field f = OpticalVideoActivity.class.getDeclaredField(name); f.setAccessible(true); value.set((T)f.get(activity)); }
            catch(Exception error) { throw new AssertionError(error); }
        });
        return value.get();
    }
    private static OpticalVideoEngine engine(ActivityScenario<OpticalVideoActivity> scenario) {
        AtomicReference<OpticalVideoEngine> value = new AtomicReference<>();
        scenario.onActivity(activity -> {
            try { Method m = OpticalVideoActivity.class.getDeclaredMethod("engine"); m.setAccessible(true); value.set((OpticalVideoEngine)m.invoke(activity)); }
            catch(Exception error) { throw new AssertionError(error); }
        }); return value.get();
    }
    private static void waitFor(BooleanSupplier check, String message, long timeout) throws Exception {
        long end = android.os.SystemClock.elapsedRealtime() + timeout;
        while( android.os.SystemClock.elapsedRealtime() < end ) { if( check.getAsBoolean() ) return; Thread.sleep(100); }
        fail("Timed out: " + message);
    }
}
