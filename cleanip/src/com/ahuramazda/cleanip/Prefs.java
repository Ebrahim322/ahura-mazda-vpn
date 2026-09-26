package com.ahuramazda.cleanip;

import android.content.Context;
import android.content.SharedPreferences;

import com.ahuramazda.cleanip.core.ScanConfig;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Persists the scan settings between runs. */
public final class Prefs {

    private static final String FILE = "cleanip";
    private static final String KEY_DOMAIN = "domain";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_COUNT = "count";
    private static final String KEY_LIVE = "live";
    private static final String KEY_MANUAL = "manual";
    private static final String KEY_RADIUS = "radius";
    private static final String KEY_REF = "ref";
    private static final String KEY_PORTS = "ports";
    private static final String KEY_TIMEOUT = "timeout";
    private static final String KEY_THREADS = "threads";
    private static final String KEY_VERIFY = "verify";
    private static final String KEY_SPEED = "speed";
    private static final String KEY_SPEED_TOP = "speedTop";
    private static final String KEY_SPEED_TIME = "speedTime";
    private static final String KEY_SPEED_PATH = "speedPath";

    private final SharedPreferences prefs;

    public Prefs(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public ScanConfig loadConfig() {
        ScanConfig config = new ScanConfig();
        config.domain = prefs.getString(KEY_DOMAIN, config.domain);
        config.timeoutMs = prefs.getInt(KEY_TIMEOUT, config.timeoutMs);
        config.threads = prefs.getInt(KEY_THREADS, config.threads);
        config.verifyCert = prefs.getBoolean(KEY_VERIFY, config.verifyCert);
        config.speedTest = prefs.getBoolean(KEY_SPEED, config.speedTest);
        config.speedTopN = prefs.getInt(KEY_SPEED_TOP, config.speedTopN);
        config.speedDurationMs = prefs.getInt(KEY_SPEED_TIME, config.speedDurationMs);
        config.speedPathOverride = prefs.getString(KEY_SPEED_PATH, "");
        config.ports.clear();
        Set<String> ports = prefs.getStringSet(KEY_PORTS, null);
        if (ports == null || ports.isEmpty()) {
            config.ports.add(443);
            config.ports.add(80);
        } else {
            List<Integer> values = new ArrayList<Integer>();
            for (String port : ports) {
                try {
                    values.add(Integer.parseInt(port));
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
            java.util.Collections.sort(values);
            config.ports.addAll(values);
        }
        return config;
    }

    public void saveConfig(ScanConfig config, String source, int count, boolean live, String manual,
                           int radius, String referenceIp) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_DOMAIN, config.domain);
        editor.putInt(KEY_TIMEOUT, config.timeoutMs);
        editor.putInt(KEY_THREADS, config.threads);
        editor.putBoolean(KEY_VERIFY, config.verifyCert);
        editor.putBoolean(KEY_SPEED, config.speedTest);
        editor.putInt(KEY_SPEED_TOP, config.speedTopN);
        editor.putInt(KEY_SPEED_TIME, config.speedDurationMs);
        editor.putString(KEY_SPEED_PATH, config.speedPathOverride == null ? "" : config.speedPathOverride);
        LinkedHashSet<String> ports = new LinkedHashSet<String>();
        for (Integer port : config.ports) {
            ports.add(String.valueOf(port));
        }
        editor.putStringSet(KEY_PORTS, ports);
        editor.putString(KEY_SOURCE, source);
        editor.putInt(KEY_COUNT, count);
        editor.putBoolean(KEY_LIVE, live);
        editor.putString(KEY_MANUAL, manual);
        editor.putInt(KEY_RADIUS, radius);
        editor.putString(KEY_REF, referenceIp);
        editor.apply();
    }

    public String source() {
        return prefs.getString(KEY_SOURCE, "cf");
    }

    public int count() {
        return prefs.getInt(KEY_COUNT, 1000);
    }

    public boolean liveRanges() {
        return prefs.getBoolean(KEY_LIVE, true);
    }

    public String manual() {
        return prefs.getString(KEY_MANUAL, "");
    }

    public int radius() {
        return prefs.getInt(KEY_RADIUS, 24);
    }

    public String referenceIp() {
        return prefs.getString(KEY_REF, "");
    }
}
