# Bubble with Gemini Flash-Lite

Bubble uses `gemini-3.1-flash-lite` with direct audio input and text output. Agora RTC still carries the microphone audio to the backend. The existing voice-activity detector groups a completed utterance; its WAV bytes go directly to Gemini. There is no additional Agora speech-to-text service for Bubble. Agora Conversational AI Engine and Murf Falcon still produce and deliver the spoken reply. Media translation and face-to-face translation are unchanged.

All model credentials stay in ignored `backend/.env`:

```dotenv
BUBBLE_LLM_PROVIDER=gemini
BUBBLE_LLM_URL=https://generativelanguage.googleapis.com/v1beta/openai/chat/completions
BUBBLE_LLM_MODEL=gemini-3.1-flash-lite
GEMINI_API_KEY=
```

The client uses Google's OpenAI-compatible HTTP endpoint, bare base64 WAV audio, streamed text/function results and minimal reasoning effort. It preserves Gemini's opaque thought-signature metadata and the actual tool-call IDs when continuing an action workflow. Incomplete tool streams cannot execute actions. Raw microphone audio is not saved in the persistent memory database.

The planner receives typed schemas for each available action, including its arguments. This replaces the old unconstrained argument object, which could prevent Gemini from generating the required fields. Explicit remember/task requests must invoke the corresponding tool; a spoken acknowledgement is not sufficient. Device-scoped memory, validated tools, bounded execution and the existing playback queue remain in place. Reply instructions request the selected language and explicit saved facts, without unnecessary offers or follow-up questions.

85 backend tests passed. Live direct-audio checks passed Telugu requests and replies, preference memory save/recall, a saved favorite-fruit fact recalled after reopening the database and model client, task creation and follow-up completion, and a Telugu YouTube search request. Device acknowledgements in the integration tests are simulated: these checks verify the correct request, not that an app opened on a physical phone. Synthetic tests do not establish universal speech-recognition accuracy or immunity to background speech.

A Linux Docker integration run also passed the full Agora RTC microphone → Gemini → Agora/Murf playback path for Telugu memory saving, follow-up recall and a YouTube search request. Silence and low-level noise produced zero model requests, and cloud-agent/RTC cleanup passed. The model portions of those turns took approximately 4.21, 4.88 and 6.59 seconds respectively. Entire turns, measured from the start of the synthetic spoken request through finished playback, took 12.17, 11.52 and 12.77 seconds. These are small-sample measurements, not time to first audio or a latency guarantee.

The deployed backend's authenticated capabilities endpoint returned HTTP 200 with the worker and Bubble configured for Gemini. The existing APK can reconnect without an update. No app notice was added and no user account memory was erased.

Rollback source tag: `anyvoice-v0.26-before-gemini`. Rollback Docker image: `anyvoice-backend:rollback-before-gemini`. The previous environment is retained only in ignored `.research/backend-before-gemini.env`; it contains credentials and must not be published. The persistent `anyvoice-memory` volume is retained during deployment.

Protocol references: [Gemini OpenAI compatibility](https://ai.google.dev/gemini-api/docs/openai), [Gemini pricing](https://ai.google.dev/gemini-api/docs/pricing).
