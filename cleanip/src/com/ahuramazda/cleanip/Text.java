package com.ahuramazda.cleanip;

/** All Persian UI strings in one place (the code builds its views programmatically). */
public final class Text {

    public static final String APP_NAME = "آی‌پی تمیز اهورا";
    public static final String VERSION = "نسخهٔ ۱٫۰";
    public static final String SUBTITLE = "پیدا کردن آی‌پی‌های سالم CDN برای برنامهٔ شیر و خورشید و کلاینت‌های v2ray";

    // sections
    public static final String SEC_DOMAIN = "۱. دامنهٔ فرانتینگ (SNI)";
    public static final String DOMAIN_HINT = "speed.cloudflare.com";
    public static final String DOMAIN_NOTE =
            "این دامنه هم برای SNI در دست‌دادن TLS و هم در سرصفحهٔ Host استفاده می‌شود؛ دقیقاً همان دامنه‌ای که در کانفیگ/اپ استفاده می‌کنید.";

    public static final String SEC_SOURCE = "۲. منبع آی‌پی‌ها";
    public static final String SRC_CF = "رنج‌های کلادفلر";
    public static final String SRC_MANUAL = "لیست دستی";
    public static final String SRC_DOMAIN = "حول یک دامنه";
    public static final String CF_COUNT = "تعداد نمونه:";
    public static final String CF_LIVE = "دریافت تازه‌ترین رنج‌ها از کلادفلر (نیاز به اینترنت)";
    public static final String CF_NOTE =
            "آی‌پی‌ها به‌صورت تصادفی از همهٔ رنج‌ها برداشته می‌شوند تا شانس پیدا کردن لبهٔ باز بیشتر شود.";
    public static final String MANUAL_HINT =
            "هر خط یک آی‌پی یا CIDR یا بازه:\n104.16.0.0/13\n172.64.5.20\n23.215.0.206\n1.1.1.1-1.1.1.40";
    public static final String MANUAL_NOTE =
            "هر آی‌پی، CIDR یا بازه‌ای که دارید را اینجا بچسبانید؛ خروجی سالم‌ها را همان‌جا می‌توانید کپی کنید.";
    public static final String PASTE = "چسباندن";
    public static final String CLEAR = "پاک کردن";
    public static final String DOMAIN_MODE_NOTE =
            "آی‌پی‌های دامنه با DNS پیدا می‌شوند و بعد تمام /۲۴ اطراف آن‌ها اسکن می‌شود (برای پیدا کردن همسایه‌های تمیز همان لبه).";
    public static final String REF_IP = "آی‌پی مرجع (اختیاری)";
    public static final String RESOLVE = "پیدا کردن آی‌پی دامنه";
    public static final String RADIUS = "محدودهٔ اسکن:";
    public static final String RADIUS_24 = "‏/۲۴ (۲۵۶)";
    public static final String RADIUS_23 = "‏/۲۳ (۵۱۲)";
    public static final String RADIUS_22 = "‏/۲۲ (۱۰۲۴)";
    public static final String RESOLVED = "آی‌پی‌های دامنه: %s";
    public static final String RESOLVE_FAILED = "دامنه با DNS حل نشد. اگر DNS ایرانی نتیجه نمی‌دهد، در کادر بالا آی‌پی مرجع را دستی وارد کنید.";
    public static final String RESOLVING = "در حال حل دامنه…";

    public static final String SEC_PORTS = "۳. پورت‌ها";
    public static final String PORTS_NOTE =
            "برنامهٔ شیر و خورشید در حالت فرانتینگ CDN از ۴۴۳ (OSSH روی TLS) و ۸۰ (HTTP-OSSH) استفاده می‌کند؛ بقیهٔ پورت‌ها برای کلاینت‌های دیگر مفیدند.";

    public static final String SEC_ADVANCED = "۴. تنظیمات پیشرفته";
    public static final String TIMEOUT = "مهلت هر تست:";
    public static final String THREADS = "تعداد هم‌زمانی:";
    public static final String VERIFY_CERT = "بررسی گواهی TLS و نام میزبان (مثل خود تانل)";
    public static final String SPEED_TEST = "تست سرعت دانلود روی نتایج برتر";
    public static final String SPEED_TOP = "تعداد نمونه برای تست سرعت:";
    public static final String SPEED_DURATION = "مدت هر تست سرعت:";
    public static final String SPEED_PATH = "مسیر فایل تست سرعت (خالی = خودکار)";
    public static final String RESET_DEFAULTS = "بازگرداندن پیش‌فرض‌ها";

    public static final String START = "شروع اسکن";
    public static final String LAST_RESULTS = "آخرین نتایج";
    public static final String HELP = "راهنمای شیر و خورشید";
    public static final String ABOUT = "درباره";
    public static final String READY = "آمادهٔ اسکن";
    public static final String TARGETS = "تعداد تست: %s";

