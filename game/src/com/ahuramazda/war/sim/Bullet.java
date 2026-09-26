package com.ahuramazda.war.sim;

/** A projectile. Owned by the player or by an enemy. */
public class Bullet {

    public static final int B_MG = 0;
    public static final int B_CANNON = 1;
    public static final int B_ENEMY = 2;
    public static final int B_SHELL = 3;
    public static final int B_BOMB = 4;

    public float x, y, vx, vy;
    public float r;
    public int dmg;
    public int kind;
    public boolean enemy;
    public float life;
    public float maxLife;
    public boolean dead;
    /** blast radius; &gt; 0 means the bullet detonates. */
    public float blast;

    public void init(float x, float y, float vx, float vy, int dmg, int kind, boolean enemy,
                     float life, float r, float blast) {
        this.x = x;
        this.y = y;
        this.vx = vx;
        this.vy = vy;
        this.dmg = dmg;
        this.kind = kind;
        this.enemy = enemy;
        this.life = life;
        this.maxLife = life;
        this.r = r;
        this.blast = blast;
        this.dead = false;
    }

    public void update(float dt) {
        x += vx * dt;
        y += vy * dt;
        life -= dt;
        if (life <= 0) {
            dead = true;
        }
    }
}
