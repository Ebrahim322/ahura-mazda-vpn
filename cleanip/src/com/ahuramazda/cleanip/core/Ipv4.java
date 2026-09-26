package com.ahuramazda.cleanip.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;

/**
 * IPv4 helpers: parsing, CIDR expansion and sanitising of pasted lists.
 *
 * <p>Pure Java, no Android dependency, so it can be unit tested on a desktop JVM.</p>
 */
public final class Ipv4 {

    /** Hard cap for a single CIDR expansion (65 536 addresses). */
    public static final int MAX_PER_CIDR = 1 << 16;
    /** Hard cap for a whole target list. */
    public static final int MAX_TARGETS = 1 << 17;

    private Ipv4() {
    }

    /** @return the address as an int (packed, big endian) or -1 when invalid. */
    public static int toInt(String s) {
        if (s == null) {
            return -1;
        }
        int n = s.length();
        if (n < 7 || n > 15) {
            return -1;
        }
        long v = 0;
        int part = 0;
        int digits = 0;
        int parts = 0;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '.') {
                if (digits == 0) {
                    return -1;
                }
                v = (v << 8) | part;
                parts++;
                if (parts > 3) {
                    return -1;
                }
                part = 0;
                digits = 0;
            } else if (c >= '0' && c <= '9') {
                if (digits == 3) {
                    return -1;
                }
                part = part * 10 + (c - '0');
                digits++;
                if (part > 255) {
                    return -1;
                }
            } else {
                return -1;
            }
        }
        if (digits == 0 || parts != 3) {
            return -1;
        }
        v = (v << 8) | part;
        return (int) v;
    }

    public static boolean isValid(String s) {
        return toInt(s) != -1;
    }

    public static String format(int ip) {
        return ((ip >>> 24) & 0xFF) + "." + ((ip >>> 16) & 0xFF) + "."
                + ((ip >>> 8) & 0xFF) + "." + (ip & 0xFF);
    }

    /** @return the canonical dotted form, or null when the input is not an IPv4 address. */
    public static String normalize(String s) {
        int v = toInt(s);
        return v == -1 ? null : format(v);
    }

    /** @return {networkAddress, addressCount} for "a.b.c.d/len", or null when invalid. */
    public static int[] cidr(String text) {
        if (text == null) {
            return null;
        }
        int slash = text.indexOf('/');
        if (slash <= 0 || slash == text.length() - 1) {
            return null;
        }
        int base = toInt(text.substring(0, slash));
        if (base == -1) {
            return null;
        }
        int prefix;
        try {
            prefix = Integer.parseInt(text.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (prefix < 0 || prefix > 32) {
            return null;
        }
        int mask = prefix == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - prefix) & 0xFFFFFFFFL);
        long count = prefix == 0 ? 4294967296L : (1L << (32 - prefix));
        if (count > MAX_PER_CIDR) {
            count = MAX_PER_CIDR;
        }
        return new int[]{base & mask, (int) count};
    }

    /** Expands a CIDR into single addresses (capped, first N addresses of the block). */
    public static List<String> expandCidr(String text) {
        int[] c = cidr(text);
        if (c == null) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>(Math.min(c[1], MAX_PER_CIDR));
        for (int i = 0; i < c[1]; i++) {
            out.add(format(c[0] + i));
        }
        return out;
    }

    /**
     * Parses a free form list of entries. Accepted, separated by new lines, spaces,
     * commas or semicolons:
     * <ul>
     *   <li>{@code 104.16.5.9}</li>
     *   <li>{@code 104.16.0.0/13} (CIDR, expanded up to {@link #MAX_PER_CIDR})</li>
     *   <li>{@code 104.16.5.9-104.16.5.40} (inclusive range, capped)</li>
     * </ul>
     *
     * @param maxTotal overall cap of the returned list
     */
    public static List<String> parseList(String text, int maxTotal) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        if (text == null) {
            return new ArrayList<String>();
        }
        String[] tokens = text.split("[\\s,;]+");
        for (String token : tokens) {
            String t = token.trim();
            if (t.isEmpty() || out.size() >= maxTotal) {
                continue;
            }
            if (t.indexOf('/') > 0) {
                for (String ip : expandCidr(t)) {
                    if (out.size() >= maxTotal) {
                        break;
                    }
                    out.add(ip);
                }
                continue;
            }
            int dash = t.indexOf('-');
            if (dash > 0 && dash < t.length() - 1) {
                int from = toInt(t.substring(0, dash));
                int to = toInt(t.substring(dash + 1));
                if (from != -1 && to != -1 && to >= from) {
                    long count = (long) to - from + 1;
                    if (count > MAX_PER_CIDR) {
                        count = MAX_PER_CIDR;
                    }
                    for (int i = 0; i < count && out.size() < maxTotal; i++) {
                        out.add(format(from + i));
                    }
                    continue;
                }
            }
            String one = normalize(t);
            if (one != null) {
                out.add(one);
            }
        }
        return new ArrayList<String>(out);
    }

    /** Counts how many addresses a list would expand to, without keeping them. */
    public static int countList(String text) {
        return parseList(text, MAX_TARGETS).size();
    }

    public static List<String> shuffle(List<String> input, Random random) {
        List<String> copy = new ArrayList<String>(input);
        Collections.shuffle(copy, random);
        return copy;
    }

    /** Groups single addresses back into CIDR blocks (only for /24 .. /32 runs). */
    public static String toCidr(List<String> sortedUnique) {
        if (sortedUnique == null || sortedUnique.isEmpty()) {
            return "";
        }
        int[] values = new int[sortedUnique.size()];
        int n = 0;
        for (String s : sortedUnique) {
            int v = toInt(s);
            if (v != -1) {
                values[n++] = v;
            }
        }
        if (n == 0) {
            return "";
        }
        int[] v = new int[n];
        System.arraycopy(values, 0, v, 0, n);
        java.util.Arrays.sort(v);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < n) {
            int start = v[i];
            int end = start;
            while (i + 1 < n && v[i + 1] == end + 1) {
                end = v[++i];
            }
            i++;
            int count = end - start + 1;
            int prefix = -1;
            if (count > 1 && (count & (count - 1)) == 0 && (start & (count - 1)) == 0) {
                prefix = 32;
                for (int rest = count; rest > 1; rest >>= 1) {
                    prefix--;
                }
            }
            if (prefix < 0) {
                for (int k = start; k <= end; k++) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(format(k));
                }
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(format(start)).append('/').append(prefix);
        }
        return sb.toString();
    }
}
