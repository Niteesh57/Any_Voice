# Bubble: Agora speech-to-text → Nemotron → Murf

Implemented and deployed locally on 21 November 2026. The Android interface and APK protocol are unchanged.

Bubble sends microphone audio over Agora RTC to a dedicated Agora recognizer. Only completed microphone utterances become text requests to NVIDIA. Nemotron handles answers, saved context and validated native tool calls. Agora Conversational AI Engine and Murf Falcon still deliver the spoken reply. Media translation and face-to-face translation retain their existing pipelines.

## Configuration

Keep all credentials in the ignored `backend/.env`:

```dotenv
BUBBLE_LLM_PROVIDER=nvidia
BUBBLE_LLM_URL=https://integrate.api.nvidia.com/v1/chat/completions
BUBBLE_LLM_MODEL=nvidia/nemotron-3-nano-omni-30b-a3b-reasoning
NVIDIA_API_KEY=
```

Although this is the Omni model, microphone audio is not sent to it. Existing screen tools can still supply screenshots. The old Qwen route remains available through provider selection for source compatibility.

The dedicated STT publisher is UID 6001 and subscribes only to Bubble's microphone UID 1002 in a unified session. It recognizes the selected output language and English, without translating the transcript. It stays connected across media/Bubble switches until session cleanup. Recognition of arbitrary additional spoken languages is not claimed by this two-language configuration.

## Input and action controls

- WebRTC speech detection plus adaptive audio-level gating, minimum voiced duration and a 650 ms end pause.
- Final captions only; partial captions never invoke the model. Group final segments belonging to the current utterance and deduplicate caption IDs.
- Ignore wrong input UIDs, inactive modes, known non-speech labels, stale timestamped captions and audio/captions during playback. Wait at most four seconds for a missing final transcript without calling the LLM on silence.
- Bound transcript size, tool rounds and tool arguments. Existing device identity, validation and SQLite memory isolation remain in place.
- Successful writes and phone requests use short localized receipts in Telugu, Hindi or English. Other supported languages use a model-generated receipt. Failed or unconfirmed tool outcomes never qualify for a success template.
- Check Indic reply scripts and allow at most one text-only repair request for a mixed-script reply; do not repeat actions during repair. Script checks do not prove semantic translation quality.

Speech detection cannot identify who is speaking. Real background speech can still be recognized; the noise checks below do not establish universal noise immunity.

## Verification

90 backend tests passed, including existing translation, bidirectional conversation, memory ownership and account-reset checks, plus new tests for final-caption grouping, duplicates, late captions, stale input, noise labels, clicks, playback echo, mode isolation, STT cleanup and native text tool calls.

Live NVIDIA text tests passed Telugu replies, memory save/recall, task creation, follow-up task completion and a YouTube search request. A separate fact test saved a favorite fruit, closed both the model client and database, then recalled that fact in Telugu after reopening them.

A Linux Docker test sent synthetic Telugu microphone audio through real Agora RTC, real Agora STT, Nemotron and real Agora/Murf playback. It passed:

| Scenario | LLM time | Entire synthetic utterance through finished playback |
| --- | ---: | ---: |
| Remember a preference | 4.95 s | 9.96 s |
| Recall the preference | 4.07 s | 10.72 s |
| Request a YouTube search | 1.90 s | 7.80 s |

These are small-sample observations, not guaranteed response times or time to first audio. Full-turn durations include speaking the synthetic request, recognition, generation and playing the reply. Quiet random noise and silence produced zero LLM requests. Cloud-agent and RTC cleanup also passed. The phone acknowledgement was simulated: the integration test verified the correct device request, not that YouTube opened on a physical phone.

Docker's authenticated capabilities endpoint returned HTTP 200 with the worker, Bubble and provider configuration ready after deployment. Persistent volume `anyvoice-memory` remains mounted at `/data`; no user account reset was performed. No app notice was added.

## Rollback

Saved source tag: `anyvoice-v0.26-before-agora-text-bubble`.

Saved Docker image: `anyvoice-backend:rollback-before-agora-text-bubble`.

The pre-change environment is saved locally under ignored `.research/backend-before-agora-text.env`. It contains credentials; do not publish it. Restore it together with the rollback image when reverting, rather than combining an old image with the new provider configuration.
