# Conversation readiness — 0.23

The 0.22 conversation UI checked `requested` before `active`. The request flag deliberately remains true during a manually authorized conversation because it gates microphone publishing. Consequently, even an active translator displayed Connecting indefinitely. Clearing this flag to repair the label would instead silence microphone input.

0.23 retains that authorization flag and separates readiness from intent. The UI displays:

- Connecting translator: permission/setup or conversation-mode transition is pending.
- Connecting microphone: the backend has enabled conversation input but a readable microphone frame is not yet eligible for publishing.
- Listening · speak now: a microphone frame is available, the backend permits conversation listening, the RTC output gate permits input, the phone is unlocked and the request is still active.
- Translating: the active conversation is not currently listening while it prepares/plays output.
- Finishing: the explicit stop is pending.

Stopping the microphone or leaving conversation input clears readiness. A new `AnyVoiceConversation: microphone_ready` diagnostic contains no speech content. Normal connection errors remain visible after state cleanup.

Recent session `3a743cc3` switched into conversation at 15:58:10 UTC; its first accepted translated text was at 15:58:48 UTC. This does not establish when the user first spoke. The misleading Connecting label is verified in code; the upstream delay cannot be attributed to initialization alone from those timestamps. This release changes readiness reporting, not provider translation latency.

Validation: debug build/lint and a standalone Java lifecycle regression passed, covering requested-but-active readiness, microphone readiness, translating, stopping and errors. APK 0.23 installed on the connected phone. The native microphone-ready diagnostic was observed and the user confirmed seeing the readiness status during a live conversation. This does not establish an end-to-end translation latency benchmark.

Rollback: source tag `anyvoice-v0.22-before-readiness-fix`, APK `dist/AnyVoice-rollback-before-readiness-fix.apk`. The backend is unchanged.
