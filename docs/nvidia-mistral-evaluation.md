# Mistral Large 4 on NVIDIA: Bubble suitability check

Tested 21 November 2026 with the supplied key, kept only in the ignored backend environment. No active model, Docker container, APK, Agora or Murf settings were changed.

- Model: `mistralai/mistral-large-4-1t-a52b`
- Endpoint: `https://integrate.api.nvidia.com/v1/chat/completions`
- [Official endpoint reference](https://build.nvidia.com/mistralai/mistral-large-4-1t-a52b/apireference)

The reference explicitly supports text, images, function calling and streaming, and explicitly excludes audio and video input. A real synthetic WAV submitted through `audio_url` returned HTTP 422 after 1.08 seconds. Thus it cannot directly replace Bubble's current audio-input model.

Telugu text and text-based YouTube tool requests with `reasoning_effort: none` returned HTTP 400, despite that value being listed in the reference. A minimal separate nonstreaming request also returned 400, with only a generic invalid-request message. A `high` reasoning text request and a request omitting reasoning effort did not deliver completed text replies within more than a minute; these probes were stopped. No Telugu text quality or successful tool-call claim can be made from these incomplete probes. These observations are a small live endpoint test, not a general benchmark of Mistral or proof of universal model unavailability.

Using this model for voice would require a separately validated speech-to-text stage. Do not deploy it as a direct audio replacement.
