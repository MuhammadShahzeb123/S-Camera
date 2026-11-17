# S Camera

A simple camera app for the OnePlus 9 Pro, based on Open Camera. **Photo**, **Video**, **Pro Video**, and **Cinematic** are directly available. The original launcher icon is preserved.

## Controls

- **Photo:** lens buttons select ultra-wide, main and telephoto; the camera-switch button selects the front camera. More contains other photo and specialty modes.
- **Video:** automatic recording, zoom and lens selection.
- **Pro Video:** tap ISO, Shutter, WB, Focus or EV, then drag the adjustment scale. **A** restores Auto. ISO and shutter lock together; EV applies in Auto exposure. The control row shows accepted values.
- **Cinematic:** choose Natural, Cinema, Soft or B&W. **Adjust**, or the color icon, opens brightness, contrast, saturation, warmth and tint. The controls icon also provides manual capture controls.
- Format buttons select resolution and FPS. The settings icon includes stabilization, preview-only highlight zebras and full settings.

Color changes affect the saved video and preview. They are RGB looks, not RAW or sensor log capture. Kelvin white balance is approximate, rather than a calibrated color-temperature measurement.

## OnePlus lenses

The first S Camera video session starts at **1080p/29 FPS**, which supports all three rear color lenses on the tested LE2121 / Android 14 firmware. Later format choices are remembered.

Main and ultra-wide also expose 4K/30; telephoto does not expose that format here. All lens buttons remain visible. Selecting telephoto while idle chooses its compatible format. During recording, an incompatible lens selection explains the limit without stopping the clip. Pinch/slider zoom stays within the selected format's compatible lenses.

Compatible rear-lens changes retain **one encoder, one audio stream and one file**. A held frame and short blend cover sequential camera reopening. Stock-camera transition speed and uninterrupted live motion during reopening are not guaranteed.

The internal application ID **`net.sourceforge.opencamera` is intentionally retained**: a new package ID hid auxiliary cameras on this OnePlus. The source namespace is `com.shahzeb.scamera`, the Android module is `camera`, and the displayed name is **S Camera**. Install as an update over the matching custom build. Existing media stays in place; new captures use the configured folder, defaulting to `DCIM/S Camera`.

## Build

Install JDK 17 and Android SDK platform 36 / build tools 36.0.0. Set `ANDROID_HOME` or provide an untracked `local.properties` with `sdk.dir`.

```sh
./gradlew :camera:testDebugUnitTest :camera:assembleDebug
```

On Windows use `gradlew.bat`. The APK is `camera/build/outputs/apk/debug/camera-debug.apk`. This is a debug-signed development build. Signing keys, recordings and machine-specific settings are excluded from the repository.

GitHub Actions runs JVM tests and builds an APK. Handset acceptance tests are in `camera/src/androidTest`. See [current verification](docs/s-camera-verification.md); other files in `docs` describe inherited code and historical builds.

## License

Based on **Open Camera by Mark Harman**, copyright 2013–2026, and the supplied custom OnePlus source. Distributed under **GPL version 3 or later**. See [LICENSE](LICENSE), [upstream source notes](source-notice.txt), and bundled AndroidX and Material icon licenses. Historical documents and author credits retain their original names.
