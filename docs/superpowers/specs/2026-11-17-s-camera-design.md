# S Camera

The user authorizes a new directory and GitHub repository on main containing an improved OnePlus 9 Pro camera application named S Camera, retaining the existing icon. Original sources remain untouched. This is an original professional camera interface inspired by direct cinema-camera controls, not an assertion of Blackmagic feature parity.

## Approach

Keep the existing Java, Camera2, EGL and MediaRecorder engines. A replacement engine would discard established single-file lens switching and photo modes. A cosmetic redesign alone would leave normal Video without manual sensor controls. Add professional controls to the persistent optical-video engine and give it a dedicated dark, readable interface. Preserve the existing photo and specialty capabilities with matching branding.

## Behavior

- Expose coupled manual ISO and shutter with explicit Auto, real exposure compensation in Auto, manual focus and white balance only on routes that advertise support. Display current accepted/clamped values. Clamp exposure to the selected frame interval and camera ranges. Reapply controls after a physical-lens handoff; never restart the recorder for a control change.
- Provide GPU color exposure, contrast, saturation, warmth, tint and selectable looks applied identically to preview, snapshots and encoded frames. Monitoring overlays such as zebras must remain preview-only. Neutral settings must be an identity transform. Do not label an ordinary flattened look as sensor log capture.
- Keep one MediaRecorder, audio stream, encoder surface and output file while changing compatible rear lenses. Retain capability-based resolution/FPS routing: never promise 4K optical telephoto when the handset does not advertise it. Format/codec changes are idle-only.
- Use a compact cinema-style interface with visible ISO/shutter/WB/focus/exposure, format and lens controls, record time, clear recording status, pause, gallery, settings and photo navigation. Do not bury manual Video controls in another engine. Preserve touch focus/pinch zoom, orientation, permission and background behavior.
- Name the application, module, media default folder and new repository S Camera / S-Camera. Preserve upstream license, author attribution and historical documentation. Keep launcher icon bytes unchanged. Use a distinct application ID so this can coexist with the old app.

## Validation

Run existing JVM regressions plus meaningful exposure/color regression tests. Build debug and instrumentation APKs. Exercise preview, manual changes, grading, recording, lens switches and saved video on the connected OnePlus when available; otherwise state the missing evidence. Review recorder lifetime, capability intersections, unsupported settings and UI lifecycle. Publish the new repository and main branch only after checks and fix review findings. Never claim hardware behavior from unit tests.
