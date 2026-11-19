# Any Voice backend

## Automatic language routing (0.11)

Start listening in any app that permits Android playback capture. Original media stays audible while Agora identifies speech. The configured detection shortlist is English, Hindi, Tamil and Telugu (`AUTO_SOURCE_LANGUAGES=en-IN,hi-IN,ta-IN,te-IN`, maximum four). Confirmed Telugu restores original media volume and suppresses generated speech. Other detected languages are translated by Agora into Telugu and spoken by Murf Falcon. Capture continues in both modes, without opening the microphone. Unknown/unconfigured languages are not guaranteed to be recognized correctly.

Stable source captions determine the route, using sentence IDs to match translated results. A finalized source result, or consistent evolving partials spanning at least 300 ms, confirms a language. Source offsets reject old decisions. Translation arriving before its source classification is held in a bounded buffer. Language changes increment a route epoch; old generation and queued audio from previous epochs are discarded deliberately. Within the active epoch every numbered chunk plays fully in order. The Android player flushes only on a confirmed language change or Stop.

Protocol 3 (`pcm-stream-v3`) adds the route epoch: five network-order uint32 fields `(transport sequence, chunk, part, epoch, byte count)`, followed by mono 16 kHz PCM16. Transport numbering remains continuous across route changes; obsolete packets are consumed and acknowledged without playback. Status exposes `route_mode`, `route_epoch`, `detected_language`, and the detection shortlist. Legacy stream endpoints remain available. Initial detection and switching are subject to Agora's recognition latency.

Rollback: source tag `anyvoice-v0.10-before-auto-language` (commit `e4765ea`), Docker image `anyvoice-backend:rollback-v0.10`, APK `dist/AnyVoice-rollback-0.10.apk`. Local environment backup is retained privately under `.research/rollback-before-auto-0.10/`. Provider keys remain in ignored `backend/.env`.


English audio is recognized and translated to Telugu by **Agora Real-Time STT/Translation**. Final Telugu text is sent to **Agora Conversational AI Engine's `speak` API**, whose TTS provider is **Murf Falcon 2.1**. There is no Finch recognizer, external translation API, external translation model or third provider key.

## Configure

Version 0.10 uses two direct-Murf slots and a bounded ordered-release buffer. Chunk numbers are allocated before any generation, and per-chunk packet numbers are assigned on ordered release. The first chunk streams as it arrives; later completed chunks wait in temporary disk buffers. At most six chunks are admitted ahead, so a slow early chunk applies backpressure without discarding text. Two simultaneous contexts require the account's concurrency allowance (tested with supplied credentials).

The Android 0.10 transport is `GET /v1/sessions/{id}/pcm-stream-v2?after=N`: each binary frame contains four network-order uint32 fields (transport sequence, chunk sequence, packet position, byte count), then PCM. A sixteen-zero-byte frame is a keepalive. Chunk numbers must start at 1 and advance only by one; packet positions reset to 1 per chunk. Legacy `/pcm-stream` remains available for the previous transport. No voice text or PCM payload is logged; assigned/generated/released chunk IDs are logged.

Version 0.9 defaults to `VOICE_PROVIDER=murf-direct`. Both routes use Agora RTC and Agora STT/translation. Direct mode uses a persistent authenticated Falcon WebSocket with `end: true` contexts; it processes every audio chunk, then the final marker, and immediately starts the next generation without waiting for playback. It never automatically replays a partially generated phrase after a network error. An idle closed socket is reopened before the next phrase. This backend uses Python 3.12 (Docker), including its streaming `audioop` resampler.

Set `VOICE_PROVIDER=agora-engine`, or choose that route in Android Connection settings, to use the saved Conversational AI voice flow. `POST /v1/sessions` optionally accepts `voice_provider` (`murf-direct` or `agora-engine`); the response reports the active route. No new provider credentials are needed.

Copy `.env.example` to `.env` and supply:

