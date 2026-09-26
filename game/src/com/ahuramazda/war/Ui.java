package com.ahuramazda.war;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import com.ahuramazda.war.sim.Enemy;
import com.ahuramazda.war.sim.Maths;
import com.ahuramazda.war.sim.Player;
import com.ahuramazda.war.sim.World;

import java.util.HashMap;

/** HUD, menus and buttons. */
public class Ui {

    public static final int A_START = 1;
    public static final int A_HELP = 2;
    public static final int A_BACK = 3;
    public static final int A_RETRY = 4;
    public static final int A_MENU = 5;
    public static final int A_RESUME = 6;
    public static final int A_RESTART = 7;
    public static final int A_SOUND = 8;
    public static final int A_MUSIC = 9;
    public static final int A_VIBE = 10;
    public static final int A_PAUSE = 11;
    public static final int A_FIRE = 12;
    public static final int A_SPECIAL = 13;

    public static class Btn {
        public float x, y, r, w, h;
        public int action;
        public String label = "";
        public String sub = "";
        public int color = Theme.BTN_HI;
        public float hi;
        public boolean circle;

        public static Btn round(float x, float y, float r, int action) {
            Btn b = new Btn();
            b.x = x;
            b.y = y;
            b.r = r;
            b.action = action;
            b.circle = true;
            return b;
        }

        public static Btn box(float x, float y, float w, float h, int action, String label) {
            Btn b = new Btn();
            b.x = x;
            b.y = y;
            b.w = w;
            b.h = h;
            b.action = action;
            b.label = label;
            b.circle = false;
            return b;
        }

        public boolean contains(float px, float py) {
            if (circle) {
                float dx = px - x;
                float dy = py - y;
                return dx * dx + dy * dy <= r * r * 1.15f;
            }
            return px > x - w * 0.5f && px < x + w * 0.5f && py > y - h * 0.5f && py < y + h * 0.5f;
        }
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final HashMap<String, Paint> cache = new HashMap<String, Paint>();

    private Paint text(float size, int color, boolean bold, Paint.Align align) {
        String key = size + "|" + color + "|" + bold + "|" + align;
        Paint p = cache.get(key);
        if (p == null) {
            p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setTextSize(size);
            p.setColor(color);
            p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            p.setTextAlign(align);
            p.setLinearText(true);
            cache.put(key, p);
        }
        return p;
    }

    public void text(Canvas c, String s, float x, float y, float size, int color, boolean bold,
                     Paint.Align align) {
        Paint p = text(size, color, bold, align);
        c.drawText(s, x, y, p);
    }

    // ----------------------------------------------------------------- HUD

