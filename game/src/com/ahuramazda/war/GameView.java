package com.ahuramazda.war;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.ahuramazda.war.sim.Event;
import com.ahuramazda.war.sim.Input;
import com.ahuramazda.war.sim.Maths;
import com.ahuramazda.war.sim.World;

import java.util.ArrayList;
import java.util.Random;

/** Owns the game loop, the input handling and the screen state machine. */
public class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable {

    private static final String TAG = "AhuraWar";
    private static final float STEP = 1f / 60f;

    public static final int ST_MENU = 0;
    public static final int ST_HELP = 1;
    public static final int ST_PLAY = 2;
    public static final int ST_PAUSE = 3;
    public static final int ST_OVER = 4;

    private final SurfaceHolder holder;
    private Thread thread;
    private volatile boolean running;

    private final World world = new World();
    private final Input input = new Input();
    private final Renderer renderer = new Renderer();
    private final Ui ui = new Ui();
    private final Audio audio = new Audio();
    private final Save save;
    private final Vibrator vibrator;

    private int vw = 1920;
    private int vh = 1080;
    private int screen = ST_MENU;

    private final ArrayList<Ui.Btn> buttons = new ArrayList<Ui.Btn>();
    private Ui.Btn pressed;

    private int joyId = -1;
    private float joyBx, joyBy, joyKx, joyKy;
    private boolean joyActive;
    private int fireId = -1;
    private boolean wantSpecial;
    private boolean firing;

    private float time;
    private float overTimer;
    private boolean record;
    private int prevBest;

    private String bannerText = "";
    private float bannerT;
    private float bannerDur = 1f;
    private int bannerColor = Theme.GOLD;

    // keyboard (useful on emulators / TV)
    private boolean kLeft, kRight, kUp, kDown, kFire, kSpecial;

    // menu dust
    private final float[] dustX = new float[70];
    private final float[] dustY = new float[70];
    private final float[] dustS = new float[70];
    private final Random rnd = new Random();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public GameView(Context c) {
        super(c);
        holder = getHolder();
        holder.addCallback(this);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setKeepScreenOn(true);
        save = new Save(c);
        audio.sfxOn = save.sound();
        audio.musicOn = save.music();
        Object v = c.getSystemService(Context.VIBRATOR_SERVICE);
        vibrator = v instanceof Vibrator ? (Vibrator) v : null;
        world.bestScore = save.best();
        world.state = World.ST_PLAYING;
        for (int i = 0; i < dustX.length; i++) {
            dustX[i] = rnd.nextFloat();
            dustY[i] = rnd.nextFloat();
            dustS[i] = 0.4f + rnd.nextFloat() * 0.8f;
        }
    }

    // ------------------------------------------------------------ lifecycle

    public void surfaceCreated(SurfaceHolder h) {
        startThread();
        audio.start();
    }

