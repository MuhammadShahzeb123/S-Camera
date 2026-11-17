# Continuous zoom and exact lens boundaries

Follow-up for the reported telephoto opening failure: [camera routing fix and latest APK](telephoto-video-routing-verification.md).

The OnePlus 9 Pro requirement applies to the entire supported zoom range, including zooming back out during one normal Video recording:

| Requested zoom | Camera |
| --- | --- |
| Below 1x | Ultra-wide |
| 1x up to, but excluding, 3.3x | Main |
| 3.3x and above | Telephoto |

Exactly 1x selects main. Exactly 3.3x selects telephoto. The interval from 3.2x to just below 3.3x uses main. The starting camera does not lock later lens selection or set the whole zoom range. Every zoom request goes through `OpticalVideoEngine.setZoom()` and the same `OpticalVideoPolicy`; the camera can change while its recorder continues running. Crop within each range is requested zoom divided by that lens's native factor. There is no special production case for 10x.

The follow-up removes a 0.00001 tolerance from lens selection so that even the nearest representable float below a boundary stays on the correct wider lens. No recorder, renderer, camera-session, or UI behavior was otherwise changed in this follow-up.

## Verification

- A boundary regression test failed before the change and passed afterward. It checks the immediately adjacent float values around both 1x and 3.3x, plus the exact thresholds.
- A continuous-range test exercises 6,482 requests: every 0.01x from 0.6x to 33x and back. It checks selected lens, confirmed lens, requested zoom retention, and digital ratio at each step. 33x is the test fixture's maximum; the phone's available lenses and limits determine the actual offered maximum.
- All 76 JVM tests pass, with no failures, errors, or skipped tests. `assembleDebug` succeeds; `git diff --check` passes.
- The APK signature verifies with the same certificate as the preceding APK. Existing source and APK deliverables were retained.
- The three emulator integration tests passed on the preceding pipeline build, as recorded in [pipeline verification](live-optical-video-verification.md). They were not rerun for this boundary comparison change. Physical OnePlus lens access and handoff quality remain unverified because the phone is unavailable.

## APK

Install `../../OpenCamera-live-lens-ranges-debug.apk` over the previous app and use normal Video.

- Size: 7,516,590 bytes.
- SHA-256: `671b6a077d87c1859f288189c833f5ca7781ea036bfaf3bdafc298351f2fb604`.
- Signing certificate SHA-256: `c1a2d719c1d4b59cf8696ed214fbc7d585c271f50c1a251bfd27530702e591c1`.

Evidence: [delivery metadata](../verification/optical-lens-ranges-delivery.json), [policy test results](../verification/optical-lens-ranges-unit-tests.xml).
