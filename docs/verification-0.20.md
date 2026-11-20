# Any Voice 0.20 — verification record

This record names the checks performed. It is not a claim that every acoustic condition, language pair or provider failure has been verified.

Artifact: `dist/verified-0.20/AnyVoice-0.20.apk`.

APK SHA-256: `92ba1cbd7cd56948442e8c69fb0ce6a1083ad746d7aa334cd0bc6f857ce6dc30`.

Backend image: `anyvoice-backend:verified-0.20`, image ID `sha256:61250e7ab272e2662210feda22cecb66aaa5a672782eee5e57207b9af23fe61d`.

| Check | Result and scope |
|---|---|
| Backend regression suite | 75 tests passed, including both directions, correct voice selection, source provenance, owner isolation, SQLite restart, legacy SQL compatibility, API voice validation, source-specific retrieval, cleanup during setup, and no replay after partial audio. |
| Android build | Debug APK, instrumentation APK and lint passed. Final small UI state/settings changes were rebuilt and installed after the audio round trip. |
| Live providers | Agora RTC/STT → Falcon completed both directions with synthetic English and Telugu recordings. Separate live requests switched between two distinct catalog voice IDs and locales on one persistent Murf connection. |
| OnePlus native audio | Both directions passed through three isolated custom RTC tracks and actual AudioTrack playback acknowledgments. Session `93e990ac` remained unchanged across media → interpreter → media → Bubble → media. |
| Warm connection reuse | Native backend integration asserted unchanged media STT and Conversational AI agent identities through the interpreter. Stop restores the prior mode without recreating the main session. |
| Source memory agreement | Qwen recalled the matching cooking statement from a video fixture and a separately labeled conversation fixture. The phone round trip also recalled both sources. |
| Source memory disagreement | A real Qwen request distinguished the video's two-minute instruction from the conversation's three-minute instruction and explicitly said they disagreed. No example-specific production rule was added. |
| UI | Dedicated screen, prefilled own language/voice, dynamic partner-language picker, compatible voice picker, manual Start, active/Stop state, caption areas and floating icon were inspected on the OnePlus. |
| Actual microphone and lock | The dedicated Start flow entered interpreter mode with a real microphone recorder. Screen-off removed the listening service; waking the phone did not restart it. No room-noise or two-person accuracy claim follows from this lifecycle check. |
| Device-specific memory | Tests use isolated identities. Another device cannot retrieve their source records. No raw audio is saved as persistent memory. |

## Issues found during verification

Two early runs hit Murf `ACTIVE_CONTEXT_LIMIT_EXCEEDED`. This was reproduced on the phone, not dismissed as a random transport failure. The fix gives final-context release a short settling interval and retries capacity rejection with bounded backoff only before any PCM has been emitted. JSON error and close-code 4029 paths are covered; partial-audio requests are never replayed. The subsequent complete phone round trip passed.

A Wi-Fi HTTP timeout also interrupted an instrumentation run. The final native instrumentation used an ADB reverse tunnel for its **isolated test connection preferences**; the user's normal Wi-Fi backend address was unchanged. The dedicated real app screen subsequently loaded its live catalog and started the interpreter through its normal connection.

The first live source comparison exposed an unconverted UTC capture time. Evidence now supplies `local_time`; a regression checks conversion to Asia/Kolkata. Actual model text remains probabilistic: occasional wording or script mistakes are possible, even when the retrieved evidence is correct.

## Still needs a human conversation check

- Speaker-to-microphone echo, soft voices and room noise with two actual people.
- Overlapping speech and switching which person uses which language. Names are language-based labels, not verified speaker identities.
- Long sessions, changing networks and every supported voice-language pair. Dropdown/API compatibility is checked dynamically; exhaustive audio testing is not claimed.
- Recall of very old content currently uses bounded recent context and literal query/pagination tools, not a global semantic embedding index.

The rollback is [the saved 0.19 baseline](verified-baseline-0.19.md). The separate provenance table keeps its eight-column media writes compatible. The older app does not have the new attribution UI or logic.
