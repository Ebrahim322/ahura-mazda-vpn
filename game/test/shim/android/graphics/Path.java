package android.graphics;

/** Preview-only stand-in for android.graphics.Path. */
public class Path {

    private java.awt.geom.GeneralPath p = new java.awt.geom.GeneralPath();

    public void reset() {
        p = new java.awt.geom.GeneralPath();
    }

    public void moveTo(float x, float y) {
        p.moveTo(x, y);
    }

    public void lineTo(float x, float y) {
        p.lineTo(x, y);
    }

    public void close() {
        p.closePath();
    }

    public java.awt.Shape shape() {
        return p;
    }
}
