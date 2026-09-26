package android.graphics;

/** Preview-only stand-in for android.graphics.Typeface. */
public class Typeface {

    public static final Typeface DEFAULT = new Typeface(0);
    public static final Typeface DEFAULT_BOLD = new Typeface(1);
    public static final Typeface MONOSPACE = new Typeface(2);

    public final int style;

    private Typeface(int style) {
        this.style = style;
    }
}
