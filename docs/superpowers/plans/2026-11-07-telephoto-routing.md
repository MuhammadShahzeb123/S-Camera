# Telephoto handoff investigation and fix

The phone report says normal Video changes cameras but stays on main beyond 3.3x; selecting the telephoto in Photo before entering Video works. No phone is attached to adb. This report establishes that the Photo path can reach the desired lens; it does not establish which route normal Video opened.

Code evidence: `OpClassicController.ocTarget()` tries an advertised direct ID before logical/physical output routing. `OpticalVideoEngine.configureFacing()` reverses that order. `OpticalVideoActivity` receives a `CameraSelection` containing the working camera/physical IDs but passes only facing and zoom into the engine. Earlier tests exercised abstract lens selection and cameras with only one opening route, so they cannot establish that an alternate route supplies the intended physical sensor.

Hypothesis: preferring a different opening route than Photo allows the parent/HAL path to behave differently, and dropping the supplied identity prevents reliably reusing a working selection. This is a concrete app-side inconsistency to fix, but proving that it is the phone's sole cause requires its camera report or a phone run.

- [x] Reproduce the route mismatch with tests containing an advertised telephoto ID and a logical-parent alternative.
- [x] Share the route selection between Photo and normal Video: direct ID first by default; preserve a valid supplied camera/physical pair.
- [x] Keep native zoom boundaries and all-lens range, and verify forward/reverse changes while one recorder stays active.
- [x] Log actual capture-result sensor metadata and available/excluded lens routes so a phone report can distinguish routing from capability filtering.
- [x] Run JVM tests, emulator regression tests, debug build and relevant static checks; package a new APK without replacing previous deliverables.

Results and remaining handset limitation: [verification](../../telephoto-video-routing-verification.md).

Scope: camera routing and evidence for this telephoto failure. No settings redesign, codec changes, FPS changes, or changes to other modes' recording pipelines.
