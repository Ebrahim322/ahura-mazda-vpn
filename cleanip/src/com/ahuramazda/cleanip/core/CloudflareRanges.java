package com.ahuramazda.cleanip.core;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/** Downloads the live Cloudflare range list, with the built-in copy as fallback. */
public final class CloudflareRanges {

    /** @return valid CIDR entries; never empty (falls back to {@link CdnDefaults#CLOUDFLARE_V4}). */
    public static List<String> fetch(int timeoutMs) {
        List<String> parsed = download(CdnDefaults.CLOUDFLARE_RANGE_URL, timeoutMs);
        if (parsed.isEmpty()) {
            return CdnDefaults.cloudflareRanges();
        }
        return parsed;
    }

    /** @return the parsed entries, or an empty list on any error. */
    public static List<String> download(String url, int timeoutMs) {
        List<String> out = new ArrayList<String>();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setRequestProperty("User-Agent", "ahura-cleanip");
            connection.setInstanceFollowRedirects(true);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                return out;
            }
            InputStream in = connection.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, Charset.forName("UTF-8")));
            String line;
            while ((line = reader.readLine()) != null) {
                String entry = line.trim();
                if (entry.isEmpty() || entry.startsWith("#")) {
                    continue;
                }
                if (Ipv4.cidr(entry) != null) {
                    out.add(entry);
                }
            }
            reader.close();
        } catch (Throwable ignored) {
            return out;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return out;
    }
}
