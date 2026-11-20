# Stage 1 validation

## Numbered parallel generation 0.10 — 2026-11-19

- Saved 0.9 source at tag `anyvoice-v0.9-before-parallel`, commit `b643b8e`, plus matching Docker image and local rollback APK. Credentials remain ignored.
- 26 backend tests passed, including forced out-of-order completion, exact sample preservation across three chunks, strict chunk/packet sequence metadata, bounded admission, cancellation cleanup, and a later failure that neither interrupts the earlier chunk nor permits skipping the missing chunk.
- Real two-connection Falcon test: generation completed in order **002, 001**, while released PCM and numbered wire frames remained **001, 002**. Both chunks generated in approximately 1450 ms and produced 207 packets. This validates ordering and account concurrency, not full translation latency.
- Android build/lint passed. New receiver validates numbered packets before enqueueing; the persistent AudioTrack is unchanged. Home screen displays the chunk whose audio has reached the playback head.
- Source recognition/translation and phone speaker fluency remain separate timing/listening checks. Parallel generation cannot make translated text arrive earlier or guarantee continuous speech through network/provider failures.

## Direct Murf comparison and integration 0.9 — 2026-11-19

- Saved the pre-experiment source at tag `anyvoice-v0.8-before-direct-murf`, commit `6bff126`, plus matching Docker rollback image and local APK. No credentials were committed.
- Compared three identical Telugu texts, Jaya/Falcon 2.1/rate 15/24 kHz, same Linux host. Both routes were persistent/warm, with alternating order. Median first audible PCM: 224 ms direct, 579 ms Engine. Aggregate full generation: 3136 ms direct, 17489 ms Engine. Direct connection setup: 300 ms. Three samples cannot establish general latency distributions.
- Real integration fixture: English PCM published via Agora RTC, transcribed/translated by Agora, synthesized directly by Falcon, resampled to 16 kHz, and queued in order. Two Telugu phrases / 8619 ms of PCM were generated. First PCM was approximately 7981 ms after source publication began, showing that upstream startup/translation context still contributes substantial delay.
- The same synthetic fixture through the saved 0.8 Docker image started PCM at approximately 9041 ms and produced 8660 ms of audio. This one-run end-to-end comparison improved by about 1060 ms; it does not establish a general improvement distribution.
- 22 tests passed, including resampling with odd network chunk boundaries, authentication in headers, context ordering, cleanup, no replay after partial audio failure, direct-mode Agora STT use, and generating a second phrase before any playback acknowledgement.
- Android build/lint passed. The existing Engine route remains separately selectable; direct mode does not start an idle Engine voice agent. Full phone listening remains a user check.

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


## 0.11 automatic routing — 2026-11-19

- 30 backend tests pass, including delayed source/translation ordering, Telugu suppression, return to translation, stale-caption rejection, cancellation of active old generation, and epoch-aware wire framing.
- Android arm64 debug assemble and lint pass.
- Real provider test with generated public test speech streamed through Agora RTC: Telugu -> English -> Telugu produced routes `original(1) -> translated(2) -> original(3)`. The first Telugu phase produced no TTS packets; English produced Telugu PCM. Detection took approximately 4.8 s, 7.7 s and 3.1 s respectively in this synthetic sample. This is an observation, not a latency guarantee.
- Provider accepted the four-language STT setup and non-Telugu-to-Telugu mappings. Hindi and Tamil recognition quality has not been separately measured.
- Docker health reports 0.11. APK installed on OnePlus 7 (88e07cd0), package versionCode 11/versionName 0.11.


## 0.12 app redesign and dynamic voices — 2026-11-19

- Android debug assemble/lint and all 37 backend tests pass; current APK is built for the OnePlus arm64 device.
- Full dynamic Falcon catalog queried live: 138 compatible voices / 43 locale options at the time of the check. No static character list or preselected voice remains. The native-only Bengali record was independently tested with real Falcon generation (approximately 3.61 s audio) before including its provider-declared native locale; counts may change.
- Live female and male Telugu previews passed (16 kHz mono WAV, approximately 3.66 s / 3.13 s).
- Real English PCM -> Agora RTC/STT -> Japanese translation -> dynamically selected Japanese Falcon voice -> queued PCM passed: two phrases / 11.05 s output, first PCM at approximately 7.8 s in this synthetic sample. This is not a latency guarantee or an audio-quality rating for every catalog voice.
- Backend rejects a fabricated voice ID with HTTP 422. Tests cover fresh catalog validation, unsupported pairs, absent selection, native/supported-locales-only filtering, aliases, preview cleanup/cache and non-Telugu provider configuration.
- Saved 0.11 before redesign: tag `anyvoice-v0.11-before-app-redesign`, commit `b3bd3d3`, Docker rollback image and APK.
- Phone disconnected before installation. User requested completing the build and will reconnect later. New screens, preview playback and drag/tap/rotation behavior still need real-device visual/interaction checks.
- Automatic approval review blocked starting the Android emulator with “blocked by policy”; no emulator was launched.


