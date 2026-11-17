# OnePlus 9 Pro: retain the 29 FPS telephoto in Video

## Confirmed failure

The user's `1.56.2-lens-report.1` / code 97 report is from a OnePlus LE2121 running Android 14. It exposes main 0, ultra-wide 2 and telephoto 3 as independent Camera2 devices. The normal Video log enters the app-managed optical pipeline and discovers all three, then marks sensor 3 `video-compatible=false`. Its capability data explains that decision:

| Camera | Relevant AE ranges | Minimum frame duration | 3840x2160 | 1920x1080 |
| --- | --- | --- | --- | --- |
| Main 0 | [24,24], [15,30], [30,30] | 33,333,333 ns | Yes | Yes |
| Ultra-wide 2 | [24,24], [15,30], [30,30] | 33,333,333 ns | Yes | Yes |
| Telephoto 3 | [24,24], [4,29], [29,29] | 34,482,758 ns | No | Yes |

The old engine probed only 30/60 FPS. That excluded the telephoto entirely, leaving the UW/main zoom stops and their 10x global maximum. This was a profile-selection bug; the report does not support the earlier suspicion that the wrong opening route was the cause of this remaining failure.

## Change

Video collects advertised AE upper endpoints from the sensors and their opening routes before pruning any lens. It adds standard normal-video rates and the saved preferred rate, then applies the existing AE, exact-size, frame-duration, opening-route and encoder checks. Normal-session candidates remain capped at 60 FPS. No capability check is relaxed to label 29 as 30.

All retained lenses must support every offered profile. For the supplied camera metadata and an encoder supporting the requested format, saved 4K/30 settings now select **1080p/29** with all three rear colour lenses. Lower resolution preferences are preserved when supported. 24/25 FPS and the advertised lower rates are also offered when all camera and encoder checks pass; the FPS control cycles the actual available list. A saved 29-FPS choice is rechecked on re-entry, even when a newly selected camera only advertises a broader range such as [15,30].

The opening route, zoom boundary and recorder architecture remain the same: below 1x uses UW, 1x to below 3.3x uses main, and at/above 3.3x uses telephoto, with digital crop calculated as global zoom divided by the selected lens's native zoom. A single recorder/audio track continues through sequential camera handoffs. The selected 29 FPS is passed to both MediaRecorder and the renderer clock. The main/UW AE request may use an advertised variable range containing 29; the renderer schedules the 29-FPS output timeline. Telephoto can use its fixed [29,29] range.

About and Camera info now check one report-wide candidate list so the telephoto-contributed 29 FPS appears in main/UW checks too. The app has a distinct version: **1.56.2-telephoto-fps.1**, code **98**.

## Verification

- Two regressions using the supplied metadata failed against the old 30/60-only candidate list. The completed suite passes all **90 JVM tests**, including the full three-lens profile intersection, 4K/30 to 1080p/29 selection, supported FPS cycling, exact zoom boundaries and clock timing at 29 FPS.
- All **four emulator instrumentation tests passed**. The engine test records at requested 29 FPS, switches actual emulator Camera2 devices through a synthetic UW/main/tele arrangement, checks all three zoom stops, zooms above/below both boundaries, pauses/resumes, and saves one video track plus one audio track with increasing timestamps. The Activity test retains a saved 29 FPS preference, clicks the actual FPS control, records, and finalizes on backgrounding. The renderer replacement and About clipboard tests also pass.
- After the report-wide candidate change and a fallback for malformed vendor AE metadata, the About clipboard test passed again on the delivered APK. It checks that each routed sensor's table includes the saved 29 FPS candidate. Independent read-only review found no critical or important production-path issues; both diagnostic findings were addressed.
- App and test APK builds pass. Lint still reports the existing **658 errors and 222 warnings**, with none in the changed optical-video/diagnostic classes. No lint cleanup was claimed or performed.
- Sources were preserved in `../../opencamera-before-telephoto-fps-20261107-131738.zip` before the change. The source audit found all **767** baseline files still present. Previous APKs remain available.

These tests establish the metadata selection correction and recording lifecycle on the emulator. They do **not** verify physical lens switching, image quality, sustained 29 FPS or codec behavior on the OnePlus handset. The emulator uses synthetic lens labels and software rendering; it is not an optical or performance substitute for the phone. No 4K or 60-FPS telephoto support is claimed.

APK: `../../OpenCamera-telephoto-29fps-debug.apk`, **6,386,801 bytes**, SHA-256 `5317f3d808d886285d955b11f15dc9386e66d58a3fb66160622bb729a83bcd72`. Version and signature verification pass, with the same signing certificate as earlier builds. Installation over the existing emulator app succeeds without uninstalling it.

Evidence: [baseline failures](../verification/telephoto-fps-before.txt), [delivery build](../verification/telephoto-fps-delivery-build.txt), [four instrumentation tests](../verification/telephoto-fps-instrumentation.txt), [runtime log](../verification/telephoto-fps-runtime-log.txt), [delivery About test](../verification/telephoto-fps-diagnostics-delivery.txt), [source audit](../verification/telephoto-fps-source-audit.json), [signature](../verification/telephoto-fps-signature.txt), [delivery](../verification/telephoto-fps-delivery.json).

## Android API references

- [Camera2 AE ranges](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) and [AE range request](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#CONTROL_AE_TARGET_FPS_RANGE): choose from the camera's advertised ranges.
- [SurfaceTexture stream durations](https://developer.android.com/reference/android/hardware/camera2/params/StreamConfigurationMap#getOutputMinFrameDuration(java.lang.Class%3CT%3E,%20android.util.Size)): the selected output size also limits the capture rate.
- [MediaRecorder frame rate](https://developer.android.com/reference/android/media/MediaRecorder#setVideoFrameRate(int)): encoder settings must be supported by the device. This pipeline queries codec size/rate support and handles recorder preparation/start errors.
