# OnePlus 9 Pro capture and zoom update

Build: `1.56.2-capture-zoom.1`, version code **99**. Previous source files and APKs are preserved; the pre-change archive is `../../opencamera-before-capture-smooth-zoom-20261110.zip`.

Delivered APK: [OpenCamera-OnePlus9Pro-capture-smooth-zoom-debug.apk](../../OpenCamera-OnePlus9Pro-capture-smooth-zoom-debug.apk), 6,479,235 bytes. SHA-256: `9c00015817561208f713b0a9775722577d7a69c5fcfc3470a9c9d28d95f2f39c`. Signature verification passes with the same certificate as the previous code 98 build; installation itself was not tested in this session.

## Still capture

The classic controller initially compared the requested mode against `Preview.isVideo()`, which starts as false. When returning from Video with the saved video preference still true, selecting Photo, Night, Pro or Panorama did not clear that preference. Camera setup subsequently read the saved value and opened in video mode under the still-mode UI.

Mode selection now always writes its capture type before camera setup. Reselecting a mode can repair a mismatch. The on-screen shutter waits during camera opening and repairs an inconsistent capture type before accepting capture; the same guard covers long presses.

`PhotoModeInstrumentedTest` exercises the actual OnePlus overlay (the old `test_project` launch bypasses it). It covers stale saved video settings for all four classic still modes, Photo recreation, actual image-file creation/decoding in Photo and Night, and a mismatched shutter state. These instrumentation tests compile but have **not run in this session**.

## Video zoom

The existing pipeline immediately changed digital crop and cut to the next camera's first frame. The new `ZoomRamp` interpolates magnification logarithmically with eased starts/stops, preserves motion velocity across continuous finger updates, preserves position when the user reverses direction, and ignores duplicate targets. Small changes settle faster than large lens-button jumps.

Both preview and the recorder now use the same animated global zoom. At a physical camera handoff, the renderer keeps the outgoing texture, matches both crops in main-camera-relative field-of-view coordinates, and blends for 180 ms. Zooming toward a longer lens reaches that lens's native field of view before blending; zooming toward a wider lens blends at a field of view both can show, then continues widening. Retired textures are released after the blend. Front/rear flips and format changes do not blend. A rejected lens request restores the renderer's target zoom when the engine chooses recovery, including while waiting for the previous camera to reopen.

The selected-lens rules and profile capability checks are preserved. On the previously supplied LE2121 report, normal video offers the shared 1080p/29 FPS profile with ultra-wide, main and 3.3x telephoto. This update does not add 4K or 60 FPS telephoto support.

The cameras still open sequentially. A held image can remain while the next camera opens; a short blend can show ghosting on moving subjects. The nominal fields of view do not calibrate parallax, distortion, exposure or white balance. **iPhone-equivalent optical transitions or handset performance have not been established.**

Apple documents smooth zoom ramps and virtual-camera switch factors where two lenses' fields of view match. Android's `CONTROL_ZOOM_RATIO` can combine optical and digital zoom when the vendor exposes a suitable logical camera. The project's existing handset report instead identifies separate camera devices, so this change retains its app-managed recorder.

- [Apple zoom APIs](https://developer.apple.com/documentation/avfoundation/capture-device-zoom)
- [Apple virtual-camera switch factors](https://developer.apple.com/documentation/avfoundation/avcapturedevice/virtualdeviceswitchovervideozoomfactors)
- [Android zoom ratio](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#CONTROL_ZOOM_RATIO)
- [Prior handset metadata and 29 FPS verification](telephoto-fps-verification.md)

## Verification and remaining phone check

- All **98 JVM tests pass**, including eight new zoom-ramp tests. Five regressions fail with instantaneous zoom; two additional sustained-input/velocity assertions caught and now prevent gesture lag caused by restarting the easing curve. See `verification/zoom-ramp-before.txt`, `verification/zoom-gesture-before.txt`, `verification/zoom-ramp-after.txt`, and the Gradle test reports.
- App and instrumentation APK builds pass. Renderer instrumentation now checks that the saved video contains multiple blended frames, keeps increasing timestamps and contains no black handoff frames. It has **not run in this session**.
- Android device verification is blocked before connection: both installed `adb.exe` versions fail with `Cannot mkdir '\.android': Permission denied` in this Windows tool session. No emulator or OnePlus runtime result is claimed.
- Lint retains 658 pre-existing errors and 221 warnings. No findings are reported in the changed optical-video engine/renderer, new ramp, or new Photo instrumentation. This is not a clean-lint claim.
- Independent read-only review identified gesture lag and a missing renderer rollback; both were corrected before packaging.
- Source audit finds all 820 baseline files still present, with changes confined to the capture controller, optical engine/renderer, renderer instrumentation, and version metadata, plus the new files listed above.

On the OnePlus, verify Photo and Night after using Video, and check that the results are images in the gallery. Then record one clip while slowly zooming both ways across ultra-wide / 1x / 3.3x, tap the lens buttons, and reverse direction during a switch. Play back the saved clip with audio. Check moving subjects and lighting changes as well as a static scene. This is the outstanding acceptance check, not a claim of a completed handset test.

Final build evidence: [build and lint](../verification/capture-zoom-delivery-build.txt), [APK metadata](../verification/capture-zoom-delivery.json), [signature](../verification/capture-zoom-signature.txt), [source audit](../verification/capture-zoom-source-audit.json), [review](../verification/capture-zoom-review.txt).
