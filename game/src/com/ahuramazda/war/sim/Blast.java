package com.ahuramazda.war.sim;

/** Expanding explosion ring drawn on top of the world. */
public class Blast {

    public float x, y;
    public float r, maxR;
    public float life, maxLife;
    public int color;
    public boolean dead;

    public void init(float x, float y, float maxR, float life, int color) {
        this.x = x;
        this.y = y;
        this.r = maxR * 0.15f;
        this.maxR = maxR;
        this.life = life;
        this.maxLife = life;
        this.color = color;
        this.dead = false;
    }

    public void update(float dt) {
        life -= dt;
        if (life <= 0) {
            dead = true;
            return;
        }
        float t = 1f - life / maxLife;
        r = maxR * (0.15f + 0.85f * Maths.smoothstep(t));
    }
}
