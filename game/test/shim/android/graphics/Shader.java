package android.graphics;

/** Preview-only stand-in for android.graphics.Shader. */
public abstract class Shader {

    public enum TileMode { CLAMP, REPEAT, MIRROR }

    public abstract java.awt.Paint toAwtPaint();
}
