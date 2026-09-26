package com.ahuramazda.war.sim;

/** Decorative particle: spark, smoke puff, debris or muzzle flash. */
public class Particle {

    public static final int K_SPARK = 0;
    public static final int K_SMOKE = 1;
    public static final int K_DEBRIS = 2;
    public static final int K_FLASH = 3;
    public static final int K_TRAIL = 4;

    public float x, y, vx, vy;
    public float life, maxLife;
    public float size, size2;
    public float rot, spin;
    public int kind;
    public int color;
    public boolean dead;

    public void init(int kind, float x, float y, float vx, float vy, float life, float size, int color) {
        this.kind = kind;
        this.x = x;
        this.y = y;
        this.vx = vx;
        this.vy = vy;
        this.life = life;
        this.maxLife = life;
        this.size = size;
        this.size2 = size * 0.2f;
        this.color = color;
        this.rot = 0;
        this.spin = 0;
        this.dead = false;
    }

    public void update(float dt) {
        life -= dt;
        if (life <= 0) {
            dead = true;
            return;
        }
        x += vx * dt;
        y += vy * dt;
        rot += spin * dt;
        float drag;
        if (kind == K_SMOKE) {
            drag = 1.6f;
            size += size * 1.1f * dt;
        } else if (kind == K_DEBRIS) {
            drag = 2.2f;
        } else {
            drag = 3.4f;
        }
        float f = 1f - drag * dt;
        if (f < 0) {
            f = 0;
        }
        vx *= f;
        vy *= f;
    }
}
