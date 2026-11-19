package dev.hiprobe;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

/** App-only playback gain. Never changes the system alarm volume. */
final class OutputVolume {
    private final SharedPreferences prefs;
    OutputVolume(Context context) { prefs=context.getSharedPreferences("audio_output",Context.MODE_PRIVATE); }
    int percent() { return Math.max(0,Math.min(100,prefs.getInt("voice_volume",75))); }
    float gain() { return percent()/100f; }
    void percent(int value) { prefs.edit().putInt("voice_volume",Math.max(0,Math.min(100,value))).apply(); }
    void listen(SharedPreferences.OnSharedPreferenceChangeListener listener) { prefs.registerOnSharedPreferenceChangeListener(listener); }
    void unlisten(SharedPreferences.OnSharedPreferenceChangeListener listener) { prefs.unregisterOnSharedPreferenceChangeListener(listener); }
    boolean key(KeyEvent event) {
        int code=event.getKeyCode();
        if(code!=KeyEvent.KEYCODE_VOLUME_UP&&code!=KeyEvent.KEYCODE_VOLUME_DOWN)return false;
        if(event.getAction()==KeyEvent.ACTION_DOWN)percent(percent()+(code==KeyEvent.KEYCODE_VOLUME_UP?5:-5));
        return true;
    }
}
