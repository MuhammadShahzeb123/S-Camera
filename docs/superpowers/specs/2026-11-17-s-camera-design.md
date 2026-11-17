# S Camera

The user authorizes a new directory and GitHub repository on main containing an improved OnePlus 9 Pro camera application named S Camera, retaining the existing icon. Original sources remain untouched. The user clarified: no Blackmagic UI or its complexity; they want easy-to-use Pro Video and Cinematic Video modes with working professional controls.

## Approach

Keep the existing Java, Camera2, EGL and MediaRecorder engines. A replacement engine would discard established single-file lens switching and photo modes. Add professional controls to the persistent optical-video engine. Use the familiar simple camera layout, with Video, Pro Video and Cinematic modes directly available, a parameter row in Pro Video, and a straightforward color panel for Cinematic. Preserve the existing photo and specialty capabilities with matching branding.

## Behavior

- Expose coupled manual ISO and shutter with explicit Auto, real exposure compensation in Auto, manual focus and white balance only on routes that advertise support. Display current accepted/clamped values. Clamp exposure to the selected frame interval and camera ranges. Reapply controls after a physical-lens handoff; never restart the recorder for a control change.
- Provide GPU color exposure, contrast, saturation, warmth, tint and selectable looks applied identically to preview, snapshots and encoded frames. Monitoring overlays such as zebras must remain preview-only. Neutral settings must be an identity transform. Do not label an ordinary flattened look as sensor log capture.
- Keep one MediaRecorder, audio stream, encoder surface and output file while changing compatible rear lenses. Retain capability-based resolution/FPS routing: never promise 4K optical telephoto when the handset does not advertise it. Format/codec changes are idle-only.
- Use a simple native camera interface, not a dashboard or Blackmagic clone. Pro Video exposes ISO/shutter/WB/focus/exposure; Cinematic offers adjustable looks and access to those same manual controls. Retain format/lens controls, zoom, record time, pause, gallery, settings and photo navigation. Preserve touch focus/pinch zoom, orientation, permission and background behavior.
- Name the application, module, code namespace, media default folder and new repository S Camera / S-Camera. Preserve upstream license, author attribution and historical documentation. Keep launcher icon bytes unchanged. Retain the original internal application ID for OxygenOS auxiliary-camera compatibility; a separate renamed ID hid the additional rear cameras in the handset trial. Install as an update using the matching signing certificate.

## Validation

Run existing JVM regressions plus meaningful exposure/color regression tests. Build debug and instrumentation APKs. Exercise preview, manual changes, grading, recording, lens switches and saved video on the connected OnePlus when available; otherwise state the missing evidence. Review recorder lifetime, capability intersections, unsupported settings and UI lifecycle. Publish the new repository and main branch only after checks and fix review findings. Never claim hardware behavior from unit tests.
