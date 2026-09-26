package com.ahuramazda.war.sim;

/** An enemy unit. Behaviour depends on {@link #type}. */
public class Enemy {

    public static final int T_SOLDIER = 0;
    public static final int T_SCOUT = 1;
    public static final int T_TANK = 2;
    public static final int T_GUNNER = 3;
    public static final int T_HELI = 4;
    public static final int T_BOSS = 5;

    public int type;
    public float x, y, vx, vy;
    public float r;
    public float hp, maxHp;
    public float speed;
    public float aim;
    public float shootCd;
    public int burst;
    public float aimT;
    public float hitFlash;
    public float spawnT;
    public float stateT;
    public int phase;
    public int orbitDir;
    public float bob;
    public boolean dead;
    public int scoreValue;

    public void init(int type, float x, float y, float scale) {
        this.type = type;
        this.x = x;
        this.y = y;
        this.vx = 0;
        this.vy = 0;
        this.aim = 0;
        this.shootCd = 0.6f;
        this.burst = 0;
        this.aimT = 0;
        this.hitFlash = 0;
        this.spawnT = 0;
        this.stateT = 0;
        this.phase = 0;
        this.bob = 0;
        this.dead = false;
        this.orbitDir = 1;
        switch (type) {
            case T_SOLDIER:
                r = 17; hp = maxHp = 24 * scale; speed = 108; scoreValue = 100; break;
            case T_SCOUT:
                r = 20; hp = maxHp = 34 * scale; speed = 240; scoreValue = 150; break;
            case T_TANK:
                r = 31; hp = maxHp = 115 * scale; speed = 64; scoreValue = 300; break;
            case T_GUNNER:
                r = 25; hp = maxHp = 80 * scale; speed = 0; scoreValue = 250; break;
            case T_HELI:
                r = 28; hp = maxHp = 70 * scale; speed = 155; scoreValue = 400; break;
            default:
                r = 74; hp = maxHp = 750 * scale; speed = 58; scoreValue = 3000; break;
        }
        this.shootCd = 0.5f + (type == T_BOSS ? 1.6f : 0.8f);
    }

    public void update(World w, float dt) {
        spawnT += dt;
        bob += dt;
        if (hitFlash > 0) {
            hitFlash -= dt * 4f;
        }
        Player p = w.player;
        float dx = p.x - x;
        float dy = p.y - y;
        float d = Maths.len(dx, dy);
        if (d < 0.001f) {
            d = 0.001f;
        }
        float ang = (float) Math.atan2(dy, dx);
        float turn = type == T_BOSS ? 1.7f : 5.5f;
        aim = Maths.angTowards(aim, ang, turn * dt);

        float tvx = 0;
        float tvy = 0;
        boolean ready = spawnT > 0.45f;

        switch (type) {
            case T_SOLDIER: {
                float want = 300f;
                float move;
                if (d > want + 60) {
                    move = 1f;
                } else if (d < want - 70) {
                    move = -0.9f;
                } else {
                    move = 0f;
                }
                float strafe = d < want + 140 ? 0.9f : 0.25f;
                tvx = (float) (Math.cos(ang) * move + Math.cos(ang + Math.PI / 2) * strafe * orbitDir);
                tvy = (float) (Math.sin(ang) * move + Math.sin(ang + Math.PI / 2) * strafe * orbitDir);
                shootCd -= dt;
                if (ready && d < 560 && shootCd <= 0) {
                    w.enemyShot(this, ang + (w.rnd.nextFloat() - 0.5f) * 0.14f, 7, 640, Bullet.B_ENEMY, 1.6f);
                    shootCd = 1.15f + w.rnd.nextFloat() * 0.75f;
                }
                break;
            }
            case T_SCOUT: {
                stateT -= dt;
                float dash = stateT > 0 ? 2.1f : 0.75f;
                tvx = (float) Math.cos(ang) * dash;
                tvy = (float) Math.sin(ang) * dash;
                if (stateT < -2.2f) {
                    stateT = 0.55f;
                }
                if (ready && d < 90 && shootCd <= 0) {
                    w.enemyShot(this, ang, 6, 700, Bullet.B_ENEMY, 1.2f);
                    shootCd = 1.4f;
                }
                shootCd -= dt;
                break;
            }
            case T_TANK: {
                float want = 470f;
                float move;
                if (d > want + 90) {
                    move = 1f;
                } else if (d < want - 110) {
                    move = -0.8f;
                } else {
                    move = 0f;
                }
                tvx = (float) Math.cos(ang) * move;
                tvy = (float) Math.sin(ang) * move;
                if (ready && d < 760) {
                    if (aimT > 0) {
                        aimT -= dt;
                        if (aimT <= 0) {
                            float lead = d / 520f;
                            float la = (float) Math.atan2(p.y + p.vy * lead - y, p.x + p.vx * lead - x);
                            w.enemyShot(this, la, 20, 520, Bullet.B_SHELL, 2.6f);
                            shootCd = 1.9f + w.rnd.nextFloat() * 0.7f;
                        }
                    } else {
                        shootCd -= dt;
                        if (shootCd <= 0) {
                            aimT = 0.62f;
                        }
                    }
                }
                break;
            }
            case T_GUNNER: {
                shootCd -= dt;
                if (ready && d < 700) {
                    if (burst > 0) {
                        if (shootCd <= 0) {
                            w.enemyShot(this, ang + (w.rnd.nextFloat() - 0.5f) * 0.16f, 6, 720, Bullet.B_ENEMY, 1.5f);
                            burst--;
                            shootCd = 0.13f;
                        }
                    } else if (shootCd <= 0) {
                        burst = 3;
                        shootCd = 0f;
                    }
                    if (burst == 0 && shootCd <= 0) {
                        shootCd = 2.3f + w.rnd.nextFloat() * 0.9f;
                    }
                }
                break;
            }
            case T_HELI: {
                float want = 330f;
                float radial = d > want + 70 ? 1f : (d < want - 70 ? -1f : 0f);
                float tang = 1f;
                tvx = (float) (Math.cos(ang) * radial + Math.cos(ang + Math.PI / 2) * tang * orbitDir);
                tvy = (float) (Math.sin(ang) * radial + Math.sin(ang + Math.PI / 2) * tang * orbitDir);
                shootCd -= dt;
                if (ready && shootCd <= 0 && d < 620) {
                    float lead = d / 300f;
                    float tx = p.x + p.vx * lead;
                    float ty = p.y + p.vy * lead;
                    float bx = x;
                    float by = y;
                    float dist = Maths.dist(bx, by, tx, ty);
                    w.spawnBullet(bx, by, (tx - bx) / dist * 300f, (ty - by) / dist * 300f,
                            18, Bullet.B_BOMB, true, 1.35f, 8f, 130f);
                    shootCd = 1.7f + w.rnd.nextFloat() * 0.6f;
                }
                break;
            }
            default: {
                // BOSS: cycles volley -> summon -> charge
                stateT -= dt;
                if (phase == 0) {
                    float want = 430f;
                    float move = d > want + 80 ? 1f : (d < want - 120 ? -0.7f : 0f);
                    tvx = (float) Math.cos(ang) * move;
                    tvy = (float) Math.sin(ang) * move;
                    if (ready && shootCd <= 0) {
                        for (int i = -4; i <= 4; i++) {
                            w.enemyShot(this, ang + i * 0.13f, 16, 480, Bullet.B_SHELL, 3f);
                        }
                        burst++;
                        shootCd = 1.5f;
                        if (burst >= 3) {
                            burst = 0;
                            phase = 1;
                            stateT = 0.7f;
                        }
                    }
                    shootCd -= dt;
                } else if (phase == 1) {
                    tvx = 0;
                    tvy = 0;
                    if (stateT <= 0) {
                        for (int i = 0; i < 3; i++) {
                            float a = w.rnd.nextFloat() * 6.283f;
                            w.spawnEnemyAt(T_SOLDIER, x + (float) Math.cos(a) * 110f,
                                    y + (float) Math.sin(a) * 110f, true);
                        }
                        phase = 2;
                        stateT = 0.5f;
                    }
                } else {
                    if (stateT > 0) {
                        tvx = 0;
                        tvy = 0;
                    } else {
                        tvx = (float) Math.cos(ang) * 2.4f;
                        tvy = (float) Math.sin(ang) * 2.4f;
                        if (d < 140) {
                            phase = 0;
                            shootCd = 1.2f;
                        }
                        if (stateT < -2.6f) {
                            phase = 0;
                            shootCd = 1.0f;
                        }
                    }
                }
                break;
            }
        }

        float sp = speed * (type == T_BOSS && phase == 2 ? 1f : 1f);
        move(w, tvx * sp, tvy * sp, dt);
    }