    public void drawHud(Canvas c, World world, int vw, int vh, float time, boolean bossAlive,
                        float bossHp) {
        Player p = world.player;

        // ---- health ----
        float bx = 48f;
        float by = 74f;
        float bw = 420f;
        float bh = 26f;
        paint.setColor(0xcc14110b);
        c.drawRoundRect(bx - 8f, by - 34f, bx + bw + 8f, by + bh + 46f, 12f, 12f, paint);
        text(c, Theme.T_HEALTH, bx, by - 10f, 24f, Theme.TEXT_DIM, false, Paint.Align.LEFT);

        float hpK = (float) p.hp / Player.MAX_HP;
        paint.setColor(0xff2a2118);
        c.drawRoundRect(bx, by, bx + bw, by + bh, 8f, 8f, paint);
        int hpColor = hpK > 0.55f ? Theme.GREEN : (hpK > 0.28f ? Theme.GOLD : Theme.RED);
        Shader sh = new LinearGradient(bx, by, bx, by + bh, hpColor, darker(hpColor),
                Shader.TileMode.CLAMP);
        paint.setShader(sh);
        c.drawRoundRect(bx, by, bx + bw * hpK, by + bh, 8f, 8f, paint);
        paint.setShader(null);
        paint.setColor(0x33ffffff);
        c.drawRoundRect(bx, by, bx + bw * hpK, by + bh * 0.45f, 8f, 8f, paint);
        text(c, String.valueOf(p.hp), bx + bw - 6f, by + bh - 5f, 20f, 0xddffffff, true,
                Paint.Align.RIGHT);

        // shield
        if (p.shield > 0) {
            float k = Math.min(1f, p.shield / 7f);
            paint.setColor(0xff1d2b33);
            c.drawRoundRect(bx, by + bh + 8f, bx + bw, by + bh + 20f, 6f, 6f, paint);
            paint.setColor(Theme.SHIELD);
            c.drawRoundRect(bx, by + bh + 8f, bx + bw * k, by + bh + 20f, 6f, 6f, paint);
        }

        // buffs
        float ix = bx;
        float iy = by + bh + 34f;
        drawBuff(c, ix, iy, p.rapidT, 9f, Theme.GOLD, "ت");
        drawBuff(c, ix + 54f, iy, p.spreadT, 11f, 0xffc48aff, "س");
        drawBuff(c, ix + 108f, iy, p.speedT, 8f, 0xff8dff9a, "ش");

        // ---- wave (centre) ----
        float cx = vw * 0.5f;
        paint.setColor(0xcc14110b);
        c.drawRoundRect(cx - 110f, 26f, cx + 110f, 122f, 12f, 12f, paint);
        text(c, Theme.T_WAVE, cx, 58f, 26f, Theme.TEXT_DIM, false, Paint.Align.CENTER);
        text(c, String.valueOf(world.wave), cx, 108f, 46f, Theme.TEXT, true, Paint.Align.CENTER);

        // ---- score (right) ----
        text(c, Theme.T_SCORE, vw - 44f, 150f, 24f, Theme.TEXT_DIM, false, Paint.Align.RIGHT);
        text(c, String.valueOf(world.score), vw - 44f, 196f, 42f, Theme.GOLD, true, Paint.Align.RIGHT);

        // ---- combo (bottom centre) ----
        if (world.combo > 1) {
            float k = world.comboTimer / 3.2f;
            paint.setColor(Theme.GOLD);
            paint.setAlpha((int) (255f * Math.min(1f, k * 2f)));
            text(c, "x" + world.combo, cx, vh - 54f, 44f, Theme.GOLD, true, Paint.Align.CENTER);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(5f);
            paint.setAlpha(90);
            c.drawArc(cx - 46f, vh - 96f, cx + 46f, vh - 4f, -90f, 360f * k, false, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(255);
        }

        // ---- boss bar ----
        if (bossAlive) {
            float bw2 = Math.min(720f, vw * 0.5f);
            float x0 = cx - bw2 * 0.5f;
            paint.setColor(0xcc14110b);
            c.drawRoundRect(x0 - 8f, 138f, x0 + bw2 + 8f, 186f, 10f, 10f, paint);
            text(c, "فرمانده دشمن", cx, 166f, 24f, 0xffd9a0a0, true, Paint.Align.CENTER);
            paint.setColor(0xff3a1c16);
            c.drawRoundRect(x0, 172f, x0 + bw2, 184f, 6f, 6f, paint);
            paint.setColor(0xffd84a3c);
            c.drawRoundRect(x0, 172f, x0 + bw2 * Maths.clamp(bossHp, 0f, 1f), 184f, 6f, 6f, paint);
        }
    }

    private void drawBuff(Canvas c, float x, float y, float t, float max, int color, String glyph) {
        if (t <= 0) {
            return;
        }
        float k = Maths.clamp(t / max, 0f, 1f);
        paint.setColor(0x55141310);
        c.drawRoundRect(x, y, x + 44f, y + 34f, 8f, 8f, paint);
        paint.setColor(color);
        paint.setAlpha((int) (70f + 120f * k));
        c.drawRoundRect(x + 2f, y + 2f, x + 42f, y + 32f, 7f, 7f, paint);
        paint.setAlpha(255);
        text(c, glyph, x + 22f, y + 25f, 22f, 0xffffffff, true, Paint.Align.CENTER);
        paint.setColor(0xff1a1712);
        c.drawRect(x, y + 34f, x + 44f * (1f - k), y + 39f, paint);
    }

    public void drawControls(Canvas c, World world, int vw, int vh, float time, float joyX,
                             float joyY, float knobX, float knobY, boolean joyActive,
                             boolean firing, boolean specialReady) {
        // fire button
        float fx = vw - 190f;
        float fy = vh - 175f;
        float fr = 108f;
        float pulse = firing ? 1f + (float) Math.sin(time * 30f) * 0.03f : 1f;
        paint.setColor(0x5511100c);
        c.drawCircle(fx, fy, fr * pulse + 6f, paint);
        paint.setColor(firing ? 0xccb8412a : 0xa8413630);
        c.drawCircle(fx, fy, fr * pulse, paint);
        paint.setColor(firing ? 0xffd8573c : 0xff7a5a4a);
        c.drawCircle(fx, fy, fr * 0.86f * pulse, paint);
        paint.setColor(0x33ffffff);
        c.drawCircle(fx - fr * 0.25f, fy - fr * 0.3f, fr * 0.45f, paint);
        text(c, "آتش", fx, fy + 16f, 40f, 0xfffff0e0, true, Paint.Align.CENTER);

        // airstrike button
        float sx = vw - 350f;
        float sy = vh - 300f;
        float sr = 76f;
        float k = 1f - world.player.specialCd / Player.SPECIAL_CD;
        k = Maths.clamp(k, 0f, 1f);
        paint.setColor(0x5511100c);
        c.drawCircle(sx, sy, sr + 5f, paint);
        paint.setColor(0xaa2a2a30);
        c.drawCircle(sx, sy, sr, paint);
        paint.setColor(k >= 1f ? 0xff3f6ea8 : 0xff3a4048);
        c.drawCircle(sx, sy, sr * 0.86f, paint);
        if (k < 1f) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(9f);
            paint.setColor(0xff9fd8ff);
            c.drawArc(sx - sr, sy - sr, sx + sr, sy + sr, -90f, 360f * k, false, paint);
            paint.setStyle(Paint.Style.FILL);
            text(c, String.valueOf((int) Math.ceil(world.player.specialCd)), sx, sy + 14f, 34f,
                    0xffcfe8ff, true, Paint.Align.CENTER);
        } else {
            // little plane glyph
            paint.setColor(0xffe8f4ff);
            path.reset();
            path.moveTo(sx - 30f, sy + 6f);
            path.lineTo(sx + 30f, sy - 12f);
            path.lineTo(sx + 18f, sy + 2f);
            path.lineTo(sx + 30f, sy + 16f);
            path.lineTo(sx - 30f, sy + 6f);
            path.close();
            c.drawPath(path, paint);
            text(c, Theme.T_AIR, sx, sy + 60f, 22f, 0xffbcd4e8, false, Paint.Align.CENTER);
        }

        // pause
        drawPauseButton(c, vw - 78f, 78f, 44f);

        // joystick: ring at the touch origin, knob follows the finger
        if (joyActive) {
            paint.setColor(0x4414120e);
            c.drawCircle(joyX, joyY, 118f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(5f);
            paint.setColor(0x66d9c9a0);
            c.drawCircle(joyX, joyY, 118f, paint);
            paint.setStyle(Paint.Style.FILL);
            float dx = knobX - joyX;
            float dy = knobY - joyY;
            float l = Maths.len(dx, dy);
            float max = 110f;
            if (l > max) {
                dx = dx / l * max;
                dy = dy / l * max;
            }
            paint.setColor(0x99e6d3a8);
            c.drawCircle(joyX + dx, joyY + dy, 58f, paint);
            paint.setColor(0xdcf0e2bc);
            c.drawCircle(joyX + dx, joyY + dy, 44f, paint);
        }
    }

    public void drawPauseButton(Canvas c, float x, float y, float r) {
        paint.setColor(0xcc14110b);
        c.drawCircle(x, y, r, paint);
        paint.setColor(0xffe0d2a8);
        c.drawRoundRect(x - 14f, y - 16f, x - 4f, y + 16f, 4f, 4f, paint);
        c.drawRoundRect(x + 4f, y - 16f, x + 14f, y + 16f, 4f, 4f, paint);
    }

    // --------------------------------------------------------------- screens

    public void drawButtons(Canvas c, java.util.ArrayList<Btn> buttons) {
        for (int i = 0; i < buttons.size(); i++) {
            Btn b = buttons.get(i);
            if (b.circle) {
                paint.setColor(0xcc14110b);
                c.drawCircle(b.x, b.y, b.r, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(3f);
                paint.setColor(b.hi > 0 ? Theme.GOLD : 0x66c8b48a);
                c.drawCircle(b.x, b.y, b.r, paint);
                paint.setStyle(Paint.Style.FILL);
            } else {
                float hl = b.hi;
                paint.setColor(0xee1a150f);
                c.drawRoundRect(b.x - b.w * 0.5f, b.y - b.h * 0.5f, b.x + b.w * 0.5f,
                        b.y + b.h * 0.5f, 16f, 16f, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(hl > 0 ? 5f : 3f);
                paint.setColor(hl > 0 ? Theme.GOLD : (b.color | 0x66000000));
                c.drawRoundRect(b.x - b.w * 0.5f, b.y - b.h * 0.5f, b.x + b.w * 0.5f,
                        b.y + b.h * 0.5f, 16f, 16f, paint);
                paint.setStyle(Paint.Style.FILL);
                if (hl > 0) {
                    paint.setColor(0x14e0a63c);
                    c.drawRoundRect(b.x - b.w * 0.5f, b.y - b.h * 0.5f, b.x + b.w * 0.5f,
                            b.y + b.h * 0.5f, 16f, 16f, paint);
                }
                text(c, b.label, b.x, b.y + 14f, 40f, hl > 0 ? 0xffffffff : Theme.TEXT, true,
                        Paint.Align.CENTER);
                if (b.sub.length() > 0) {
                    text(c, b.sub, b.x, b.y + 48f, 22f, Theme.TEXT_DIM, false, Paint.Align.CENTER);
                }
            }
        }
    }

    public void dim(Canvas c, int vw, int vh, int alpha) {
        paint.setColor(Color.argb(alpha, 8, 6, 4));
        c.drawRect(0, 0, vw, vh, paint);
    }

    public void panel(Canvas c, float x, float y, float w, float h) {
        paint.setColor(Theme.PANEL);
        c.drawRoundRect(x, y, x + w, y + h, 26f, 26f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f);
        paint.setColor(0x88e0a63c);
        c.drawRoundRect(x, y, x + w, y + h, 26f, 26f, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    public void drawTitle(Canvas c, int vw, int vh, float time, int best) {
        float cx = vw * 0.5f;
        float y = vh * 0.26f;
        paint.setColor(0x88110d08);
        text(c, Theme.T_TITLE, cx + 6f, y + 6f, 108f, 0x88110d08, true, Paint.Align.CENTER);
        text(c, Theme.T_TITLE, cx, y, 108f, 0xfff2cd7a, true, Paint.Align.CENTER);
        text(c, Theme.T_SUB, cx, y + 52f, 34f, 0xffa08a5c, false, Paint.Align.CENTER);

        float pulse = 0.6f + 0.4f * (float) Math.sin(time * 3f);
        paint.setAlpha((int) (255f * pulse));
        text(c, Theme.T_BEST + "  " + best, cx, vh - 70f, 30f, Theme.TEXT_DIM, false,
                Paint.Align.CENTER);
        paint.setAlpha(255);
    }

    public void drawMenuTank(Canvas c, int vw, int vh, float time) {
        float x = vw * 0.5f;
        float y = vh * 0.58f + (float) Math.sin(time * 1.6f) * 6f;
        Paint saved = paint;
        saved.setColor(Theme.OLIVE);
        c.save();
        c.translate(x, y);
        c.scale(1.6f, 1.6f);
        saved.setColor(Theme.SHADOW);
        c.drawOval(-36f, 16f, 36f, 34f, saved);
        saved.setColor(Theme.TRACK);
        c.drawRoundRect(-34f, -34f, -20f, 34f, 9f, 9f, saved);
        c.drawRoundRect(20f, -34f, 34f, 34f, 9f, 9f, saved);
        saved.setColor(Theme.OLIVE);
        c.drawRoundRect(-24f, -30f, 24f, 30f, 8f, 8f, saved);
        saved.setColor(Theme.OLIVE_LIGHT);
        c.drawRoundRect(-18f, -26f, 18f, -12f, 5f, 5f, saved);
        c.rotate((float) Math.sin(time * 0.7f) * 8f);
        saved.setColor(Theme.TRACK);
        saved.setStrokeWidth(9f);
        saved.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(6f, 0f, 46f, 0f, saved);
        saved.setColor(Theme.OLIVE_DARK);
        c.drawCircle(-2f, 0f, 19f, saved);
        saved.setColor(Theme.OLIVE);
        c.drawCircle(-2f, 0f, 15f, saved);
        c.restore();
    }

    public void drawHelp(Canvas c, int vw, int vh) {
        float cx = vw * 0.5f;
        text(c, Theme.T_HELP, cx, 150f, 62f, Theme.GOLD, true, Paint.Align.CENTER);
        float y = 250f;
        for (int i = 0; i < Theme.HELP.length; i++) {
            text(c, Theme.HELP[i], cx, y, 32f, Theme.TEXT, false, Paint.Align.CENTER);
            y += 56f;
        }
    }

    public void drawGameOver(Canvas c, int vw, int vh, World world, boolean record) {
        float cx = vw * 0.5f;
        text(c, Theme.T_OVER, cx, vh * 0.3f, 84f, 0xffd8544a, true, Paint.Align.CENTER);
        if (record) {
            float a = 0.5f + 0.5f * (float) Math.abs(Math.sin(world.time * 3f));
            paint.setAlpha((int) (255f * a));
            text(c, Theme.T_RECORD, cx, vh * 0.3f + 70f, 40f, Theme.GOLD, true, Paint.Align.CENTER);
            paint.setAlpha(255);
        }
        text(c, Theme.T_SCORE, cx - 200f, vh * 0.48f, 32f, Theme.TEXT_DIM, false, Paint.Align.CENTER);
        text(c, String.valueOf(world.score), cx - 200f, vh * 0.48f + 56f, 56f, Theme.GOLD, true,
                Paint.Align.CENTER);
        text(c, Theme.T_BEST, cx + 200f, vh * 0.48f, 32f, Theme.TEXT_DIM, false, Paint.Align.CENTER);
        text(c, String.valueOf(Math.max(world.bestScore, world.score)), cx + 200f, vh * 0.48f + 56f,
                56f, Theme.TEXT, true, Paint.Align.CENTER);
        text(c, Theme.T_WAVE + " " + world.wave + "    " + Theme.T_KILLS + " " + world.kills, cx,
                vh * 0.48f + 118f, 28f, Theme.TEXT_DIM, false, Paint.Align.CENTER);
    }

    public void drawPausePanel(Canvas c, int vw, int vh) {
        text(c, Theme.T_PAUSE, vw * 0.5f, vh * 0.3f, 72f, Theme.TEXT, true, Paint.Align.CENTER);
    }

    public void drawBanner(Canvas c, int vw, int vh, String text, float k, int color) {
        float cx = vw * 0.5f;
        float cy = vh * 0.42f;
        float scale = 0.7f + 0.3f * Maths.smoothstep(k);
        paint.setAlpha((int) (255f * Maths.clamp(k * 3f, 0f, 1f)));
        c.save();
        c.translate(cx, cy);
        c.scale(scale, scale);
        text(c, text, 0f, 0f, 76f, color, true, Paint.Align.CENTER);
        c.restore();
        paint.setAlpha(255);
    }

    private static int darker(int c) {
        return Color.rgb((int) (((c >> 16) & 255) * 0.72f), (int) (((c >> 8) & 255) * 0.72f),
                (int) ((c & 255) * 0.72f));
    }

    public static float bossHp(World w) {
        for (int i = 0; i < w.enemies.size(); i++) {
            Enemy e = w.enemies.get(i);
            if (e.type == Enemy.T_BOSS) {
                return e.hp / e.maxHp;
            }
        }
        return 0f;
    }

    public static boolean bossAlive(World w) {
        for (int i = 0; i < w.enemies.size(); i++) {
            if (w.enemies.get(i).type == Enemy.T_BOSS) {
                return true;
            }
        }
        return false;
    }
}
