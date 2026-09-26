package com.ahuramazda.war.sim;

/** The player's tank. */
public class Player {

    public static final int MAX_HP = 100;
    public static final float SPECIAL_CD = 15f;

    public float x, y, vx, vy;
    public float r = 27f;
    public float hull;
    public float aim;
    public int hp = MAX_HP;
    public float fireCd;
    public float specialCd;
    public float shield;
    public float rapidT;
    public float spreadT;
    public float speedT;
    public float invuln;
    public float muzzle;
    public float recoil;
    public boolean alive = true;

    public void reset(float x, float y) {
        this.x = x;
        this.y = y;
        vx = 0;
        vy = 0;
        hull = 0;
        aim = 0;
        hp = MAX_HP;
        fireCd = 0;
        specialCd = 0;
        shield = 0;
        rapidT = 0;
        spreadT = 0;
        speedT = 0;
        invuln = 1.2f;
        muzzle = 0;
        recoil = 0;
        alive = true;
    }

    public void update(World w, Input in, float dt) {
        if (!alive) {
            return;
        }
        float speed = 305f * (speedT > 0 ? 1.32f : 1f);
        float tvx = in.mx * speed;
        float tvy = in.my * speed;
        float f = 1f - (float) Math.exp(-13f * dt);
        vx += (tvx - vx) * f;
        vy += (tvy - vy) * f;
        x += vx * dt;
        y += vy * dt;

        // aiming: follow movement, but snap onto the closest target while firing
        float wantAim = aim;
        if (in.mx != 0 || in.my != 0) {
            wantAim = (float) Math.atan2(in.my, in.mx);
        }
        if (in.fire) {
            Enemy t = w.nearestEnemy(x, y, 760f);
            if (t != null) {
                float lead = Maths.dist(x, y, t.x, t.y) / 1000f;
                wantAim = (float) Math.atan2(t.y + t.vy * lead - y, t.x + t.vx * lead - x);
            }
        }
        aim = Maths.angTowards(aim, wantAim, (in.fire ? 13f : 9f) * dt);
        if (in.mx != 0 || in.my != 0) {
            hull = Maths.angTowards(hull, (float) Math.atan2(in.my, in.mx), 8f * dt);
        } else {
            hull = Maths.angTowards(hull, aim, 4f * dt);
        }

        // collisions with cover
        for (int i = 0; i < w.obstacles.size(); i++) {
            Obstacle o = w.obstacles.get(i);
            if (!o.blocksMove) {
                continue;
            }
            float px = o.nx(x);
            float py = o.ny(y);
            float dx = x - px;
            float dy = y - py;
            float dd = Maths.len(dx, dy);
            if (dd < r) {
                if (dd < 0.001f) {
                    x += r;
                } else {
                    x = px + dx / dd * r;
                    y = py + dy / dd * r;
                    float dot = vx * (dx / dd) + vy * (dy / dd);
                    if (dot < 0) {
                        vx -= dx / dd * dot;
                        vy -= dy / dd * dot;
                    }
                }
            }
        }
        x = Maths.clamp(x, r, w.W - r);
        y = Maths.clamp(y, r, w.H - r);

        // weapon timers
        if (fireCd > 0) {
            fireCd -= dt;
        }
        if (specialCd > 0) {
            specialCd -= dt;
        }
        if (invuln > 0) {
            invuln -= dt;
        }
        if (muzzle > 0) {
            muzzle -= dt * 6f;
        }
        if (recoil > 0) {
            recoil -= dt * 5f;
        }
        if (rapidT > 0) {
            rapidT -= dt;
        }
        if (spreadT > 0) {
            spreadT -= dt;
        }
        if (speedT > 0) {
            speedT -= dt;
        }
        if (shield > 0) {
            shield -= dt;
        }

        if (in.fire && fireCd <= 0) {
            fire(w);
        }
        if (in.special && specialCd <= 0) {
            w.startAirstrike();
            specialCd = SPECIAL_CD;
        }

        // engine smoke
        float sp = Maths.len(vx, vy);
        if (sp > 40f && w.rnd.nextFloat() < dt * 22f) {
            float a = hull + (float) Math.PI;
            w.particle(Particle.K_SMOKE, x + (float) Math.cos(a) * 22f, y + (float) Math.sin(a) * 22f,
                    vx * 0.12f, vy * 0.12f, 0.7f, 12f, 0x33403a);
        }
    }

    private void fire(World w) {
        float interval = 0.155f * (rapidT > 0 ? 0.52f : 1f);
        fireCd = interval;
        recoil = 1f;
        muzzle = 1f;
        float bx = x + (float) Math.cos(aim) * 38f;
        float by = y + (float) Math.sin(aim) * 38f;
        if (spreadT > 0) {
            for (int i = -1; i <= 1; i++) {
                float a = aim + i * 0.15f + (w.rnd.nextFloat() - 0.5f) * 0.03f;
                w.spawnBullet(bx, by, (float) Math.cos(a) * 1020f, (float) Math.sin(a) * 1020f,
                        11, Bullet.B_MG, false, 1.15f, 6f, 0f);
            }
        } else {
            float a = aim + (w.rnd.nextFloat() - 0.5f) * 0.035f;
            w.spawnBullet(bx, by, (float) Math.cos(a) * 1050f, (float) Math.sin(a) * 1050f,
                    15, Bullet.B_MG, false, 1.15f, 7f, 0f);
        }
        for (int i = 0; i < 4; i++) {
            float a = aim + (w.rnd.nextFloat() - 0.5f) * 0.8f;
            float s = 120f + w.rnd.nextFloat() * 260f;
            w.particle(Particle.K_SPARK, bx, by, (float) Math.cos(a) * s, (float) Math.sin(a) * s,
                    0.16f, 4f, 0xffe08a);
        }
        vx -= (float) Math.cos(aim) * 22f;
        vy -= (float) Math.sin(aim) * 22f;
        w.events.add(new Event(Event.EV_SHOOT, x, y, 0.55f));
    }

    public void hurt(World w, int dmg) {
        if (!alive || invuln > 0) {
            return;
        }
        if (shield > 0) {
            shield = 0;
            invuln = 0.5f;
            w.events.add(new Event(Event.EV_HIT, x, y, 0.7f));
            return;
        }
        hp -= dmg;
        invuln = 0.75f;
        w.shake = Math.min(1.2f, w.shake + 0.28f + dmg * 0.006f);
        w.events.add(new Event(Event.EV_PLAYER_HIT, x, y, 1f));
        for (int i = 0; i < 10; i++) {
            float a = w.rnd.nextFloat() * 6.283f;
            float s = 90f + w.rnd.nextFloat() * 220f;
            w.particle(Particle.K_SPARK, x, y, (float) Math.cos(a) * s, (float) Math.sin(a) * s,
                    0.3f, 5f, 0xffb347);
        }
        if (hp <= 0) {
            hp = 0;
            alive = false;
            w.explode(x, y, 220f, 0, false);
            w.gameOver();
        }
    }
}
