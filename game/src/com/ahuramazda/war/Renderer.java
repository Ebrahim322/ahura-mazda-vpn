package com.ahuramazda.war;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import com.ahuramazda.war.sim.Blast;
import com.ahuramazda.war.sim.Bullet;
import com.ahuramazda.war.sim.Enemy;
import com.ahuramazda.war.sim.Maths;
import com.ahuramazda.war.sim.Obstacle;
import com.ahuramazda.war.sim.Particle;
import com.ahuramazda.war.sim.PowerUp;
import com.ahuramazda.war.sim.World;

import java.util.Random;

/** Draws the battle field. Everything is vector art or a generated sprite. */
public class Renderer {

    private static final int MAX_SCORCH = 48;

    public int w = 1920;
    public int h = 1080;

    private Bitmap bg;
    private Bitmap glowWarm;
    private Bitmap glowCyan;
    private Bitmap glowGreen;
    private Bitmap glowViolet;
    private Bitmap smoke;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF sprite = new RectF();
    private final Path path = new Path();

    private final float[] scorchX = new float[MAX_SCORCH];
    private final float[] scorchY = new float[MAX_SCORCH];
    private final float[] scorchR = new float[MAX_SCORCH];
    private final float[] scorchA = new float[MAX_SCORCH];
    private int scorchN;

    public void resize(int width, int height) {
        w = width;
        h = height;
        if (bg != null) {
            bg.recycle();
            bg = null;
        }
        bg = makeBackground(width, height);
        if (glowWarm == null) {
            glowWarm = glow(0xffffc46a, 96);
            glowCyan = glow(0xff7fe9ff, 64);
            glowGreen = glow(0xff8dff9a, 64);
            glowViolet = glow(0xffc48aff, 64);
            smoke = soft(0xff6b6152, 64);
        }
        scorchN = 0;
    }

    public void addScorch(float x, float y, float r) {
        if (scorchN >= MAX_SCORCH) {
            for (int i = 0; i < MAX_SCORCH - 1; i++) {
                scorchX[i] = scorchX[i + 1];
                scorchY[i] = scorchY[i + 1];
                scorchR[i] = scorchR[i + 1];
                scorchA[i] = scorchA[i + 1];
            }
            scorchN = MAX_SCORCH - 1;
        }
        scorchX[scorchN] = x;
        scorchY[scorchN] = y;
        scorchR[scorchN] = r * 0.55f;
        scorchA[scorchN] = 0.5f;
        scorchN++;
    }

    public void clearScorch() {
        scorchN = 0;
    }

    // ------------------------------------------------------------ background

    private Bitmap makeBackground(int width, int height) {
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(bmp);
        Random r = new Random(0xC0FFEE);
        Paint p = new Paint();

        LinearGradient g = new LinearGradient(0, 0, 0, height, Theme.SAND_TOP, Theme.SAND_BOT,
                Shader.TileMode.CLAMP);
        p.setShader(g);
        c.drawRect(0, 0, width, height, p);
        p.setShader(null);

        // sand patches
        for (int i = 0; i < 220; i++) {
            float x = r.nextFloat() * width;
            float y = r.nextFloat() * height;
            float rad = 40f + r.nextFloat() * 220f;
            p.setColor(r.nextBoolean() ? Theme.SAND_SPOT : Theme.SAND_DARK);
            p.setAlpha(28 + r.nextInt(40));
            c.drawOval(x - rad, y - rad * 0.6f, x + rad, y + rad * 0.6f, p);
        }
        // pebbles
        for (int i = 0; i < 900; i++) {
            float x = r.nextFloat() * width;
            float y = r.nextFloat() * height;
            float s = 1.2f + r.nextFloat() * 2.6f;
            p.setColor(r.nextBoolean() ? 0xff7a6a4a : 0xff241d13);
            p.setAlpha(70 + r.nextInt(90));
            c.drawOval(x - s, y - s * 0.7f, x + s, y + s * 0.7f, p);
        }
        // faint survey grid
        p.setColor(Theme.GRID);
        p.setStrokeWidth(1.5f);
        for (float x = 0; x < width; x += 120f) {
            c.drawLine(x, 0, x, height, p);
        }
        for (float y = 0; y < height; y += 120f) {
            c.drawLine(0, y, width, y, p);
        }
        // vignette
        RadialGradient vg = new RadialGradient(width * 0.5f, height * 0.5f,
                Math.max(width, height) * 0.62f, 0x00000000, 0x88000000, Shader.TileMode.CLAMP);
        p.setShader(vg);
        c.drawRect(0, 0, width, height, p);
        p.setShader(null);
        return bmp;
    }

