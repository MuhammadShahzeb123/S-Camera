package net.sourceforge.opencamera;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.preference.PreferenceManager;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import net.sourceforge.opencamera.op.CameraMode;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/** Runs the real OnePlus overlay; test_project would disable the controller under test. */
@RunWith(AndroidJUnit4.class)
public class PhotoModeInstrumentedTest {
    @Rule public GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);

    @Test public void photoOverridesSavedVideoAndSavesAnImage() throws Exception {
        checkStillMode(CameraMode.PHOTO, true);
    }

    @Test public void nightOverridesSavedVideoAndSavesAnImage() throws Exception {
        checkStillMode(CameraMode.NIGHT, true);
    }

    @Test public void proAndPanoramaOverrideSavedVideo() throws Exception {
        checkStillMode(CameraMode.PRO, false);
        checkStillMode(CameraMode.PANORAMA, false);
    }

    private void checkStillMode(CameraMode mode, boolean capture) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(PreferenceKeys.IsVideoPreferenceKey, true)
                .putBoolean("done_first_time", true)
                .putBoolean(PreferenceKeys.ShowWhatsNewPreferenceKey, false)
                .putString(PreferenceKeys.TimerPreferenceKey, "0")
                .putString(PreferenceKeys.RawPreferenceKey, "preference_raw_no")
                .putBoolean("op_photo_hdr", false).commit();
        Intent intent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(OpClassicController.EXTRA_MODE, mode.name());
        try(ActivityScenario<MainActivity> scenario = ActivityScenario.launch(intent)) {
            AtomicReference<MainActivity> host = new AtomicReference<>();
            scenario.onActivity(host::set);
            waitFor(() -> onMain(() -> host.get().getPreview().isPreviewStarted()), "still preview opens", 25_000);
            scenario.onActivity(activity -> {
                assertFalse(mode + " must replace the persisted video setting", activity.getApplicationInterface().isVideoPref());
                assertFalse(mode + " must configure the capture engine for still images", activity.getPreview().isVideo());
            });
            // Activity recreation must preserve the capture type after EXTRA_MODE was consumed.
            if( mode == CameraMode.PHOTO ) {
                scenario.recreate();
                scenario.onActivity(host::set);
                waitFor(() -> onMain(() -> host.get().getPreview().isPreviewStarted()), "recreated preview opens", 25_000);
            }
            if( !capture ) return;
            AtomicReference<Uri> before = new AtomicReference<>();
            scenario.onActivity(activity -> {
                before.set(activity.getStorageUtils().getLastMediaScanned());
                activity.findViewById(R.id.op_shutter).performClick();
                assertFalse("still shutter must never start video", activity.getPreview().isVideoRecording());
            });
            AtomicReference<Uri> saved = new AtomicReference<>();
            waitFor(() -> onMain(() -> {
                assertFalse("still capture became a video", host.get().getPreview().isVideoRecording());
                saved.set(host.get().getStorageUtils().getLastMediaScanned());
                return saved.get() != null && !saved.get().equals(before.get());
            }), "still image is saved", 45_000);
            assertTrue(context.getContentResolver().getType(saved.get()).startsWith("image/"));
            try(InputStream stream = context.getContentResolver().openInputStream(saved.get())) {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(stream, null, bounds);
                assertTrue("saved image is decodable", bounds.outWidth > 0 && bounds.outHeight > 0);
            }
        }
    }

    @Test public void staleEngineStateIsRepairedBeforeShutterCanRecord() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(OpClassicController.EXTRA_MODE, CameraMode.PHOTO.name());
        try(ActivityScenario<MainActivity> scenario = ActivityScenario.launch(intent)) {
            AtomicReference<MainActivity> host = new AtomicReference<>();
            scenario.onActivity(host::set);
            waitFor(() -> onMain(() -> host.get().getPreview().isPreviewStarted()), "photo preview", 25_000);
            scenario.onActivity(activity -> activity.getPreview().switchVideo(true, true));
            scenario.onActivity(activity -> activity.findViewById(R.id.op_shutter).performClick());
            waitFor(() -> onMain(() -> !host.get().getPreview().isVideo() && host.get().getPreview().isPreviewStarted()),
                    "shutter restores the selected photo mode", 25_000);
            scenario.onActivity(activity -> assertFalse(activity.getPreview().isVideoRecording()));
        }
    }

    private static boolean onMain(BooleanSupplier condition) {
        boolean[] result = new boolean[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> result[0] = condition.getAsBoolean());
        return result[0];
    }

    private static void waitFor(BooleanSupplier condition, String description, long timeout) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + timeout;
        while( !condition.getAsBoolean() && android.os.SystemClock.elapsedRealtime() < deadline ) Thread.sleep(60);
        assertTrue(description, condition.getAsBoolean());
    }
}
