import android.graphics.Bitmap;
import android.graphics.Canvas;

import com.ahuramazda.war.Renderer;
import com.ahuramazda.war.Theme;
import com.ahuramazda.war.Ui;
import com.ahuramazda.war.sim.Enemy;
import com.ahuramazda.war.sim.Input;
import com.ahuramazda.war.sim.Maths;
import com.ahuramazda.war.sim.Player;
import com.ahuramazda.war.sim.World;

import java.io.File;
import java.util.ArrayList;

import javax.imageio.ImageIO;

/**
 * Renders the game's screens to PNG files so the visuals can be checked without
 * a device. Dev tool only - never shipped in the APK.
 */
public class Preview {

    static final int VW = 1920;
    static final int VH = 1080;

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : ".";
        Renderer renderer = new Renderer();
        renderer.resize(VW, VH);
        Ui ui = new Ui();

        // ---------------- gameplay ----------------
        World w = new World();
        w.rnd.setSeed(11);
        w.W = VW;
        w.H = VH;
        w.reset();
        Input in = new Input();
        for (int i = 0; i < 60 * 40; i++) {
            bot(w, in, i);
            w.update(1f / 60f, in);
            w.events.clear();
            if (i == 60 * 3) {
                w.player.spreadT = 8f;
                w.player.rapidT = 6f;
                w.player.shield = 5f;
            }
            if (i > 60 && w.enemies.size() >= 5 && w.particles.size() > 25) {
                break;
            }
        }
        // make sure every pickup type is on screen for the snapshot
        for (int k = 0; k < 5; k++) {
            com.ahuramazda.war.sim.PowerUp pu = new com.ahuramazda.war.sim.PowerUp();
            pu.init(300f + k * 320f, 300f, k);
            w.pickups.add(pu);
        }
        w.player.hp = 72;
        w.combo = 4;
        w.comboTimer = 2f;
        save(renderer, ui, w, dir + "/preview-play.png", 9f, false);

        // ---------------- boss wave ----------------
        World b = new World();
        b.rnd.setSeed(5);
        b.W = VW;
        b.H = VH;
        b.reset();
        b.startWave(5);
        for (int i = 0; i < 60 * 30; i++) {
            bot(b, in, i);
            b.update(1f / 60f, in);
            b.events.clear();
            if (i > 60 && Ui.bossAlive(b) && b.particles.size() > 20) {
                break;
            }
        }
        save(renderer, ui, b, dir + "/preview-boss.png", 14f, false);

        // ---------------- menu ----------------
        Bitmap bmp = Bitmap.createBitmap(VW, VH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        renderer.drawBackground(c);
        ui.dim(c, VW, VH, 60);
        ui.drawMenuTank(c, VW, VH, 2f);
        ui.drawTitle(c, VW, VH, 2f, 184500);
        ArrayList<Ui.Btn> buttons = new ArrayList<Ui.Btn>();
        float cx = VW * 0.5f;
        buttons.add(Ui.Btn.box(cx, VH * 0.70f, 460f, 104f, Ui.A_START, Theme.T_START));
        buttons.add(Ui.Btn.box(cx - 240f, VH * 0.815f, 380f, 84f, Ui.A_HELP, Theme.T_HELP));
        buttons.add(Ui.Btn.box(cx + 240f, VH * 0.815f, 380f, 84f, Ui.A_SOUND, "صدا: روشن"));
        buttons.add(Ui.Btn.box(cx - 240f, VH * 0.925f, 380f, 76f, Ui.A_MUSIC, "موسیقی: روشن"));
        buttons.add(Ui.Btn.box(cx + 240f, VH * 0.925f, 380f, 76f, Ui.A_VIBE, "لرزش: روشن"));
        ui.drawButtons(c, buttons);
        ImageIO.write(bmp.image, "png", new File(dir + "/preview-menu.png"));
        System.out.println("wrote preview-menu.png");

        // ---------------- help ----------------
        bmp = Bitmap.createBitmap(VW, VH, Bitmap.Config.ARGB_8888);
        c = new Canvas(bmp);
        renderer.drawBackground(c);
        ui.dim(c, VW, VH, 150);
        ui.drawHelp(c, VW, VH);
        ArrayList<Ui.Btn> back = new ArrayList<Ui.Btn>();
        back.add(Ui.Btn.box(cx, VH * 0.865f, 340f, 88f, Ui.A_BACK, "بازگشت"));
        ui.drawButtons(c, back);
        ImageIO.write(bmp.image, "png", new File(dir + "/preview-help.png"));
        System.out.println("wrote preview-help.png");

        // ---------------- game over ----------------
        bmp = Bitmap.createBitmap(VW, VH, Bitmap.Config.ARGB_8888);
        c = new Canvas(bmp);
        renderer.drawBackground(c);
        renderer.drawWorld(c, w, 12f);
        ui.dim(c, VW, VH, 170);
        w.score = 184500;
        w.kills = 137;
        w.bestScore = 120000;
        ui.drawGameOver(c, VW, VH, w, true);
        ArrayList<Ui.Btn> over = new ArrayList<Ui.Btn>();
        over.add(Ui.Btn.box(cx - 210f, VH * 0.74f, 380f, 100f, Ui.A_RETRY, Theme.T_RETRY));
        over.add(Ui.Btn.box(cx + 210f, VH * 0.74f, 380f, 100f, Ui.A_MENU, Theme.T_MENU));
        ui.drawButtons(c, over);
        ImageIO.write(bmp.image, "png", new File(dir + "/preview-over.png"));
        System.out.println("wrote preview-over.png");
    }

    static void save(Renderer renderer, Ui ui, World w, String path, float time, boolean controls)
            throws Exception {
        Bitmap bmp = Bitmap.createBitmap(VW, VH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        renderer.drawBackground(c);
        renderer.drawWorld(c, w, time);
        ui.drawHud(c, w, VW, VH, time, Ui.bossAlive(w), Ui.bossHp(w));
        if (controls) {
            ui.drawControls(c, w, VW, VH, time, 300f, 780f, 360f, 700f, true, true, false);
        } else {
            ui.drawControls(c, w, VW, VH, time, 300f, 780f, 360f, 700f, true, true, true);
        }
        ImageIO.write(bmp.image, "png", new File(path));
        System.out.println("wrote " + path + "  score=" + w.score + " wave=" + w.wave
                + " enemies=" + w.enemies.size() + " particles=" + w.particles.size());
    }

    static void bot(World w, Input in, int frame) {
        Player p = w.player;
        Enemy e = w.nearestEnemy(p.x, p.y, 4000f);
        if (e == null) {
            in.mx = 0;
            in.my = 0;
            in.fire = false;
            return;
        }
        float d = Maths.dist(p.x, p.y, e.x, e.y);
        float ang = (float) Math.atan2(e.y - p.y, e.x - p.x);
        float want = ang;
        if (d < 260f) {
            want = ang + (float) Math.PI;
        } else if (d > 520f) {
            want = ang;
        } else {
            want = ang + (float) Math.PI / 2f * (frame % 600 < 300 ? 1f : -1f);
        }
        in.mx = (float) Math.cos(want);
        in.my = (float) Math.sin(want);
        in.fire = true;
        in.special = p.specialCd <= 0 && w.enemies.size() > 3;
    }
}
