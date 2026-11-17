# Live optical video implementation plan

> Workflow: systematic debugging, test-driven camera policy, independent renderer/engine implementation, integrated emulator checks and code review. The user selected an app-managed pipeline and cannot connect the phone right now.

**Goal:** Record one continuous video/audio file while changing rear camera inputs at optical boundaries, then use digital crop on the selected lens.

**Architecture:** Add an isolated normal-Video activity using the existing camera UI. Camera2 produces SurfaceTexture frames; a dedicated EGL renderer owns the preview and one persistent MediaRecorder input surface. Camera device/session changes do not replace or stop the recorder. Retain the previous texture until the next camera supplies a frame. Keep the existing classic and specialty engines for their other modes and external capture intents.

**Math:** global zoom = native optical factor * digital ratio. At 10x, a 3.3x telephoto uses 10/3.3 = 3.030303x digital crop. Choose the longest available lens at or below the requested global zoom. Never compute the overall range from only the starting camera.

**Constraints:** Preserve source, previous APKs, working settings, and other modes. No guessed camera IDs or claim that simultaneous rear cameras are guaranteed. Use actual stream-size/FPS capabilities. Lock the encoder format for the clip. Keep lifecycle and camera work off the UI thread. Document phone-only verification limits.

## Tasks

- [x] Research document: Android primary sources, existing code causes, pipeline options, correct zoom examples, device evidence and acceptance criteria.
- [x] EGL renderer: persistent preview/encoder targets, independent camera input slots, first-frame handoff, normalized output timestamps, crop and capture rotation, resource teardown.
- [x] Optical video engine: capability-filtered lenses/profiles, global zoom policy tests, sequential camera handoff, continuous recorder/audio, latest-request wins, rollback, pause/save lifecycle, useful diagnostics.
- [x] UI integration: route normal Video on multi-lens Camera2 devices to the new engine; preserve camera selection, resolution/FPS controls, focus/EV/torch, recording controls, gallery, settings, and mode navigation.
- [x] Verification: unit regressions, debug APK, emulator recording with camera changes and saved-file inspection, independent review, source audit, new named APK and accurate notes.

## Review focus

- Stop, pause, Back, or activity shutdown during camera opening must not corrupt or restart the recording.
- Failed/unavailable targets must not be presented as successful optical handoffs.
- One encoder, one audio stream, one file, and increasing timestamps must survive lens changes.
- Encoder resolution/FPS must remain fixed and supported by every selectable recording lens.
- Camera resources close in callback order; a stale callback cannot take over a later session.

Sequential camera opening may briefly hold the last frame while audio continues. Smooth overlapping handoff is an optimization only after the phone proves the concurrent combination works. No OnePlus 9 Pro hardware result is available in this environment.

Final verification: 74 JVM tests and all 3 emulator instrumentation tests pass. Debug/app-test APK builds succeed. Lint retains the baseline 658 errors/222 warnings and has no findings in the new optical-video files. All 750 pre-change files remain. Delivery: `OpenCamera-live-optical-zoom-debug.apk`; see [verification notes](../../live-optical-video-verification.md) for the recorded sample's throughput and A/V limits, supported settings, and remaining phone checks.
