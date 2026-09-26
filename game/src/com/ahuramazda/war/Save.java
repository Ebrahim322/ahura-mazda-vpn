package com.ahuramazda.war;

import android.content.Context;
import android.content.SharedPreferences;

/** Tiny wrapper around SharedPreferences. */
public class Save {

    private static final String PREF = "ahura_war";
    private static final String K_BEST = "best";
    private static final String K_SOUND = "sound";
    private static final String K_MUSIC = "music";
    private static final String K_VIBE = "vibe";
    private static final String K_PLAYED = "played";

    private final SharedPreferences prefs;

    public Save(Context c) {
        prefs = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public int best() {
        return prefs.getInt(K_BEST, 0);
    }

    public void best(int v) {
        prefs.edit().putInt(K_BEST, v).apply();
    }

    public boolean sound() {
        return prefs.getBoolean(K_SOUND, true);
    }

    public void sound(boolean v) {
        prefs.edit().putBoolean(K_SOUND, v).apply();
    }

    public boolean music() {
        return prefs.getBoolean(K_MUSIC, true);
    }

    public void music(boolean v) {
        prefs.edit().putBoolean(K_MUSIC, v).apply();
    }

    public boolean vibe() {
        return prefs.getBoolean(K_VIBE, true);
    }

    public void vibe(boolean v) {
        prefs.edit().putBoolean(K_VIBE, v).apply();
    }

    public boolean played() {
        return prefs.getBoolean(K_PLAYED, false);
    }

    public void played(boolean v) {
        prefs.edit().putBoolean(K_PLAYED, v).apply();
    }
}
