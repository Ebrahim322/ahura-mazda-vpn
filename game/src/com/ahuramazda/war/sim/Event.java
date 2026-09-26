package com.ahuramazda.war.sim;

/** A one-shot notification produced by the simulation (used for sound / haptics). */
public class Event {

    public static final int EV_SHOOT = 1;
    public static final int EV_ENEMY_SHOOT = 2;
    public static final int EV_EXPLOSION = 3;
    public static final int EV_HIT = 4;
    public static final int EV_PLAYER_HIT = 5;
    public static final int EV_PICKUP = 6;
    public static final int EV_WAVE_START = 7;
    public static final int EV_WAVE_CLEAR = 8;
    public static final int EV_GAME_OVER = 9;
    public static final int EV_AIRSTRIKE = 10;
    public static final int EV_SPAWN = 11;
    public static final int EV_EMPTY = 12;

    public int type;
    public float x;
    public float y;
    /** 0..1 loudness hint, usually based on distance from the player. */
    public float v;

    public Event(int type, float x, float y, float v) {
        this.type = type;
        this.x = x;
        this.y = y;
        this.v = v;
    }
}
