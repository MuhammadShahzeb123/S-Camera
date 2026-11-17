# S Camera 2.0.0 — OnePlus 9 Pro verification

Validated on 2026-11-17 with the connected **OnePlus 9 Pro LE2121, Android 14**. This report describes the new S Camera build, rather than the earlier reports in this directory.

## Build and automated checks

- Java 17 / Android SDK 36: `:camera:testDebugUnitTest`, `:camera:assembleDebug` and `:camera:assembleDebugAndroidTest` succeed.
- **114 JVM tests pass**, with no failures, errors or skips. Coverage includes manual sensor range intersections, frame-duration limits, preserving the measured exposure partner, EV quantization, legal WB gains, neutral grading, optical routing, format eligibility and zoom transitions.
- **10 distinct handset instrumentation tests pass**: the nine-test combined acceptance run plus a separate 4K recording check. Tests use real OnePlus cameras, not an emulator or simulated rear-lens inventory.
- `lintDebug` completes. The inherited **658 errors** remain, largely missing translations; this repository is not lint-clean. The final report also contains 225 warnings. Newly added camera-control and renderer code introduces no lint errors.
- All **21 launcher-related assets** match the supplied source by SHA-256. GPL text and original author attribution are retained.
- APK signature verifies with the same certificate as the supplied custom build: `c1a2d719c1d4b59cf8696ed214fbc7d585c271f50c1a251bfd27530702e591c1`.

## Observed handset behavior

- Photo opens the actual main, ultra-wide and telephoto routes through the visible lens controls. Photo and Night save decodable images. Photo recreation and transitions from saved video state remain still captures; Pro Photo and Panorama initialize correctly.
- Pro Video requests manual ISO 200 and shutter 1/120, then checks the sensor's returned values within explicit tolerances (12% sensitivity, 8% exposure duration). Manual requests and accepted values survive compatible rear-lens handoffs.
- At 1080p/29, ultra-wide, main and telephoto feed one recording. Camera-service traces show the physical routes changing between IDs 2, 0 and 3. The test checks that recording remains active and no intermediate video is published. Pause/resume and returning to main remain in the same clip.
- Saved clips contain one video track and one audio track, real samples, unique video presentation times and increasing audio sample times. Audio/video final-sample times differ by less than 200 ms. This checks track timing, not perceptual lip sync or long-duration drift.
- A separate **4K/30** test records main and ultra-wide in one file and verifies encoded dimensions **2160 × 3840** in portrait. The sensor format is 3840 × 2160; display orientation is handled separately. Telephoto remains discoverable at 4K; selecting it while idle chooses its supported 1080p/29 format.
- Video, Pro Video and Cinematic transitions reuse the optical engine. Camera control edits replace repeating requests without stopping the recorder.
- The GPU output test records a colored producer with a monochrome grade, decodes the saved frames and checks the result. It also checks neutral snapshots, visible preview zebras and their absence from snapshots and the encoded output. The original retained-frame/blended-handoff renderer test passes.
- All five static shortcuts resolve against the installed application. Existing launcher/widget and Quick Settings component identities resolve through compatibility aliases/forwarders.

## Issues found and corrected during verification

Changing the Android application ID to `com.shahzeb.scamera` hid auxiliary cameras on this firmware. The final build retains `net.sourceforge.opencamera` internally while using the S Camera display name, `com.shahzeb.scamera` source namespace and `camera` module. The temporary separate-ID installation was removed; its app data was retained. Existing media was not deleted.

The lens bar now keeps every compatible-format lens discoverable and derives its active indicator from the confirmed sensor. Digital main-camera zoom cannot falsely highlight telephoto. Idle optical-lens selection can adapt the format; incompatible recording selections explain the restriction without ending the clip.

Independent review also found and corrected rounded manual-value labels, a missing sensor-specific WB transform after handoff, an incorrect shortcut package, and legacy widget/tile component compatibility. Manual WB now waits for that sensor's Auto color matrix before applying Kelvin.

The phone's AVC encoder uses B frames, so extractor presentation times can arrive in decode order. Tests check unique presentation times rather than treating legal B-frame reordering as a failure. This handset's time-based frame retrieval repeatedly returned the first keyframe for the renderer samples; frame-index decoding verified the actual later frames correctly.

## Practical limits

- Firmware does not advertise 4K telephoto in this path. Use 1080p/29 for physical switching across all three rear color lenses. Format changes are idle-only.
- Camera reopening can hold the preceding image and then blend to the new lens. One file continues, but stock-camera handoff speed and live motion during the reopening interval are not promised.
- Kelvin conversion is approximate. Calibrated white-point accuracy, manual focus/EV readback across every lens, sustained thermal performance, prolonged audio synchronization and every inherited specialty option have not been exhaustively measured.
- Cinematic looks process ordinary RGB output; they do not provide RAW, sensor log, extra dynamic range or Blackmagic feature parity.

Local build logs, device test transcripts, lint reports and screenshots are preserved under the ignored `verification/` directory. Camera footage and screenshots are intentionally excluded from GitHub. The original `opencamera-4k-restored-src` directory remains unchanged.
