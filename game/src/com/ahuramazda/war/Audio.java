package com.ahuramazda.war;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.util.Log;

/**
 * Tiny software synthesiser: every sound effect and the music loop are generated
 * at run time, so the game needs no audio assets at all.
 */
public class Audio {

    public static final int S_SHOOT = 0;
    public static final int S_ESHOOT = 1;
    public static final int S_BOOM = 2;
    public static final int S_HIT = 3;
    public static final int S_HURT = 4;
    public static final int S_PICK = 5;
    public static final int S_WAVE = 6;
    public static final int S_OVER = 7;
    public static final int S_AIR = 8;
    public static final int S_SPAWN = 9;
    public static final int S_UI = 10;
    public static final int S_BOSS = 11;

    private static final String TAG = "AhuraWar";
    private static final int SR = 44100;
    private static final int NV = 30;

    private static final int W_SINE = 0;
    private static final int W_SQUARE = 1;
    private static final int W_SAW = 2;
    private static final int W_NOISE_LP = 3;
    private static final int W_NOISE_HI = 4;

    private final int[] vWave = new int[NV];
    private final float[] vT = new float[NV];
    private final float[] vDur = new float[NV];
    private final float[] vF0 = new float[NV];
    private final float[] vF1 = new float[NV];
    private final float[] vG = new float[NV];
    private final float[] vPhase = new float[NV];
    private final float[] vLp = new float[NV];
    private final float[] vCut = new float[NV];

    private final float[] acc = new float[2048];
    private final short[] out = new short[2048];

    private AudioTrack track;
    private Thread thread;
    private volatile boolean running;

    public boolean sfxOn = true;
    public boolean musicOn = true;
    private float master = 0.9f;

    private long sample;
    private long nextStep;
    private int stepIdx;
    private int noiseSeed = 1234567;

    private static final float[] BASS = {110f, 110f, 130.81f, 98f, 87.31f, 98f, 110f, 130.81f};

