# Hi Audio Probe — stage 1

Native Android experiment for a OnePlus 7 running Android 10 or newer. No translation yet.

## Install and try

Install `dist/HiAudioProbe-debug.apk` on the phone (allow installation from the file manager when Android asks).

1. Make the phone's **alarm volume** audible. The app does not change alarm volume.
2. Play a spoken YouTube video using the **phone speaker**. Begin with ordinary, unprotected media.
3. Open **Hi Audio Probe**. Optionally enable floating controls using the settings button.
4. Tap **Start capture + mute + hi**, allow audio capture, then approve Android's capture prompt. On versions offering a screen-sharing choice, choose the entire screen for switching apps. The app never creates a virtual display or captures screen images.
5. Return to the video. It should continue visually, with media sound muted. If captured audio energy is detected, the app says “hi” about once a second using the alarm stream.
6. Inspect the floating panel, or return to the app for diagnostics. **Non-silent buffers while muted** must keep increasing. Use **Original / replacement comparison** to compare capture with sound restored. Hi is paused in comparison mode.
7. Tap **Stop + restore** on the panel/notification, or stop inside the app. The previous media volume is restored unless you changed it yourself during the session.

**Test hi output only** speaks once regardless of capture. This checks the voice engine and routing, but does not prove audio interception.

## What counts as success

All of these must hold on the real phone:

- The source video continues playing.
- The original audio is inaudible.
- Hi is audible through the phone speaker.
- Non-silent capture buffers keep increasing **while muted**.
- Stopping restores normal playback and media volume.

If capture only works with original sound restored, this volume-based approach does not meet the requirement. If both capture counters stay at zero, the source may block capture, be paused, or be silent. Compare with another source. The meter measures audio energy above -60 dBFS, not speech or language; music also triggers it.

## Scope and limitations

This is a stock-Android **media-volume experiment**, not a system audio replacement driver. Android playback capture copies permitted media/game audio. It cannot guarantee capture of all apps, protected content, or calls. Hi uses an independent alarm usage; device/TTS routing behavior must be verified. No audio focus is requested, to avoid deliberately pausing the source. Bluetooth and headphones are not yet validated.

Android requires `RECORD_AUDIO` permission for playback capture; this app never selects a microphone audio source. Audio buffers are examined in memory and discarded. No Internet permission, recording files, analytics, backend, or translation service is included. Its own UID is excluded from capture.

Normal stop, task dismissal, service teardown, and projection revocation restore saved media volume. After force-stop or a process crash, reopen the app to recover saved volume, or raise media volume manually. Android cannot guarantee application cleanup after force-stop. Only media volume is modified; alarm volume and Do Not Disturb settings are not changed.

## Build

Requires JDK 17+ (tested with JDK 21), Android SDK platform 36, and Android Gradle Plugin 9.0.1 / Gradle 9.3.1.

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
adb install -r .\dist\HiAudioProbe-debug.apk
```

Source: `app/src/main/java/dev/hiprobe/`. Debug-signed APK is for direct device testing.

See `VALIDATION.md` for measured emulator results and remaining physical-device checks. `test-source` is a separate developer-only known-audio fixture, not part of the probe APK.

References: [Android playback capture](https://developer.android.com/media/platform/av-capture), [media projection service requirements](https://developer.android.com/media/grow/media-projection).

# Any_Voice