    private static Bitmap glow(int color, int size) {
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        RadialGradient g = new RadialGradient(size / 2f, size / 2f, size / 2f,
                color, color & 0x00ffffff, Shader.TileMode.CLAMP);
        p.setShader(g);
        c.drawRect(0, 0, size, size, p);
        return bmp;
    }

    private static Bitmap soft(int color, int size) {
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        RadialGradient g = new RadialGradient(size / 2f, size / 2f, size / 2f,
                color, color & 0x00ffffff, Shader.TileMode.CLAMP);
        p.setShader(g);
        c.drawOval(0, 0, size, size, p);
        return bmp;
    }

    // ------------------------------------------------------------------ draw

    public void drawBackground(Canvas c) {
        if (bg != null) {
            paint.setAlpha(255);
            c.drawBitmap(bg, 0, 0, paint);
        } else {
            c.drawColor(Theme.SAND_TOP);
        }
        for (int i = 0; i < scorchN; i++) {
            paint.setColor(0xff14100a);
            paint.setAlpha((int) (scorchA[i] * 120f));
            float r = scorchR[i];
            c.drawOval(scorchX[i] - r, scorchY[i] - r * 0.75f, scorchX[i] + r, scorchY[i] + r * 0.75f,
                    paint);
        }
        paint.setAlpha(255);
    }

    public void drawWorld(Canvas c, World world, float time) {
        drawObstacles(c, world, time);
        drawPickups(c, world, time);
        drawEnemies(c, world, time);
        if (world.player.alive) {
            drawPlayer(c, world, time);
        }
        drawBullets(c, world);
        drawParticles(c, world);
        drawBlasts(c, world);
    }

    private void drawObstacles(Canvas c, World world, float time) {
        for (int i = 0; i < world.obstacles.size(); i++) {
            Obstacle o = world.obstacles.get(i);
            float sx = 0;
            float sy = 0;
            if (o.shake > 0) {
                sx = (float) (Math.sin(time * 90f) * o.shake * 6f);
                sy = (float) (Math.cos(time * 77f) * o.shake * 5f);
            }
            float x = o.x + sx;
            float y = o.y + sy;
            float hw = o.w * 0.5f;
            float hh = o.h * 0.5f;

            paint.setColor(Theme.SHADOW);
            c.drawRoundRect(x - hw + 6f, y - hh + 9f, x + hw + 6f, y + hh + 9f, 8f, 8f, paint);

            if (o.kind == Obstacle.O_CONCRETE) {
                paint.setColor(0xff7d7768);
                c.drawRoundRect(x - hw, y - hh, x + hw, y + hh, 10f, 10f, paint);
                paint.setColor(0xff9a9382);
                c.drawRoundRect(x - hw + 5f, y - hh + 5f, x + hw - 5f, y - hh + 15f, 6f, 6f, paint);
                paint.setColor(0xff5a5548);
                c.drawRoundRect(x - hw + 7f, y + hh - 17f, x + hw - 7f, y + hh - 6f, 5f, 5f, paint);
                paint.setColor(0xff4b463a);
                paint.setStrokeWidth(2.5f);
                paint.setStyle(Paint.Style.STROKE);
                c.drawRoundRect(x - hw, y - hh, x + hw, y + hh, 10f, 10f, paint);
                paint.setStyle(Paint.Style.FILL);
            } else if (o.kind == Obstacle.O_SANDBAG) {
                float n = Math.max(2, (int) (o.w / 44f));
                for (int k = 0; k < n; k++) {
                    float bx = x - hw + (k + 0.5f) * (o.w / n);
                    paint.setColor(k % 2 == 0 ? 0xff9c8757 : 0xff8a7649);
                    c.drawRoundRect(bx - o.w / n * 0.46f, y - hh, bx + o.w / n * 0.46f, y + hh,
                            12f, 12f, paint);
                    paint.setColor(0x33c9b083);
                    c.drawRoundRect(bx - o.w / n * 0.42f, y - hh + 4f, bx + o.w / n * 0.42f,
                            y - hh + 11f, 8f, 8f, paint);
                }
            } else {
                paint.setColor(0xff8d3b2c);
                c.drawRoundRect(x - hw, y - hh, x + hw, y + hh, 12f, 12f, paint);
                paint.setColor(0xffb0523c);
                c.drawRoundRect(x - hw + 6f, y - hh + 4f, x + hw - 6f, y + hh - 4f, 8f, 8f, paint);
                paint.setColor(0xfff0d9a0);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(20f);
                c.drawText("!", x, y + 7f, paint);
                paint.setColor(0x2b1a0c00);
                c.drawRoundRect(x - hw + 6f, y - 5f, x + hw - 6f, y + 5f, 4f, 4f, paint);
            }

            float hpK = o.hp / o.maxHp;
            if (hpK < 0.999f) {
                paint.setColor(0x99111111);
                c.drawRect(x - hw, y - hh - 12f, x + hw, y - hh - 6f, paint);
                paint.setColor(hpK > 0.5f ? Theme.GREEN : (hpK > 0.22f ? Theme.GOLD : Theme.RED));
                c.drawRect(x - hw, y - hh - 12f, x - hw + o.w * hpK, y - hh - 6f, paint);
            }
        }
    }

