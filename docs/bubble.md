# Bubble: manual voice conversation (0.14)

Choose a supported language and Murf voice, tap Enable Bubble, open the floating controls and tap Ask Bubble. Showing the icon alone does not record audio. There is no wake phrase or automatic greeting. The conversation continues across other apps until End conversation, lock, translation, or service shutdown. After lock or translation, tap Ask Bubble again.

Questions use adaptive microphone activity detection with a 650 ms silence endpoint, 200 ms preroll, and a 12 second maximum turn. Microphone PCM travels through Agora RTC to the backend, then goes as WAV audio to Qwen. Text responses use the selected language and the validated Murf voice through Agora Conversational AI Engine manual speech. Bubble does not start an Agora speech-to-text bot. Playback acknowledgements prevent opening the next question before the answer finishes. Microphone upload is muted during thinking/playback; the recorder is released on lock or translation.

Qwen configuration is in the ignored backend/.env, never in the APK. Audio and three turns of conversation history are kept in backend memory for the session. Bubble implements spoken questions and answers, not device automation tools.

Validation: 44 backend unit tests pass; Android assembleDebug and lintDebug pass. Earlier live RTC/Qwen/Murf tests covered the audio path; the new manual activation flow still needs an on-phone spoken question check. Version 0.13 rollback remains dist/AnyVoice-rollback-0.13.apk and tag anyvoice-v0.13-voice-volume.

The original expandable floating panel is restored. Ask Bubble is a single tap control, with End conversation while active. The floating volume slider is removed; voice volume remains available in the main app.
