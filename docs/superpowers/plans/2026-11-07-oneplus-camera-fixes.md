# OnePlus 9 Pro camera fixes

> Implementation workflow: systematic debugging, test driven fixes for camera logic, parallel work in separate files, then integrated verification.

**Goal:** Correct specialty preview orientation, preserve camera selection across modes and recordings, prevent recording/save failures, expose supported 60 FPS options, and improve all settings pages.

**Architecture:** Preserve the existing classic Camera2 and Studio engines and every existing feature. Fix their state handoff and lifecycle boundaries. Keep the native preference screens and give them shared styling. Do not hard-code hardware support based on the phone model.

**Tech stack:** Java 17, Android Camera2, OpenGL ES, MediaRecorder, native preferences, Gradle/JUnit.

**Authorization:** The user requested all five fixes, authorized implementation choices, and prohibited deleting their work. Work in the existing modified tree, preserve its changes, do not reset or commit unrelated work. Save a source backup before edits.

## Tasks

- [x] Preview orientation: trace the SurfaceTexture transform, GL texture coordinates, front mirroring, and portrait-locked window; add coordinate and orientation regression coverage, then correct the actual transform order.
- [x] Recording/save failure: reproduce the normal-video crash, correct zero-sized thumbnail scaling after a successful save, and finalize active Studio recordings before pause teardown. Avoid a speculative rewrite of recording callbacks. Fast-stop, failed-start, and device-specific session recovery remain physical-device checks.
- [x] Lens selection: resolve camera IDs independently of list ordering, maintain usable lens controls after recording, and transfer selected facing/lens/zoom between activities without opening duplicate cameras.
- [x] Frame rates: replace arbitrary 4K/60 filtering with capability checks for the selected camera and resolution; add a discoverable FPS control and an explanation when 60 FPS is unavailable.
- [x] Settings: apply consistent dark surfaces, readable rows/summaries, section hierarchy, and back navigation to root and internal settings; retain every setting and preference key.
- [x] Verification: run JVM tests, debug APK build, and appropriate lint; inspect the integrated diff and source backup. Package a new debug APK without replacing previous deliverables.

## Review focus

- Portrait-locked preview must not counter-rotate with the phone; capture metadata still follows capture orientation.
- A selected physical lens must remain selected after Photo/Video changes and recording stop.
- A failed/short recording must restore the camera without publishing success or discarding a completed video.
- Advertised 60 FPS must account for both AE ranges and output size throughput, including nanosecond rounding.
- Settings must work on nested pages and with larger text while retaining camera capability filtering.

## Verification limits

No Android device was connected to ADB at initial inspection. Host tests and APK compilation do not prove OnePlus hardware behavior. Record this clearly with the deliverable and provide focused device checks.

## Completed verification

All 60 JVM tests pass and the final debug APK builds. The final APK installs as an update to the previous APK on the Android 15 emulator; both have the same signing certificate. The previous APK's normal recording reproduced the reported post-save crash. The fixed APK saved a new recording, remained alive, opened Portrait on the rear camera, and returned to Video through Back. Settings pages, dialogs, navigation, and 1.5x font scale were checked visually. Lint completed but still reports 658 existing errors and 222 warnings; this is not a clean lint result.

The source audit found no missing original files. Independent code review found no remaining blocker. Device-specific lens access, full rotation coverage, and actual 60 FPS recording on the user's OnePlus 9 Pro / Android 14 remain unverified. See [the delivery and verification notes](../../oneplus-9-pro-fixes.md).
