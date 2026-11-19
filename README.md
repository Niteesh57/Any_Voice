# Any Voice

Native Android **English → Telugu** voice translation prototype, built in the context of Build with Agora – Bengaluru 2026.

Any Voice captures permitted app audio, silences original media output and plays a Telugu AI voice. Phase one keeps the language pair fixed and the UI simple.

## Current pipeline

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
