package com.ahuramazda.cleanip.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;

/** Small Base64 implementation (no Android dependency, works on the desktop JVM too). */
public final class Base64Lite {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    private Base64Lite() {
    }

    public static String encode(String text) {
        if (text == null) {
            return "";
        }
        byte[] data = text.getBytes(UTF8);
        StringBuilder sb = new StringBuilder(((data.length + 2) / 3) * 4);
        int i = 0;
        while (i + 2 < data.length) {
            int chunk = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            sb.append(ALPHABET[(chunk >>> 18) & 63]).append(ALPHABET[(chunk >>> 12) & 63])
                    .append(ALPHABET[(chunk >>> 6) & 63]).append(ALPHABET[chunk & 63]);
            i += 3;
        }
        int rest = data.length - i;
        if (rest == 1) {
            int chunk = (data[i] & 0xFF) << 16;
            sb.append(ALPHABET[(chunk >>> 18) & 63]).append(ALPHABET[(chunk >>> 12) & 63]).append("==");
        } else if (rest == 2) {
            int chunk = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            sb.append(ALPHABET[(chunk >>> 18) & 63]).append(ALPHABET[(chunk >>> 12) & 63])
                    .append(ALPHABET[(chunk >>> 6) & 63]).append('=');
        }
        return sb.toString();
    }

    /** @return the decoded text, or null when the input is not valid Base64. */
    public static String decode(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.trim().replace("\n", "").replace("\r", "").replace(" ", "");
        int padding = 0;
        while (cleaned.endsWith("=")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
            padding++;
        }
        cleaned = cleaned.replace('-', '+').replace('_', '/');
        ByteArrayOutputStream out = new ByteArrayOutputStream(cleaned.length() * 3 / 4);
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < cleaned.length(); i++) {
            int value = index(cleaned.charAt(i));
            if (value < 0) {
                return null;
            }
            buffer = (buffer << 6) | value;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >>> bits) & 0xFF);
            }
        }
        if (padding > 2) {
            return null;
        }
        return new String(out.toByteArray(), UTF8);
    }

    private static int index(char c) {
        if (c >= 'A' && c <= 'Z') {
            return c - 'A';
        }
        if (c >= 'a' && c <= 'z') {
            return c - 'a' + 26;
        }
        if (c >= '0' && c <= '9') {
            return c - '0' + 52;
        }
        if (c == '+') {
            return 62;
        }
        if (c == '/') {
            return 63;
        }
        return -1;
    }
}
