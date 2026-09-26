package com.ahuramazda.cleanip.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns working addresses into the artefacts people actually paste somewhere:
 *
 * <ul>
 *   <li>a plain address list (the "CDN edge IPs" field of the Shir o Khorshid app)</li>
 *   <li>vless / trojan / vmess share links for v2rayNG, NekoBox, Karing, ...</li>
 * </ul>
 */
public final class ConfigLinks {

    /** What the user wants to generate. */
    public static final class Profile {
        public String protocol = "vless";      // vless | trojan | vmess
        public String uuid = "";
        public String network = "ws";           // ws | grpc | tcp | httpupgrade | h2
        public String path = "/?ed=2560";
        public String host = "";                // empty = fronting domain
        public String sni = "";                 // empty = fronting domain
        public String fingerprint = "chrome";
        public String serviceName = "";
        public String alpn = "";
        public String flow = "";
        public String namePrefix = "Ahura-Clean";
        public int limit = 10;                  // how many links to build
    }

    private ConfigLinks() {
    }

    public static String buildLink(Profile p, ProbeResult r, String domain) {
        String host = empty(p.host) ? domain : p.host;
        String sni = empty(p.sni) ? domain : p.sni;
        String name = p.namePrefix + "-" + r.ip + ":" + r.port
                + (r.speedBps > 0 ? "-" + (r.speedBps / 1024) + "K" : "");
        if ("vmess".equals(p.protocol)) {
            StringBuilder json = new StringBuilder();
            json.append('{')
                    .append("\"v\":\"2\",").append("\"ps\":\"").append(jsonEscape(name)).append("\",")
                    .append("\"add\":\"").append(r.ip).append("\",")
                    .append("\"port\":\"").append(r.port).append("\",")
                    .append("\"id\":\"").append(jsonEscape(p.uuid)).append("\",")
                    .append("\"aid\":\"0\",").append("\"scy\":\"auto\",")
                    .append("\"net\":\"").append(jsonEscape(p.network)).append("\",")
                    .append("\"type\":\"none\",")
                    .append("\"host\":\"").append(jsonEscape(host)).append("\",")
                    .append("\"path\":\"").append(jsonEscape(p.path)).append("\",")
                    .append("\"tls\":\"tls\",")
                    .append("\"sni\":\"").append(jsonEscape(sni)).append("\",")
                    .append("\"alpn\":\"").append(jsonEscape(p.alpn)).append("\",")
                    .append("\"fp\":\"").append(jsonEscape(p.fingerprint)).append("\"")
                    .append('}');
            return "vmess://" + Base64Lite.encode(json.toString());
        }

        StringBuilder query = new StringBuilder();
        append(query, "type", p.network);
        append(query, "security", "tls");
        append(query, "sni", sni);
        append(query, "host", host);
        append(query, "path", p.path);
        append(query, "fp", p.fingerprint);
        if (!empty(p.alpn)) {
            append(query, "alpn", p.alpn);
        }
        if ("grpc".equals(p.network) && !empty(p.serviceName)) {
            append(query, "serviceName", p.serviceName);
        }
        if ("vless".equals(p.protocol)) {
            append(query, "encryption", "none");
            if (!empty(p.flow)) {
                append(query, "flow", p.flow);
            }
            return "vless://" + p.uuid + "@" + r.ip + ":" + r.port + query + "#" + encode(name);
        }
        // trojan
        return "trojan://" + encode(p.uuid) + "@" + r.ip + ":" + r.port + query + "#" + encode(name);
    }