## Unified floating control 0.15 — 2026-11-19

- Saved exact previous 0.14 source as commit `7c2bac0`, tag `anyvoice-v0.14-before-unified`; matching rollback APK: `dist/AnyVoice-rollback-before-unified.apk`.
- One foreground service now owns playback monitoring, microphone mode, one cloud session and one floating panel. One Enable/Disable control replaces the separate media/Bubble starts. Existing Agora translation and Murf backend are unchanged.
- Standalone JVM policy regression scenarios passed for quiet input, isolated clicks, sustained media, sentence gaps, quiet-period return, immediate microphone gating on app sound, explicit Ask Bubble override, automatic return and timeout.
- `:app:assembleDebug` and `:app:lintDebug` passed. No provider credentials were added to Android. Provider configuration remains in ignored backend/.env.
- Device installation and live mode-transition checks are pending: ADB lost the connected OnePlus during the first install attempt. Windows sees its file-transfer device, but ADB lists no device. Do not interpret the build/policy results as a completed on-phone audio test.
- Known limits: source choice uses sustained captured audio levels, not semantic voice classification. Music can keep media mode selected. Switching reconnects Agora/provider sessions and adds connection time. Media input is buffered in memory (bounded to 20 seconds) while connecting, and mode changes wait for played output plus a quiet grace period. See docs/unified-bubble.md.


## Memory and native tools 0.16 — 2026-11-20

52 backend tests and Android assemble/lint passed. Live Qwen audio/function-calling tests saved a preference, created/completed a task across database/client reopen, and generated app/YouTube actions with simulated device acknowledgements. Native Agora RTC -> Qwen memory write -> selected-language Murf -> queued PCM/playback ACK passed (12.6 s request start to completed Telugu speech in one fixture). Docker container recreation retained an isolated test task. Real phone UI/app-search integration is unverified: ADB shows no device. Detailed latency boundaries and rollback are in docs/memory-tools.md.


## On-device 0.16 verification — 2026-11-20

- USB ADB restored after the phone re-enumerated as its composite MTP+ADB configuration. Installed dist/AnyVoice-0.16.apk and verified versionCode 16/versionName 0.16.
- A separate instrumentation test identity used the phone's BackendClient to save/read a fact, create/complete/reopen a task, read from a fresh client, forget the fact and verify the real installation could not see the fixture data. All passed. Test records did not enter the user's Tasks & memory screen.
- The main app's Tasks & memory screen loaded correctly and showed its empty-state guidance for the real installation.
- The production DeviceTools path dispatched an app launch and a YouTube search. On-screen YouTube results showed the exact query relaxing Telugu music. WhatsApp raised the OnePlus normal/Clone chooser; selecting normal WhatsApp opened com.whatsapp.home.ui.HomeActivity. No message was sent and no chat contents were inspected.
- Independent live backend isolation checks: two device identities read only their own saved facts; a correct device ID with the other identity's key returned 401. This is per-installation record isolation, not concurrent voice support: the backend still allows one active voice session globally.
- Removed the instrumentation APK after testing and returned the phone to Any Voice. Android lint passed with the new repeatable DeviceSmokeInstrumentation harness. Spoken user interaction was not repeated in this phone check; the earlier native RTC/Qwen/Murf voice test remains the voice-path evidence.


## Screen research and readiness fixes 0.17 — 2026-11-20

- Preserved 0.16 source/APK/Docker as anyvoice-before-screen-aware (56fbdfa), dist/AnyVoice-rollback-before-screen.apk and anyvoice-backend:rollback-before-screen.
- Android accessibility was correctly enabled on the user's phone. Confirmed bound service and in-app opt-in. Fixed a stale startup-only screen capability by refreshing it on each status heartbeat and each agent turn. Added live home-screen readiness and a Done action when setup is already ready.
- Fixed stale accessibility tree/scroll detection by subscribing to cache-invalidation events, refreshing roots and including screenshot evidence in change hashes. Permission-only state is not treated as a continuously running voice/research session.
- 58 backend tests and Android build/lint pass. JVM tests include holding conversation beyond the usual override timeout for a pending clarification.
- Real Qwen audio/image comparison test passed with two known test images; it recommended Course B based on beginner suitability and practice. Phone acknowledgements in that model test were simulated.
- On-device non-restarting capture probes verified text plus screenshots in Any Voice and YouTube. Controlled fixture probes read A/B then scrolled to B/C, returning different screenshot hashes. A retry of the same native scroll command returned the same cached hash, verifying no duplicate scroll. Test instrumentation itself was found to restart/disconnect the accessibility process, so final capture checks used the normal service without instrumentation restarts.
- Updated APK 0.17 installed and backend Docker updated with its persistent memory volume. Screenshots remain ephemeral in production paths. A new complete user-spoken research request through the phone has not yet been confirmed; this report distinguishes native tool/model tests from that user listening check. See docs/screen-assist.md for limits and rollback.

