package com.ahuramazda.war.sim;

/** Small math helpers shared by the simulation. */
public final class Maths {

    private Maths() {
    }

    public static float clamp(float v, float a, float b) {
        return v < a ? a : (v > b ? b : v);
    }

    public static float len(float x, float y) {
        return (float) Math.sqrt(x * x + y * y);
    }

    public static float dist(float ax, float ay, float bx, float by) {
        float dx = ax - bx;
        float dy = ay - by;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Shortest signed difference between two angles, in (-PI, PI]. */
    public static float angDiff(float a, float b) {
        return (float) (((b - a + Math.PI * 3.0) % (Math.PI * 2.0)) - Math.PI);
    }

    /** Rotate angle a towards b by at most maxStep radians. */
    public static float angTowards(float a, float b, float maxStep) {
        float d = angDiff(a, b);
        if (d > maxStep) {
            d = maxStep;
        }
        if (d < -maxStep) {
            d = -maxStep;
        }
        return a + d;
    }

    public static float smoothstep(float t) {
        t = clamp(t, 0f, 1f);
        return t * t * (3f - 2f * t);
    }
}
