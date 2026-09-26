package com.ahuramazda.war.sim;

import java.util.ArrayList;
import java.util.Random;

/**
 * The whole battle simulation. Deliberately free of any android.* import so it
 * can be unit tested on a plain JVM.
 */
public class World {

    public static final int ST_PLAYING = 0;
    public static final int ST_WAVE_CLEAR = 1;
    public static final int ST_GAME_OVER = 2;

    public int W = 1920;
    public int H = 1080;

    public Random rnd = new Random();
    public Player player = new Player();

    public final ArrayList<Enemy> enemies = new ArrayList<Enemy>();
    public final ArrayList<Bullet> bullets = new ArrayList<Bullet>();
    public final ArrayList<Particle> particles = new ArrayList<Particle>();
    public final ArrayList<Blast> blasts = new ArrayList<Blast>();
    public final ArrayList<PowerUp> pickups = new ArrayList<PowerUp>();
    public final ArrayList<Obstacle> obstacles = new ArrayList<Obstacle>();
    public final ArrayList<Event> events = new ArrayList<Event>();

    public int score;
    public int wave;
    public int kills;
    public int bestScore;
    public int combo;
    public float comboTimer;
    public float shake;
    public int state = ST_PLAYING;
    public float stateTimer;
    public float time;
    public float spawnTimer;
    public int airQueue;
    public float airTimer;
    public int waveHpBonus;

    private final ArrayList<Integer> pending = new ArrayList<Integer>();
    private int blastDepth;

    public World() {
        reset();
    }

    public void reset() {
        enemies.clear();
        bullets.clear();
        particles.clear();
        blasts.clear();
        pickups.clear();
        obstacles.clear();
        events.clear();
        pending.clear();
        score = 0;
        wave = 0;
        kills = 0;
        combo = 0;
        comboTimer = 0;
        shake = 0;
        time = 0;
        airQueue = 0;
        airTimer = 0;
        player.reset(W * 0.5f, H * 0.5f);
        startWave(1);
    }

    // ---------------------------------------------------------------- waves

    public void startWave(int n) {
        wave = n;
        state = ST_PLAYING;
        stateTimer = 0;
        spawnTimer = 0.55f;
        pending.clear();

        boolean boss = n % 5 == 0;
        int count = 5 + (int) (n * 1.8f);
        if (count > 24) {
            count = 24;
        }
        if (boss) {
            pending.add(Integer.valueOf(Enemy.T_BOSS));
            count = (int) (count * 0.6f);
        }
        for (int i = 0; i < count; i++) {
            pending.add(Integer.valueOf(rollType(n)));
        }
        generateObstacles(n);
        events.add(new Event(Event.EV_WAVE_START, player.x, player.y, 1f));
    }

    private int rollType(int n) {
        float r = rnd.nextFloat();
        if (n <= 1) {
            return r < 0.78f ? Enemy.T_SOLDIER : Enemy.T_SCOUT;
        }
        if (n <= 2) {
            return r < 0.5f ? Enemy.T_SOLDIER : (r < 0.86f ? Enemy.T_SCOUT : Enemy.T_GUNNER);
        }
        if (n <= 3) {
            return r < 0.4f ? Enemy.T_SOLDIER
                    : (r < 0.66f ? Enemy.T_SCOUT : (r < 0.88f ? Enemy.T_GUNNER : Enemy.T_TANK));
        }
        return r < 0.3f ? Enemy.T_SOLDIER
                : (r < 0.54f ? Enemy.T_SCOUT
                : (r < 0.72f ? Enemy.T_GUNNER : (r < 0.9f ? Enemy.T_TANK : Enemy.T_HELI)));
    }

    private void generateObstacles(int n) {
        obstacles.clear();
        int want = 4 + (int) (rnd.nextFloat() * 3f) + n / 2;
        if (want > 13) {
            want = 13;
        }
        for (int i = 0; i < want; i++) {
            float w;
            float h;
            int kind;
            float r = rnd.nextFloat();
            if (r < 0.42f) {
                kind = Obstacle.O_CONCRETE;
                w = 60f + rnd.nextFloat() * 130f;
                h = 60f + rnd.nextFloat() * 70f;
            } else if (r < 0.72f) {
                kind = Obstacle.O_SANDBAG;
                w = 110f + rnd.nextFloat() * 130f;
                h = 34f;
            } else {
                kind = Obstacle.O_BARREL;
                w = 46f;
                h = 46f;
            }
            for (int tries = 0; tries < 30; tries++) {
                float x = 120f + rnd.nextFloat() * (W - 240f);
                float y = 130f + rnd.nextFloat() * (H - 260f);
                if (Maths.dist(x, y, player.x, player.y) < 300f) {
                    continue;
                }
                boolean bad = false;
                for (int j = 0; j < obstacles.size(); j++) {
                    Obstacle o = obstacles.get(j);
                    if (Math.abs(o.x - x) < (o.w + w) * 0.6f && Math.abs(o.y - y) < (o.h + h) * 0.6f) {
                        bad = true;
                        break;
                    }
                }
                if (bad) {
                    continue;
                }
                Obstacle o = new Obstacle();
                o.init(kind, x, y, w, h);
                obstacles.add(o);
                break;
            }
        }
    }

