package com.ahuramazda.war.sim;

/** Joystick + button state handed to the simulation every frame. */
public class Input {

    /** Normalised movement direction (length clamped to 1). */
    public float mx;
    public float my;
    public boolean fire;
    public boolean special;

    public void reset() {
        mx = 0;
        my = 0;
        fire = false;
        special = false;
    }
}
