package com.shahzeb.scamera.op;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.preference.PreferenceManager;
import android.util.Size;

import com.shahzeb.scamera.studio.OpticalVideoActivity;
import com.shahzeb.scamera.studio.OpticalVideoEngine;
import com.shahzeb.scamera.studio.StudioLog;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Complete metadata-only report; does not open cameras or change the cached inventory. */
public final class CameraDiagnostics {
    private CameraDiagnostics() { }

    public static String report(Context context) {
        StringBuilder text = new StringBuilder("=== All-camera video diagnostics ===\n");
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            text.append("Build: ").append(info.versionName).append(" (code ").append(info.versionCode).append(")\n");
        }
        catch(Exception e) { text.append("Build unavailable: ").append(e).append('\n'); }
        try {
            CameraInventory inventory = CameraInventory.get(context);
            CameraManager manager = (CameraManager)context.getSystemService(Context.CAMERA_SERVICE);
            text.append(inventory.report()).append('\n');
            // A rate contributed by telephoto (e.g. 29) must also be checked against main
            // and ultra-wide, whose own AE endpoints might only list 24 and 30.
            Map<String, CameraCharacteristics> cameras = new LinkedHashMap<>();
            for( CameraInventory.Lens lens : inventory.sensors ) cameras.put(lens.id, lens.characteristics);
            for( CameraInventory.Lens lens : inventory.sensors ) {
                for( CameraRoutes.Route route : CameraRoutes.forLens(lens, null) ) {
                    if( cameras.containsKey(route.cameraId) ) continue;
                    try { cameras.put(route.cameraId, manager.getCameraCharacteristics(route.cameraId)); }
                    catch(Exception e) { text.append("Route metadata unavailable for ").append(route.cameraId).append(": ").append(e).append('\n'); }
                }
            }
            int preferredFps = PreferenceManager.getDefaultSharedPreferences(context).getInt(OpticalVideoActivity.PREF_FPS, 30);
            List<OpticalVideoEngine.Profile> candidates;
            try {
                candidates = OpticalVideoEngine.candidateProfilesForCameras(preferredFps,
                        cameras.values().toArray(new CameraCharacteristics[0]));
            }
            catch(Exception e) {
                text.append("FPS candidate collection failed; checking standard/saved rates: ").append(e).append('\n');
                // Keep the raw per-sensor evidence available if one vendor's AE metadata
                // cannot be decoded. Each sensor's remaining failures are isolated below.
                candidates = OpticalVideoEngine.candidateProfilesForCameras(preferredFps);
            }
            for( CameraInventory.Lens lens : inventory.sensors ) {
                text.append("Video checks for sensor ").append(lens.id).append(":\n");
                List<CameraInventory.Lens> usable = inventory.usableLenses(lens.facing, false);
                String inventoryResult = lens.isMono ? "excluded: monochrome"
                        : usable.contains(lens) ? "eligible for route/profile checks"
                        : "excluded: hidden/unreachable or duplicate lens";
                text.append("  Inventory: ").append(inventoryResult).append('\n');
                try {
                    appendStreams(text, lens.characteristics);
                    List<CameraRoutes.Route> routes = CameraRoutes.forLens(lens, null);
                    if( routes.isEmpty() ) text.append("  No advertised opening route\n");
                    for( CameraRoutes.Route route : routes ) {
                        text.append("  Route open=").append(route.cameraId).append(" physical=").append(route.physicalId).append('\n');
                        CameraCharacteristics parent;
                        try { parent = route.physicalId == null ? lens.characteristics : manager.getCameraCharacteristics(route.cameraId); }
                        catch(Exception e) { text.append("    Cannot read route: ").append(e).append('\n'); continue; }
                        if( route.physicalId != null )
                            text.append("    Parent AE ranges: ").append(Arrays.toString(parent.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES))).append('\n');
                        for( OpticalVideoEngine.Profile profile : candidates ) {
                            String sensorFailure = OpticalVideoEngine.profileRejection(lens.characteristics, profile);
                            String parentFailure = route.physicalId == null ? null : OpticalVideoEngine.profileRejection(parent, profile);
                            text.append("    ").append(profile.width).append('x').append(profile.height).append('@').append(profile.fps).append(": ");
                            if( sensorFailure == null && parentFailure == null ) text.append("camera checks pass; encoder support still required; lens available only for compatible formats");
                            else {
                                if( sensorFailure != null ) text.append("sensor: ").append(sensorFailure);
                                if( sensorFailure != null && parentFailure != null ) text.append("; ");
                                if( parentFailure != null ) text.append("parent: ").append(parentFailure);
                            }
                            text.append('\n');
                        }
                    }
                }
                catch(Exception e) { text.append("  Metadata read failed: ").append(e).append('\n'); }
            }
        }
        catch(Exception e) { text.append("Inventory unavailable: ").append(e).append('\n'); }
        text.append('\n').append(StudioLog.dump());
        return text.toString();
    }

    private static void appendStreams(StringBuilder text, CameraCharacteristics c) {
        if( c == null ) { text.append("  Missing characteristics\n"); return; }
        text.append("  AE ranges: ").append(Arrays.toString(c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES))).append('\n');
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(SurfaceTexture.class);
        text.append("  SurfaceTexture sizes / minimum frame duration (ns): ");
        if( sizes == null ) text.append("none");
        else for( int i = 0; i < sizes.length; i++ ) {
            if( i != 0 ) text.append(", ");
            text.append(sizes[i]).append('/');
            try { text.append(map.getOutputMinFrameDuration(SurfaceTexture.class, sizes[i])); }
            catch(IllegalArgumentException e) { text.append("query-rejected"); }
        }
        text.append('\n');
    }
}
