# Telephoto opening route in normal Video

The user reports that Video remains on main beyond 3.3x, while selecting telephoto in Photo before entering Video works. This update fixes a demonstrated difference between the two code paths. It does not establish that this was the handset's only cause: no OnePlus was connected and no phone camera report was provided.

## Change

Photo previously opened an advertised sensor directly first. Video instead tried a physical output through a logical parent first. These are different camera-opening paths. Video also dropped the camera/physical IDs received from Photo, forwarding only facing and zoom.

`CameraRoutes` now supplies both modes with the same default order: direct advertised camera ID first, logical/physical alternatives afterward. A valid explicit pair supplied by Photo is retained for its matching lens. A main-camera selection cannot pin telephoto requests to main. Hidden IDs are not promoted to direct camera access.

Zoom policy is unchanged: ultra-wide below 1x, main from 1x to below 3.3x, telephoto at 3.3x and above. The same policy applies to zooming out. The original recorder and file remain active during camera replacement.

Camera info now records incoming IDs, available lens routes and compatibility, zoom-boundary requests, and the first capture result from each opened route. It logs logical active ID separately from physical-output result IDs and focal length. Android provides per-physical-camera results for explicitly requested physical outputs; a logical active ID alone does not establish which sensor supplied such an output. [Android TotalCaptureResult documentation](https://developer.android.com/reference/android/hardware/camera2/TotalCaptureResult#getPhysicalCameraTotalResults())

## Tests and review

- Four of five new routing tests failed against the extracted previous route ordering. All five pass after the correction. They cover direct telephoto versus parent routes, retaining an explicitly selected physical parent, rejecting a mismatched main route, excluding hidden direct IDs, and the actual camera IDs selected through all three zoom ranges in both directions.
- All **81 JVM tests pass**, with no failures, errors, or skipped tests.
- The final emulator run passes **all three instrumentation tests**. The expanded engine test starts on main at 1x, records with audio, enters telephoto at exactly 3.3x, crops that input at 6.6x, returns to main at 3.2x, opens ultra-wide at 0.9x, returns to main at 1x, changes cameras while paused, resumes, and saves one file. It checks opened IDs, residual crop, one audio/video track, monotonic timestamps, and no intermediate save.
- The emulator has two real camera devices. Main and ultra-wide use separate test identities on the same rear device; telephoto uses the front device with a test-only rear label. Its synthetic parent route is intentionally not a real logical-array configuration. This verifies lifecycle and routing but does not simulate OnePlus optics. The unit tests specifically prove route preference; a final direct-camera assertion by itself could also pass after a failed parent attempt.
- The unchanged renderer test verifies saved red/held-red/blue frames and monotonic timestamps. The activity test verifies recording controls, background save, audio preference, and camera recovery.
- Independent read-only review found no actionable source regressions in the change relative to the pre-fix ZIP. It explicitly excluded handset lens identity and recording performance from its conclusions.
- Debug app/test builds succeed. Lint has the existing **658 errors and 222 warnings**, with **zero findings in the affected camera-routing/optical-video files**. A newly introduced API-level guard finding was fixed before the final run. `git diff --check` passes.

The first cold-emulator run failed two existing quality checks: video/audio tail difference was 1.335711 s, and the renderer produced fewer than its required 35 frames. All camera-switch assertions had passed. The emulator showed high system/background-service load. After the build completed, the same read-only emulator was left booted, networking was disabled and unrelated Google background apps were stopped; the final run passed with the **same assertions and thresholds**. No encoder, recorder, or rendering changes were made to obtain that pass. The first failures remain recorded; a later pass does not prove handset performance or establish the precise cause of those initial failures.

The preserved final sample contains one H.264 video track (480x640, 123 frames, duration 9.138367 s) and one AAC audio track (8.553042 s). About 13.5 FPS and a 0.585325 s duration difference meet only the emulator smoke-test checks. They do not demonstrate 30/60 FPS throughput or production audio/video synchronization on the OnePlus.

## Deliverable and evidence

APK: `../../OpenCamera-telephoto-routing-debug.apk`. Install over the existing app and use normal Video. Test one recording from 1x to 4x and back through 1x to 0.8x; confirm the physical lens changes in both preview and saved video. If the phone still stays on main, **More > Camera info > Copy** includes the route and sensor evidence needed to distinguish an excluded lens, a failed camera open, and unexpected HAL output.

- Size: 7,516,590 bytes.
- SHA-256: `a30ca5fe9202689f99cbf603695c1cd6ca3db3946a418774360b4d876fef588a`.
- Signature verifies with the previous certificate SHA-256: `c1a2d719c1d4b59cf8696ed214fbc7d585c271f50c1a251bfd27530702e591c1`.
- The APK's DEX files contain the new route helper and direct-first start log; the deliverable hash matches the built and emulator-installed APK.
- Source backup: `../../opencamera-before-telephoto-routing-20261107-103043.zip`. All 760 pre-change source/document files are present. Earlier APKs remain present.

Evidence: [before tests](../verification/telephoto-routing-before.xml), [after tests](../verification/telephoto-routing-after.xml), [first emulator run](../verification/telephoto-instrumentation.txt), [final emulator run](../verification/telephoto-instrumentation-final.txt), [handoff and sensor log](../verification/telephoto-handoff-log.txt), [build log](../verification/telephoto-final-build.txt), [sample video](../verification/telephoto-three-lens.mp4), [sample metadata](../verification/telephoto-three-lens-ffprobe.json), [source audit](../verification/telephoto-source-audit.json).
