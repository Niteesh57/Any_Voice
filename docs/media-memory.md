# Live handoff and media memory (0.18)

Media capture continuously drains AudioRecord, but sends only fresh frames while the media RTC connection is joined. There is no pre-roll or historical playback buffer. Audio captured while connecting or while Bubble handles microphone commands is discarded instead of replayed later. This removes the old queue that could retain 20 seconds and never catch up at normal capture speed.

Entering media mode immediately mutes original media while language detection connects. A confirmed same-language result restores original sound; other languages use translated output. Tapping Ask Bubble while in media mode closes the old media session immediately and mutes media for the microphone handoff. Ordinary translated chunks within a media session still play in order without interruption.

The backend stores finalized source captions and accepted translated captions in SQLite `media_segments`. Updates replace the same session/segment/kind record; they do not duplicate growing caption prefixes. Entries are scoped to the existing authenticated installation identity. Separate listening connections have separate session IDs. No raw audio is stored. Screen research screenshots and screen text remain ephemeral.

Bubble receives bounded recent caption excerpts and a recent-session catalog in its saved context. `media_recall` retrieves older excerpts, optionally filtered by session or literal text; `before=next_before` pages backward without replaying audio. `memory_search` also searches media text. Full stored captions survive stopping translation, app restarts and Docker recreation through the existing `anyvoice-memory` volume. Request prompts treat stored captions as untrusted reference, not instructions.

This is memory of captured speech, not a downloaded video or a record of visual events. It cannot reconstruct earlier videos that were never saved, content missed during connection setup, or inaudible/noncapturable audio. A listening session can contain multiple videos if playback changes without a mode switch; it is not an authoritative video title or URL. Bubble should ask when the intended content is unclear and qualify answers as based on the captured portion.

Rollback: source tag `anyvoice-before-live-handoff-memory`, APK `dist/AnyVoice-rollback-before-media-memory.apk`, image `anyvoice-backend:rollback-before-media-memory`. The new table is additive and compatible with the earlier backend.