    // scan screen
    public static final String PHASE_PROBE = "مرحلهٔ ۱ از ۲ — تست اتصال و TLS";
    public static final String PHASE_SPEED = "مرحلهٔ ۲ از ۲ — تست سرعت دانلود";
    public static final String PHASE_DONE = "اسکن تمام شد";
    public static final String PHASE_CANCELLED = "اسکن متوقف شد";
    public static final String TESTED = "تست‌شده";
    public static final String ALIVE = "سالم";
    public static final String RATE = "سرعت تست";
    public static final String ELAPSED = "زمان";
    public static final String ETA = "باقی‌مانده";
    public static final String STOP = "توقف";
    public static final String COPY_IPS = "کپی آی‌پی‌ها";
    public static final String COPY_ENDPOINTS = "کپی آی‌پی:پورت";
    public static final String COPY_SNI = "کپی دامنه (SNI)";
    public static final String COPY_REPORT = "کپی گزارش کامل";
    public static final String CONFIGS = "کانفیگ‌ها";
    public static final String SHARE = "اشتراک";
    public static final String SAVE = "ذخیره در Downloads";
    public static final String SPEED_BTN = "تست سرعت";
    public static final String ONLY_ALIVE = "فقط سالم‌ها";
    public static final String BLOCKS = "خلاصهٔ رنج‌ها";
    public static final String NO_RESULT_YET = "هنوز موردی پیدا نشده… شکیبا باشید.";
    public static final String NO_ALIVE = "هیچ آی‌پی سالمی پیدا نشد. دامنهٔ دیگری امتحان کنید یا رنج‌های بیشتری اسکن کنید.";
    public static final String RESULTS_COUNT = "%s سالم از %s تست";
    public static final String TAP_TO_COPY = "برای کپی روی هر سطر بزنید";

    // grades
    public static final String G0 = "عالی";
    public static final String G1 = "خوب";
    public static final String G2 = "متوسط";
    public static final String G3 = "ضعیف";
    public static final String G_DEAD = "بی‌پاسخ";

    // errors
    public static final String E_TIMEOUT = "بی‌پاسخ (timeout)";
    public static final String E_REFUSED = "بسته/رد شد";
    public static final String E_TLS = "خطای TLS";
    public static final String E_HTTP = "پاسخ ناقص";
    public static final String E_BLOCKED = "صفحهٔ مسدودسازی";
    public static final String E_OTHER = "خطای دیگر";

    // toasts / dialogs
    public static final String COPIED = "در کلیپ‌بورد کپی شد";
    public static final String NOTHING_TO_COPY = "چیزی برای کپی نیست";
    public static final String EMPTY_CLIPBOARD = "کلیپ‌بورد خالی است";
    public static final String EMPTY_LIST = "لیست آی‌پی خالی است";
    public static final String INVALID_DOMAIN = "دامنهٔ معتبر وارد کنید (مثلاً speed.cloudflare.com)";
    public static final String INVALID_IP = "آی‌پی مرجع معتبر نیست";
    public static final String SAVED_TO = "ذخیره شد: %s";
    public static final String SAVE_FAILED = "ذخیره نشد: %s";
    public static final String NEED_PERMISSION = "برای ذخیره در Downloads دسترسی ذخیره‌سازی لازم است";
    public static final String FETCHING_RANGES = "در حال گرفتن رنج‌های کلادفلر…";
    public static final String RANGES_OK = "رنج‌ها به‌روز شد: %s رنج";
    public static final String RANGES_FALLBACK = "لیست پیش‌فرض استفاده شد (کلادفلر در دسترس نبود)";

    // speed units
    public static final String BITES = "بایت";

    // config dialog
    public static final String CFG_TITLE = "ساخت کانفیگ از آی‌پی‌های سالم";
    public static final String CFG_NOTE =
            "این کانفیگ‌ها برای v2rayNG، NekoBox، Karing و… هستند. برای شیر و خورشید همان «کپی آی‌پی‌ها» و «کپی دامنه» کافی است.";
    public static final String CFG_PROTOCOL = "پروتکل:";
    public static final String CFG_UUID = "UUID (vless/vmess) یا گذرواژه (trojan)";
    public static final String CFG_NETWORK = "شبکه:";
    public static final String CFG_PATH = "مسیر (path)";
    public static final String CFG_HOST = "Host (خالی = دامنهٔ فرانتینگ)";
    public static final String CFG_SNI = "SNI (خالی = دامنهٔ فرانتینگ)";
    public static final String CFG_FP = "اثر انگشت (fp)";
    public static final String CFG_ALPN = "ALPN";
    public static final String CFG_SERVICE = "serviceName (برای gRPC)";
    public static final String CFG_COUNT = "تعداد کانفیگ:";
    public static final String CFG_PRESET = "پرکردن از کلیپ‌بورد";
    public static final String CFG_PRESET_BPB = "پیش‌فرض BPB";
    public static final String CFG_BUILD = "ساخت و کپی";
    public static final String CFG_NO_UUID = "UUID/گذرواژه را وارد کنید";
    public static final String CFG_BUILT = "%s کانفیگ ساخته و کپی شد";
    public static final String CFG_NOTHING = "هیچ آی‌پی سالمی برای ساخت کانفیگ نیست";
    public static final String CFG_PARSE_FAILED = "لینک کانفیگ در کلیپ‌بورد پیدا نشد";
    public static final String CFG_PARSED = "فیلدها از لینک پر شد";

