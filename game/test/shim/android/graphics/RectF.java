package android.graphics;

/** Preview-only stand-in for android.graphics.RectF. */
public class RectF {

    public float left, top, right, bottom;

    public RectF() {
    }

    public RectF(float l, float t, float r, float b) {
        set(l, t, r, b);
    }

    public void set(float l, float t, float r, float b) {
        left = l;
        top = t;
        right = r;
        bottom = b;
    }

    public float width() {
        return right - left;
    }

    public float height() {
        return bottom - top;
    }
}