    /** Builds the links of the best {@code p.limit} results. */
    public static String buildAll(Profile p, List<ProbeResult> results, String domain) {
        StringBuilder sb = new StringBuilder();
        int built = 0;
        for (ProbeResult r : results) {
            if (!r.isUsable()) {
                continue;
            }
            if (built++ >= Math.max(1, p.limit)) {
                break;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(buildLink(p, r, domain));
        }
        return sb.toString();
    }

    /** Best effort parser for an existing share link, used to pre-fill the dialog. */
    public static Profile fromLink(String link) {
        if (link == null) {
            return null;
        }
        String text = link.trim();
        int newline = text.indexOf('\n');
        if (newline > 0) {
            text = text.substring(0, newline).trim();
        }
        Profile p = new Profile();
        if (text.startsWith("vmess://")) {
            String json = Base64Lite.decode(text.substring("vmess://".length()));
            if (json == null) {
                return null;
            }
            p.protocol = "vmess";
            p.uuid = jsonValue(json, "id");
            p.network = valueOrDefault(jsonValue(json, "net"), "ws");
            p.path = valueOrDefault(jsonValue(json, "path"), "/");
            p.host = jsonValue(json, "host");
            p.sni = valueOrDefault(jsonValue(json, "sni"), p.host);
            p.fingerprint = valueOrDefault(jsonValue(json, "fp"), "chrome");
            p.alpn = jsonValue(json, "alpn");
            return p;
        }
        String scheme;
        if (text.startsWith("vless://")) {
            scheme = "vless";
        } else if (text.startsWith("trojan://")) {
            scheme = "trojan";
        } else {
            return null;
        }
        p.protocol = scheme;
        int hash = text.indexOf('#');
        String withoutName = hash > 0 ? text.substring(0, hash) : text;
        int queryStart = withoutName.indexOf('?');
        String query = queryStart > 0 ? withoutName.substring(queryStart + 1) : "";
        String authority = withoutName.substring(scheme.length() + 3,
                queryStart > 0 ? queryStart : withoutName.length());
        int at = authority.lastIndexOf('@');
        p.uuid = at > 0 ? authority.substring(0, at) : "";
        Map<String, String> params = parseQuery(query);
        p.network = valueOrDefault(params.get("type"), "ws");
        p.path = valueOrDefault(params.get("path"), "/");
        p.host = valueOrDefault(params.get("host"), "");
        p.sni = valueOrDefault(params.get("sni"), p.host);
        p.fingerprint = valueOrDefault(params.get("fp"), "chrome");
        p.alpn = valueOrDefault(params.get("alpn"), "");
        p.serviceName = valueOrDefault(params.get("serviceName"), "");
        p.flow = valueOrDefault(params.get("flow"), "");
        return p;
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        if (query == null || query.isEmpty()) {
            return map;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            map.put(pair.substring(0, eq).toLowerCase(Locale.US), decode(pair.substring(eq + 1)));
        }
        return map;
    }

    private static String jsonValue(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int index = json.indexOf(needle);
        if (index < 0) {
            // numbers are stored without quotes ("port":"443" is a string, but be tolerant)
            needle = "\"" + key + "\":";
            index = json.indexOf(needle);
            if (index < 0) {
                return "";
            }
            int start = index + needle.length();
            int end = start;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
                end++;
            }
            return json.substring(start, end).replace("\"", "").trim();
        }
        int start = index + needle.length();
        int end = json.indexOf('"', start);
        return end < 0 ? "" : json.substring(start, end);
    }

    private static void append(StringBuilder sb, String key, String value) {
        if (value == null) {
            value = "";
        }
        sb.append(sb.length() == 0 ? '?' : '&').append(key).append('=').append(encode(value));
    }

    private static String encode(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        byte[] bytes;
        try {
            bytes = value.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            bytes = value.getBytes();
        }
        for (byte b : bytes) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return sb.toString();
    }

    private static String decode(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length()) {
                try {
                    sb.append((char) Integer.parseInt(value.substring(i + 1, i + 3), 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException ignored) {
                    // keep the raw character
                }
            }
            if (c == '+') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean empty(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String valueOrDefault(String value, String fallback) {
        return empty(value) ? fallback : value;
    }

    /** Plain address list, one per line - what the Psiphon style client wants. */
    public static String ipList(List<ProbeResult> results, boolean usableOnly, boolean withRanges) {
        List<String> ips = new ArrayList<String>();
        for (ProbeResult r : results) {
            if (usableOnly && !r.isUsable()) {
                continue;
            }
            if (!ips.contains(r.ip)) {
                ips.add(r.ip);
            }
        }
        if (withRanges) {
            String cidr = Ipv4.toCidr(ips);
            return cidr;
        }
        StringBuilder sb = new StringBuilder();
        for (String ip : ips) {
            sb.append(ip).append('\n');
        }
        return sb.toString().trim();
    }

    /** Plain ip:port list, handy for other clients. */
    public static String endpointList(List<ProbeResult> results, boolean usableOnly) {
        StringBuilder sb = new StringBuilder();
        for (ProbeResult r : results) {
            if (usableOnly && !r.isUsable()) {
                continue;
            }
            sb.append(r.endPoint()).append('\n');
        }
        return sb.toString().trim();
    }

    public static List<ProbeResult> usable(List<ProbeResult> results) {
        List<ProbeResult> out = new ArrayList<ProbeResult>();
        for (ProbeResult r : results) {
            if (r.isUsable()) {
                out.add(r);
            }
        }
        return out.isEmpty() ? Collections.<ProbeResult>emptyList() : out;
    }
}