    public void start() {
        if (running) {
            return;
        }
        try {
            int min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int buf = Math.max(min, SR / 5);
            if (Build.VERSION.SDK_INT >= 23) {
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();
                AudioFormat fmt = new AudioFormat.Builder()
                        .setSampleRate(SR)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build();
                track = new AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt)
                        .setBufferSizeInBytes(buf).setTransferMode(AudioTrack.MODE_STREAM).build();
            } else {
                track = new AudioTrack(android.media.AudioManager.STREAM_MUSIC, SR,
                        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, buf,
                        AudioTrack.MODE_STREAM);
            }
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track = null;
                return;
            }
            track.play();
        } catch (Throwable t) {
            Log.w(TAG, "audio unavailable", t);
            track = null;
            return;
        }
        running = true;
        thread = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "ahura-audio");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        if (track != null) {
            try {
                track.stop();
                track.release();
            } catch (Throwable ignored) {
                // ignore
            }
            track = null;
        }
    }

    private void loop() {
        int n = 1024;
        while (running && track != null) {
            mix(n);
            try {
                track.write(out, 0, n);
            } catch (Throwable t) {
                running = false;
                break;
            }
        }
    }

    private void mix(int n) {
        for (int i = 0; i < n; i++) {
            acc[i] = 0f;
        }
        for (int v = 0; v < NV; v++) {
            float dur = vDur[v];
            if (dur <= 0f) {
                continue;
            }
            float g = vG[v];
            int wave = vWave[v];
            float f0 = vF0[v];
            float f1 = vF1[v];
            float t = vT[v];
            float phase = vPhase[v];
            float lp = vLp[v];
            float cut = vCut[v];
            float step = 1f / SR;
            for (int i = 0; i < n; i++) {
                float tt = t + i * step;
                float k = tt / dur;
                float env;
                if (k >= 1f) {
                    env = 0f;
                } else {
                    env = (1f - k) * (1f - k);
                    if (k < 0.02f) {
                        env *= k * 50f;
                    }
                }
                float f = f0 + (f1 - f0) * k;
                float s;
                if (wave == W_NOISE_LP || wave == W_NOISE_HI) {
                    float nz = nextNoise();
                    if (wave == W_NOISE_HI) {
                        s = (nz - lp) * 0.7f;
                        lp = nz;
                    } else {
                        // low pass that closes over the life of the voice: bright crack -> rumble
                        lp += (nz - lp) * (cut * (1f - k * 0.92f));
                        s = lp * 2.4f;
                    }
                } else {
                    phase += f * step;
                    if (phase > 1f) {
                        phase -= 1f;
                    }
                    if (wave == W_SINE) {
                        s = (float) Math.sin(phase * 6.28318f);
                    } else if (wave == W_SQUARE) {
                        s = phase < 0.5f ? 1f : -1f;
                        s = s * 0.7f + (phase < 0.5f ? 0.3f : -0.3f) * (1f - k);
                    } else {
                        s = phase * 2f - 1f;
                    }
                }
                acc[i] += s * env * g;
            }
            vT[v] = t + n * step;
            vPhase[v] = phase;
            vLp[v] = lp;
            if (vT[v] >= dur) {
                vDur[v] = 0f;
            }
        }

        if (musicOn) {
            mixMusic(n);
        }

        float m = master;
        for (int i = 0; i < n; i++) {
            float s = acc[i] * m;
            if (s > 1f) {
                s = 1f;
            } else if (s < -1f) {
                s = -1f;
            }
            s = s * (1.5f - 0.5f * s * s);
            out[i] = (short) (s * 32000f);
        }
        sample += n;
    }

    /** Cheap white noise in [-1,1) from an integer LCG. */
    private float nextNoise() {
        noiseSeed = noiseSeed * 1103515245 + 12345;
        return ((noiseSeed >> 16) & 0x7fff) / 16383.5f - 1f;
    }

    private void mixMusic(int n) {
        float spb = 60f / 128f / 4f;
        if (nextStep == 0) {
            nextStep = sample + (long) (spb * SR);
        }
        for (int i = 0; i < n; i++) {
            if (sample + i >= nextStep) {
                nextStep += (long) (spb * SR);
                step(stepIdx);
                stepIdx = (stepIdx + 1) & 31;
            }
        }
    }

    private void step(int s) {
        int q = s & 15;
        if (q == 0 || q == 4 || q == 8 || q == 12) {
            voice(W_SINE, 130f, 42f, 0.17f, 0.42f);
        }
        if (q == 4 || q == 12) {
            voice(W_NOISE_HI, 0f, 0f, 0.13f, 0.14f);
        }
        if ((q & 1) == 0) {
            voice(W_NOISE_HI, 0f, 0f, 0.03f, 0.05f);
        }
        if ((q & 3) == 0 || q == 6 || q == 14) {
            int idx = (s >> 2) & 7;
            voice(W_SAW, BASS[idx], BASS[idx], 0.2f, 0.075f);
        }
        if (s == 30) {
            voice(W_SQUARE, 880f, 660f, 0.12f, 0.05f);
        }
    }

    private void voice(int wave, float f0, float f1, float dur, float gain) {
        for (int v = 0; v < NV; v++) {
            if (vDur[v] <= 0f) {
                vWave[v] = wave;
                vT[v] = 0f;
                vDur[v] = dur;
                vF0[v] = f0;
                vF1[v] = f1;
                vG[v] = gain;
                vPhase[v] = 0f;
                vLp[v] = 0f;
                vCut[v] = wave == W_NOISE_LP ? 0.05f : 0.5f;
                return;
            }
        }
    }

    public void play(int s, float vol) {
        if (!sfxOn || track == null) {
            return;
        }
        float v = vol < 0f ? 0f : (vol > 1f ? 1f : vol);
        switch (s) {
            case S_SHOOT:
                voice(W_SQUARE, 640f, 200f, 0.09f, 0.17f * v);
                voice(W_NOISE_HI, 0f, 0f, 0.05f, 0.06f * v);
                break;
            case S_ESHOOT:
                voice(W_SQUARE, 330f, 150f, 0.08f, 0.10f * v);
                break;
            case S_BOOM: {
                int slot = freeSlot();
                if (slot >= 0) {
                    vWave[slot] = W_NOISE_LP;
                    vT[slot] = 0f;
                    vDur[slot] = 0.55f;
                    vF0[slot] = 0f;
                    vF1[slot] = 0f;
                    vG[slot] = 0.42f * v;
                    vPhase[slot] = 0f;
                    vLp[slot] = 0f;
                    vCut[slot] = 0.42f;
                    // make the filter sweep down by shrinking dur effect
                }
                voice(W_SINE, 110f, 38f, 0.45f, 0.34f * v);
                break;
            }
            case S_HIT:
                voice(W_NOISE_HI, 0f, 0f, 0.06f, 0.11f * v);
                break;
            case S_HURT:
                voice(W_SAW, 300f, 80f, 0.32f, 0.26f * v);
                break;
            case S_PICK:
                voice(W_SINE, 620f, 620f, 0.09f, 0.16f * v);
                voice(W_SINE, 930f, 930f, 0.10f, 0.13f * v);
                break;
            case S_WAVE:
                voice(W_SQUARE, 440f, 440f, 0.13f, 0.12f * v);
                voice(W_SQUARE, 587f, 587f, 0.14f, 0.11f * v);
                voice(W_SQUARE, 880f, 880f, 0.20f, 0.10f * v);
                break;
            case S_OVER:
                voice(W_SAW, 392f, 380f, 0.30f, 0.16f * v);
                voice(W_SAW, 311f, 300f, 0.32f, 0.15f * v);
                voice(W_SAW, 233f, 220f, 0.55f, 0.15f * v);
                break;
            case S_AIR:
                voice(W_SINE, 1900f, 320f, 0.75f, 0.16f * v);
                break;
            case S_SPAWN:
                voice(W_SINE, 180f, 460f, 0.14f, 0.09f * v);
                break;
            case S_UI:
                voice(W_SQUARE, 760f, 900f, 0.06f, 0.11f * v);
                break;
            case S_BOSS:
                voice(W_SAW, 90f, 55f, 1.1f, 0.26f * v);
                voice(W_SQUARE, 220f, 165f, 0.9f, 0.10f * v);
                break;
            default:
                break;
        }
    }

    private int freeSlot() {
        for (int v = 0; v < NV; v++) {
            if (vDur[v] <= 0f) {
                return v;
            }
        }
        return -1;
    }
}