| Setting | Source |
| --- | --- |
| `ANYVOICE_ACCESS_TOKEN` | A long random app password you create; also enter it in Android Connection settings |
| `AGORA_APP_ID` | Agora project |
| `AGORA_APP_CERTIFICATE` | Agora project token configuration |
| `AGORA_CUSTOMER_ID`, `AGORA_CUSTOMER_SECRET` | Agora REST credentials |
| `MURF_API_KEY` | Murf account with Falcon streaming access |
| `ANYVOICE_PUBLIC_URL` | Public HTTPS address of this backend |
| `MURF_VOICE_ID` | Defaults to Jaya; verify this voice supports `te-IN` on your account |

Enable RTC, Real-Time STT/Translation, and Conversational AI Engine for the Agora project. STT translation is a provider beta feature; account availability and Telugu quality must be verified with a real session.

Enable Agora Signaling (RTM) too: the voice agent's manual-turn configuration requires it. The bundled official AccessToken2 builder gives that agent combined RTC + RTM privileges; it does not require another provider key.

`ANYVOICE_PUBLIC_URL` is not a separate model service. Agora requires an LLM callback in the agent's startup configuration, even though `speak` bypasses it. Our authenticated `/v1/agent/idle/chat/completions` returns an empty SSE completion and never calls a model. The agent listens only to a silent backend control UID, uses manual turns, has no greeting/filler messages, and receives explicit Telugu broadcasts. A live account test is still needed to confirm the full agent configuration is accepted.

## Run on Linux / Docker

The native Agora Server SDK requires the Linux worker environment. The HTTP-only API and mocked tests can run on Windows, but live sessions cannot.

From the repository root:

```powershell
docker build -t anyvoice-backend:dev backend
docker run --rm --name anyvoice-backend --env-file backend/.env -p 8000:8000 anyvoice-backend:dev
```

Use one server process (`--workers 1`): session ownership and the one-session limit are in memory. Publish it through an HTTPS reverse proxy or tunnel and set `ANYVOICE_PUBLIC_URL` to that same service's external address. Cloud API credentials stay only on the backend and Agora's provider configuration.

For an Android phone connected over USB:

```powershell
adb reverse tcp:8000 tcp:8000
```

In Any Voice → Connection settings, use `http://127.0.0.1:8000` and the `ANYVOICE_ACCESS_TOKEN`. The public HTTPS address is still needed for Agora's callback configuration. Alternatively use the public HTTPS address directly on the phone. Arbitrary cleartext Internet/LAN URLs are rejected by the app; loopback cleartext is for local development only.

The container doesn't require a microphone or audio device. Version 0.8 stores sequenced Telugu PCM packets temporarily until the phone acknowledges playback or the session is cleaned up. Ensure the host network permits Agora traffic; the HTTP port alone isn't the worker's complete network requirement.

## Audio and control flow

Latency tuning: text requests may use the full 512-byte provider limit while audio remains streamed in 50 ms packets. This avoids extra TTS startups without waiting to fill a text request. `MURF_SPEECH_RATE=15` modestly accelerates generated speech; set it to 0 for normal delivery. Timing logs distinguish provider-timestamp speech-end-to-translation (clock-dependent), caption queue wait, and TTS first audio. Logs contain timings and byte counts, not speech text.

For same-Wi-Fi debugging, publish port 8000 on the host and enter `http://COMPUTER_WIFI_IP:8000` in Android Connection settings. Debug builds allow private IPv4 HTTP addresses; release builds require HTTPS except loopback. The default address can be supplied at build time with `-PanyVoiceBackendUrl=http://COMPUTER_WIFI_IP:8000`; it stays editable in the UI. No credentials are compiled into the APK. The public HTTPS callback is still required by Agora. Allow inbound TCP 8000 on the trusted private network if Windows Firewall blocks phone access.

