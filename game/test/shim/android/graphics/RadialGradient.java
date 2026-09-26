package android.graphics;

/** Preview-only stand-in for android.graphics.RadialGradient. */
public class RadialGradient extends Shader {

    private final float x, y, r;
    private final int c0, c1;

    public RadialGradient(float x, float y, float r, int c0, int c1, TileMode t) {
        this.x = x;
        this.y = y;
        this.r = r;
        this.c0 = c0;
        this.c1 = c1;
    }

    public java.awt.Paint toAwtPaint() {
        float[] fr = {0f, 1f};
        java.awt.Color[] cols = {new java.awt.Color(c0, true), new java.awt.Color(c1, true)};
        return new java.awt.RadialGradientPaint(x, y, r, fr, cols);
    }
}