    private void drawPickups(Canvas c, World world, float time) {
        for (int i = 0; i < world.pickups.size(); i++) {
            PowerUp p = world.pickups.get(i);
            float bob = (float) Math.sin(time * 3f + p.x) * 5f;
            float rot = (float) Math.sin(time * 2f + p.y) * 0.25f;
            float blink = p.life < 3f && ((int) (p.life * 8f) % 2 == 0) ? 0.35f : 1f;
            int color = p.kind == PowerUp.P_HEAL ? Theme.GREEN
                    : p.kind == PowerUp.P_SHIELD ? Theme.SHIELD
                    : p.kind == PowerUp.P_RAPID ? Theme.GOLD
                    : p.kind == PowerUp.P_SPREAD ? 0xffc48aff : 0xfff2f2f2;

            float gr = 46f + (float) Math.sin(time * 5f) * 5f;
            paint.setAlpha((int) (110f * blink));
            sprite.set(p.x - gr, p.y - gr + bob, p.x + gr, p.y + gr + bob);
            c.drawBitmap(p.kind == PowerUp.P_HEAL ? glowGreen
                    : p.kind == PowerUp.P_SPREAD ? glowViolet : glowCyan, null, sprite, paint);
            paint.setAlpha(255);

            paint.setColor(Theme.SHADOW);
            c.drawOval(p.x - 20f, p.y - 8f, p.x + 20f, p.y + 12f, paint);

            c.save();
            c.translate(p.x, p.y + bob);
            c.rotate(rot * 20f);
            paint.setColor(0xdd22201a);
            c.drawRoundRect(-19f, -19f, 19f, 19f, 7f, 7f, paint);
            paint.setColor(color);
            paint.setAlpha((int) (255f * blink));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3.5f);
            c.drawRoundRect(-19f, -19f, 19f, 19f, 7f, 7f, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setStrokeWidth(4f);
            if (p.kind == PowerUp.P_HEAL) {
                c.drawRect(-11f, -3.5f, 11f, 3.5f, paint);
                c.drawRect(-3.5f, -11f, 3.5f, 11f, paint);
            } else if (p.kind == PowerUp.P_SHIELD) {
                path.reset();
                path.moveTo(0f, -12f);
                path.lineTo(10f, -6f);
                path.lineTo(10f, 3f);
                path.lineTo(0f, 13f);
                path.lineTo(-10f, 3f);
                path.lineTo(-10f, -6f);
                path.close();
                c.drawPath(path, paint);
            } else if (p.kind == PowerUp.P_RAPID) {
                path.reset();
                path.moveTo(3f, -13f);
                path.lineTo(-7f, 1f);
                path.lineTo(1f, 1f);
                path.lineTo(-3f, 13f);
                path.lineTo(8f, -2f);
                path.lineTo(0f, -2f);
                path.close();
                c.drawPath(path, paint);
            } else if (p.kind == PowerUp.P_SPREAD) {
                for (int k = -1; k <= 1; k++) {
                    float a = k * 0.42f;
                    c.save();
                    c.rotate(a * 28f);
                    c.drawRect(-2f, -13f, 2f, 8f, paint);
                    c.drawRect(-5f, 4f, 5f, 12f, paint);
                    c.restore();
                }
            } else {
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(0f, 0f, 6f, paint);
                for (int k = 0; k < 3; k++) {
                    float a = (float) (k * 2.0944 + time * 2f);
                    path.reset();
                    path.moveTo(0f, 0f);
                    path.lineTo((float) Math.cos(a - 0.35f) * 14f, (float) Math.sin(a - 0.35f) * 14f);
                    path.lineTo((float) Math.cos(a + 0.35f) * 14f, (float) Math.sin(a + 0.35f) * 14f);
                    path.close();
                    c.drawPath(path, paint);
                }
            }
            c.restore();
            paint.setAlpha(255);
        }
    }