Debug follow-up: UI hierarchy inspection itself temporarily suppressed accessibility services (documented Android UiAutomation behavior), producing an artificial disconnected label while the Android switch remained on. Verified the live readiness label using a screenshot, switched final checks to non-restarting probes, and separated configured authorization from temporary reconnection in the native client. Cloud capability now follows configured authorization; each actual read/scroll still checks the live binding.
# App routing correction — 2026-11-20

- Phone had 106 launcher packages; the alphabetically sorted 100-package limit omitted YouTube. Raised native and API bounds to 512.
- Recent stored turns falsely claimed YouTube was absent; an unclear utterance led the model to request `com.android.contacts`. Updated planning instructions to clarify unclear app names and prefer the current capability list over conversation history. Explicit YouTube transcripts cannot launch another app through `open_app`.
- Found that YouTube accepted ACTION_SEARCH without displaying search results. Replaced it with an explicitly packaged HTTPS search deep link. Native on-phone test reported 106 apps including YouTube; screenshot confirmed C++ tutorial search results, rather than the home feed.
- Backend: 59 tests passed. Android debug build and lint passed. Updated backend container and APK installed. New spoken ambiguous-command behavior still needs user testing; native list/search and guard regression were verified.
- Rollback APK: `dist/AnyVoice-rollback-before-app-routing.apk`; image: `anyvoice-backend:rollback-before-app-routing`; stopped prior container: `anyvoice-backend-before-app-routing`.

# 0.18 live handoff and media recall — 2026-11-20

- Removed the historical capture queue; new RTC media sessions receive live frames only. Media is muted at connection start and explicit Ask Bubble immediately closes the media connection instead of draining old translation into the conversation handoff.
- Added installation-scoped persistent source/translation caption records, prefix upserts, recent context, session catalog and paginated `media_recall`. Removed the Android latest-caption-pair context shortcut.
- Backend: 62 tests passed, including restart, prefix deduplication, cross-device isolation, wrong-participant/partial rejection, assistant exclusion, bounded context and complete cursor traversal. Android debug build/lint passed. Fixed three pre-existing non-UTF-8 separator bytes in ScreenTools with Java Unicode escapes.
- Live Linux integration: generated cooking speech uploaded over Agora RTC; Agora produced 10 source/translation records; direct Murf produced 221 voice packets. After closing/reopening SQLite, real Qwen answered a spoken cooking-video question in Telugu with the captured steps and no invented ingredients.
- A separate real-model test recalled an earlier Maggi session despite a newer C++ session. These use synthetic audio and isolated test memory, not the user's history.
- Updated Docker and installed 0.18 on OnePlus. A fresh user-driven Android media-to-microphone switch still needs a listening check; the full native server pipeline was tested separately.

# 0.19 non-interruptible handoffs and retained sessions — 2026-11-20

- 66 backend tests passed. Coverage includes source-language flapping without queue invalidation, confirmed target-route changes, playback-end metadata surviving early ACK, source/microphone isolation, pending-caption quiet interval, generation/actual-playback drain and identity reuse. Updated a fragile 30 ms worker scheduling assertion to wait for its actual state with a bounded timeout.
- Android debug build, test build and lint passed.
- On OnePlus, native instrumentation used generated cooking audio and a generated question with a separate installation identity. Agora translated media, direct Falcon played it, Qwen recalled the captured recipe, and Agora Conversational AI Engine/Murf spoke the Telugu answer. Media → Bubble → media retained session `d0c41838` throughout. No physical microphone audio was needed for this synthetic regression.
- Backend handoffs: media to Bubble at packet 217 with ACK 217; back to media at packet 527 with ACK 527. Android logs likewise show handoffs at completed chunk 5 / played 217 / boundary 217, then chunk 7 / played 527 / boundary 527. The answer correctly included water, noodles, masala, two minutes and no onions, qualified as the captured portion only.
- Subsequent small fixes preserve completed-chunk markers when playback ACK precedes synthesis finalization, hold an explicit Ask request through long drains, and keep original media muted during fresh detection. Backend tests cover the ACK race; Android build/lint verifies final client edits.
- Rollback: `anyvoice-before-noninterruptible-routing`, `dist/AnyVoice-rollback-before-noninterruptible.apk`, `anyvoice-backend:rollback-before-noninterruptible`.

