# OnePlus 9 Pro camera fixes

Built on 2026-11-07 for the reported OnePlus 9 Pro, Android 14, Camera2 configuration.

**Follow-up:** This first build fixed camera handoff between modes and after recording. It did not implement changing physical lenses during one recording. The user subsequently clarified that requirement; see [the live optical zoom research](oneplus-9-pro-live-optical-zoom-research.md) and [the new implementation notes](live-optical-video-verification.md).

## Deliverable

The debug APK is `../../OpenCamera-OnePlus9Pro-fixes-debug.apk` relative to this document. It uses the same app ID and signing certificate as the backed-up APK, and an update installation succeeded on the emulator.

SHA-256: `3b07a9e792deb7cebf7414baac56d074fdadf6b4c052c951e7dc8a8954cb1fe6`

The original source is preserved in `../../opencamera-source-backup-20261107.zip`, and the original APK in `../../OpenCamera-before-fixes.apk`. The source audit found zero missing original files. Existing work was retained; no reset or commit was performed.

## Changes

- **Video save crash:** a hidden legacy gallery button had width zero. After saving a valid video, the thumbnail code passed that width to `Bitmap.createScaledBitmap`, which crashed the app. Thumbnail sizing now uses a positive fallback and protects both dimensions. Active Studio recordings are finalized before camera teardown on pause.
- **Specialty-mode orientation:** the GL preview now applies the camera's SurfaceTexture transform once, with corrected texture coordinates. It avoids adding a second phone rotation or front-camera mirror. Crop calculations account for the transform; front capture orientation is corrected separately.
- **Lens continuity:** mode transitions carry the selected camera, physical camera, facing, and zoom. Studio Back returns to the preceding classic mode. Lens selection handles unordered inventories, logical-camera zoom coverage, missing front/rear candidates, and reopening the requested lens. The UI follows the camera's accepted zoom.
- **60 FPS:** Video and Pro Video have separate resolution and FPS controls. Capability checks preserve each advertised AE range and intersect it with output-size throughput, including rounding 16,666,667 ns to 60 FPS. The arbitrary 4K/60 exclusion was removed. Unavailable 60 FPS produces a useful explanation.
- **Settings:** root and internal pages share dark surfaces, readable wrapping text, grouped sections, native controls, and a visible Back button. All 177 preference keys were retained, along with capability-dependent settings and native dialogs.

Camera2 frame rate depends on the selected lens's advertised FPS ranges and the configured stream sizes. The app can expose supported combinations; it cannot guarantee that OnePlus firmware exposes 60 FPS on every lens. See [Android's Camera2 FPS contract](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#CONTROL_AE_TARGET_FPS_RANGE).

## Verification

- `testDebugUnitTest`: **60 tests, zero failures/errors/skips**. Includes regression coverage for thumbnail dimensions, GL coordinates/orientation, lens selection, and FPS filtering.
- `assembleDebug`: successful on the final source.
- APK signature verification: both original and final APKs pass and have certificate SHA-256 `c1a2d719c1d4b59cf8696ed214fbc7d585c271f50c1a251bfd27530702e591c1`.
- Original APK on Android 15 emulator: reproduced the post-save `IllegalArgumentException: width and height must be > 0` in `MyApplicationInterface.stoppedVideo`. The video was present in media storage.
- Final packaged APK on the same emulator: saved `VID_20261107_064534.mp4` (2,245 ms); process 6415 remained alive through save, Portrait entry, and Back to Video. No new crash-buffer entry appeared. Portrait showed the rear Main camera with an upright preview.
- Visual settings checks: root, Photo, Video, resolution dialog, disabled rows, Back navigation, and 1.5x system font scale.
- Independent final code review: no remaining blocker. Source audit: zero missing originals, 23 modified originals, 17 added source files.
- `git diff --check`: passed.
- `lintDebug`: completed, but **not clean**. Existing findings remain: 658 errors (650 missing translations, 3 NewApi, 3 StartActivityAndCollapseDeprecated, 2 WrongThread) and 222 warnings. Findings introduced by these changes were corrected. The project allows lint to complete without aborting the build.

Evidence is in `../verification/`: `before-crash.txt`, `after-crash-buffer.txt`, `final-apk-smoke.json`, `apk-signatures.json`, `source-audit.json`, and screenshots. Settings previews: [root](../verification/settings-root.png), [Photo](../verification/settings-photo.png), [Video](../verification/settings-video.png), [larger text](../verification/settings-large.png). Final [Portrait](../verification/final-apk-portrait.png) and [return to Video](../verification/final-apk-return.png).

## Checks still requiring the phone

No physical OnePlus was connected. The emulator runs Android 15; it does not verify the OnePlus Android 14 camera HAL, auxiliary camera access, or actual 60 FPS capture.

1. Select ultra-wide, enter Video, record and stop, then switch back to main/telephoto. Check selection after entering Portrait and returning with Back.
2. Check rear and front previews/captures in specialty modes while rotating the phone through all four orientations.
3. In Video on main 1x, start at 1080p and tap FPS. If 60 FPS is available, record a short clip. Check other lenses and 4K separately; their exposed capabilities can differ.
4. Check a very short recording, a failed recording start, and leaving the app during a Studio recording. These device-dependent lifecycle cases were not fully exercised on hardware.