    private void drawEnemies(Canvas c, World world, float time) {
        for (int i = 0; i < world.enemies.size(); i++) {
            Enemy e = world.enemies.get(i);
            float spawnK = Maths.clamp(e.spawnT / 0.45f, 0f, 1f);
            if (spawnK < 1f) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(4f);
                paint.setColor(Theme.RED);
                paint.setAlpha((int) (200f * (1f - spawnK)));
                c.drawCircle(e.x, e.y, e.r + 40f * (1f - spawnK), paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setAlpha(255);
            }
            int alpha = (int) (255f * spawnK);
            paint.setAlpha(alpha);
            switch (e.type) {
                case Enemy.T_SOLDIER:
                    drawSoldier(c, e, time);
                    break;
                case Enemy.T_SCOUT:
                    drawScout(c, e, time);
                    break;
                case Enemy.T_TANK:
                    drawTank(c, e.x, e.y, e.aim, e.aim, e.r / 31f, Theme.RUST, Theme.RUST_DARK,
                            Theme.RUST_LIGHT, e.hitFlash, time, 0f, 0f);
                    break;
                case Enemy.T_GUNNER:
                    drawGunner(c, e, time);
                    break;
                case Enemy.T_HELI:
                    drawHeli(c, e, time);
                    break;
                default:
                    drawBoss(c, e, time);
                    break;
            }
            if (e.type != Enemy.T_BOSS && e.hp < e.maxHp) {
                float bw = e.r * 2.2f;
                float bx = e.x - bw * 0.5f;
                float by = e.y - e.r - 18f;
                paint.setAlpha(alpha);
                paint.setColor(0x99111111);
                c.drawRect(bx, by, bx + bw, by + 6f, paint);
                paint.setColor(Theme.RED);
                c.drawRect(bx, by, bx + bw * (e.hp / e.maxHp), by + 6f, paint);
            }
            paint.setAlpha(255);
        }
    }

