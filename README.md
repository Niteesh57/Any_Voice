# Any Voice

## Automatic language routing (0.11)

Start listening in any app that permits Android playback capture. Original media stays audible while Agora identifies speech. The configured detection shortlist is English, Hindi, Tamil and Telugu (`AUTO_SOURCE_LANGUAGES=en-IN,hi-IN,ta-IN,te-IN`, maximum four). Confirmed Telugu restores original media volume and suppresses generated speech. Other detected languages are translated by Agora into Telugu and spoken by Murf Falcon. Capture continues in both modes, without opening the microphone. Unknown/unconfigured languages are not guaranteed to be recognized correctly.

Stable source captions determine the route, using sentence IDs to match translated results. A finalized source result, or consistent evolving partials spanning at least 300 ms, confirms a language. Source offsets reject old decisions. Translation arriving before its source classification is held in a bounded buffer. Language changes increment a route epoch; old generation and queued audio from previous epochs are discarded deliberately. Within the active epoch every numbered chunk plays fully in order. The Android player flushes only on a confirmed language change or Stop.

Protocol 3 (`pcm-stream-v3`) adds the route epoch: five network-order uint32 fields `(transport sequence, chunk, part, epoch, byte count)`, followed by mono 16 kHz PCM16. Transport numbering remains continuous across route changes; obsolete packets are consumed and acknowledged without playback. Status exposes `route_mode`, `route_epoch`, `detected_language`, and the detection shortlist. Legacy stream endpoints remain available. Initial detection and switching are subject to Agora's recognition latency.

Rollback: source tag `anyvoice-v0.10-before-auto-language` (commit `e4765ea`), Docker image `anyvoice-backend:rollback-v0.10`, APK `dist/AnyVoice-rollback-0.10.apk`. Local environment backup is retained privately under `.research/rollback-before-auto-0.10/`. Provider keys remain in ignored `backend/.env`.


Native Android **English → Telugu** voice translation prototype, built in the context of Build with Agora – Bengaluru 2026.

Any Voice captures permitted app audio, silences original media output and plays a Telugu AI voice. Phase one keeps the language pair fixed and the UI simple.

## Current pipeline

### Numbered parallel generation (0.10)

Fast mode now uses **two persistent Murf connections**. One dispatcher assigns chunk IDs **001, 002, 003… before generation starts**. Each chunk's PCM packets also have position numbers. The first chunk streams immediately; later chunks buffer on disk until every earlier chunk is finished and released. Completing 002 before 001 can never change playback order. Android checks chunk, packet and transport sequence numbers and writes to the same persistent AudioTrack, with no replacement/flush at chunk boundaries.

Generation is bounded to two active requests with a six-chunk preparation window. This uses the account's Murf concurrency allowance; the real account test successfully ran both slots. A failed later chunk does not skip a hole or interrupt an earlier generation. Stop/cancellation cleans up both connections and temporary buffers. Agora RTC/STT and the selectable Engine route remain available; Engine mode stays sequential.

Rollback for this change: Git tag `anyvoice-v0.9-before-parallel` (`b643b8e`), Docker image `anyvoice-backend:rollback-v0.9`, and local `dist/AnyVoice-rollback-0.9.apk`. Parallel preparation helps subsequent chunks; it does not remove the initial wait for translated text.

### Direct Murf experiment (0.9)

The default fast route keeps **Agora RTC + Agora English-to-Telugu STT/translation** and sends stable Telugu text to a persistent **Murf Falcon WebSocket**. Audio generation runs ahead of playback instead of waiting for an Agora voice turn to finish. Audio is resampled from 24 kHz to 16 kHz and uses the existing ordered 50 ms packet stream and continuous Android player.

In **Connection settings → Voice mode**, choose **Fast · direct Murf streaming** or **Agora Conversational AI Engine**. The Engine route remains implemented and runnable for the hackathon demonstration and comparisons. The fast route does not start an unused voice agent; Agora RTC and translation remain active in both modes. This preserves the Engine integration but does not assert that the fast route alone fulfills every hackathon requirement.

A three-text, same-voice/rate comparison measured median voice first-audio of **224 ms direct versus 579 ms Engine**, and aggregate generation of **3136 ms versus 17489 ms**. Direct socket setup added 300 ms. See [benchmark data](docs/murf-streaming-benchmark.json). These measurements exclude recognition/translation and are not full end-to-end latency claims. A synthetic English-video integration still waited about eight seconds for its first PCM; upstream translation/context remains a separate bottleneck.

Rollback checkpoint: Git tag `anyvoice-v0.8-before-direct-murf` (`6bff126`), Docker image `anyvoice-backend:rollback-v0.8`, and local `dist/AnyVoice-rollback-0.8.apk`. Credentials remain in the ignored local environment file.

