# Any Voice 0.22 — simpler controls

- Home now uses a compact greeting, language/voice card, one enable control, source-language selection and compact tool shortcuts. Repeated feature explanations and the duplicate voice card are removed.
- Source, listening-language and conversation language/voice choices share a searchable bottom sheet. Filtering retains original catalog indices. Compatibility filtering and server validation remain unchanged.
- Conversation mode shows You / Them, language and voice choices, one Start / Stop control, and a breathing brand animation. Longer instructions, memory behavior and lock behavior are available through the info control.
- Empty transcript placeholders are hidden. Actual captions remain visible when available. Listening / Speaking / Connecting labels summarize normal state; error messages remain visible.
- Animation follows Android's animator setting. The conversation refresh avoids rewriting unchanged labels.

Validation: Android debug build and lint passed; 0.22 installed on the connected phone. Inspected the compact home, conversation setup and voice-preview sheet. Searched Hindi in the source picker, selected the correct result, then restored Auto-detect. Searched English in the conversation picker, selected English (India), verified the voice list refreshed, and selected Samar. Those conversation changes were not started or saved; existing profile and saved conversation preferences are retained. Screen assist returned to Ready after UI inspection. No AndroidRuntime crash was logged during these checks.

The live conversation animation and speech still need an ordinary listening check; this UI pass did not start a microphone session. Backend translation/audio routing was not changed for this release; the running backend remains 0.21.

APK: `dist/AnyVoice-0.22.apk`. Rollback: source tag `anyvoice-v0.21-before-ui-polish`, APK `dist/AnyVoice-rollback-before-ui-polish.apk`, with the existing 0.21 backend.