    private void spawnOne() {
        int type = pending.remove(0).intValue();
        float x = 0;
        float y = 0;
        for (int tries = 0; tries < 24; tries++) {
            int edge = rnd.nextInt(4);
            float m = 90f;
            if (edge == 0) {
                x = m + rnd.nextFloat() * (W - 2 * m);
                y = m;
            } else if (edge == 1) {
                x = W - m;
                y = m + rnd.nextFloat() * (H - 2 * m);
            } else if (edge == 2) {
                x = m + rnd.nextFloat() * (W - 2 * m);
                y = H - m;
            } else {
                x = m;
                y = m + rnd.nextFloat() * (H - 2 * m);
            }
            if (Maths.dist(x, y, player.x, player.y) < 430f) {
                continue;
            }
            boolean inside = false;
            for (int j = 0; j < obstacles.size(); j++) {
                Obstacle o = obstacles.get(j);
                if (o.blocksMove && o.contains(x, y)) {
                    inside = true;
                    break;
                }
            }
            if (!inside) {
                break;
            }
        }
        spawnEnemyAt(type, x, y, false);
    }

    public void spawnEnemyAt(int type, float x, float y, boolean silent) {
        float scale = 1f + 0.14f * (wave - 1);
        Enemy e = new Enemy();
        e.init(type, x, y, type == Enemy.T_BOSS ? 1f + 0.22f * (wave - 1) : scale);
        e.orbitDir = rnd.nextFloat() < 0.5f ? -1 : 1;
        e.aim = (float) Math.atan2(player.y - y, player.x - x);
        enemies.add(e);
        for (int i = 0; i < 8; i++) {
            float a = rnd.nextFloat() * 6.283f;
            particle(Particle.K_SMOKE, x + (float) Math.cos(a) * 24f, y + (float) Math.sin(a) * 24f,
                    (float) Math.cos(a) * 40f, (float) Math.sin(a) * 40f, 0.5f, 16f, 0x6b5a44);
        }
        if (!silent) {
            events.add(new Event(Event.EV_SPAWN, x, y, 0.5f));
        }
    }

    // --------------------------------------------------------------- update

    public void update(float dt, Input in) {
        time += dt;
        if (shake > 0) {
            shake -= dt * 2.4f;
            if (shake < 0) {
                shake = 0;
            }
        }
        if (comboTimer > 0) {
            comboTimer -= dt;
            if (comboTimer <= 0) {
                combo = 0;
            }
        }
        if (state == ST_PLAYING) {
            player.update(this, in, dt);

            spawnTimer -= dt;
            if (!pending.isEmpty() && spawnTimer <= 0) {
                spawnOne();
                spawnTimer = 0.32f + rnd.nextFloat() * 0.5f;
            }

            for (int i = enemies.size() - 1; i >= 0; i--) {
                Enemy e = enemies.get(i);
                e.update(this, dt);
                if (e.dead) {
                    enemies.remove(i);
                }
            }
            updateBullets(dt);
            updatePickups(dt);
            updateAirstrike(dt);

            for (int i = obstacles.size() - 1; i >= 0; i--) {
                Obstacle o = obstacles.get(i);
                if (o.shake > 0) {
                    o.shake -= dt;
                }
                if (o.dead) {
                    obstacles.remove(i);
                }
            }

            if (pending.isEmpty() && enemies.isEmpty() && player.alive) {
                state = ST_WAVE_CLEAR;
                stateTimer = 2.7f;
                player.hp = Math.min(Player.MAX_HP, player.hp + 14);
                events.add(new Event(Event.EV_WAVE_CLEAR, player.x, player.y, 1f));
            }
        } else if (state == ST_WAVE_CLEAR) {
            stateTimer -= dt;
            player.update(this, in, dt);
            updateBullets(dt);
            updatePickups(dt);
            if (stateTimer <= 0) {
                startWave(wave + 1);
            }
        }

        updateParticles(dt);
        updateBlasts(dt);
    }

