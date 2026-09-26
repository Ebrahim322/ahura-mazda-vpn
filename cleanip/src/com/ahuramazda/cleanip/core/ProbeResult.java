package com.ahuramazda.cleanip.core;

/** Result of probing a single {@code ip:port} pair. */
public final class ProbeResult {

    /** Error kinds, translated to Persian by the UI. */
    public static final int OK = 0;
    public static final int ERR_TIMEOUT = 1;
    public static final int ERR_REFUSED = 2;
    public static final int ERR_TLS = 3;
    public static final int ERR_HTTP = 4;
    public static final int ERR_BLOCKED = 5;
    public static final int ERR_OTHER = 6;

    public final String ip;
    public final int port;

    public int errorKind = ERR_OTHER;
    public String errorText = "";

    /** TCP connect time in milliseconds. */
    public int tcpMs = -1;
    /** TLS handshake time in milliseconds (-1 when the port is not TLS). */
    public int tlsMs = -1;
    /** Time from sending the request until the first response byte. */
    public int ttfbMs = -1;

    public boolean tcpOk;
    public boolean tlsOk;
    public boolean certOk;
    public boolean httpOk;

    public int statusCode = -1;
    public String serverName = "";
    public String cfRay = "";
    public String contentType = "";
    public int bodyBytes;
    public String tlsVersion = "";
    public String alpn = "";
    public String certSubject = "";
    public String certIssuer = "";
    public String certNotAfter = "";

    /** Download speed in bytes per second (-1 = not measured). */
    public long speedBps = -1;
    public long speedBytes = -1;
    public int speedMs = -1;

    public ProbeResult(String ip, int port) {
        this.ip = ip;
        this.port = port;
    }

    /** A route the tunnel can actually use: TCP + TLS (+ HTTP check) all fine. */
    public boolean isUsable() {
        if (!tcpOk) {
            return false;
        }
        if (tlsUsed && !tlsOk) {
            return false;
        }
        if (tlsOk && !certOk) {
            return false;
        }
        if (httpUsed && !httpOk) {
            return false;
        }
        return true;
    }

    /** Whether this target was probed over TLS (and therefore had to handshake). */
    public boolean tlsUsed;
    /** Whether an HTTP request was part of the probe. */
    public boolean httpUsed;

    public void setTlsUsed(boolean used) {
        tlsUsed = used;
    }

    public void setHttpCheckUsed(boolean used) {
        httpUsed = used;
    }

    /** Total time until the CDN answered, the main ranking value. */
    public int latencyMs() {
        if (!isUsable()) {
            return Integer.MAX_VALUE;
        }
        int total = Math.max(tcpMs, 0);
        if (tlsMs > 0) {
            total += tlsMs;
        }
        if (ttfbMs > 0) {
            total += ttfbMs;
        }
        return total;
    }

    /** Ranking score: latency first, a confirmed Cloudflare edge and speed help. */
    public long score() {
        if (!isUsable()) {
            return Long.MAX_VALUE;
        }
        long score = latencyMs();
        if (isCloudflareEdge()) {
            score -= 25;
        }
        if (speedBps > 0) {
            score -= Math.min(150, speedBps / 20000);
        }
        return Math.max(1, score);
    }

    public boolean isCloudflareEdge() {
        return cfRay != null && cfRay.length() > 0
                || (serverName != null && serverName.toLowerCase(java.util.Locale.US).contains("cloudflare"));
    }

    public boolean isFastlyEdge() {
        String s = serverName == null ? "" : serverName.toLowerCase(java.util.Locale.US);
        return s.contains("fastly") || s.contains("varnish") || s.contains("ghost");
    }

    /** 0 = excellent .. 3 = poor, -1 = dead. */
    public int grade() {
        if (!isUsable()) {
            return -1;
        }
        int l = latencyMs();
        if (l < 400) {
            return 0;
        }
        if (l < 900) {
            return 1;
        }
        if (l < 1800) {
            return 2;
        }
        return 3;
    }

    public String endPoint() {
        return ip + ":" + port;
    }

    /** "104.16.5.9:443" plus measured values, tab separated (used for exports). */
    public String toExportLine() {
        return ip + "\t" + port + "\t" + (isUsable() ? "ok" : "fail") + "\t"
                + (latencyMs() == Integer.MAX_VALUE ? "" : latencyMs() + "ms") + "\t"
                + (speedBps > 0 ? (speedBps / 1024) + "KB/s" : "") + "\t"
                + (isCloudflareEdge() ? "cloudflare" : (isFastlyEdge() ? "fastly" : "")) + "\t"
                + errorText;
    }

    @Override
    public String toString() {
        return endPoint() + " " + (isUsable() ? "ok " + latencyMs() + "ms" : "fail " + errorText);
    }
}
