package android.graphics;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayDeque;

/** Preview-only stand-in for android.graphics.Canvas (drawn with Java2D). */
public class Canvas {

    private final Graphics2D g;
    private final ArrayDeque<AffineTransform> stack = new ArrayDeque<AffineTransform>();

    public Canvas(Bitmap bmp) {
        g = bmp.image.createGraphics();
        hints();
    }

    public Canvas(Graphics2D g) {
        this.g = g;
        hints();
    }

    private void hints() {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    // ---- transforms ----

    public void save() {
        stack.push(g.getTransform());
    }

    public void restore() {
        if (!stack.isEmpty()) {
            g.setTransform(stack.pop());
        }
    }

    public void translate(float dx, float dy) {
        g.translate(dx, dy);
    }

    public void rotate(float deg) {
        g.rotate(Math.toRadians(deg));
    }

    public void rotate(float deg, float px, float py) {
        g.rotate(Math.toRadians(deg), px, py);
    }

    public void scale(float sx, float sy) {
        g.scale(sx, sy);
    }

    // ---- drawing ----

    public void drawColor(int color) {
        Composite old = g.getComposite();
        g.setComposite(AlphaComposite.Src);
        g.setColor(new java.awt.Color(color, true));
        g.fillRect(0, 0, 100000, 100000);
        g.setComposite(old);
    }

    public void drawRect(float l, float t, float r, float b, Paint p) {
        shape(new Rectangle2D.Float(l, t, r - l, b - t), p);
    }

    public void drawRoundRect(float l, float t, float r, float b, float rx, float ry, Paint p) {
        shape(new RoundRectangle2D.Float(l, t, r - l, b - t, rx * 2f, ry * 2f), p);
    }

    public void drawOval(float l, float t, float r, float b, Paint p) {
        shape(new Ellipse2D.Float(l, t, r - l, b - t), p);
    }

    public void drawCircle(float cx, float cy, float radius, Paint p) {
        shape(new Ellipse2D.Float(cx - radius, cy - radius, radius * 2f, radius * 2f), p);
    }

    public void drawLine(float x0, float y0, float x1, float y1, Paint p) {
        shape(new java.awt.geom.Line2D.Float(x0, y0, x1, y1), p);
    }

    public void drawArc(RectF oval, float start, float sweep, boolean useCenter, Paint p) {
        int type = useCenter ? Arc2D.PIE : Arc2D.OPEN;
        // android counts clockwise from 3 o'clock, java2d counter-clockwise
        shape(new Arc2D.Float(oval.left, oval.top, oval.width(), oval.height(),
                -start, -sweep, type), p);
    }

    public void drawArc(float l, float t, float r, float b, float start, float sweep,
                        boolean useCenter, Paint p) {
        drawArc(new RectF(l, t, r, b), start, sweep, useCenter, p);
    }

    public void drawPath(Path path, Paint p) {
        shape(path.shape(), p);
    }

    public void drawText(String text, float x, float y, Paint p) {
        apply(p);
        g.setFont(p.awtFont());
        float w = (float) g.getFontMetrics().getStringBounds(text, g).getWidth();
        float px = x;
        if (p.align == Paint.Align.CENTER) {
            px = x - w * 0.5f;
        } else if (p.align == Paint.Align.RIGHT) {
            px = x - w;
        }
        g.drawString(text, px, y);
    }

    public void drawText(char[] text, int index, int count, float x, float y, Paint p) {
        drawText(new String(text, index, count), x, y, p);
    }

    public void drawBitmap(Bitmap bmp, float left, float top, Paint p) {
        apply(p);
        g.drawImage(bmp.image, Math.round(left), Math.round(top), null);
    }

    public void drawBitmap(Bitmap bmp, RectF src, RectF dst, Paint p) {
        apply(p);
        if (src == null) {
            g.drawImage(bmp.image, Math.round(dst.left), Math.round(dst.top),
                    Math.round(dst.width()), Math.round(dst.height()), null);
        } else {
            int sx = Math.round(src.left);
            int sy = Math.round(src.top);
            int sw = Math.round(src.width());
            int sh = Math.round(src.height());
            g.drawImage(bmp.image, Math.round(dst.left), Math.round(dst.top),
                    Math.round(dst.left + dst.width()), Math.round(dst.top + dst.height()),
                    sx, sy, sx + sw, sy + sh, null);
        }
    }

    // ---- internals ----

    private void shape(java.awt.Shape s, Paint p) {
        if (p.style == Paint.Style.FILL || p.style == Paint.Style.FILL_AND_STROKE) {
            apply(p);
            g.fill(s);
        }
        if (p.style == Paint.Style.STROKE || p.style == Paint.Style.FILL_AND_STROKE) {
            apply(p);
            g.setStroke(p.awtStroke());
            g.draw(s);
        }
    }

    private void apply(Paint p) {
        if (p.shader != null) {
            g.setPaint(p.shader.toAwtPaint());
        } else {
            g.setPaint(new java.awt.Color(p.effColor(), true));
        }
    }

    public Graphics2D graphics() {
        return g;
    }
}