    /** Used on menu screens: keep smoke drifting, but freeze the battle. */
    public void updateParticlesOnly(float dt) {
        time += dt;
        updateParticles(dt);
        updateBlasts(dt);
    }

    private void updateParticles(float dt) {
        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle p = particles.get(i);
            p.update(dt);
            if (p.dead) {
                particles.remove(i);
            } else if (particles.size() > 700) {
                particles.remove(i);
            }
        }
    }

    private void updateBlasts(float dt) {
        for (int i = blasts.size() - 1; i >= 0; i--) {
            Blast b = blasts.get(i);
            b.update(dt);
            if (b.dead) {
                blasts.remove(i);
            }
        }
    }

    private void updateBullets(float dt) {
        for (int i = bullets.size() - 1; i >= 0; i--) {
            Bullet b = bullets.get(i);
            b.update(dt);
            if (!b.dead) {
                if (b.x < -60f || b.x > W + 60f || b.y < -60f || b.y > H + 60f) {
                    b.dead = true;
                }
            }
            if (!b.dead) {
                for (int j = 0; j < obstacles.size(); j++) {
                    Obstacle o = obstacles.get(j);
                    if (o.blocksBullets && o.contains(b.x, b.y)) {
                        b.dead = true;
                        hitSparks(b.x, b.y, 5, 0xffd8a0);
                        damageObstacle(o, b.dmg);
                        break;
                    }
                }
            }
            if (!b.dead && b.enemy && player.alive) {
                if (Maths.dist(b.x, b.y, player.x, player.y) < player.r + b.r) {
                    b.dead = true;
                    if (b.blast > 0) {
                        explode(b.x, b.y, b.blast, b.dmg, false);
                    } else {
                        player.hurt(this, b.dmg);
                        hitSparks(b.x, b.y, 4, 0xff9a5c);
                    }
                }
            }
            if (!b.dead && !b.enemy) {
                for (int j = 0; j < enemies.size(); j++) {
                    Enemy e = enemies.get(j);
                    if (Maths.dist(b.x, b.y, e.x, e.y) < e.r + b.r) {
                        b.dead = true;
                        if (b.blast > 0) {
                            explode(b.x, b.y, b.blast, b.dmg, true);
                        } else {
                            damageEnemy(e, b.dmg, b.x, b.y);
                            hitSparks(b.x, b.y, 5, 0xffd070);
                        }
                        break;
                    }
                }
            }
            if (b.dead) {
                if (b.blast > 0) {
                    explode(b.x, b.y, b.blast, b.dmg, !b.enemy);
                }
                bullets.remove(i);
            }
        }
    }

    private void updatePickups(float dt) {
        for (int i = pickups.size() - 1; i >= 0; i--) {
            PowerUp p = pickups.get(i);
            p.update(dt);
            if (player.alive) {
                float d = Maths.dist(p.x, p.y, player.x, player.y);
                if (d < 190f) {
                    float f = (190f - d) / 190f * 220f * dt;
                    p.x += (player.x - p.x) / d * f;
                    p.y += (player.y - p.y) / d * f;
                }
                if (d < player.r + 26f) {
                    applyPickup(p);
                    p.dead = true;
                }
            }
            if (p.dead) {
                pickups.remove(i);
            }
        }
    }

    private void applyPickup(PowerUp p) {
        events.add(new Event(Event.EV_PICKUP, p.x, p.y, 1f));
        for (int i = 0; i < 14; i++) {
            float a = rnd.nextFloat() * 6.283f;
            float s = 60f + rnd.nextFloat() * 200f;
            particle(Particle.K_SPARK, p.x, p.y, (float) Math.cos(a) * s, (float) Math.sin(a) * s,
                    0.35f, 5f, 0x9dffb0);
        }
        switch (p.kind) {
            case PowerUp.P_HEAL:
                player.hp = Math.min(Player.MAX_HP, player.hp + 32);
                break;
            case PowerUp.P_SHIELD:
                player.shield = 7f;
                break;
            case PowerUp.P_RAPID:
                player.rapidT = 9f;
                break;
            case PowerUp.P_SPREAD:
                player.spreadT = 11f;
                break;
            default:
                nuke();
                break;
        }
    }

    public void nuke() {
        shake = 1.3f;
        for (int i = enemies.size() - 1; i >= 0; i--) {
            Enemy e = enemies.get(i);
            damageEnemy(e, 9999, e.x, e.y);
        }
        for (int i = 0; i < 6; i++) {
            float x = rnd.nextFloat() * W;
            float y = rnd.nextFloat() * H;
            explode(x, y, 200f, 0, true);
        }
    }

    private void updateAirstrike(float dt) {
        if (airQueue <= 0) {
            return;
        }
        airTimer -= dt;
        if (airTimer > 0) {
            return;
        }
        airTimer = 0.11f;
        airQueue--;
        float x;
        float y;
        Enemy e = enemies.isEmpty() ? null : enemies.get(rnd.nextInt(enemies.size()));
        if (e != null && rnd.nextFloat() < 0.75f) {
            x = e.x + (rnd.nextFloat() - 0.5f) * 120f;
            y = e.y + (rnd.nextFloat() - 0.5f) * 120f;
        } else {
            x = 120f + rnd.nextFloat() * (W - 240f);
            y = 120f + rnd.nextFloat() * (H - 240f);
        }
        explode(x, y, 150f, 55, true);
    }

    public void startAirstrike() {
        airQueue = 14;
        airTimer = 0.35f;
        events.add(new Event(Event.EV_AIRSTRIKE, player.x, player.y, 1f));
    }

    // ------------------------------------------------------------- combat

    public void spawnBullet(float x, float y, float vx, float vy, int dmg, int kind, boolean enemy,
                            float life, float r, float blast) {
        Bullet b = new Bullet();
        b.init(x, y, vx, vy, dmg, kind, enemy, life, r, blast);
        bullets.add(b);
    }

    public void enemyShot(Enemy e, float ang, int dmg, float speed, int kind, float life) {
        float bx = e.x + (float) Math.cos(ang) * (e.r + 6f);
        float by = e.y + (float) Math.sin(ang) * (e.r + 6f);
        spawnBullet(bx, by, (float) Math.cos(ang) * speed, (float) Math.sin(ang) * speed,
                dmg, kind, true, life, kind == Bullet.B_SHELL ? 9f : 6f, 0f);
        for (int i = 0; i < 3; i++) {
            float a = ang + (rnd.nextFloat() - 0.5f) * 0.7f;
            particle(Particle.K_SPARK, bx, by, (float) Math.cos(a) * 150f, (float) Math.sin(a) * 150f,
                    0.12f, 3.5f, 0xffb066);
        }
        events.add(new Event(Event.EV_ENEMY_SHOOT, e.x, e.y,
                Maths.clamp(1f - Maths.dist(e.x, e.y, player.x, player.y) / 1400f, 0.15f, 1f)));
    }

    public void damageEnemy(Enemy e, int dmg, float hx, float hy) {
        if (e.dead) {
            return;
        }
        e.hp -= dmg;
        e.hitFlash = 1f;
        hitSparks(hx, hy, 4, 0xffe0a0);
        if (e.hp <= 0) {
            killEnemy(e);
        } else {
            events.add(new Event(Event.EV_HIT, hx, hy, 0.4f));
        }
    }

    public void killEnemy(Enemy e) {
        if (e.dead) {
            return;
        }
        e.dead = true;
        kills++;
        combo = Math.min(8, combo + 1);
        comboTimer = 3.2f;
        score += e.scoreValue * combo;
        float rad = e.type == Enemy.T_BOSS ? 300f : (e.type == Enemy.T_TANK ? 165f : 115f);
        explode(e.x, e.y, rad, 0, true);
        shake = Math.min(1.4f, shake + (e.type == Enemy.T_BOSS ? 0.9f : 0.12f));

        if (e.type == Enemy.T_BOSS) {
            dropAt(e.x - 90f, e.y, PowerUp.P_HEAL);
            dropAt(e.x + 90f, e.y, PowerUp.P_SPREAD);
            dropAt(e.x, e.y + 90f, PowerUp.P_SHIELD);
            return;
        }
        float chance = player.hp < 50 ? 0.3f : 0.17f;
        if (rnd.nextFloat() < chance) {
            float r = rnd.nextFloat();
            int kind;
            if (r < 0.3f) {
                kind = PowerUp.P_HEAL;
            } else if (r < 0.5f) {
                kind = PowerUp.P_SHIELD;
            } else if (r < 0.7f) {
                kind = PowerUp.P_RAPID;
            } else if (r < 0.92f) {
                kind = PowerUp.P_SPREAD;
            } else {
                kind = PowerUp.P_NUKE;
            }
            dropAt(e.x, e.y, kind);
        }
    }

    private void dropAt(float x, float y, int kind) {
        PowerUp p = new PowerUp();
        p.init(Maths.clamp(x, 40f, W - 40f), Maths.clamp(y, 40f, H - 40f), kind);
        pickups.add(p);
    }

    public void damageObstacle(Obstacle o, int dmg) {
        if (o.dead) {
            return;
        }
        o.hp -= dmg;
        o.shake = 0.22f;
        if (o.hp <= 0) {
            o.dead = true;
            if (o.kind == Obstacle.O_BARREL) {
                explode(o.x, o.y, 190f, 70, false);
            } else {
                for (int i = 0; i < 12; i++) {
                    float a = rnd.nextFloat() * 6.283f;
                    float s = 80f + rnd.nextFloat() * 200f;
                    particle(Particle.K_DEBRIS, o.x, o.y, (float) Math.cos(a) * s,
                            (float) Math.sin(a) * s, 0.8f, 8f, 0x8b7c63);
                }
                explode(o.x, o.y, 90f, 0, true);
            }
        }
    }

    /** Big bada-boom. dmg 0 means "visual only". */
    public void explode(float x, float y, float radius, int dmg, boolean fromPlayer) {
        Blast b = new Blast();
        b.init(x, y, radius, 0.42f, 0xffa33c);
        blasts.add(b);

        int n = 10 + (int) (radius * 0.12f);
        for (int i = 0; i < n; i++) {
            float a = rnd.nextFloat() * 6.283f;
            float s = 60f + rnd.nextFloat() * radius * 1.6f;
            particle(Particle.K_SPARK, x, y, (float) Math.cos(a) * s, (float) Math.sin(a) * s,
                    0.35f + rnd.nextFloat() * 0.3f, 5f + rnd.nextFloat() * 5f,
                    rnd.nextFloat() < 0.5f ? 0xffd27a : 0xff7a2a);
            particle(Particle.K_SMOKE, x, y, (float) Math.cos(a) * s * 0.35f,
                    (float) Math.sin(a) * s * 0.35f, 0.7f + rnd.nextFloat() * 0.6f,
                    14f + rnd.nextFloat() * 16f, 0x4a4238);
        }
        float v = Maths.clamp(1f - Maths.dist(x, y, player.x, player.y) / 1600f, 0.2f, 1f);
        events.add(new Event(Event.EV_EXPLOSION, x, y, v));
        shake = Math.min(1.5f, shake + radius * 0.0016f);

        if (dmg > 0 && blastDepth < 4) {
            blastDepth++;
            for (int i = enemies.size() - 1; i >= 0; i--) {
                Enemy e = enemies.get(i);
                float d = Maths.dist(x, y, e.x, e.y);
                if (d < radius + e.r) {
                    int dd = (int) (dmg * (1f - d / (radius + e.r)) + 3f);
                    damageEnemy(e, dd, e.x, e.y);
                }
            }
            for (int i = obstacles.size() - 1; i >= 0; i--) {
                Obstacle o = obstacles.get(i);
                float ox = o.nx(x);
                float oy = o.ny(y);
                if (Maths.dist(x, y, ox, oy) < radius) {
                    damageObstacle(o, dmg / 2);
                }
            }
            if (!fromPlayer && player.alive) {
                float d = Maths.dist(x, y, player.x, player.y);
                if (d < radius + player.r) {
                    player.hurt(this, (int) (dmg * (1f - d / (radius + player.r)) + 4f));
                }
            }
            blastDepth--;
        }
    }

    private void hitSparks(float x, float y, int n, int color) {
        for (int i = 0; i < n; i++) {
            float a = rnd.nextFloat() * 6.283f;
            float s = 60f + rnd.nextFloat() * 180f;
            particle(Particle.K_SPARK, x, y, (float) Math.cos(a) * s, (float) Math.sin(a) * s,
                    0.18f, 4f, color);
        }
    }

    public void particle(int kind, float x, float y, float vx, float vy, float life, float size, int color) {
        if (particles.size() > 640) {
            return;
        }
        Particle p = new Particle();
        p.init(kind, x, y, vx, vy, life, size, color);
        particles.add(p);
    }

    public Enemy nearestEnemy(float x, float y, float maxDist) {
        Enemy best = null;
        float bd = maxDist;
        for (int i = 0; i < enemies.size(); i++) {
            Enemy e = enemies.get(i);
            float d = Maths.dist(x, y, e.x, e.y);
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    public void gameOver() {
        if (state == ST_GAME_OVER) {
            return;
        }
        state = ST_GAME_OVER;
        stateTimer = 0f;
        if (score > bestScore) {
            bestScore = score;
        }
        events.add(new Event(Event.EV_GAME_OVER, player.x, player.y, 1f));
    }
}