    private void move(World w, float tvx, float tvy, float dt) {
        float f = 1f - (float) Math.exp(-9f * dt);
        vx += (tvx - vx) * f;
        vy += (tvy - vy) * f;
        x += vx * dt;
        y += vy * dt;

        if (type != T_HELI) {
            for (int i = 0; i < w.obstacles.size(); i++) {
                Obstacle o = w.obstacles.get(i);
                if (!o.blocksMove) {
                    continue;
                }
                resolveBox(o);
            }
        }
        for (int i = 0; i < w.enemies.size(); i++) {
            Enemy e = w.enemies.get(i);
            if (e == this || e.type == T_HELI) {
                continue;
            }
            float dx = x - e.x;
            float dy = y - e.y;
            float dd = Maths.len(dx, dy);
            float min = r + e.r;
            if (dd > 0.001f && dd < min) {
                float push = (min - dd) * 0.5f;
                x += dx / dd * push;
                y += dy / dd * push;
            }
        }
        x = Maths.clamp(x, r, w.W - r);
        y = Maths.clamp(y, r, w.H - r);
    }

    private void resolveBox(Obstacle o) {
        float px = o.nx(x);
        float py = o.ny(y);
        float dx = x - px;
        float dy = y - py;
        float dd = Maths.len(dx, dy);
        if (dd >= r) {
            return;
        }
        if (dd < 0.001f) {
            // centre inside the box: push out along the shallow axis
            float left = x - (o.x - o.w * 0.5f);
            float right = (o.x + o.w * 0.5f) - x;
            float top = y - (o.y - o.h * 0.5f);
            float bottom = (o.y + o.h * 0.5f) - y;
            float m = Math.min(Math.min(left, right), Math.min(top, bottom));
            if (m == left) {
                x = o.x - o.w * 0.5f - r;
            } else if (m == right) {
                x = o.x + o.w * 0.5f + r;
            } else if (m == top) {
                y = o.y - o.h * 0.5f - r;
            } else {
                y = o.y + o.h * 0.5f + r;
            }
            return;
        }
        x = px + dx / dd * r;
        y = py + dy / dd * r;
    }
}
