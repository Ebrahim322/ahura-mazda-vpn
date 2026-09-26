package android.graphics;

import java.awt.BasicStroke;
import java.awt.Font;

/** Preview-only stand-in for android.graphics.Paint. */
public class Paint {

    public enum Align { LEFT, CENTER, RIGHT }

    public enum Style { FILL, STROKE, FILL_AND_STROKE }

    public enum Cap { BUTT, ROUND, SQUARE }

    public static final int ANTI_ALIAS_FLAG = 1;

    private int color = 0xff000000;
    private int alpha = 255;
    private boolean alphaSet;
    public Align align = Align.LEFT;
    public Style style = Style.FILL;
    public Cap cap = Cap.BUTT;
    public float strokeWidth = 1f;
    public float textSize = 14f;
    public Shader shader;
    public Typeface typeface = Typeface.DEFAULT;
    public int flags;

    public Paint() {
    }

    public Paint(int flags) {
        this.flags = flags;
    }

    public void setColor(int c) {
        color = c;
    }

    public void setAlpha(int a) {
        alpha = a < 0 ? 0 : (a > 255 ? 255 : a);
        alphaSet = true;
    }

    public int getAlpha() {
        return alphaSet ? alpha : (color >>> 24);
    }

    public void setStyle(Style s) {
        style = s;
    }

    public void setStrokeWidth(float w) {
        strokeWidth = w < 0.05f ? 0.05f : w;
    }

    public void setStrokeCap(Cap c) {
        cap = c;
    }

    public void setTextAlign(Align a) {
        align = a;
    }

    public void setTextSize(float s) {
        textSize = s;
    }

    public void setTypeface(Typeface t) {
        typeface = t;
    }

    public void setShader(Shader s) {
        shader = s;
    }

    public void setAntiAlias(boolean b) {
    }

    public void setLinearText(boolean b) {
    }

    public void setShadowLayer(float r, float dx, float dy, int c) {
    }

    /** Effective ARGB, mimicking "setAlpha replaces the colour alpha". */
    public int effColor() {
        int a = alphaSet ? alpha : (color >>> 24);
        int ca = color >>> 24;
        if (alphaSet && ca < a) {
            a = ca;
        }
        return (a << 24) | (color & 0x00ffffff);
    }

    public java.awt.BasicStroke awtStroke() {
        int c = cap == Cap.ROUND ? BasicStroke.CAP_ROUND : BasicStroke.CAP_BUTT;
        return new BasicStroke(strokeWidth, c, BasicStroke.JOIN_ROUND);
    }

    public Font awtFont() {
        int style = typeface != null && typeface.style == 1 ? Font.BOLD : Font.PLAIN;
        return new Font(Font.SANS_SERIF, style, Math.round(textSize));
    }
}
