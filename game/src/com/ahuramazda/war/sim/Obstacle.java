package com.ahuramazda.war.sim;

/** Static cover: concrete block, sandbag wall or an explosive barrel. */
public class Obstacle {

    public static final int O_CONCRETE = 0;
    public static final int O_SANDBAG = 1;
    public static final int O_BARREL = 2;

    public float x, y, w, h;
    public float hp, maxHp;
    public int kind;
    public boolean blocksMove;
    public boolean blocksBullets;
    public boolean dead;
    public float shake;

    public void init(int kind, float x, float y, float w, float h) {
        this.kind = kind;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.dead = false;
        this.shake = 0;
        if (kind == O_CONCRETE) {
            hp = maxHp = 320;
            blocksMove = true;
            blocksBullets = true;
        } else if (kind == O_SANDBAG) {
            hp = maxHp = 110;
            blocksMove = false;
            blocksBullets = true;
        } else {
            hp = maxHp = 30;
            blocksMove = true;
            blocksBullets = true;
        }
    }

    public boolean contains(float px, float py) {
        return px > x - w * 0.5f && px < x + w * 0.5f && py > y - h * 0.5f && py < y + h * 0.5f;
    }

    /** Nearest point on the box to (px,py) - used for circle/box resolution. */
    public float nx(float px) {
        return Maths.clamp(px, x - w * 0.5f, x + w * 0.5f);
    }

    public float ny(float py) {
        return Maths.clamp(py, y - h * 0.5f, y + h * 0.5f);
    }
}
