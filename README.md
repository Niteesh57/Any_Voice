# Any Voice

Hear app audio in the language you choose. A native Android hackathon app built for **Build with Agora — Bengaluru 2026**, organized by Agora and powered by KNOTiC.

Version 0.17 adds [on-demand screen reading, bounded scrolling, visual comparison and clarification](docs/screen-assist.md).

Version 0.19 adds [confirmed background detection, uninterrupted chunk handoffs and retained media/Bubble connections](docs/noninterruptible-routing.md). Version 0.18 added [persistent media-caption recall](docs/media-memory.md).

Version 0.16 adds [persistent Bubble memory, task tools and native app/YouTube actions](docs/memory-tools.md).

## App experience

- Local profile: name and optional gender.
- Live Falcon voice library: languages and characters come from Murf, with male/female filters and real voice previews. No character is preselected.
- Both the preview and session-start APIs refresh the provider catalog and validate the exact voice ID against its declared native `locale` and `supportedLocales`. Unsupported pairs are rejected; there is no automatic substitute voice.
- Output options cover the intersection of Falcon locales and Agora transcription/translation support, including documented locale-code mappings. The current catalog yielded 138 compatible voices and 43 locale options; these counts are dynamic. Hinglish is excluded because Agora has no corresponding translation target.
- Original audio is muted during connection/detection. Confirmed speech in the selected language restores its original voice; other detected languages are translated and spoken by the selected Falcon voice. Detection runs independently of playback, with handoffs at completed voice chunks.
- One Enable/Disable control starts automatic media translation and Bubble conversation. Media takes priority; after sustained quiet, the microphone can take questions while unlocked. Ask Bubble temporarily overrides media.
- A translucent 52 dp floating bubble can be dragged, snaps to a screen edge, and expands on tap to show status and controls.
- Animated waveform, screen entrances, voice sheet and button feedback. System motion settings are respected.

Name and gender stay on the phone. Provider keys remain in ignored `backend/.env`. Android's capture permission is required. Media mode captures app playback; conversation mode sends microphone audio through Agora to Qwen, then speaks text replies with Murf. Microphone input is gated during replies and released on lock or media mode. Only apps permitting Android playback capture can be heard. Agora detects among at most four configured source languages; the picker shows the active shortlist for each output language.

See [unified mode and rollback](docs/unified-bubble.md) for switching behavior and limits.

## Run

Configure `backend/.env` from [backend/.env.example](backend/.env.example), then:

```powershell
docker build -t anyvoice-backend:dev backend
docker run -d --name anyvoice-backend --restart unless-stopped --env-file backend/.env -e BUBBLE_MEMORY_PATH=/data/bubble.sqlite3 -v anyvoice-memory:/data -p 8000:8000 anyvoice-backend:dev
.\gradlew.bat :app:assembleDebug :app:lintDebug -PtargetAbi=arm64-v8a -PanyVoiceBackendUrl=http://192.168.1.9:8000 --console=plain
```

The shared Wi-Fi address is a build parameter. In the app, profile → Demo connection holds the backend address, access token and voice route. The APK contains no Agora/Murf credentials. Builds for release use HTTPS. See [backend setup](backend/README.md), [design notes](docs/app-design.md) and [validation](VALIDATION.md).

## Pipeline

Captured playback → Agora RTC → Agora real-time transcription/translation → two persistent Murf Falcon 2.1 connections → numbered 50 ms PCM packets → one continuous Android player. Language-change epochs suppress obsolete translation; ordinary chunks finish in order without cutting off the current voice.

The default fast route uses Agora RTC and STT/translation. The selectable **Agora Conversational AI Engine** route also remains implemented for the hackathon demo. This does not claim that the fast route alone satisfies all Conversational AI/agentic-workflow requirements.

## Revert

The previous working version is saved as Git tag `anyvoice-v0.11-before-app-redesign` (`b3bd3d3`), Docker image `anyvoice-backend:rollback-v0.11` and `dist/AnyVoice-rollback-0.11.apk`. Earlier checkpoints remain intact. Pair matching source, Docker and APK when reverting.

## Verify

```powershell
cd backend
.\.venv\Scripts\python.exe -m unittest discover -s tests -q
```

The branded 0.12 APK is `dist/AnyVoice-debug.apk`. Installation and visual checks await the phone's reconnection.
