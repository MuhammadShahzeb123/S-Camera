# Restore still capture and smooth optical video zoom

Target: OnePlus 9 Pro (previous LE2121 / Android 14 report). Keep the existing Java/Camera2/GL recording architecture and all existing user changes.

1. Reproduce stale saved video state through the real OnePlus UI: launch Photo, Night, Pro and Panorama with a saved video preference. Verify engine mode and a saved JPEG for Photo/Night. Always persist the selected capture type before camera setup; ignore shutter input while switching and repair any mismatch before capture.
2. Add a time-based logarithmic zoom ramp and short, field-of-view-matched blend in the shared GL output. Retain the outgoing texture until the replacement supplies frames, blend in preview and recording, then release it. Retarget smoothly, preserve exact final lens routing and 29 FPS selection, and avoid blending front/rear flips or format changes.
3. Test ramp continuity, reversals, repeated targets, invalid input, blend progression, and the encoded replacement transition. Run JVM tests and build app/instrumentation APKs. Run device tests if Android tooling is available; report unrun tests explicitly.
4. Deliver a separately versioned, signature-checked APK and verification notes. Preserve previous sources/APKs.

Research: Apple's public AVFoundation APIs describe gradual zoom ramps and lens changes at matching fields of view. Android's logical multi-camera zoom depends on vendor support; the supplied OnePlus report exposes independent cameras, so retain the app-managed pipeline. A held frame during sequential camera opening remains possible; software blending cannot establish iPhone-equivalent motion or colour calibration.

Sources:
- https://developer.apple.com/documentation/avfoundation/capture-device-zoom
- https://developer.apple.com/documentation/avfoundation/avcapturedevice/virtualdeviceswitchovervideozoomfactors
- https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#CONTROL_ZOOM_RATIO
