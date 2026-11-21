# NVIDIA Nemotron audio evaluation

Tested on 21 November 2026 with the configured NVIDIA key, using the direct NVIDIA endpoint and synthetic test audio. No account memory or phone actions were changed. The simulated YouTube action only acknowledged a request inside a temporary test store.

- Model: `nvidia/nemotron-3-nano-omni-30b-a3b-reasoning`
- Endpoint: `https://integrate.api.nvidia.com/v1/chat/completions`
- Audio: 16 kHz mono PCM16 wrapped as WAV. Tested `audio_url` with a base64 data URI and `input_audio` with bare base64.
- Reference: https://build.nvidia.com/nvidia/nemotron-3-nano-omni-30b-a3b-reasoning/apireference

## Results

| Check | Result |
| --- | --- |
| Key and model access | HTTP 200 |
| Short Telugu text greeting | Passed; first text 0.64 s, complete 0.66 s |
| English audio arithmetic | Correct answer; first text 1.23 s, complete 1.31 s |
| Forced function call on English audio | Valid transcript and arguments; first tool delta 0.88 s, complete 1.09 s |
| Telugu spoken memory request and follow-up | Failed: misrecognized the request, saved no memory and did not answer the follow-up |
| Alternate audio format | Accepted, but Telugu transcription still contained substitutions and repetition |
| Telugu YouTube search | Fast configuration returned clarification rather than performing the requested action; with a 512-token reasoning budget the bounded agent recovered a valid search request, taking 22.28 s |

The fast configuration used `reasoning_budget: 0`; full-agent Telugu turns took approximately 1.1–4.4 seconds but failed the memory/action acceptance checks. With `reasoning_budget: 512`, the memory request and follow-up took 6.16 and 7.69 seconds and still failed. These are model/agent durations after the complete audio upload, excluding Android VAD, Agora transport and Murf synthesis/playback. They are a small synthetic sample, not a latency benchmark or a general statement about the model's language support.

## Decision

Do not deploy this as Bubble's direct Telugu audio model yet. Valid Telugu characters and HTTP 200 are insufficient when the requested memory action is lost. The experiment's adapter changes were reverted, and the running Docker container, Agora and Murf configuration were left unchanged. No app notice or APK change was made.

A separate speech recognizer followed by text-only Nemotron is a possible next experiment, but it has not been implemented or validated here.