    // help dialog
    public static final String HELP_TITLE = "اتصال با آی‌پی‌های تمیز در شیر و خورشید";
    public static final String HELP_BODY =
            "۱) در شیر و خورشید وارد Options شوید و سپس More Options را بزنید.\n\n"
            + "۲) گزینهٔ «انتخاب پروتکل» را روی «فرانتینگ CDN» بگذارید (در حالت خودکار هم این تنظیمات اعمال می‌شود).\n\n"
            + "۳) در بخش «فرانتینگ CDN» روی «ای‌پی‌های لبه CDN» بزنید و آی‌پی‌هایی را که این برنامه پیدا کرده پیست کنید؛ "
            + "با کاما، فاصله یا خط جدید جدا شوند. همان لحظه که آی‌پی تمیز پیدا کردید، دکمهٔ «کپی آی‌پی‌ها» را بزنید.\n\n"
            + "۴) در «نام‌های میزبان SNI برای CDN» دامنهٔ فرانتینگ را پیست کنید (دکمهٔ «کپی دامنه»).\n\n"
            + "۵) برگردید به صفحهٔ اصلی و دکمهٔ اتصال را بزنید. چند ثانیه صبر کنید تا مسیر سالم انتخاب شود.\n\n"
            + "نکته: اگر اتصال برقرار شد ولی سرعت پایین بود، آی‌پی‌های بعدی لیست را امتحان کنید یا اسکن را با دامنهٔ دیگری تکرار کنید. "
            + "برای پورت ۸۰ کافی است همان آی‌پی‌ها را استفاده کنید.";

    // about
    public static final String ABOUT_BODY =
            "این برنامه آی‌پی‌های لبهٔ CDN (کلادفلر، فستلی و…) را مثل خود تانل تست می‌کند: "
            + "اتصال TCP، دست‌دادن TLS با SNI و ALPN http/1.1، سپس یک درخواست HTTP و در صورت تمایل تست سرعت دانلود.\n\n"
            + "خروجی: لیست آی‌پی سالم برای فیلد «ای‌پی‌های لبه CDN» و دامنه برای فیلد SNI، "
            + "به‌همراه کانفیگ‌های vless/vmess/trojan.\n\n"
            + "هیچ داده‌ای از دستگاه شما جایی فرستاده نمی‌شود؛ همهٔ تست‌ها مستقیم از خود گوشی انجام می‌شوند.";

    // protocol / source labels
    public static final String PROTO_VLESS = "VLESS";
    public static final String PROTO_TROJAN = "Trojan";
    public static final String PROTO_VMESS = "VMess";
    public static final String NET_WS = "ws";
    public static final String NET_GRPC = "grpc";
    public static final String NET_TCP = "tcp";
    public static final String NET_HU = "httpupgrade";

    private Text() {
    }

    /** Converts the digits of a string to Persian digits. */
    public static String fa(Object value) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c >= '0' && c <= '9' ? (char) ('۰' + (c - '0')) : c);
        }
        return sb.toString();
    }

    public static String elapsed(long ms) {
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return fa(minutes + ":" + (seconds < 10 ? "0" + seconds : String.valueOf(seconds)));
    }

    public static String bytes(long value) {
        if (value < 0) {
            return "—";
        }
        if (value < 1024) {
            return fa(value) + " بایت";
        }
        if (value < 1024 * 1024) {
            return fa(value / 1024) + " کیلوبایت";
        }
        return fa(value / (1024 * 1024)) + " مگابایت";
    }

    public static String speed(long bytesPerSecond) {
        if (bytesPerSecond <= 0) {
            return "—";
        }
        double kb = bytesPerSecond / 1024.0;
        if (kb < 1000) {
            return fa(Math.round(kb)) + " KB/s";
        }
        String value = String.format(java.util.Locale.US, "%.1f", kb / 1024.0);
        return fa(value) + " MB/s";
    }

    public static String grade(int grade) {
        switch (grade) {
            case 0:
                return G0;
            case 1:
                return G1;
            case 2:
                return G2;
            case 3:
                return G3;
            default:
                return G_DEAD;
        }
    }

    public static String error(int kind) {
        switch (kind) {
            case com.ahuramazda.cleanip.core.ProbeResult.ERR_TIMEOUT:
                return E_TIMEOUT;
            case com.ahuramazda.cleanip.core.ProbeResult.ERR_REFUSED:
                return E_REFUSED;
            case com.ahuramazda.cleanip.core.ProbeResult.ERR_TLS:
                return E_TLS;
            case com.ahuramazda.cleanip.core.ProbeResult.ERR_HTTP:
                return E_HTTP;
            case com.ahuramazda.cleanip.core.ProbeResult.ERR_BLOCKED:
                return E_BLOCKED;
            default:
                return E_OTHER;
        }
    }
}