### Previous streaming transport (retained)

Version **0.8** streams generated Telugu PCM immediately in **50 ms packets** over one persistent authenticated HTTP connection. It no longer waits for a complete phrase before sending audio. Android uses a roughly **100 ms starting buffer**, writes at most 100 ms per call, and keeps one persistent AudioTrack fed continuously. There is no per-packet playback-completion wait and no automatic backlog pause/restart behavior.

Stable Telugu text goes to Agora Conversational AI + Murf Falcon immediately. Provider completion events order TTS requests, while audio delivery runs independently during generation. Packet sizes are transport units, not a promise of 50/100 ms end-to-end translation latency. Recognition, language context and network time still contribute; target speech that takes longer than the source can still accumulate delay. Audio is transient and deleted after playback acknowledgement or session cleanup. Explicit Stop remains immediate; sessions retain the existing 15-minute limit.

**Android app audio → Agora RTC → Agora Real-Time STT + Telugu translation → Conversational AI Engine → Murf Falcon → Agora RTC → Android speaker**

Agora performs recognition and translation. Falcon generates speech through the Conversational AI agent's `speak` API. There is no separate translation API, translation model, Finch recognizer or third provider account.

The code is wired for this pipeline. Live service access, Telugu translation quality and updated OnePlus playback still require configured credentials and real-device validation. The earlier local hi prototype was reported working on OnePlus 7. See [validation](VALIDATION.md) for exactly what has been checked.

## Build Android

Requires JDK 17+ (tested with JDK 21), SDK platform 36 and Android 10+ on the phone.

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
New-Item -ItemType Directory -Path dist -Force
Copy-Item .\app\build\outputs\apk\debug\app-debug.apk .\dist\AnyVoice-debug.apk
adb install -r .\dist\AnyVoice-debug.apk
```

The APK is generated locally and is not committed. The stable application ID remains `dev.hiprobe` so updates replace the original installed prototype; the displayed name is **Any Voice**.

For a smaller OnePlus 7 APK, add `-PtargetAbi=arm64-v8a` to the Gradle command. Default builds include arm64 and x86_64 for phone/emulator testing.

## Configure and try

1. Follow [backend setup](backend/README.md). Only Agora and Murf provider credentials are required, plus your own backend app password and public HTTPS URL.
2. Install the APK. In **Connection settings**, enter the backend URL and the app access token. Do not put provider API keys in the phone app.
3. Optionally enable floating controls. Make the phone's **alarm volume** audible; the app does not increase it.
4. Play an English video and return to Any Voice. Tap **Start listening**, approve capture consent, and return to the video. If Android offers a sharing choice, choose the entire screen for switching apps; screen images are not collected.
5. Once the session is connected, original media output is muted. English and Telugu captions appear in the app, and the agent's Telugu audio plays through the independent output stream.
6. Tap **Stop listening**, the floating **Stop + restore**, or the notification action to end the session and restore media volume.

**Local audio check · says hi** retains the offline capture-and-mute diagnostic. It needs no backend and does not demonstrate translation. Its capture meter is available on the floating panel.

To uninstall, stop the active session first:

```powershell
adb uninstall dev.hiprobe
```

## Scope

- Permitted media playback on the phone speaker is the first target. Some apps block Android playback capture. Calls and protected playback are not supported by this design.
- Live mode sends audio to Agora and speech services. The physical microphone is never selected. Raw audio and transcripts are not written to recording files by this app/backend.
- Translation incurs delay; video is not automatically delayed to match it. Continuous speech can create a backlog; the prototype fails visibly rather than silently dropping queued words.
- Alarm-stream behavior, DND, Bluetooth, headphones and capture compatibility vary by device. The original media mix, including music/effects, is muted in full.
- Sessions have a 15-minute maximum and a phone-heartbeat lease. Stop and provider failure paths clean up cloud agents. Cloud cleanup errors require checking Agora Console.
- Normal stop restores saved media volume unless the user changed it. After a process crash or force-stop, reopen the app for recovery or raise media volume manually.

## Project files

- `app/`: native Java Android client, minimal UI, capture, volume guard, custom Agora audio source/sink.
- `backend/`: Python control/caption worker, Agora REST integration, setup template, Dockerfile and tests.
- `test-source/`: separate developer-only audio fixture, not included in the distributed app.
- [Architecture](docs/ARCHITECTURE.md), [hackathon requirements](docs/HACKATHON.md), [provided event brief](docs/hackathon-brief.txt).

Conversational AI Engine is used for actual spoken output. A conversational explanation/glossary workflow remains future work, so using the engine does not by itself establish every hackathon requirement is complete.
