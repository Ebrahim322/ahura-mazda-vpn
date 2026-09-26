package android.graphics;

import java.awt.image.BufferedImage;

/** Preview-only stand-in for android.graphics.Bitmap (backed by a BufferedImage). */
public class Bitmap {

    public enum Config { ALPHA_8, RGB_565, ARGB_4444, ARGB_8888 }

    public BufferedImage image;

    private Bitmap(BufferedImage image) {
        this.image = image;
    }

    public static Bitmap createBitmap(int w, int h, Config c) {
        int type = c == Config.RGB_565 ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB;
        return new Bitmap(new BufferedImage(Math.max(1, w), Math.max(1, h), type));
    }

    public static Bitmap wrap(BufferedImage image) {
        return new Bitmap(image);
    }

    public void recycle() {
    }

    public int getWidth() {
        return image.getWidth();
    }

    public int getHeight() {
        return image.getHeight();
    }
}
