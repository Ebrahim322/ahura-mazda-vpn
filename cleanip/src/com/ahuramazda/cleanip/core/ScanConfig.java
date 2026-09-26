package com.ahuramazda.cleanip.core;

import java.util.ArrayList;
import java.util.List;

/** Everything the scanner needs to know. */
public final class ScanConfig {

    /** TCP + TLS + HTTP timeout in milliseconds. */
    public int timeoutMs = 1500;
    /** Worker threads used for the probing phase. */
    public int threads = 64;
    /** Fronting domain: used as SNI and as the HTTP Host header. */
    public String domain = "speed.cloudflare.com";
    /** Ports that are probed for every address. */
    public final List<Integer> ports = new ArrayList<Integer>();
    /** Verify the TLS certificate + hostname (what the tunnel does as well). */
    public boolean verifyCert = true;
    /** Run a download test on the best results once the scan is over. */
    public boolean speedTest = true;
    /** Duration of a single download test in milliseconds. */
    public int speedDurationMs = 2500;
    /** How many of the best results are download tested. */
    public int speedTopN = 12;
    /** Path used for the download test; empty = auto (see {@link #speedPath()}). */
    public String speedPathOverride = "";
    /** Request / and read a bit of the body to be sure the edge really answers. */
    public boolean httpCheck = true;
    /** Noise level of the live result feed. */
    public int keepAliveResults = 400;
    /** Random seed for reproducible scans (0 = random). */
    public long seed = 0L;

    public ScanConfig() {
        ports.add(443);
        ports.add(80);
    }

    public boolean isTlsPort(int port) {
        return port != 80 && port != 8080 && port != 8880;
    }

    /** @return the path used to measure download speed. */
    public String speedPath() {
        if (speedPathOverride != null && speedPathOverride.length() > 0) {
            return speedPathOverride.startsWith("/") ? speedPathOverride : "/" + speedPathOverride;
        }
        String d = domain == null ? "" : domain.toLowerCase(java.util.Locale.US);
        if (d.equals("speed.cloudflare.com")) {
            return "/__down?bytes=50000000";
        }
        if (d.equals("pypi.org") || d.equals("files.pythonhosted.org")) {
            // a small wheel that Fastly serves from the edge, ~2 MB
            return "/packages/source/s/six/six-1.16.0.tar.gz";
        }
        if (d.equals("cdn.jsdelivr.net") || d.equals("fastly.jsdelivr.net")) {
            return "/npm/jquery@3.7.1/dist/jquery.min.js";
        }
        if (d.equals("cdnjs.cloudflare.com")) {
            return "/ajax/libs/jquery/3.7.1/jquery.min.js";
        }
        return "/";
    }

    public String portsAsText() {
        StringBuilder sb = new StringBuilder();
        for (Integer p : ports) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(p);
        }
        return sb.toString();
    }
}
