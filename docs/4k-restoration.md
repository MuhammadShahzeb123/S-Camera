# OnePlus 9 Pro: restore 4K without prewarmed cameras

This source tree starts from `opencamera-before-prewarmed-lenses-20261111.zip`
(SHA-256 `9379a2549dc52899428c49b532446ea9a945cf78177811d03021f0cb1a822d1b`).
The archive and the later `opencamera-src` tree are preserved. This build is
`1.56.2-4k-restored.1`, version code **101**, so it can update the code 100 build
when signed with the same certificate.

## Research and the handset's Camera2 capabilities

[OnePlus's specifications](https://www.oneplus.com/us/9-pro/specs) advertise 4K
recording, but do not promise every resolution on every lens through Camera2.
A [first-hand OnePlus LAB review](https://www.oneplus.in/store-app/discover/-the-lab--oneplus-9-pro-review---a-giant-leap-for-oneplus-)
reports 4K/30 on main and ultra-wide and a 1080p/30 telephoto limit in the stock
camera. These stock-camera claims do not establish third-party API access.

The more specific evidence is the LE2121 / Android 14 Camera2 report previously
recorded in this baseline's [telephoto investigation](telephoto-fps-verification.md).
It reports independently accessible main 0, ultra-wide 2 and telephoto 3:

| Lens | 3840 x 2160 at 30 FPS | 1920 x 1080 at 29 FPS |
| --- | --- | --- |
| Main, ID 0 | Advertised | Advertised |
| Ultra-wide, ID 2 | Advertised | Advertised |
| Telephoto, ID 3 | No 4K stream; AE tops out at 29 FPS | Advertised |

This session did not obtain a fresh handset dump. The application checks current
metadata rather than hardcoding that report or the model name. A format requires
a reachable camera route, matching SurfaceTexture output size, supported AE
range, sufficient minimum frame duration and support from the selected AVC/HEVC
surface encoder at the actual encoded dimensions. Android documents these
[stream size and frame-duration checks](https://developer.android.com/reference/android/hardware/camera2/params/StreamConfigurationMap#getOutputMinFrameDuration(java.lang.Class,%20android.util.Size)).
Firmware may expose different limits. The app does not bypass them.

## Cause and change

The baseline intersected the formats of every rear lens. Keeping the 1080p/29
telephoto therefore removed 4K and 30 FPS even from main and ultra-wide.

Video now builds a catalog of formats with the eligible lenses for each format.
A format is offered when the encoder and at least one camera route support it.
The selected format determines the zoom stops and permitted handoffs:

- **4K/30:** main and ultra-wide on the reported handset. Zoom above 1x uses
  digital crop on main; it does not masquerade as optical telephoto.
- **1080p/30:** main and ultra-wide on the reported handset.
- **1080p/29:** all three rear colour lenses, including optical 3.3x telephoto.

Returning to a lower format restores its eligible lenses. Format changes rebuild
the zoom policy and retain the global magnification within the new supported
range. A failed format change follows the existing recovery path to the last
working format/lens. A camera is not marked ready for the new format while its
old-format session is still live. Format changes remain disabled during recording.

Camera opening remains sequential: close the current device and wait for its
closure callback before opening the next. This build contains no prewarming or
concurrent-camera group implementation. Smooth zoom and transition rendering are
the baseline implementation. Portrait and the still-photo controller are unchanged.

## Use

Enter normal **Video**, then tap the resolution control until **4K** and the frame
rate control until **30 FPS**. Existing saved settings are respected, so installing
the update does not forcibly change a saved 1080p/29 preference. At 4K only the
eligible lens buttons appear. Select **1080P**, **29 FPS** to use optical telephoto
on the reported firmware. The resolution and frame rate controls are unavailable
while recording.

## Verification

The regression suite uses the existing handset-metadata fixture. Six new cases
failed against the old all-lens intersection after a behavior-preserving extraction
of its catalog logic. Cases cover 4K/30 retention, 1080p/30 lens filtering,
telephoto availability at 1080p/29, main-camera digital zoom at the telephoto
boundary in 4K, encoder rejection, a missing opening route and zero eligible
cameras. The before log is `verification/4k-profiles-before.txt`.

The engine instrumentation test also exercises changing and restoring the format
before recording, restoration of lens buttons and rejecting format changes during
a recording. Compiling an instrumentation test does not establish it passed on a
device.

The complete JVM suite passes **105 tests**, with zero failures, errors or skips.
The app and instrumentation APKs build successfully. Signature verification passes
with the same certificate as the prior custom builds. Lint still reports the
baseline's **658 errors and 221 warnings**, with no findings in the changed Java
files; the repository is not lint-clean. The source audit retains all **825**
baseline files, with six existing files changed and this document added.

Final build, test totals, signature and source audit are recorded in
`verification/4k-delivery.json` and the adjacent verification files.

**Handset verification remains outstanding.** Both installed ADB binaries fail
before connecting with `Cannot mkdir '\.android': Permission denied` in this
managed Windows session. No installation, emulator test or physical 4K recording
is claimed. On the OnePlus, verify a saved 4K/30 clip from main and ultra-wide,
audio playback, sequential lens switching, return to 1080p/29 telephoto, and
background/resume behavior. Check both saved video dimensions and motion; metadata
eligibility alone does not prove sustained camera or encoder performance.
