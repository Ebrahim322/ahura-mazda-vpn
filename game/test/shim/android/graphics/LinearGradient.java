package android.graphics;

/** Preview-only stand-in for android.graphics.LinearGradient. */
public class LinearGradient extends Shader {

    private final float x0, y0, x1, y1;
    private final int c0, c1;

    public LinearGradient(float x0, float y0, float x1, float y1, int c0, int c1, TileMode t) {
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
        this.c0 = c0;
        this.c1 = c1;
    }

    public java.awt.Paint toAwtPaint() {
        return new java.awt.GradientPaint(x0, y0, new java.awt.Color(c0, true), x1, y1,
                new java.awt.Color(c1, true));
    }
}
