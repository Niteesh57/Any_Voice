# Any Voice backend

Agora RTC carries captured app playback to a Linux worker. Agora recognizes and translates speech; Murf Falcon 2.1 speaks the selected output voice. No third translation provider is required.

## Configuration

Copy `.env.example` to `.env` and supply `ANYVOICE_ACCESS_TOKEN`, `AGORA_APP_ID`, `AGORA_APP_CERTIFICATE`, `AGORA_CUSTOMER_ID`, `AGORA_CUSTOMER_SECRET`, `MURF_API_KEY` and `ANYVOICE_PUBLIC_URL` (this backend's public HTTPS address for the Engine callback). Configure `VOICE_PROVIDER=murf-direct` for the fast route, or `agora-engine` for Conversational AI Engine orchestration. `MURF_SPEECH_RATE` defaults to 15. Provider keys must never enter the APK or Git.

The app supplies a selected voice ID and Murf locale for every session. A fresh `GET https://api.murf.ai/v1/speech/voices?model=falcon-2.1` validates the pair before starting generation. UI library requests cache catalog metadata for ten minutes; preview/start validation always refreshes it. Only the provider-declared native `locale` and `supportedLocales` authorize voice-language compatibility. Unknown IDs, unknown locales, and incompatible pairs return 422, without substituting another voice.

The intersection with Agora's verified transcription/translation locale table is in `anyvoice/agora_languages.json` (source link/date included). Aliases map Murf English UK/Scotland to Agora en-GB and Murf Arabic XA to ar-SA; the original Murf locale still reaches TTS. Hinglish has no Agora translation target and is excluded. Native names in the UI come from Android locale data.

`AUTO_SOURCE_LANGUAGES=en-IN,hi-IN,ta-IN,te-IN` is the default detection shortlist (2–4 entries). If the selected target is outside it, the target replaces the last entry so same-language passthrough remains possible. The app shows this shortlist. Automatic detection is not simultaneous detection of every language in the voice library.

## APIs

All `/v1/` requests require `Authorization: Bearer <ANYVOICE_ACCESS_TOKEN>`.

- `GET /health`: service/version, no credentials.
- `GET /v1/capabilities`: runtime/configuration availability.
- `GET /v1/voices`: dynamic language and voice metadata, without provider secrets.
- `GET /v1/voice-preview?voice_id=...&language=...`: a short real Falcon-generated mono 16 kHz WAV. Previews are validated, serialized, bounded and cached (32 entries). Previews are blocked while a session is active, preserving its two generation slots.
- `POST /v1/sessions`: source `auto`, chosen `target_language` (Murf locale), `voice_id` and optional `voice_provider`. Name/gender are never sent.
- Existing status, stop, audio ACK and `pcm-stream-v3` endpoints retain ordered PCM delivery. V3 header: five network-order uint32 fields `(sequence,chunk,part,epoch,bytes)` followed by PCM. Twenty zero bytes are a keepalive.

Source caption identity and offsets associate language decisions with translation. A source-language change increments the epoch. Old generation results/packets cannot reappear in a newer route. Normal chunks still finish completely in their assigned order. Two Murf sockets prepare ahead within a six-chunk window; streams and temporary audio are cleaned up on Stop.

## Run and test

The native Agora Server SDK needs Linux; use Docker from the repository root:

```powershell
docker build -t anyvoice-backend:dev backend
docker run -d --name anyvoice-backend --restart unless-stopped --env-file backend/.env -p 8000:8000 anyvoice-backend:dev
```

Use a single Uvicorn worker because session ownership is in memory. Enable RTC and STT/Translation on Agora; the optional Engine route also requires Conversational AI and Signaling. Its authenticated inert LLM callback generates no text and calls no external model.

Tests run from this directory: `.\.venv\Scripts\python.exe -m unittest discover -s tests -q`. See root `VALIDATION.md` for observations and limitations. Working 0.11 rollback: `anyvoice-backend:rollback-v0.11` with the matching Git tag and APK.
