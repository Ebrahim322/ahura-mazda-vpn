package com.ahuramazda.cleanip;

import android.content.Context;

import com.ahuramazda.cleanip.core.ProbeResult;
import com.ahuramazda.cleanip.core.Scanner;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/** Keeps the last scan in memory (and on disk) so it survives switching screens. */
public final class Store {

    private static final String FILE = "last-scan.tsv";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static volatile List<ProbeResult> results = new ArrayList<ProbeResult>();
    public static volatile String domain = "";
    public static volatile long timestamp;
    public static volatile boolean speedTested;
    public static volatile int scannedCount;

    /** Handed over from the setup screen to the scan screen. */
    public static volatile com.ahuramazda.cleanip.core.ScanConfig pendingConfig;
    public static volatile List<Scanner.Target> pendingTargets;

    private Store() {
    }

    public static boolean hasResults() {
        return !results.isEmpty();
    }

    public static List<ProbeResult> usable() {
        List<ProbeResult> out = new ArrayList<ProbeResult>();
        for (ProbeResult r : results) {
            if (r.isUsable()) {
                out.add(r);
            }
        }
        return out;
    }

    public static void save(Context context, List<ProbeResult> list, String domain, int scanned,
                            boolean speedTested) {
        results = new ArrayList<ProbeResult>(list);
        Store.domain = domain;
        Store.timestamp = System.currentTimeMillis();
        Store.scannedCount = scanned;
        Store.speedTested = speedTested;
        write(context);
    }

    /** @return true when something was loaded */
    public static boolean load(Context context) {
        File file = new File(context.getFilesDir(), FILE);
        if (!file.exists()) {
            return false;
        }
        List<ProbeResult> list = new ArrayList<ProbeResult>();
        BufferedReader reader = null;
        String loadedDomain = "";
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), UTF8));
            String header = reader.readLine();
            if (header != null && header.startsWith("domain=")) {
                loadedDomain = header.substring("domain=".length()).trim();
            }
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t", -1);
                if (parts.length < 9) {
                    continue;
                }
                ProbeResult r = new ProbeResult(parts[0], parseInt(parts[1]));
                r.tcpMs = parseInt(parts[2]);
                r.tlsMs = parseInt(parts[3]);
                r.ttfbMs = parseInt(parts[4]);
                r.statusCode = parseInt(parts[5]);
                r.speedBps = parseLong(parts[6]);
                r.tcpOk = "1".equals(parts[7]);
                r.tlsOk = r.tcpOk;
                r.certOk = r.tcpOk;
                r.httpOk = r.statusCode > 0;
                r.setHttpCheckUsed(true);
                r.serverName = parts[8];
                if (parts.length > 9) {
                    r.cfRay = parts[9];
                }
                list.add(r);
            }
        } catch (Exception ignored) {
            return false;
        } finally {
            close(reader);
        }
        if (list.isEmpty()) {
            return false;
        }
        Scanner.sort(list);
        results = list;
        domain = loadedDomain;
        return true;
    }

    private static void write(Context context) {
        File file = new File(context.getFilesDir(), FILE);
        OutputStreamWriter writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(file), UTF8);
            writer.write("domain=" + domain + "\n");
            int limit = 0;
            for (ProbeResult r : results) {
                if (limit++ > 3000) {
                    break;
                }
                writer.write(r.ip + "\t" + r.port + "\t" + r.tcpMs + "\t" + r.tlsMs + "\t" + r.ttfbMs
                        + "\t" + r.statusCode + "\t" + r.speedBps + "\t" + (r.isUsable() ? "1" : "0")
                        + "\t" + safe(r.serverName) + "\t" + safe(r.cfRay) + "\n");
            }
        } catch (Exception ignored) {
            // not being able to cache is not fatal
        } finally {
            close(writer);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\t', ' ');
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return -1;
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return -1;
        }
    }

    private static void close(java.io.Closeable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (Exception ignored) {
            // ignore
        }
    }
}
