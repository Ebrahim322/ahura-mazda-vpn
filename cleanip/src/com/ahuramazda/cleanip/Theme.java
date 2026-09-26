package com.ahuramazda.cleanip;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.View;

/** Colour palette + drawable helpers for the programmatic UI. */
public final class Theme {

    public static final int BG = 0xFF0A0E16;
    public static final int CARD = 0xFF131A26;
    public static final int CARD_SOFT = 0xFF1A2233;
    public static final int STROKE = 0xFF232F42;
    public static final int GOLD = 0xFFE9C46A;
    public static final int GOLD_DEEP = 0xFFB98F35;
    public static final int TEXT = 0xFFE9EFF8;
    public static final int TEXT_DIM = 0xFF94A3B8;
    public static final int OK = 0xFF3FD68F;
    public static final int WARN = 0xFFF0B84E;
    public static final int BAD = 0xFFEF5D6B;
    public static final int BLUE = 0xFF52A9F2;
    public static final int ON_GOLD = 0xFF14100A;

    private Theme() {
    }

    public static int dp(Context context, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }

    public static GradientDrawable rounded(int color, int radiusDp, int strokeColor, Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        if (strokeColor != 0) {
            drawable.setStroke(Math.max(1, dp(context, 1)), strokeColor);
        }
        return drawable;
    }

    public static GradientDrawable card(Context context) {
        return rounded(CARD, 18, STROKE, context);
    }

    public static GradientDrawable pill(int color, Context context) {
        return rounded(color, 40, 0, context);
    }

    /** Background that changes a bit when pressed / selected. */
    public static StateListDrawable selectable(Context context, int normalColor, int selectedColor) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_selected}, rounded(selectedColor, 14, 0, context));
        states.addState(new int[]{android.R.attr.state_pressed}, rounded(selectedColor, 14, 0, context));
        states.addState(new int[]{}, rounded(normalColor, 14, STROKE, context));
        return states;
    }

    public static Typeface bold() {
        return Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
    }

    public static Typeface mono() {
        return Typeface.MONOSPACE;
    }

    public static int alpha(int color, float factor) {
        int a = Math.round(Color.alpha(color) * factor);
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }

    public static int gradeColor(int grade) {
        switch (grade) {
            case 0:
                return OK;
            case 1:
                return 0xFF9BE07A;
            case 2:
                return WARN;
            case 3:
                return BAD;
            default:
                return TEXT_DIM;
        }
    }

    public static void setRtl(View view) {
        view.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    }
}