    public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
        int nvw = (int) (1080f * width / Math.max(1, height));
        if (nvw < 1500) {
            nvw = 1500;
        }
        if (nvw > 2600) {
            nvw = 2600;
        }
        int nvh = 1080;
        if (nvw == vw && nvh == vh && renderer.w == vw) {
            return;
        }
        vw = nvw;
        vh = nvh;
        holder.setFixedSize(vw, vh);
        renderer.resize(vw, vh);
        resizeWorld();
        buildButtons();
    }

    public void surfaceDestroyed(SurfaceHolder h) {
        stopThread();
        audio.stop();
    }

    public void resume() {
        audio.sfxOn = save.sound();
        audio.musicOn = save.music();
        audio.start();
    }

    public void pause() {
        if (screen == ST_PLAY) {
            screen = ST_PAUSE;
            buildButtons();
        }
        input.reset();
        audio.stop();
    }

    private void startThread() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this, "ahura-loop");
        thread.start();
    }

    private void stopThread() {
        running = false;
        if (thread != null) {
            try {
                thread.join(600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    private void resizeWorld() {
        world.W = vw;
        world.H = vh;
        world.player.x = Maths.clamp(world.player.x, 60f, vw - 60f);
        world.player.y = Maths.clamp(world.player.y, 60f, vh - 60f);
        for (int i = 0; i < world.enemies.size(); i++) {
            world.enemies.get(i).x = Maths.clamp(world.enemies.get(i).x, 40f, vw - 40f);
            world.enemies.get(i).y = Maths.clamp(world.enemies.get(i).y, 40f, vh - 40f);
        }
        for (int i = 0; i < world.pickups.size(); i++) {
            world.pickups.get(i).x = Maths.clamp(world.pickups.get(i).x, 40f, vw - 40f);
            world.pickups.get(i).y = Maths.clamp(world.pickups.get(i).y, 40f, vh - 40f);
        }
    }

    // ----------------------------------------------------------------- loop

    public void run() {
        long last = System.nanoTime();
        float acc = 0f;
        while (running) {
            long now = System.nanoTime();
            float dt = (now - last) / 1e9f;
            last = now;
            if (dt > 0.25f) {
                dt = 0.25f;
            }
            acc += dt;
            int steps = 0;
            while (acc >= STEP && steps < 5) {
                update(STEP);
                acc -= STEP;
                steps++;
            }
            if (steps == 5) {
                acc = 0f;
            }
            render();
            long work = System.nanoTime() - now;
            long sleep = 16_000_000L - work;
            if (sleep > 2_000_000L) {
                try {
                    Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void update(float dt) {
        time += dt;
        if (bannerT > 0) {
            bannerT -= dt;
        }
        if (screen == ST_PLAY) {
            input.special = wantSpecial;
            wantSpecial = false;
            applyKeyboard();
            world.update(dt, input);
            drainEvents();
            if (world.state == World.ST_WAVE_CLEAR) {
                bannerText = Theme.T_WAVE_DONE;
                bannerDur = 2.7f;
                bannerT = world.stateTimer;
                bannerColor = Theme.GOLD;
            }
            if (world.state == World.ST_GAME_OVER) {
                overTimer += dt;
                if (overTimer > 1.5f) {
                    showGameOver();
                }
            }
        } else if (screen == ST_MENU || screen == ST_HELP || screen == ST_PAUSE
                || screen == ST_OVER) {
            if (screen == ST_MENU) {
                for (int i = 0; i < dustX.length; i++) {
                    dustX[i] += dt * (0.01f + dustS[i] * 0.02f);
                    dustY[i] += dt * dustS[i] * 0.006f;
                    if (dustX[i] > 1.05f) {
                        dustX[i] = -0.05f;
                        dustY[i] = rnd.nextFloat();
                    }
                    if (dustY[i] > 1.05f) {
                        dustY[i] = -0.05f;
                    }
                }
            }
            world.updateParticlesOnly(dt);
        }
        for (int i = 0; i < buttons.size(); i++) {
            Ui.Btn b = buttons.get(i);
            if (b.hi > 0) {
                b.hi -= dt * 4f;
                if (b.hi < 0) {
                    b.hi = 0;
                }
            }
        }
    }

    private void applyKeyboard() {
        float mx = 0;
        float my = 0;
        if (kLeft) {
            mx -= 1;
        }
        if (kRight) {
            mx += 1;
        }
        if (kUp) {
            my -= 1;
        }
        if (kDown) {
            my += 1;
        }
        if (mx != 0 || my != 0) {
            float l = Maths.len(mx, my);
            input.mx = mx / l;
            input.my = my / l;
        }
        if (kFire) {
            input.fire = true;
        }
        if (kSpecial) {
            input.special = true;
            kSpecial = false;
        }
    }

    private void drainEvents() {
        for (int i = 0; i < world.events.size(); i++) {
            Event e = world.events.get(i);
            switch (e.type) {
                case Event.EV_SHOOT:
                    audio.play(Audio.S_SHOOT, 0.6f);
                    break;
                case Event.EV_ENEMY_SHOOT:
                    audio.play(Audio.S_ESHOOT, e.v * 0.5f);
                    break;
                case Event.EV_EXPLOSION:
                    audio.play(Audio.S_BOOM, e.v);
                    renderer.addScorch(e.x, e.y, 120f);
                    if (e.v > 0.75f) {
                        vibe(18);
                    }
                    break;
                case Event.EV_HIT:
                    audio.play(Audio.S_HIT, e.v * 0.45f);
                    break;
                case Event.EV_PLAYER_HIT:
                    audio.play(Audio.S_HURT, 0.9f);
                    vibe(60);
                    break;
                case Event.EV_PICKUP:
                    audio.play(Audio.S_PICK, 0.85f);
                    break;
                case Event.EV_WAVE_START:
                    audio.play(Audio.S_WAVE, 1f);
                    if (world.wave % 5 == 0) {
                        audio.play(Audio.S_BOSS, 1f);
                        banner(Theme.T_BOSS, 3.2f, 0xffdc5a4a);
                        vibe(120);
                    } else {
                        banner(Theme.T_WAVE + " " + world.wave, 1.6f, Theme.GOLD);
                    }
                    break;
                case Event.EV_AIRSTRIKE:
                    audio.play(Audio.S_AIR, 1f);
                    banner(Theme.T_AIR, 1.2f, 0xff9fd8ff);
                    break;
                case Event.EV_GAME_OVER:
                    audio.play(Audio.S_OVER, 1f);
                    vibe(220);
                    break;
                case Event.EV_SPAWN:
                    audio.play(Audio.S_SPAWN, e.v * 0.4f);
                    break;
                default:
                    break;
            }
        }
        world.events.clear();
    }

    private void vibe(int ms) {
        if (vibrator == null || !save.vibe()) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(ms);
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private void banner(String text, float dur, int color) {
        bannerText = text;
        bannerDur = dur;
        bannerT = dur;
        bannerColor = color;
    }

    // --------------------------------------------------------------- render

    private void render() {
        Canvas c = null;
        try {
            c = holder.lockCanvas();
        } catch (Throwable t) {
            c = null;
        }
        if (c == null) {
            return;
        }
        try {
            drawFrame(c);
        } catch (Throwable t) {
            Log.e(TAG, "draw failed", t);
        } finally {
            try {
                holder.unlockCanvasAndPost(c);
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    private void drawFrame(Canvas c) {
        if (screen == ST_PLAY || screen == ST_PAUSE || screen == ST_OVER) {
            float sh = world.shake;
            float ox = 0;
            float oy = 0;
            if (sh > 0.01f) {
                ox = (rnd.nextFloat() - 0.5f) * sh * 34f;
                oy = (rnd.nextFloat() - 0.5f) * sh * 34f;
            }
            c.save();
            c.translate(ox, oy);
            renderer.drawBackground(c);
            renderer.drawWorld(c, world, time);
            c.restore();

            // low health vignette
            float hpK = (float) world.player.hp / 100f;
            if (world.player.alive && hpK < 0.34f) {
                float a = (0.34f - hpK) / 0.34f;
                paint.setColor(android.graphics.Color.argb((int) (90f * a
                        * (0.6f + 0.4f * (float) Math.sin(time * 6f))), 200, 30, 20));
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(60f);
                c.drawRect(-30f, -30f, vw + 30f, vh + 30f, paint);
                paint.setStyle(Paint.Style.FILL);
            }

            boolean boss = Ui.bossAlive(world);
            ui.drawHud(c, world, vw, vh, time, boss, Ui.bossHp(world));
            if (screen == ST_PLAY) {
                ui.drawControls(c, world, vw, vh, time, joyBx, joyBy, joyKx, joyKy, joyActive,
                        firing, world.player.specialCd <= 0);
            } else {
                ui.drawPauseButton(c, vw - 78f, 78f, 44f);
            }
            if (bannerT > 0) {
                ui.drawBanner(c, vw, vh, bannerText, 1f - bannerT / bannerDur, bannerColor);
            }
            if (screen == ST_PAUSE) {
                ui.dim(c, vw, vh, 150);
                ui.drawPausePanel(c, vw, vh);
                ui.drawButtons(c, buttons);
            } else if (screen == ST_OVER) {
                ui.dim(c, vw, vh, 135);
                ui.drawGameOver(c, vw, vh, world, record);
                ui.drawButtons(c, buttons);
            }
        } else {
            renderer.drawBackground(c);
            paint.setColor(0x33cbb894);
            for (int i = 0; i < dustX.length; i++) {
                paint.setAlpha((int) (70f * dustS[i]));
                c.drawCircle(dustX[i] * vw, dustY[i] * vh, 2f + dustS[i] * 2.6f, paint);
            }
            paint.setAlpha(255);
            if (screen == ST_MENU) {
                ui.dim(c, vw, vh, 60);
                ui.drawMenuTank(c, vw, vh, time);
                ui.drawTitle(c, vw, vh, time, Math.max(world.bestScore, save.best()));
            } else {
                ui.dim(c, vw, vh, 150);
                ui.drawHelp(c, vw, vh);
            }
            ui.drawButtons(c, buttons);
        }
    }

    // --------------------------------------------------------------- screens

    private void buildButtons() {
        buttons.clear();
        float cx = vw * 0.5f;
        if (screen == ST_MENU) {
            buttons.add(Ui.Btn.box(cx, vh * 0.70f, 460f, 104f, Ui.A_START, Theme.T_START));
            buttons.add(Ui.Btn.box(cx - 240f, vh * 0.815f, 380f, 84f, Ui.A_HELP, Theme.T_HELP));
            buttons.add(Ui.Btn.box(cx + 240f, vh * 0.815f, 380f, 84f, Ui.A_SOUND,
                    save.sound() ? "صدا: روشن" : "صدا: خاموش"));
            buttons.add(Ui.Btn.box(cx - 240f, vh * 0.925f, 380f, 76f, Ui.A_MUSIC,
                    save.music() ? "موسیقی: روشن" : "موسیقی: خاموش"));
            buttons.add(Ui.Btn.box(cx + 240f, vh * 0.925f, 380f, 76f, Ui.A_VIBE,
                    save.vibe() ? "لرزش: روشن" : "لرزش: خاموش"));
        } else if (screen == ST_HELP) {
            buttons.add(Ui.Btn.box(cx, vh * 0.865f, 340f, 88f, Ui.A_BACK, "بازگشت"));
        } else if (screen == ST_PAUSE) {
            buttons.add(Ui.Btn.box(cx, vh * 0.44f, 420f, 96f, Ui.A_RESUME, Theme.T_CONTINUE));
            buttons.add(Ui.Btn.box(cx, vh * 0.56f, 420f, 86f, Ui.A_RESTART, Theme.T_RESTART));
            buttons.add(Ui.Btn.box(cx - 200f, vh * 0.68f, 360f, 78f, Ui.A_SOUND,
                    save.sound() ? "صدا: روشن" : "صدا: خاموش"));
            buttons.add(Ui.Btn.box(cx + 200f, vh * 0.68f, 360f, 78f, Ui.A_MUSIC,
                    save.music() ? "موسیقی: روشن" : "موسیقی: خاموش"));
            buttons.add(Ui.Btn.box(cx, vh * 0.80f, 420f, 86f, Ui.A_MENU, Theme.T_MENU));
        } else if (screen == ST_OVER) {
            buttons.add(Ui.Btn.box(cx - 210f, vh * 0.74f, 380f, 100f, Ui.A_RETRY, Theme.T_RETRY));
            buttons.add(Ui.Btn.box(cx + 210f, vh * 0.74f, 380f, 100f, Ui.A_MENU, Theme.T_MENU));
        }
    }

    private void startGame() {
        world.bestScore = save.best();
        prevBest = save.best();
        world.reset();
        world.bestScore = prevBest;
        renderer.clearScorch();
        input.reset();
        firing = false;
        joyActive = false;
        joyId = -1;
        fireId = -1;
        overTimer = 0;
        record = false;
        screen = ST_PLAY;
        buildButtons();
        audio.play(Audio.S_WAVE, 1f);
        banner(Theme.T_WAVE + " " + world.wave, 1.6f, Theme.GOLD);
    }

    private void showGameOver() {
        screen = ST_OVER;
        record = world.score > prevBest && world.score > 0;
        if (world.score > save.best()) {
            save.best(world.score);
        }
        world.bestScore = save.best();
        buildButtons();
    }

    public boolean onBack() {
        if (screen == ST_PLAY) {
            screen = ST_PAUSE;
            input.reset();
            buildButtons();
            return true;
        }
        if (screen == ST_PAUSE || screen == ST_HELP || screen == ST_OVER) {
            screen = ST_MENU;
            buildButtons();
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float kx = vw / (float) Math.max(1, getWidth());
        float ky = vh / (float) Math.max(1, getHeight());
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        int pid = e.getPointerId(idx);

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            float x = e.getX(idx) * kx;
            float y = e.getY(idx) * ky;
            if (screen == ST_PLAY) {
                Ui.Btn pause = hitButton(x, y);
                if (pause != null && pause.action == Ui.A_PAUSE) {
                    screen = ST_PAUSE;
                    input.reset();
                    buildButtons();
                    audio.play(Audio.S_UI, 1f);
                    return true;
                }
                if (x < vw * 0.5f) {
                    if (joyId == -1) {
                        joyId = pid;
                        joyBx = x;
                        joyBy = y;
                        joyKx = x;
                        joyKy = y;
                        joyActive = true;
                    }
                } else {
                    float sx = vw - 350f;
                    float sy = vh - 300f;
                    float dx = x - sx;
                    float dy = y - sy;
                    if (dx * dx + dy * dy < 100f * 100f) {
                        wantSpecial = true;
                        audio.play(Audio.S_UI, 1f);
                    }
                    if (fireId == -1) {
                        fireId = pid;
                        input.fire = true;
                        firing = true;
                    }
                }
                return true;
            }
            Ui.Btn b = hitButton(x, y);
            if (b != null) {
                pressed = b;
                b.hi = 1f;
            }
            return true;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            int n = e.getPointerCount();
            for (int i = 0; i < n; i++) {
                int id = e.getPointerId(i);
                float x = e.getX(i) * kx;
                float y = e.getY(i) * ky;
                if (id == joyId) {
                    joyKx = x;
                    joyKy = y;
                    float dx = x - joyBx;
                    float dy = y - joyBy;
                    float l = Maths.len(dx, dy);
                    float max = 110f;
                    if (l > max) {
                        dx = dx / l * max;
                        dy = dy / l * max;
                    }
                    input.mx = dx / max;
                    input.my = dy / max;
                } else if (id == fireId) {
                    input.fire = true;
                }
            }
            if (pressed != null) {
                Ui.Btn b = hitButton(e.getX(0) * kx, e.getY(0) * ky);
                if (b != pressed) {
                    pressed = null;
                }
            }
            return true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
                || action == MotionEvent.ACTION_CANCEL) {
            if (pid == joyId) {
                joyId = -1;
                joyActive = false;
                input.mx = 0;
                input.my = 0;
            } else if (pid == fireId) {
                fireId = -1;
                input.fire = false;
                firing = false;
            }
            if (pressed != null) {
                float x = e.getX(Math.min(idx, e.getPointerCount() - 1)) * kx;
                float y = e.getY(Math.min(idx, e.getPointerCount() - 1)) * ky;
                Ui.Btn b = hitButton(x, y);
                if (b == pressed) {
                    activate(pressed);
                }
                pressed = null;
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                input.reset();
                joyId = -1;
                fireId = -1;
                joyActive = false;
                firing = false;
            }
            return true;
        }
        return true;
    }

    private Ui.Btn hitButton(float x, float y) {
        for (int i = 0; i < buttons.size(); i++) {
            if (buttons.get(i).contains(x, y)) {
                return buttons.get(i);
            }
        }
        if (screen == ST_PLAY) {
            float dx = x - (vw - 78f);
            float dy = y - 78f;
            if (dx * dx + dy * dy < 60f * 60f) {
                Ui.Btn b = Ui.Btn.round(vw - 78f, 78f, 44f, Ui.A_PAUSE);
                return b;
            }
        }
        return null;
    }

    private void activate(Ui.Btn b) {
        audio.play(Audio.S_UI, 1f);
        switch (b.action) {
            case Ui.A_START:
            case Ui.A_RESTART:
            case Ui.A_RETRY:
                startGame();
                break;
            case Ui.A_HELP:
                screen = ST_HELP;
                buildButtons();
                break;
            case Ui.A_BACK:
                screen = ST_MENU;
                buildButtons();
                break;
            case Ui.A_MENU:
                screen = ST_MENU;
                input.reset();
                buildButtons();
                break;
            case Ui.A_RESUME:
                screen = ST_PLAY;
                buildButtons();
                break;
            case Ui.A_SOUND:
                save.sound(!save.sound());
                audio.sfxOn = save.sound();
                buildButtons();
                break;
            case Ui.A_MUSIC:
                save.music(!save.music());
                audio.musicOn = save.music();
                buildButtons();
                break;
            case Ui.A_VIBE:
                save.vibe(!save.vibe());
                buildButtons();
                break;
            default:
                break;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_A:
                kLeft = true;
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_D:
                kRight = true;
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_W:
                kUp = true;
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_S:
                kDown = true;
                return true;
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                kFire = true;
                input.fire = true;
                return true;
            case KeyEvent.KEYCODE_Q:
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_BUTTON_B:
                kSpecial = true;
                return true;
            case KeyEvent.KEYCODE_BACK:
                return onBack();
            case KeyEvent.KEYCODE_P:
            case KeyEvent.KEYCODE_ESCAPE:
                if (screen == ST_PLAY) {
                    onBack();
                }
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_A:
                kLeft = false;
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_D:
                kRight = false;
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_W:
                kUp = false;
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_S:
                kDown = false;
                return true;
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                kFire = false;
                input.fire = false;
                firing = false;
                return true;
            default:
                return super.onKeyUp(keyCode, event);
        }
    }
}
