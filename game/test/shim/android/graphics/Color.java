package android.graphics;

/** Preview-only stand-in for android.graphics.Color. */
public class Color {

    public static int argb(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int rgb(int r, int g, int b) {
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }
}
