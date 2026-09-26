package com.ahuramazda.cleanip.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Built-in defaults: Cloudflare edge ranges, fronting domains and ports. */
public final class CdnDefaults {

    /** Official Cloudflare IPv4 ranges (fallback copy of https://www.cloudflare.com/ips-v4/). */
    public static final String[] CLOUDFLARE_V4 = {
            "173.245.48.0/20",
            "103.21.244.0/22",
            "103.22.200.0/22",
            "103.31.4.0/22",
            "141.101.64.0/18",
            "108.162.192.0/18",
            "190.93.240.0/20",
            "188.114.96.0/20",
            "197.234.240.0/22",
            "198.41.128.0/17",
            "162.158.0.0/15",
            "104.16.0.0/13",
            "104.24.0.0/14",
            "172.64.0.0/13",
            "131.0.72.0/22",
    };

    /** The ranges that Iranian users report as reachable most of the time. */
    public static final String[] CLOUDFLARE_POPULAR = {
            "104.16.0.0/13",
            "104.24.0.0/14",
            "172.64.0.0/13",
            "162.158.0.0/15",
            "188.114.96.0/20",
            "198.41.128.0/17",
    };

    public static final String CLOUDFLARE_RANGE_URL = "https://www.cloudflare.com/ips-v4/";

    /** Fronting (SNI) domains that are known to work well for CDN fronting. */
    public static final String[] DOMAIN_PRESETS = {
            "speed.cloudflare.com",
            "pypi.org",
            "cdn.jsdelivr.net",
            "cdnjs.cloudflare.com",
    };

    /** Ports used by the Psiphon style CDN fronting: 443 = OSSH over TLS, 80 = HTTP-OSSH. */
    public static final int[] PORTS_USED_BY_FRONTING = {443, 80};

    /** Optional extra TCP ports of the big CDNs (Cloudflare/Fastly). */
    public static final int[] PORTS_EXTRA = {8443, 2053, 2083, 2096, 8080, 8880};

    private CdnDefaults() {
    }

    public static List<String> cloudflareRanges() {
        return new ArrayList<String>(Arrays.asList(CLOUDFLARE_V4));
    }

    public static List<String> popularRanges() {
        return new ArrayList<String>(Arrays.asList(CLOUDFLARE_POPULAR));
    }

    /**
     * Builds a Cloudflare edge candidate list.
     *
     * @param count how many addresses should be produced
     * @param seed  random seed used to spread the addresses over all ranges
     */
    public static List<String> cloudflareTargets(int count, long seed) {
        return sampleFromRanges(cloudflareRanges(), count, seed);
    }

    /** Picks {@code count} unique addresses spread over the given CIDR ranges. */
    public static List<String> sampleFromRanges(List<String> source, int count, long seed) {
        java.util.Random random = new java.util.Random(seed == 0L ? System.nanoTime() : seed);
        List<int[]> blocks = new ArrayList<int[]>();
        long total = 0;
        if (source != null) {
            for (String range : source) {
                int[] c = Ipv4.cidr(range);
                if (c == null || c[1] <= 0) {
                    continue;
                }
                blocks.add(c);
                total += c[1];
            }
        }
        List<String> out = new ArrayList<String>(Math.max(0, count));
        if (blocks.isEmpty() || total <= 0) {
            return out;
        }
        java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<String>();
        int guard = 0;
        while (unique.size() < count && guard++ < count * 25 + 100) {
            long pick = (long) (random.nextDouble() * total);
            int[] block = null;
            for (int[] candidate : blocks) {
                if (pick < candidate[1]) {
                    block = candidate;
                    break;
                }
                pick -= candidate[1];
            }
            if (block == null) {
                continue;
            }
            // pick a host inside the block (keep it on a /24 when the block allows it)
            long offset = pick;
            if (block[1] >= 256) {
                offset = (pick & ~0xFFL) | (1 + random.nextInt(254));
                if (offset >= block[1]) {
                    offset = pick;
                }
            }
            unique.add(Ipv4.format(block[0] + (int) offset));
        }
        out.addAll(unique);
        Collections.shuffle(out, random);
        return out;
    }
}
