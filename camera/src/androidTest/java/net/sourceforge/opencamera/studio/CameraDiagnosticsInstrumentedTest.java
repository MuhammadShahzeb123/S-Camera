package net.sourceforge.opencamera.studio;

import android.Manifest;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import net.sourceforge.opencamera.MainActivity;
import net.sourceforge.opencamera.PreferenceKeys;
import net.sourceforge.opencamera.R;
import net.sourceforge.opencamera.op.CameraInventory;
import net.sourceforge.opencamera.op.CameraRoutes;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.*;

/** Exercises the exact Settings > About > Copy path used in the phone report. */
@RunWith(AndroidJUnit4.class)
public class CameraDiagnosticsInstrumentedTest {
    @Rule public GrantPermissionRule permissions = GrantPermissionRule.grant(Manifest.permission.CAMERA);

    @Test public void aboutCopyIncludesLensesOtherThanTheCurrentlyOpenCamera() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(PreferenceKeys.IsVideoPreferenceKey, false)
                .putInt(OpticalVideoActivity.PREF_FPS, 29).commit();
        Intent intent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("test_project", true);
        try(ActivityScenario<MainActivity> scenario = ActivityScenario.launch(intent)) {
            AtomicReference<MainActivity> host = new AtomicReference<>();
            scenario.onActivity(host::set);
            long deadline = android.os.SystemClock.elapsedRealtime() + 20_000;
            while( host.get().getPreview().getCameraController() == null
                    && android.os.SystemClock.elapsedRealtime() < deadline ) Thread.sleep(50);
            assertNotNull(host.get().getPreview().getCameraController());
            scenario.onActivity(MainActivity::openSettings);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                activity.getFragmentManager().executePendingTransactions();
                PreferenceFragment settings = (PreferenceFragment)activity.getFragmentManager()
                        .findFragmentByTag("PREFERENCE_FRAGMENT");
                assertNotNull(settings);
                Preference about = settings.findPreference("preference_about");
                assertNotNull(about);
                about.getOnPreferenceClickListener().onPreferenceClick(about);
            });
            onView(withText(R.string.about_copy_to_clipboard)).perform(click());
            AtomicReference<String> report = new AtomicReference<>();
            scenario.onActivity(activity -> {
                ClipboardManager clipboard = (ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
                assertNotNull(clipboard.getPrimaryClip());
                report.set(clipboard.getPrimaryClip().getItemAt(0).coerceToText(activity).toString());
            });
            assertNotNull(report.get());
            CameraInventory inventory = CameraInventory.get(context);
            assertFalse(inventory.sensors.isEmpty());
            for( CameraInventory.Lens lens : inventory.sensors ) {
                assertTrue("copied report omits sensor " + lens.id, report.get().contains("SENSOR " + lens.id + " "));
                assertTrue("copied report omits video eligibility for " + lens.id,
                        report.get().contains("Video checks for sensor " + lens.id + ":"));
                if( !CameraRoutes.forLens(lens, null).isEmpty() ) {
                    int start = report.get().indexOf("Video checks for sensor " + lens.id + ":");
                    int end = report.get().indexOf("Video checks for sensor ", start + 1);
                    String section = report.get().substring(start, end < 0 ? report.get().length() : end);
                    assertTrue("saved shared FPS must appear in every camera's checks: " + lens.id,
                            section.contains("640x480@29:"));
                }
            }
            assertTrue("recording pipeline history must be available from About", report.get().contains("=== Studio log ==="));
        }
    }
}
