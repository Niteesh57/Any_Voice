# Any Voice 0.21 — source-language and original-audio safeguards

## What failed

Session `a49ba791` contained a Hindi video with English technical words. Saved source captions were classified as Hindi (6), Tamil (7) and Telugu (1). All 12 stored translation records were labelled Telugu, but some contained transliterated Hindi rather than a meaningful Telugu translation. Murf reads the supplied text; setting its Telugu locale cannot repair an incorrect transcript.

At 2026-11-20 09:23:08 UTC the backend selected original audio after a Telugu detection. At 09:23:14 UTC it returned to translation. This established a backend route that could expose Hindi audio; the exact audible interval was not verified with phone playback logs. The previous routing code trusted the initial stable detection and could also accumulate nonconsecutive original-language votes.

## Changes

- Auto mode now requires three distinct finalized target-language segments spanning at least two seconds before enabling original sound, including at startup. Repeated segment IDs and partial updates cannot satisfy this confirmation.
- A stable non-target result clears the target-language votes and restores the translated route immediately. The Android player retains its existing finish-current-chunk handoff behavior.
- The home screen has **Video language**. Auto-detect remains the default. For a known Hindi video, select **Hindi (India)** before enabling Any Voice. Agora receives a single Hindi recognition language and a Hindi → selected-output translation pair, eliminating the four-way recognition guess for that session.
- Source-language options come from the backend's supported Agora locale table. The setting affects media only, not Bubble's question input or the dedicated bidirectional translator. It persists until changed; choose Auto-detect again when moving between unknown languages.
- Voice selection and locale validation, the ordered PCM queue, memory and retained connections remain in place. Fixed-source same-language playback also works across media/Bubble handoffs.

## Verification

- 80 backend tests passed, including startup misclassification, conflicting-language vote reset, partial/duplicate captions, retained chunk ordering, API source validation and a single-language Hindi session.
- Android 0.21 debug build and lint passed; APK installed successfully on the connected phone. The phone was locked, so the new selector has not yet been visually checked there.
- A live synthetic Hindi cooking recording passed through Agora RTC → Hindi-only STT → Telugu translation → Falcon. Four source segments and four Telugu translations produced 121 PCM packets. The route remained translated throughout. Returned phrases included “ఈ రోజు మనం పాస్తా తయారు చేయడం నేర్చుకుంటాము” and “మొదట నీరు మరిగించండి”. This verifies the provider path, not every noisy or mixed-language recording.
- The running Docker backend was replaced with the updated image, retaining the `anyvoice-memory` volume. APK: `dist/AnyVoice-0.21.apk`.

These safeguards fix the demonstrated routing bug and provide a deterministic source setting. They do not guarantee flawless automatic language identification or translation from an external provider. No text-only script filter can reliably detect Hindi that has already been transliterated into Telugu characters. The original user video still needs a replay to judge its recognition and pronunciation.

## Rollback

Source tag: `anyvoice-v0.20-before-language-fix`. Docker image: `anyvoice-backend:rollback-before-language-fix`; the previous container is retained stopped as `anyvoice-backend-before-language-fix`. APK: `dist/AnyVoice-rollback-before-language-fix.apk`. Restore the matching app/backend together; preserve the memory volume.
