package com.ahuramazda.war;

/** Palette and Persian UI strings. */
public final class Theme {

    private Theme() {
    }

    // ground
    public static final int SAND_TOP = 0xff6d5b3d;
    public static final int SAND_BOT = 0xff453925;
    public static final int SAND_SPOT = 0xff826c45;
    public static final int SAND_DARK = 0xff332a1b;
    public static final int GRID = 0x14ffffff;

    // player
    public static final int OLIVE = 0xff6d7a45;
    public static final int OLIVE_DARK = 0xff3a4126;
    public static final int OLIVE_LIGHT = 0xff98a568;
    public static final int TRACK = 0xff20231a;

    // enemy
    public static final int RUST = 0xff8d4a35;
    public static final int RUST_DARK = 0xff4d2a1e;
    public static final int RUST_LIGHT = 0xffb9705a;
    public static final int STEEL = 0xff6a6f78;
    public static final int STEEL_DARK = 0xff34383e;

    // fx
    public static final int CYAN = 0xff7fe9ff;
    public static final int GOLD = 0xffe0a63c;
    public static final int FIRE = 0xffff8a3c;
    public static final int FIRE_HOT = 0xffffe6a8;
    public static final int RED = 0xffdc4d4d;
    public static final int GREEN = 0xff7ddc7a;
    public static final int SHIELD = 0xff59d8ff;
    public static final int SMOKE = 0xff4a4238;

    // ui
    public static final int TEXT = 0xfff0e2c0;
    public static final int TEXT_DIM = 0xffb09b74;
    public static final int PANEL = 0xd816130d;
    public static final int PANEL_EDGE = 0xffe0a63c;
    public static final int BTN = 0xe63a2f22;
    public static final int BTN_HI = 0xffe0a63c;
    public static final int SHADOW = 0x66000000;

    // ---- strings (Persian) ----
    public static final String T_TITLE = "نبرد اهورا";
    public static final String T_SUB = "AHURA  WAR";
    public static final String T_START = "شروع نبرد";
    public static final String T_HELP = "راهنما";
    public static final String T_RETRY = "دوباره";
    public static final String T_MENU = "منوی اصلی";
    public static final String T_CONTINUE = "ادامه";
    public static final String T_RESTART = "شروع دوباره";
    public static final String T_PAUSE = "توقف";
    public static final String T_OVER = "پایان نبرد";
    public static final String T_SCORE = "امتیاز";
    public static final String T_BEST = "بهترین";
    public static final String T_WAVE = "موج";
    public static final String T_KILLS = "کشته";
    public static final String T_RECORD = "رکورد جدید!";
    public static final String T_HEALTH = "سلامت";
    public static final String T_AIR = "هجوم هوایی";
    public static final String T_WAVE_DONE = "موج تمام شد";
    public static final String T_BOSS = "هشدار: فرمانده دشمن";
    public static final String T_TAP = "برای شروع ضربه بزن";

    public static final String[] HELP = {
            "ناحیه چپ صفحه را لمس کن و بکش تا تانک حرکت کند",
            "سمت راست صفحه را نگه دار تا شلیک کنی",
            "دکمه هوایی، پشتیبانی هوایی می‌آورد (هر ۱۵ ثانیه)",
            "جعبه سبز = درمان | آبی = سپر | زرد = تیر بیشتر",
            "بنفش = سه‌تیر همزمان | سفید = بمب اتمی",
            "بشکه‌های قرمز را بزن تا دشمنان اطراف نابود شوند",
            "هر ۵ موج یک فرمانده دشمن می‌آید"
    };
}
