# Stage 1 validation

## Streaming audio 0.8 — 2026-11-19

Later timing review: 15 real voice requests had median first audio 697 ms (265–752 ms). Total generation/control time was 90331 ms for 79950 ms of PCM, a 10381 ms difference. The path includes Agora, Murf, RTC and turn signaling; this is not isolated Murf model latency. Text request limits were increased from 256 to 512 UTF-8 bytes without waiting to fill requests, and configurable Murf rate 15 was enabled. A controlled direct Falcon test produced 5559 ms of PCM at rate 15 versus 5910 ms at rate 0 for the same text; speech rate is not an end-to-end latency guarantee. Translation and queue-wait instrumentation was added. 18 tests and live pre-completion packet streaming passed after tuning.

- Full-phrase buffering, the 500 ms text batching delay, 350 ms tail hold, and Android's per-packet completion wait were removed. Generated PCM now becomes available in 50 ms packets over one persistent framed HTTP stream.
- Android build/lint and 18 backend tests passed. Tests include live queue wakeup, binary framing, resume after a sequence, byte preservation, and packets becoming available before provider completion without interrupting the current TTS command.
- Real Agora Conversational AI + Murf Falcon test: first 1600-byte packet was available while the turn was still speaking, approximately 456 ms after submitting known Telugu text. 147 packets totaling 7350 ms were retained in order. This is text-to-first-PCM timing, not English-audio-to-Telugu latency.
- Phone uses approximately 100 ms initial buffering and writes continuously to one persistent AudioTrack. Playback acknowledgements are independent of writes. End-to-end audibility and long-video lag require user listening; packet size is not an end-to-end latency guarantee.

## Queued live audio 0.7 — 2026-11-19

- Android build/lint passed. Version 0.7 installed on OnePlus 7; backend rebuilt for RTM completion events and authenticated PCM delivery.
- 17 backend tests passed on Windows and Linux, including byte-preserving audio delivery, authentication, ordered manifests, cumulative/idempotent acknowledgement, cleanup, unfinished audio not being exposed during internal pauses, and processing text older than the removed age cutoff.
- Real Agora Conversational AI + Murf Falcon test produced two complete, nonzero 16 kHz PCM clips (2150 ms and 1670 ms), retained both in order, and removed them after acknowledgement. Source test text deliberately exceeded the old 15-second age threshold.
- No automatic YouTube pause state or backlog restart instruction is used. The Android player no longer renders live agent audio directly: it downloads completed clips, writes every sample through one persistent AudioTrack, and checks playback-head completion.
- Actual OnePlus speaker audibility and long-running queue fluency require user listening. Full-phrase preparation adds initial latency; slower target-language speech can still accumulate delay. Explicit Stop, network/process failures, and the existing session limit remain practical limits.

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
