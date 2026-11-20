# Conversation turns — 0.24

This change is confined to the face-to-face conversation translator. Media translation and Bubble question routing are unchanged.

The conversation screen has Auto / My turn / Their turn controls. My turn means the profile language toward the partner language; Their turn reverses it. The server acknowledges the choice before microphone input resumes. These are user-selected directions, not voice identification. Each new conversation begins in Auto.

Auto retains the previous direction for short or low-text fragments (at most two words or fewer than twelve letter/combining-mark characters), rather than reversing direction for a brief number or borrowed word. A longer clear opposite-language segment can select the other direction. This is a heuristic, not guaranteed speaker detection; use the explicit controls for ambiguous turns or sustained mixed-language speech.

Once a segment is admitted, its target and voice remain fixed through finalized-prefix updates. Opposite-target translation rows cannot change that segment's playback direction. If recognition already supplies the desired output language for a retained/selected direction, that text is spoken with the selected target voice instead of translating it back. This preserves the chosen direction; it does not repair incorrectly recognized words. Otherwise the normal Agora translation is used.

Turn changes establish a new timestamp boundary, clear unpaired pending translations and reject older source segments. Already queued audio is allowed to finish; the player is not interrupted. Conversation memory marks user-selected turns separately from language-inferred or retained-turn attribution. Speaker identity remains unverified.

Validation: 84 backend tests passed, including numbers, correct target voices, opposite-target suppression, normal direction changes, provenance, late-caption boundaries, owner isolation, invalid sides and rejecting changes outside conversation mode. Android debug build and lint passed. Docker and the phone APK were updated to 0.24. A real two-person mixed-language check is still needed; no translation-quality guarantee is implied.

Rollback: source tag `anyvoice-v0.23-before-turn-lock`, APK `dist/AnyVoice-rollback-before-turn-lock.apk`, Docker image `anyvoice-backend:rollback-before-turn-lock`. The new APK is `dist/AnyVoice-0.24.apk`.
