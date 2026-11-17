package com.shahzeb.scamera.studio;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.view.KeyEvent;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import com.shahzeb.scamera.PreferenceKeys;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

@RunWith(AndroidJUnit4.class)
public class OpticalVideoActivityInstrumentedTest {
    @Rule public GrantPermissionRule permissions = GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);

    @Test public void normalVideoControlsAndBackgroundSave() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putInt("op_optical_video_width", 640).putInt("op_optical_video_height", 480).putInt("op_optical_video_fps", 29)
                .putBoolean(PreferenceKeys.RecordAudioPreferenceKey, false)
                .putString(PreferenceKeys.VolumeKeysPreferenceKey, "volume_take_photo").commit();
        Intent intent = new Intent(context, OpticalVideoActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(OpticalVideoActivity.EXTRA_WIDTH, 1920).putExtra(OpticalVideoActivity.EXTRA_HEIGHT, 1080);
        Field stateField = OpticalVideoActivity.class.getDeclaredField("state"); stateField.setAccessible(true);
        Field savedField = OpticalVideoActivity.class.getDeclaredField("lastSaved"); savedField.setAccessible(true);
        try(ActivityScenario<OpticalVideoActivity> scenario = ActivityScenario.launch(intent)) {
            AtomicReference<OpticalVideoEngine.State> state = new AtomicReference<>();
            BooleanSupplier ready = () -> {
                scenario.onActivity(activity -> {
                    try { state.set((OpticalVideoEngine.State)stateField.get(activity)); }
                    catch(IllegalAccessException error) { throw new AssertionError(error); }
                });
                return state.get() != null && state.get().ready && !state.get().busy;
            };
            waitFor(ready, "video screen ready", 20_000);
            assertEquals("ordinary re-entry preserves the optical profile", 640, state.get().width);
            assertEquals("retain the nonstandard saved frame rate", 29, state.get().fps);
            onView(withContentDescription("Frame rate")).check(matches(withText("29 FPS"))).perform(click());
            waitFor(() -> ready.getAsBoolean() && state.get().fps != 29, "frame-rate button selects a supported rate", 10_000);
            assertTrue(state.get().profiles.contains(new OpticalVideoEngine.Profile(state.get().width, state.get().height, state.get().fps)));
            onView(withContentDescription("Frame rate")).check(matches(withText(state.get().fps + " FPS")));
            screenshot(context, "optical-video-idle.png");
            scenario.onActivity(OpticalVideoActivity::onShutterClick);
            waitFor(() -> ready.getAsBoolean() && state.get().recording, "record button", 10_000);
            Thread.sleep(1000);
            scenario.onActivity(activity -> activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)));
            waitFor(() -> ready.getAsBoolean() && state.get().paused && state.get().recording, "volume down pauses rather than saves", 5000);
            scenario.onActivity(activity -> activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)));
            waitFor(() -> ready.getAsBoolean() && !state.get().paused && state.get().recording, "volume down resumes", 5000);
            PreferenceManager.getDefaultSharedPreferences(context).edit().putString(PreferenceKeys.VolumeKeysPreferenceKey, "volume_zoom").commit();
            float previousZoom = state.get().zoom;
            scenario.onActivity(activity -> activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)));
            waitFor(() -> ready.getAsBoolean() && state.get().zoom > previousZoom && state.get().recording,
                    "configured volume zoom must not stop recording", 5000);
            screenshot(context, "optical-video-recording.png");
            Thread.sleep(1000);
            AtomicReference<OpticalVideoActivity> instance = new AtomicReference<>(); scenario.onActivity(instance::set);
            scenario.moveToState(Lifecycle.State.CREATED);
            AtomicReference<Uri> saved = new AtomicReference<>();
            waitFor(() -> {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    try { saved.set((Uri)savedField.get(instance.get())); }
                    catch(IllegalAccessException error) { throw new AssertionError(error); }
                });
                return saved.get() != null;
            }, "backgrounding finalizes the video", 15_000);
            android.media.MediaExtractor extractor = new android.media.MediaExtractor();
            try {
                extractor.setDataSource(context, saved.get(), null);
                assertEquals("disabled audio preference is respected with granted mic permission", 1, extractor.getTrackCount());
            }
            finally { extractor.release(); }
            scenario.moveToState(Lifecycle.State.RESUMED);
            waitFor(() -> ready.getAsBoolean() && !state.get().recording, "camera reopens after resume", 20_000);
            screenshot(context, "optical-video-resumed.png");
        }
    }

    private static void screenshot(Context context, String name) throws Exception {
        Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(image);
        try(FileOutputStream stream = new FileOutputStream(new File(context.getExternalFilesDir(null), name))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
        image.recycle();
    }

    private static void waitFor(BooleanSupplier condition, String description, long timeoutMs) throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + timeoutMs;
        while( !condition.getAsBoolean() && android.os.SystemClock.elapsedRealtime() < until ) Thread.sleep(60);
        assertTrue(description + "\n" + StudioLog.dump(), condition.getAsBoolean());
    }
}
