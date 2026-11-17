package com.shahzeb.scamera;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class UpdateCompatibilityInstrumentedTest {
    @Test public void installedShortcutsWidgetsAndTilesStillResolve() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PackageManager pm = context.getPackageManager();
        String app = context.getPackageName(), prefix = "net.sourceforge.opencamera.";
        assertNotNull(pm.getActivityInfo(new ComponentName(app, prefix + "MainActivity"), 0));
        assertNotNull(pm.getActivityInfo(new ComponentName(app, prefix + "TakePhoto"), 0));
        assertNotNull(pm.getReceiverInfo(new ComponentName(app, prefix + "MyWidgetProviderTakePhoto"), 0));
        for(String tile : new String[]{"MyTileService", "MyTileServiceVideo", "MyTileServiceFrontCamera"})
            assertNotNull(pm.getServiceInfo(new ComponentName(app, prefix + tile), 0));
        if( Build.VERSION.SDK_INT >= 25 ) {
            java.util.List<ShortcutInfo> shortcuts = context.getSystemService(ShortcutManager.class).getManifestShortcuts();
            assertEquals(5, shortcuts.size());
            for(ShortcutInfo shortcut : shortcuts) {
                assertNotNull(shortcut.getIntent());
                assertNotNull("shortcut resolves: " + shortcut.getId(), pm.resolveActivity(shortcut.getIntent(), 0));
            }
        }
    }
}
