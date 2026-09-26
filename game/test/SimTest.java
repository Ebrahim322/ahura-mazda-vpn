import com.ahuramazda.war.sim.Blast;
import com.ahuramazda.war.sim.Bullet;
import com.ahuramazda.war.sim.Enemy;
import com.ahuramazda.war.sim.Event;
import com.ahuramazda.war.sim.Input;
import com.ahuramazda.war.sim.Maths;
import com.ahuramazda.war.sim.Obstacle;
import com.ahuramazda.war.sim.Particle;
import com.ahuramazda.war.sim.Player;
import com.ahuramazda.war.sim.PowerUp;
import com.ahuramazda.war.sim.World;

/**
 * Headless sanity test for the battle simulation. Runs on a plain JVM - the sim
 * package has no dependency on Android.
 */
public class SimTest {

    static int failures = 0;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) {
        System.out.println("== idle test (player does nothing) ==");
        World w = new World();
        w.rnd.setSeed(1234);
        Input idle = new Input();
        float dt = 1f / 60f;
        for (int i = 0; i < 60 * 60; i++) {
            w.update(dt, idle);
        }
        System.out.println("  hp=" + w.player.hp + " alive=" + w.player.alive + " state=" + w.state
                + " wave=" + w.wave + " enemies=" + w.enemies.size());
        check("idle player loses hp or dies", !w.player.alive || w.player.hp < Player.MAX_HP);
        check("simulation is still finite (no NaN)",
                Float.isFinite(w.player.x) && Float.isFinite(w.player.y) && Float.isFinite(w.player.vx));

        System.out.println("== bot test (60s of play) ==");
        World g = new World();
        g.rnd.setSeed(7);
        Input in = new Input();
        long t0 = System.nanoTime();
        int frames = 60 * 60;
        int maxEnemies = 0;
        int maxParticles = 0;
        for (int i = 0; i < frames; i++) {
            botInput(g, in, i);
            g.update(dt, in);
            if (g.enemies.size() > maxEnemies) {
                maxEnemies = g.enemies.size();
            }
            if (g.particles.size() > maxParticles) {
                maxParticles = g.particles.size();
            }
            if (g.state == World.ST_GAME_OVER) {
                break;
            }
        }
        long ms = (System.nanoTime() - t0) / 1000000L;
        System.out.println("  score=" + g.score + " wave=" + g.wave + " kills=" + g.kills
                + " hp=" + g.player.hp + " state=" + g.state + " events=" + g.events.size());
        System.out.println("  maxEnemies=" + maxEnemies + " maxParticles=" + maxParticles
                + " sim time for " + frames + " frames = " + ms + "ms ("
                + (ms * 1000f / frames) + " us/frame)");
        check("bot scores points", g.score > 0);
        check("bot kills enemies", g.kills > 5);
        check("waves progress", g.wave >= 2);
        check("bot survives 60s of play", g.state != World.ST_GAME_OVER);
        check("sim is fast enough for 60fps", ms * 1000f / frames < 2000f);
        check("event queue is drained by the consumer", drainEvents(g) > 0);

        System.out.println("== boss wave test ==");
        World b = new World();
        b.rnd.setSeed(99);
        b.startWave(5);
        boolean sawBoss = false;
        for (int i = 0; i < 60 * 40; i++) {
            botInput(b, in, i);
            b.update(dt, in);
            for (int k = 0; k < b.enemies.size(); k++) {
                if (b.enemies.get(k).type == Enemy.T_BOSS) {
                    sawBoss = true;
                }
            }
            if (b.state == World.ST_GAME_OVER) {
                break;
            }
        }
        System.out.println("  wave=" + b.wave + " score=" + b.score + " kills=" + b.kills);
        check("boss spawns on wave 5", sawBoss);
        check("bot clears the boss wave", b.wave > 5);

        System.out.println("== nuke / powerup test ==");
        World n = new World();
        n.rnd.setSeed(5);
        for (int i = 0; i < 60 * 8; i++) {
            n.update(dt, idle);
        }
        int before = n.enemies.size();
        n.nuke();
        n.update(dt, idle);
        System.out.println("  enemies " + before + " -> " + n.enemies.size());
        check("nuke clears the field", n.enemies.size() == 0);

        System.out.println(failures == 0 ? "\nALL TESTS PASSED" : "\n" + failures + " TEST(S) FAILED");
        if (failures > 0) {
            System.exit(1);
        }
    }

    /** A very dumb bot: orbit the nearest enemy, keep shooting, use the airstrike. */
    static void botInput(World w, Input in, int frame) {
        Player p = w.player;
        Enemy e = w.nearestEnemy(p.x, p.y, 4000f);
        if (e == null) {
            in.mx = 0;
            in.my = 0;
            in.fire = false;
            in.special = false;
            return;
        }
        float d = Maths.dist(p.x, p.y, e.x, e.y);
        float ang = (float) Math.atan2(e.y - p.y, e.x - p.x);
        float wantAng = ang;
        if (d < 260f) {
            wantAng = ang + (float) Math.PI;
        } else if (d > 520f) {
            wantAng = ang;
        } else {
            wantAng = ang + (float) Math.PI / 2f * (frame % 600 < 300 ? 1f : -1f);
        }
        in.mx = (float) Math.cos(wantAng);
        in.my = (float) Math.sin(wantAng);
        in.fire = true;
        in.special = p.specialCd <= 0 && w.enemies.size() > 3;
    }

    static int drainEvents(World w) {
        int n = w.events.size();
        w.events.clear();
        return n;
    }
}
