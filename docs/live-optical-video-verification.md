# Live lens switching in normal Video

Latest APK and continuous-range checks: [Exact lens ranges](live-optical-zoom-ranges.md). The evidence below documents the preceding pipeline build.

This follow-up implements the user's clarified requirement: one recording that can change the camera supplying its frames. The previous build improved idle camera handoff but still locked recording to its starting lens.

APK: `../../OpenCamera-live-optical-zoom-debug.apk` (7,516,590 bytes). SHA-256: `3467af593b255c2e789e4d168be2201d52a2478715ec5ce8483187141c4578a2`. Signature verification passes and the certificate matches the previous APK. Install over the prior version; use normal **Video** mode.

## Implementation

Normal Video on Camera2 devices exposing multiple accessible rear lenses now uses `OpticalVideoActivity`, `OpticalVideoEngine`, and `OpticalVideoRenderer`. Existing Photo, Pro Video, slow motion, time lapse, and specialty engines remain available. External capture intents retain their original result handling.

One MediaRecorder and one audio stream remain active for the clip. Camera2 feeds an EGL texture input; the renderer feeds the preview and the same encoder surface. Changing cameras closes the old camera session, opens the requested camera, and waits for its first frame before changing the visible/recorded input. The previous texture stays visible during that interval. Camera changes never call recorder stop/start. Latest zoom requests supersede older requests; a failed handoff attempts recovery to the previous lens and reports the failure.

The zoom policy selects the longest available native focal factor not greater than the requested global zoom, then crops by `globalZoom / nativeZoom`. Using the app's nominal OnePlus labels, 10x selects the 3.3x telephoto with a 3.030303x digital crop. The zoom range comes from all compatible lenses, not the starting ultra-wide camera.

The encoder dimensions, codec, and FPS remain fixed for a clip. Normal Video offers profiles shared by all selectable lenses. A 60 FPS or 4K option may therefore be unavailable here if one lens cannot supply it; Pro Video retains individual-lens options. The new path supports MP4 with H.264/HEVC, audio preference/permission/source/channels, bitrate, per-lens torch/stabilization, focus/EV, snapshots, pause/resume, pinch/slider/volume zoom, and the existing settings navigation. WebM and 3GPP produce an explicit unsupported-format message.

Source and previous deliverables were backed up before implementation. No source files or previous APKs were deleted.

## Verified evidence

Final results: **74 unit tests and 3 emulator instrumentation tests pass**. `assembleDebug` and `assembleDebugAndroidTest` succeed. `lintDebug` completes with the existing 658 errors and 222 warnings; no lint findings remain in the new optical-video files. `git diff --check` passes. The source audit checked 750 pre-change files and found none missing. Source backup: `../../opencamera-before-live-zoom-20261107-072637.zip`.

Machine-readable results: [delivery](../verification/optical-delivery.json), [source audit](../verification/optical-source-audit.json), [final instrumentation run](../verification/optical-instrumentation-final.txt), [camera handoff log](../verification/optical-camera-handoff-log.txt). Final UI: [idle](../verification/optical-video-idle-final.png), [resumed](../verification/optical-video-resumed-final.png).

- JVM tests cover optical boundaries, the 10/3.3 calculation, the all-lens maximum, FPS rounding, stale first-frame callbacks, latest-request handling, rollback, crop geometry, and encoder-clock monotonicity, alongside the existing regression suite.
- The renderer instrumentation test records red and blue independent input surfaces into one MP4. It checks the held red frame while the input is replaced, the blue frame afterward, one video track, and strictly increasing sample timestamps.
- The Camera2 instrumentation test uses the emulator's real rear and front cameras, mapped **only inside the test** to nominal 0.6x/3.3x rear lenses. It opens the first camera, records with audio, changes to the second at 10x, returns, pauses, changes cameras while paused, resumes, and saves one file. It checks one video track, one audio track, increasing timestamps, no intermediate save, and the telephoto crop calculation. This verifies device/session replacement; it does not simulate physical OnePlus optics.
- The activity test checks remembered video profile, default Volume Down pause/resume, configured volume-key zoom while recording, saving when backgrounded, audio disabled despite granted microphone permission, and camera recovery on resume.
- Independent review checked recorder lifetime, first-frame confirmation, pending-camera shutdown, profile persistence/settings return, gestures, focus, and snapshots. Identified regressions were corrected.

An inspected emulator sample (`verification/optical-camera-switch.mp4`) contains one H.264 video track (480x640) and one AAC audio track. Its video duration is 7.502489 s and audio duration 6.971833 s. The approximately 0.53 s duration difference and roughly 12.7 FPS achieved by this software-rendered emulator are **not** evidence of production-quality A/V synchronization or 30/60 FPS throughput. The automated A/V duration-difference check uses a 0.75 s smoke-test tolerance; a physical-device quality check must be stricter and use visible/audible timing cues.

The [research document](oneplus-9-pro-live-optical-zoom-research.md) links the primary Android and OnePlus sources behind the architecture and includes the device acceptance checklist. No OnePlus was connected; the user explicitly reported that it was unavailable.

## Remaining phone checks

Install the new live-optical-zoom APK, use normal Video at a shared profile (start with 1080p/30 if offered), begin on ultra-wide, and drag through main, telephoto, and 10x without stopping. Confirm the preview and saved clip change lenses, then repeat in reverse. Check rapid zoom changes, pause/resume, backgrounding, portrait/landscape, and audio timing.

A short held frame during camera reopening is possible. Simultaneous rear-camera prewarming, stock-camera transition smoothness, OnePlus auxiliary-camera access, and sustained 60 FPS are not established by these tests. Existing camera-ID calibration/labels still require confirmation against this phone's exposed inventory. Camera info logs report the opened route, confirmed lens, requested global zoom, residual digital zoom, and whether the encoder remained active.

The current shared pipeline saves through the existing StudioSaver media path (DCIM/OpenCamera). Advanced classic-only recording features such as a custom SAF save folder, automatic file splitting, duration limits/restarts, subtitles, and external capture intents remain on the classic recording paths; they have not been ported into the new normal-Video pipeline. Use Pro Video for those classic recording options.
