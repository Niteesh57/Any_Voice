# Bidirectional translator and evidence memory — 0.20

Open **Bidirectional translator** from the Any Voice home screen. Your language and voice come from the existing profile. Choose the other person's language, a compatible voice for your translated replies, and optionally their name. Tap **Start conversation** explicitly. The voice list comes from the live Falcon catalog; both selected voice-language pairs are revalidated by the backend before starting.

The app interprets only the selected pair: the other language becomes your language, and your language becomes theirs. People should take turns. The screen shows original and translated text. Listening pauses while translated audio is played to avoid retranslating the phone's own voice, then rearms after playback finishes. This is not overlapping-speaker separation or biometric speaker identification.

Media translation and Bubble question listening are suspended while the mode is active. Their connections remain open; media uses UID 1001, Bubble UID 1002 and this microphone UID 1003. The interpreter's STT bot subscribes only to UID 1003 and has two source-to-target mappings. Direct Falcon streams use the appropriate validated voice and locale on every chunk. No translated conversation is sent to Bubble as an instruction or tool command.

Stop gates microphone input immediately, lets accepted audio finish, and restores the preceding media/Bubble mode without recreating its session. The same interpreter task can be reused for the same language pair; selecting a different pair reconfigures the interpreter task while retaining the main connections. Locking or screen-off disables the conversation capture and ends the enabled service. Unlocking does not restart it automatically. Normal provider disconnects and session expiry can still require reconnecting.

## Memory provenance

Each conversation has a fresh random capture ID, separate from the retained RTC session ID. Every evidence row is scoped to the existing authenticated installation identity and carries:

- Source type: `media` or `conversation`.
- Capture/session ID and segment ID; original and translation are two versions of one statement.
- Language, original or translated text, capture time and a reference cursor.
- Speaker label and attribution basis. `language_inferred` means the label was assigned from the selected languages; it does not verify who spoke.

The existing `media_segments` schema stays unchanged. The separate `caption_provenance` table adds source metadata without breaking 0.19 writes. Historical captures default to media with unknown speaker identity. Raw audio is not saved. Memory survives restarting the app/backend through the existing SQLite volume.

Recent media and translated conversations are retrieved separately, with bounded context for each. `media_recall` can filter either source or retrieve both, including older captures. Recall preserves conflicting statements rather than merging them into one fact. Capture times are supplied to Bubble in the selected local timezone. **Tasks & memory** displays the source labels, speaker uncertainty, text and local capture times for review.

Live tests covered agreement and disagreement between a video fixture and a conversation fixture. Bubble attributed the matching statement to both sources. With a two-minute video instruction and a three-minute conversation instruction, it explicitly reported the difference. No recipe/person-specific instruction was added to the production prompt.

## Verification boundaries

The release record in `VALIDATION.md` lists checks that passed and checks still needing a real two-person session. Synthetic RTC tests verify routing, translation, voices, playback acknowledgments and source memory; they do not establish reliability for room noise, simultaneous speech, accents or physical speaker-to-microphone echo on every phone. Entered names are never a verified identity. Captured media does not automatically include its video title, URL or full video contents.

Testing exposed Murf's `ACTIVE_CONTEXT_LIMIT_EXCEEDED` on rapid successive contexts. The client now allows the previous final turn's slot to settle and retries this rejection only when no PCM has been emitted. It never replays a partially generated phrase. Murf documents that a slot is freed shortly after `final`; see [context lifecycle](https://murf.ai/api/docs/text-to-speech/web-sockets/context-id). Errors now include a safe provider code/type rather than speech or credentials.

The verified 0.19 rollback and its SHA-256 are recorded in [verified-baseline-0.19.md](verified-baseline-0.19.md). Tests use isolated fixture identities and do not insert test conversations into the user's memory.