    private void drawSoldier(Canvas c, Enemy e, float time) {
        float bob = (float) Math.sin(time * 9f + e.x) * 1.6f;
        paint.setColor(Theme.SHADOW);
        c.drawOval(e.x - 15f, e.y + 8f, e.x + 15f, e.y + 20f, paint);
        // rifle
        c.save();
        c.translate(e.x, e.y);
        c.rotate((float) Math.toDegrees(e.aim));
        paint.setColor(0xff2b2b2b);
        paint.setStrokeWidth(4.5f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(2f, 5f, 26f, 5f, paint);
        paint.setColor(Theme.RUST_DARK);
        c.drawRoundRect(-2f, 1f, 8f, 9f, 2f, 2f, paint);
        c.restore();
        // body
        paint.setColor(0xff4c5a3a);
        c.drawRoundRect(e.x - 11f, e.y - 6f + bob, e.x + 11f, e.y + 12f + bob, 8f, 8f, paint);
        paint.setColor(0xff3b4630);
        c.drawRoundRect(e.x - 13f, e.y - 1f + bob, e.x + 13f, e.y + 5f + bob, 4f, 4f, paint);
        // head
        paint.setColor(0xffc9a887);
        c.drawCircle(e.x, e.y - 12f + bob, 7f, paint);
        paint.setColor(0xff5d6a41);
        c.drawArc(e.x - 8f, e.y - 21f + bob, e.x + 8f, e.y - 5f + bob, 180f, 180f, true, paint);
        if (e.hitFlash > 0) {
            paint.setColor(0xffffe0e0);
            paint.setAlpha((int) (200f * e.hitFlash));
            c.drawCircle(e.x, e.y, e.r, paint);
            paint.setAlpha(255);
        }
    }

    private void drawScout(Canvas c, Enemy e, float time) {
        float ang = e.aim;
        paint.setColor(Theme.SHADOW);
        c.drawOval(e.x - 20f, e.y + 8f, e.x + 20f, e.y + 22f, paint);
        c.save();
        c.translate(e.x, e.y);
        c.rotate((float) Math.toDegrees(ang));
        // wheels
        paint.setColor(0xff1c1c1c);
        for (int i = -1; i <= 1; i += 2) {
            for (int j = -1; j <= 1; j += 2) {
                c.drawRoundRect(j * 14f - 7f, i * 15f - 5f, j * 14f + 7f, i * 15f + 5f, 4f, 4f, paint);
            }
        }
        // body
        paint.setColor(0xffa08a4e);
        c.drawRoundRect(-18f, -12f, 20f, 12f, 6f, 6f, paint);
        paint.setColor(0xff7d6a3a);
        c.drawRoundRect(-10f, -9f, 12f, 9f, 4f, 4f, paint);
        paint.setColor(0xff2f2f2f);
        c.drawRoundRect(14f, -6f, 27f, 6f, 3f, 3f, paint);
        paint.setColor(0xffd8c48a);
        c.drawRoundRect(-4f, -15f, 8f, -9f, 3f, 3f, paint);
        c.restore();
        if (e.hitFlash > 0) {
            paint.setColor(0xffffe0e0);
            paint.setAlpha((int) (200f * e.hitFlash));
            c.drawCircle(e.x, e.y, e.r, paint);
            paint.setAlpha(255);
        }
    }

    private void drawTank(Canvas c, float x, float y, float hull, float aim, float scale,
                          int body, int dark, int light, float flash, float time, float muzzle,
                          float recoil) {
        paint.setColor(Theme.SHADOW);
        c.drawOval(x - 34f * scale, y + 14f * scale, x + 34f * scale, y + 32f * scale, paint);

        c.save();
        c.translate(x, y);
        c.rotate((float) Math.toDegrees(hull));
        c.scale(scale, scale);
        // tracks
        paint.setColor(Theme.TRACK);
        c.drawRoundRect(-32f, -34f, -18f, 34f, 9f, 9f, paint);
        c.drawRoundRect(18f, -34f, 32f, 34f, 9f, 9f, paint);
        paint.setColor(0xff3d4033);
        for (int i = -2; i <= 2; i++) {
            c.drawRect(-31f, i * 13f - 3.5f, -19f, i * 13f + 3.5f, paint);
            c.drawRect(19f, i * 13f - 3.5f, 31f, i * 13f + 3.5f, paint);
        }
        // hull
        paint.setColor(body);
        c.drawRoundRect(-22f, -30f, 22f, 30f, 8f, 8f, paint);
        paint.setColor(light);
        c.drawRoundRect(-17f, -26f, 17f, -12f, 5f, 5f, paint);
        paint.setColor(dark);
        c.drawRoundRect(-17f, 12f, 17f, 26f, 5f, 5f, paint);
        c.restore();

        c.save();
        c.translate(x, y);
        c.rotate((float) Math.toDegrees(aim));
        c.scale(scale, scale);
        float kick = -recoil * 7f;
        // barrel
        paint.setColor(Theme.TRACK);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(9f);
        c.drawLine(6f + kick, 0f, 44f + kick, 0f, paint);
        paint.setColor(dark);
        paint.setStrokeWidth(5f);
        c.drawLine(8f + kick, 0f, 42f + kick, 0f, paint);
        // turret
        paint.setColor(dark);
        c.drawCircle(-2f, 0f, 19f, paint);
        paint.setColor(body);
        c.drawCircle(-2f, 0f, 15f, paint);
        paint.setColor(light);
        c.drawCircle(-5f, -4f, 6f, paint);
        if (muzzle > 0) {
            paint.setAlpha((int) (235f * muzzle));
            float fr = 30f + muzzle * 26f;
            sprite.set(38f + kick - fr, -fr, 38f + kick + fr, fr);
            c.drawBitmap(glowWarm, null, sprite, paint);
            paint.setAlpha(255);
        }
        c.restore();

        if (flash > 0) {
            paint.setColor(0xffffdcdc);
            paint.setAlpha((int) (170f * flash));
            c.drawCircle(x, y, 32f * scale, paint);
            paint.setAlpha(255);
        }
    }

    private void drawGunner(Canvas c, Enemy e, float time) {
        paint.setColor(Theme.SHADOW);
        c.drawOval(e.x - 28f, e.y + 10f, e.x + 28f, e.y + 26f, paint);
        // sandbag ring
        for (int k = 0; k < 8; k++) {
            float a = (float) (k * 0.7854f);
            paint.setColor(k % 2 == 0 ? 0xff9c8757 : 0xff8a7649);
            c.drawRoundRect(e.x + (float) Math.cos(a) * 28f - 9f, e.y + (float) Math.sin(a) * 22f - 6f,
                    e.x + (float) Math.cos(a) * 28f + 9f, e.y + (float) Math.sin(a) * 22f + 6f,
                    6f, 6f, paint);
        }
        paint.setColor(0xff4a4f57);
        c.drawCircle(e.x, e.y, 20f, paint);
        c.save();
        c.translate(e.x, e.y);
        c.rotate((float) Math.toDegrees(e.aim));
        paint.setColor(Theme.STEEL_DARK);
        c.drawRoundRect(-6f, -16f, 32f, -4f, 3f, 3f, paint);
        c.drawRoundRect(-6f, 4f, 32f, 16f, 3f, 3f, paint);
        paint.setColor(Theme.STEEL);
        c.drawRoundRect(22f, -14f, 34f, 14f, 4f, 4f, paint);
        c.restore();
        paint.setColor(0xff5c6570);
        c.drawCircle(e.x, e.y, 11f, paint);
        paint.setColor(0xff20242a);
        c.drawCircle(e.x, e.y, 5f, paint);
        if (e.hitFlash > 0) {
            paint.setColor(0xffffdcdc);
            paint.setAlpha((int) (170f * e.hitFlash));
            c.drawCircle(e.x, e.y, e.r, paint);
            paint.setAlpha(255);
        }
    }

    private void drawHeli(Canvas c, Enemy e, float time) {
        float bob = (float) Math.sin(time * 3.4f + e.bob) * 4f;
        paint.setColor(0x55000000);
        c.drawOval(e.x - 26f, e.y + 26f, e.x + 26f, e.y + 42f, paint);
        // rotor blur
        paint.setColor(0x55d8d8d8);
        c.drawOval(e.x - 40f, e.y - 40f + bob, e.x + 40f, e.y + 40f + bob, paint);
        paint.setColor(0x22ffffff);
        c.drawOval(e.x - 26f, e.y - 26f + bob, e.x + 26f, e.y + 26f + bob, paint);
        c.save();
        c.translate(e.x, e.y + bob);
        c.rotate((float) Math.toDegrees(e.aim));
        // tail
        paint.setColor(Theme.STEEL_DARK);
        c.drawRoundRect(-52f, -5f, -14f, 5f, 4f, 4f, paint);
        paint.setColor(Theme.STEEL);
        c.drawRoundRect(-56f, -14f, -44f, 14f, 4f, 4f, paint);
        // fuselage
        paint.setColor(0xff4d4436);
        c.drawRoundRect(-20f, -15f, 22f, 15f, 12f, 12f, paint);
        paint.setColor(0xff6a5f4a);
        c.drawRoundRect(-14f, -11f, 16f, 11f, 9f, 9f, paint);
        paint.setColor(0xff9fd8ff);
        c.drawRoundRect(6f, -9f, 20f, 5f, 6f, 6f, paint);
        // skids
        paint.setColor(0xff2c2823);
        c.drawRoundRect(-16f, 15f, 16f, 19f, 3f, 3f, paint);
        c.restore();
        // rotor blade
        float rot = time * 26f;
        paint.setColor(0x772c2c2c);
        paint.setStrokeWidth(4f);
        c.drawLine(e.x - (float) Math.cos(rot) * 42f, e.y + bob - (float) Math.sin(rot) * 14f,
                e.x + (float) Math.cos(rot) * 42f, e.y + bob + (float) Math.sin(rot) * 14f, paint);
        if (e.hitFlash > 0) {
            paint.setColor(0xffffdcdc);
            paint.setAlpha((int) (170f * e.hitFlash));
            c.drawCircle(e.x, e.y, e.r, paint);
            paint.setAlpha(255);
        }
    }

    private void drawBoss(Canvas c, Enemy e, float time) {
        float s = 1f;
        paint.setColor(Theme.SHADOW);
        c.drawOval(e.x - 80f * s, e.y + 30f * s, e.x + 80f * s, e.y + 66f * s, paint);

        c.save();
        c.translate(e.x, e.y);
        c.rotate((float) Math.toDegrees(e.aim));
        // tracks
        paint.setColor(Theme.TRACK);
        c.drawRoundRect(-78f, -68f, -52f, 68f, 14f, 14f, paint);
        c.drawRoundRect(52f, -68f, 78f, 68f, 14f, 14f, paint);
        paint.setColor(0xff3d4033);
        for (int i = -3; i <= 3; i++) {
            c.drawRect(-76f, i * 18f - 5f, -54f, i * 18f + 5f, paint);
            c.drawRect(54f, i * 18f - 5f, 76f, i * 18f + 5f, paint);
        }
        // hull (octagon)
        path.reset();
        float[] px = {-56f, -30f, 30f, 56f, 56f, 30f, -30f, -56f};
        float[] py = {-40f, -58f, -58f, -40f, 40f, 58f, 58f, 40f};
        path.moveTo(px[0], py[0]);
        for (int i = 1; i < 8; i++) {
            path.lineTo(px[i], py[i]);
        }
        path.close();
        paint.setColor(0xff6b4636);
        c.drawPath(path, paint);
        paint.setColor(0xff8a5a44);
        c.drawRoundRect(-40f, -46f, 40f, -18f, 8f, 8f, paint);
        paint.setColor(0xff3f2a20);
        c.drawRoundRect(-40f, 18f, 40f, 46f, 8f, 8f, paint);
        paint.setColor(0xffe0a63c);
        c.drawRoundRect(-14f, -10f, 14f, 10f, 4f, 4f, paint);
        // twin barrels
        paint.setColor(Theme.TRACK);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(13f);
        c.drawLine(20f, -18f, 96f, -18f, paint);
        c.drawLine(20f, 18f, 96f, 18f, paint);
        paint.setColor(0xff4a2a1e);
        paint.setStrokeWidth(7f);
        c.drawLine(24f, -18f, 92f, -18f, paint);
        c.drawLine(24f, 18f, 92f, 18f, paint);
        // turret
        paint.setColor(0xff4a2f22);
        c.drawCircle(-6f, 0f, 40f, paint);
        paint.setColor(0xff7d4e3a);
        c.drawCircle(-6f, 0f, 32f, paint);
        paint.setColor(0xffa86a4c);
        c.drawCircle(-14f, -10f, 11f, paint);
        c.restore();

        if (e.phase == 2 && e.stateT <= 0) {
            paint.setColor(0x33ff5a3c);
            c.drawCircle(e.x, e.y, 110f + (float) Math.sin(time * 12f) * 10f, paint);
        }
        if (e.hitFlash > 0) {
            paint.setColor(0xffffdcdc);
            paint.setAlpha((int) (150f * e.hitFlash));
            c.drawCircle(e.x, e.y, 78f, paint);
            paint.setAlpha(255);
        }
    }

    private void drawPlayer(Canvas c, World world, float time) {
        com.ahuramazda.war.sim.Player p = world.player;
        float blink = 1f;
        if (p.invuln > 0 && ((int) (time * 18f) % 2 == 0)) {
            blink = 0.45f;
        }
        paint.setAlpha((int) (255f * blink));
        drawTank(c, p.x, p.y, p.hull, p.aim, 1f, Theme.OLIVE, Theme.OLIVE_DARK, Theme.OLIVE_LIGHT,
                0f, time, p.muzzle, p.recoil);
        paint.setAlpha(255);

        if (p.shield > 0) {
            float a = 0.5f + 0.5f * (float) Math.sin(time * 6f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(4f);
            paint.setColor(Theme.SHIELD);
            paint.setAlpha((int) (110f + 70f * a * (p.shield < 2f ? 0.5f : 1f)));
            c.drawCircle(p.x, p.y, 44f + a * 4f, paint);
            c.save();
            c.translate(p.x, p.y);
            c.rotate(time * 60f);
            for (int k = 0; k < 3; k++) {
                c.drawArc(-44f, -44f, 44f, 44f, k * 120f, 45f, false, paint);
            }
            c.restore();
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(255);
        }
    }

    private void drawBullets(Canvas c, World world) {
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < world.bullets.size(); i++) {
            Bullet b = world.bullets.get(i);
            float ang = (float) Math.atan2(b.vy, b.vx);
            float cos = (float) Math.cos(ang);
            float sin = (float) Math.sin(ang);
            if (b.kind == Bullet.B_BOMB) {
                paint.setColor(0x44000000);
                c.drawOval(b.x - 10f, b.y + 12f, b.x + 10f, b.y + 24f, paint);
                paint.setColor(0xff3b3a34);
                c.drawOval(b.x - 9f, b.y - 12f, b.x + 9f, b.y + 12f, paint);
                paint.setColor(0xff56544a);
                c.drawOval(b.x - 6f, b.y - 9f, b.x + 1f, b.y + 6f, paint);
                paint.setColor(((int) (world.time * 12f) % 2 == 0) ? Theme.RED : 0xff5a1c1c);
                c.drawCircle(b.x, b.y - 10f, 3.4f, paint);
                continue;
            }
            boolean enemy = b.enemy;
            float len = b.kind == Bullet.B_SHELL ? 30f : 22f;
            int core = enemy ? 0xffffb066 : 0xffe8fbff;
            int edge = enemy ? Theme.FIRE : Theme.CYAN;
            paint.setColor(edge);
            paint.setAlpha(90);
            paint.setStrokeWidth(b.kind == Bullet.B_SHELL ? 15f : 11f);
            c.drawLine(b.x - cos * len, b.y - sin * len, b.x, b.y, paint);
            paint.setAlpha(255);
            paint.setColor(core);
            paint.setStrokeWidth(b.kind == Bullet.B_SHELL ? 7f : 5f);
            c.drawLine(b.x - cos * len * 0.85f, b.y - sin * len * 0.85f, b.x, b.y, paint);
            float gr = b.kind == Bullet.B_SHELL ? 26f : 17f;
            paint.setAlpha(190);
            sprite.set(b.x - gr, b.y - gr, b.x + gr, b.y + gr);
            c.drawBitmap(enemy ? glowWarm : glowCyan, null, sprite, paint);
            paint.setAlpha(255);
        }
    }

    private void drawParticles(Canvas c, World world) {
        for (int i = 0; i < world.particles.size(); i++) {
            Particle p = world.particles.get(i);
            float k = p.life / p.maxLife;
            if (k < 0f) {
                k = 0f;
            }
            if (p.kind == Particle.K_SMOKE) {
                float r = p.size * (1.6f - k * 0.6f);
                paint.setAlpha((int) (100f * k * k));
                sprite.set(p.x - r, p.y - r, p.x + r, p.y + r);
                c.drawBitmap(smoke, null, sprite, paint);
            } else if (p.kind == Particle.K_DEBRIS) {
                paint.setColor(p.color);
                paint.setAlpha((int) (255f * k));
                c.save();
                c.translate(p.x, p.y);
                c.rotate(p.rot * 40f + p.maxLife * 30f);
                float s = p.size * k;
                c.drawRect(-s, -s * 0.6f, s, s * 0.6f, paint);
                c.restore();
            } else if (p.kind == Particle.K_FLASH) {
                paint.setAlpha((int) (255f * k));
                sprite.set(p.x - p.size, p.y - p.size, p.x + p.size, p.y + p.size);
                c.drawBitmap(glowWarm, null, sprite, paint);
            } else {
                paint.setColor(p.color);
                paint.setAlpha((int) (255f * k));
                c.drawCircle(p.x, p.y, p.size * (0.35f + 0.65f * k), paint);
            }
        }
        paint.setAlpha(255);
    }

    private void drawBlasts(Canvas c, World world) {
        for (int i = 0; i < world.blasts.size(); i++) {
            Blast b = world.blasts.get(i);
            float k = b.life / b.maxLife;
            paint.setAlpha((int) (230f * k * k));
            float g = b.r * 1.5f;
            sprite.set(b.x - g, b.y - g, b.x + g, b.y + g);
            c.drawBitmap(glowWarm, null, sprite, paint);
            paint.setAlpha((int) (220f * k));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(4f + 16f * k);
            paint.setColor(0xffffcf7a);
            c.drawCircle(b.x, b.y, b.r, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(255);
        }
    }
}