1. The authenticated phone requests a session. Backend creates fresh channel/tokens and joins as a silent control participant.
2. Backend starts STT with source `en-IN`, translation target `te-IN`, JSON/gzip captions, and a subscription limited to the phone source UID.
3. Backend starts a Conversational AI voice agent with Murf Falcon `te-IN` and manual turns. Backend receives its 16 kHz PCM; Android disables automatic RTC audio subscriptions.
4. Native worker receives caption data from the STT bot. It ignores other senders, other source UIDs, other target languages and unfinished text.
5. Final caption prefixes are deduplicated by sentence identity. Genuine repetitions with new IDs remain audible.
6. Telugu text is split under the engine's **512-byte UTF-8 limit**, preserving characters and order. Broadcasts use `APPEND` and `interruptable=false`.
7. Incoming voice-agent PCM is packetized into 1600-byte (50 ms) mono 16 kHz PCM16 packets immediately, before the `turn.finished` event. Completion events only order TTS commands; there is no full-phrase delivery gate, 500 ms text batching delay or 350 ms audio tail wait. Residual partial packets flush at turn end. Late RTC frames remain part of the continuous stream.
8. Android maintains one persistent `/pcm-stream` connection, reconnecting after the last fully queued sequence. Binary frames use network-order uint32 sequence + uint32 byte count + raw PCM; zero/zero is a keepalive. Android starts with approximately 100 ms of audio, buffers at most 100 packets (5 seconds) in app memory, and writes continuously without waiting at packet boundaries. Playback-head counters provide cumulative acknowledgements independently. New packets never flush or replace existing AudioTrack content.
8. Stop, errors, session expiry or a missing phone heartbeat tear down both cloud agents and the worker connection. The phone restores original media volume.

UIDs: phone source 1001, voice agent 2001, silent control 3001, STT bot 4001. They are unique per fresh session channel. Generated RTC tokens expire shortly after the maximum session lifetime (15 minutes). Another session can start after cleanup finishes.

## API

All `/v1/*` routes require `Authorization: Bearer <ANYVOICE_ACCESS_TOKEN>`.

- `GET /health`: basic process health, no secrets.
- `GET /v1/sessions/{id}/pcm-stream?after=N`: persistent framed PCM stream; live generation wakes the stream immediately.
- `GET /v1/sessions/{id}/audio?after=N`: authenticated manifest of prepared clips after sequence N.
- `GET /v1/sessions/{id}/audio/{sequence}`: complete immutable PCM bytes, 16 kHz mono PCM16 little endian.
- `POST /v1/sessions/{id}/audio/{sequence}/played`: acknowledge completed playback and delete queued clips through that sequence. Provider errors retain completed clips for draining; explicit Stop deletes the queue.
- `GET /v1/capabilities`: missing setting names, native SDK availability and fixed language pair. “Configured” means local prerequisites exist, not that provider credentials were accepted.
- `POST /v1/sessions`: body `{"source_language":"en-IN","target_language":"te-IN"}`; returns client RTC connection data after cloud agent creation.
- `GET /v1/sessions/{id}`: captions and state; also renews the phone heartbeat lease.
- `POST /v1/sessions/{id}/stop`: cancel work and stop cloud agents.
- `POST /v1/agent/idle/chat/completions`: inert required agent callback; no translation or generation.

This is a single-user prototype with a shared app password. Before multi-user deployment, add individual authentication, session ownership, a durable store, distributed cleanup and quotas. Successful HTTP submission of a broadcast is not proof of audible playback. Cloud stop failures are reported for Console inspection.

## Tests

```powershell
python -m venv backend/.venv
backend/.venv/Scripts/python.exe -m pip install -r backend/requirements.txt
cd backend
.venv/Scripts/python.exe -m unittest discover -s tests -v
```

Tests mock provider traffic. They cover exact Agora service requests, source isolation, final/partial captions, gzip limits, UTF-8 splitting, duplicate updates, genuine repetition, authentication, session limits, startup failure cleanup and stop. They do not establish live service access, translation accuracy or phone speaker behavior.

## References

- [Agora STT start/configuration](https://docs.agora.io/en/api-reference/api-ref/speech-to-text/join)
- [Agora caption data protocol](https://docs.agora.io/en/realtime-media/speech-to-text/build/process-transcription-data/parse-data)
- [Conversational AI speak](https://docs.agora.io/en/api-reference/api-ref/conversational-ai/speak)
- [Murf provider configuration](https://docs.agora.io/en/ai/models/tts/murf)
- [Agora Python Server SDK](https://github.com/AgoraIO-Extensions/Agora-Python-Server-SDK)
