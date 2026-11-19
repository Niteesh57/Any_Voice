# Stage 1 validation

## English-to-Telugu integration — 2026-11-19

- Android `:app:assembleDebug :app:lintDebug -PtargetAbi=arm64-v8a`: passed. The packaged phone build is approximately 41 MB and retains application ID `dev.hiprobe`.
- Backend: 13 tests passed on Windows and in the Linux Docker worker, covering provider requests, Telugu caption parsing, token permissions, deduplication, UTF-8 chunking, authentication and session cleanup. Provider calls are mocked.
- Linux native Agora SDK initialization and release passed without joining a real channel.
- Emulator UI rendered the Any Voice home screen and connection dialog. Starting without connection settings opened setup before capture or muting.
- Separate translation API/model settings were removed. Agora STT translation supplies Telugu text; Conversational AI Engine `speak` uses Murf Falcon for audio.
- Live cloud translation, voice availability, latency and physical-device translated playback remain unverified pending valid provider credentials and enabled Agora services. The original probe checks below do not validate this new cloud pipeline.

## Original local probe

Product name: **Any Voice**. The initial prototype was branded Hi Audio Probe. The user subsequently reported it working on their OnePlus 7. Device-specific version information and a reproducible physical-device test log have not yet been recorded. The application ID stays `dev.hiprobe` so the renamed build can update the initial install.

Built on 2026-11-18 using JDK 21, AGP 9.0.1, Gradle 9.3.1 and SDK 36.

- `:app:assembleDebug :app:lintDebug :test-source:assembleDebug`: passed. Lint has no errors; remaining warnings concern target/tool versions and English diagnostic strings.
- APK signature verified using `apksigner verify --verbose` (v2 signature).
- Emulator: Pixel 7a AVD, Android API 37, x86_64, 4 GB RAM. Headless with host audio disabled: physical audibility was **not** evaluated.
- Installed final probe APK and developer-only `test-source` APK.
- Approved full-screen MediaProjection consent. No virtual display is created by the app.
- Separate-UID test source produced a continuous 440 Hz, capturable `USAGE_MEDIA` tone in a foreground playback service.
- With replacement enabled: `dumpsys activity service dev.hiprobe/.ProbeService` reported -15 dBFS and rising non-silent muted-buffer counts (52, then 157). `dumpsys audio` showed media volume 0, muted.
- Android playback diagnostics showed the TTS engine starting repeated tracks with `USAGE_ALARM`, `CONTENT_TYPE_SPEECH`, and capture-blocking flags. This verifies routing/playback requests, not what a person hears on a physical speaker.
- Comparison mode restored media volume to 5, paused hi, and accumulated 80 non-silent comparison buffers. Switching back muted media and resumed hi.
- After stopping the test source, captured level fell to -96 dBFS and hi stopped. The replacement voice did not sustain capture activity.
- Floating Stop restored media volume to 5 and ended the service. No AndroidRuntime exceptions were observed.

The first attempt used a 2 GB emulator and suffered low-memory process kills during consent; successful checks above used a fresh 4 GB emulator.

## Still to verify on OnePlus 7

Real speaker audibility, YouTube allowing capture, installed OxygenOS behavior, pause/resume, revoked projection consent, screen lock, and volume recovery after abnormal process exit. Calls, protected playback, Bluetooth, and translation are outside this prototype's validated scope.

An attached USB device was unauthorized, so no physical-device installation or test has been performed.

## Developer diagnostics

```powershell
adb -s DEVICE_SERIAL shell dumpsys activity service dev.hiprobe/.ProbeService
adb -s DEVICE_SERIAL shell dumpsys audio
```

`test-source` is only a repeatable developer fixture and is not included in the distributed probe APK.
