package com.ahuramazda.war.sim;

/** Pickup dropped by destroyed enemies. */
public class PowerUp {

    public static final int P_HEAL = 0;
    public static final int P_SHIELD = 1;
    public static final int P_RAPID = 2;
    public static final int P_SPREAD = 3;
    public static final int P_NUKE = 4;

    public float x, y;
    public int kind;
    public float life;
    public float t;
    public boolean dead;

    public void init(float x, float y, int kind) {
        this.x = x;
        this.y = y;
        this.kind = kind;
        this.life = 13f;
        this.t = 0;
        this.dead = false;
    }

    public void update(float dt) {
        life -= dt;
        t += dt;
        if (life <= 0) {
            dead = true;
        }
    }
}
