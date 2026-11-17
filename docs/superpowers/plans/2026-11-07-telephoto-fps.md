# Keep the OnePlus 29 FPS telephoto in normal Video

The code 97 report confirms the cause: sensor 3 is available and correctly labelled, but has AE ranges ending at 29 FPS and a 34,482,758 ns minimum frame duration. The candidate list contains only 30/60 FPS, so `supportsAny` removes the telephoto before computing shared profiles. Sensor 3 also lacks 3840x2160 output; all three colour rear lenses advertise 1920x1080.

1. Preserve the current source/APKs and add a regression using the supplied AE ranges, frame durations and relevant sizes. Demonstrate failure on the existing candidate list.
2. Generate normal-video candidates from standard frame rates plus advertised AE upper endpoints, capped at the existing normal-video ceiling of 60 FPS. Use the same candidates for per-lens eligibility and the all-lens/encoder intersection. Preserve exact stream/FPS validation; do not relabel 29 FPS as 30.
3. Choose the closest supported resolution and frame rate to the saved preference. For this report, 4K/30 falls back to 1080p/29 with all three lenses retained. Cycle the actual supported frame rates in the UI and include those candidates in diagnostics.
4. Keep the existing single-recorder pipeline and exact zoom boundaries. Test 29 FPS device handoffs in the emulator alongside existing rendering/lifecycle tests, unit tests, build, and lint. Review the delta against the saved ZIP, sign and provide a distinct version 98 APK.

No phone is connected. The metadata cause is confirmed; physical OnePlus lens handoff and sustained recording performance still require handset verification. Previous files remain available.

Completed: two baseline regression failures recorded; shared 29 FPS selection and actual-rate UI implemented; all 90 unit tests and four emulator instrumentation tests passed. The final diagnostic fallback also passed its targeted clipboard test. Code 98 APK, source audit and verification details are in [telephoto FPS verification](../../telephoto-fps-verification.md).
