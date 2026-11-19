# Voice volume — Any Voice 0.13

The Home screen, voice picker and expanded floating control share a saved 0–100% voice-volume setting. It changes playback gain for translated audio and previews, without resetting or flushing the audio queue. Main-screen and voice-picker volume keys change it in 5% steps. It does not change system alarm volume; alarm audio remains the underlying output route.

While viewing another app, use the floating slider. Media-volume changes no longer stop translation: the service restores the media mute while translated playback continues. Original-audio mode retains normal media-volume behavior.

Android assemble/lint passed. Installed versionCode 13/versionName 0.13 on the OnePlus 7. Device checks confirmed hardware-key settings of 75 → 80 → 75, Home slider to 50%, floating slider back to 75%, and capture remaining active through slider changes. Explicit Stop restored original audio. The existing profile, language and character selection were preserved.

The active translated-speech regression remains pending: automatic approval review blocked opening the local test-audio page with “blocked by policy”. Capture during the permitted test was silent and detecting; it does not establish runtime coverage of the translated-audio media-remute branch. The temporary HTTP server and USB reverse mapping were removed afterward.

This is an Android-only update; Docker and provider settings did not need changes. The 0.12 source tag and APK remain available for revert.
