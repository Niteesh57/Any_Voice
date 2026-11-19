package dev.hiprobe;

import android.content.Context;
import android.content.SharedPreferences;

final class UserProfile {
    private final SharedPreferences prefs;
    UserProfile(Context context) { prefs=context.getSharedPreferences("profile",Context.MODE_PRIVATE); }
    String name() { return prefs.getString("name", ""); }
    String gender() { return prefs.getString("gender", "Female"); }
    String language() { return prefs.getString("language", ""); }
    String languageName() { return prefs.getString("language_name", "Choose language"); }
    String nativeName() { return prefs.getString("native_name", "Your language"); }
    String voice() { return prefs.getString("voice", ""); }
    String voiceName() { return prefs.getString("voice_name", "Choose voice"); }
    String voiceGender() { return prefs.getString("voice_gender", ""); }
    void identity(String name,String gender) { prefs.edit().putString("name",name.trim()).putString("gender",gender).apply(); }
    void selection(String language,String languageName,String nativeName,String voice,String voiceName,String gender) {
        prefs.edit().putString("language",language).putString("language_name",languageName).putString("native_name",nativeName)
            .putString("voice",voice).putString("voice_name",voiceName).putString("voice_gender",gender).apply();
    }
}
