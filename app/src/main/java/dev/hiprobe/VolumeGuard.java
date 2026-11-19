package dev.hiprobe;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;

/** Saves volume before mutation so it can be recovered after an abnormal process exit. */
final class VolumeGuard {
    private final AudioManager audio;
    private final SharedPreferences saved;
    VolumeGuard(Context context) {
        audio = context.getSystemService(AudioManager.class);
        saved = context.getSharedPreferences("volume_recovery", Context.MODE_PRIVATE);
    }
    void mute() {
        if (!saved.getBoolean("pending", false)) {
            int previous = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (!saved.edit().putInt("original", previous).putBoolean("pending", true).commit()) {
                throw new IllegalStateException("Could not save volume for recovery");
            }
        }
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != 0) {
            throw new IllegalStateException("Phone did not allow media volume to be silenced");
        }
    }
    @android.annotation.SuppressLint("ApplySharedPref") // Persist recovery state before subsequent sessions.
    void restore() {
        if (!saved.getBoolean("pending", false)) return;
        int original = saved.getInt("original", 0);
        // Preserve a deliberate user volume change made after our mute operation.
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0);
        }
        saved.edit().remove("original").putBoolean("pending", false).commit();
    }
    boolean isMuted() { return audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0; }
}
