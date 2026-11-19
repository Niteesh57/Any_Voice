# Any Voice 0.15: one floating control

Enable in the main screen, grant microphone/notification permissions and approve Android playback capture. One foreground service owns one floating panel, playback capture and the active cloud connection. Disable in either the main screen or panel stops both modes and restores media volume. There is no second Bubble enable button or separate Bubble service.

## Source selection

The local playback monitor excludes Any Voice's own UID. App audio above -55 dBFS for about 400 ms selects media translation; a 6.5 second quiet interval releases that priority. This is sustained audio activity detection, not semantic speech classification: music can also keep media mode active. Short isolated clicks do not select media mode. Microphone upload is gated immediately around new app sounds to avoid sending speaker playback as a question.

Media translation retains the existing Agora language detection and selected-language original-voice routing. Other detected languages use the existing Agora translation and Murf voice pipeline. Same-language media still has media priority; it is not treated as a microphone question.

After media becomes quiet and translated output drains, Bubble conversation uses physical microphone audio through Agora RTC, Qwen audio input and selected-language Murf speech. As before, a question ends after about 650 ms of silence, and answers finish before the next question opens. Microphone is released on lock and during media translation. On unlock, conversation can resume while the overall Enable remains on.

Ask Bubble in the panel temporarily overrides media priority. Original media is muted while the question is active, preserving and later restoring the previous media volume. The override returns to automatic after an answer, on Return to automatic, or after 30 seconds without an answer. An answer already thinking/speaking finishes before switching to media. Switching away from media stops its upload, waits for its queued output to drain, and restores the original volume.

## Switching limits

Modes currently use separate backend sessions. Handoffs close the old session/RTC engine before opening the next and therefore include provider connection time; they are not instantaneous. Media has a bounded 20 second in-memory input buffer while connecting, then uploads in order at real-time pace. Its buffering can add delay; exceptionally long connections exceed that bound. Capture-restricted apps still cannot be translated. Microphone conversation cannot distinguish arbitrary nearby speech from deliberate questions while enabled; Disable stops listening.

## Rollback

The exact previous source is commit 7c2bac0, tag anyvoice-v0.14-before-unified. Its matching APK is dist/AnyVoice-rollback-before-unified.apk. The backend is unchanged from that snapshot; no Docker rebuild is required.

## Validation

Standalone JVM regression scenarios cover silence, brief transients, sustained app audio, sentence gaps, quiet release, immediate microphone gating, explicit override, return to automatic and override expiry. Android build/lint and device results are recorded in VALIDATION.md.
