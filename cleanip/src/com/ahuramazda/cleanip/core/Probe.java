package com.ahuramazda.cleanip.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Probes a single CDN edge address the same way the tunnel does it:
 * TCP connect &rarr; TLS handshake (SNI + ALPN http/1.1) &rarr; HTTP/1.1 request.
 * The optional second phase downloads a file to measure throughput.
 */
public final class Probe {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; SM-A536B) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/122.0.0.0 Mobile Safari/537.36";

    /** Fingerprints of Iranian block pages / captive portals. */
    private static final String[] BLOCK_MARKERS = {
            "10.10.34.34", "10.10.34.35", "blocked by", "access denied by", "shabgard",
            "مسدود", "فیلتر", "شبکه ملی اطلاعات", "صفحه دسترسی", "filtering.ir",
            "dpi", "content-blocking", "internet.ir",
    };

    private Probe() {
    }

    /** Probes one target; never throws. */
    public static ProbeResult probe(String ip, int port, ScanConfig cfg) {
        ProbeResult result = new ProbeResult(ip, port);
        Socket raw = null;
        try {
            raw = new Socket();
            long t0 = System.nanoTime();
            raw.connect(new InetSocketAddress(ip, port), cfg.timeoutMs);
            raw.setSoTimeout(cfg.timeoutMs);
            raw.setTcpNoDelay(true);
            result.tcpMs = elapsedMs(t0);
            result.tcpOk = true;

            Socket socket = raw;
            if (cfg.isTlsPort(port)) {
                result.setTlsUsed(true);
                socket = wrapTls(raw, port, cfg, result);
                if (!result.tlsOk) {
                    return result;
                }
            }
            result.setHttpCheckUsed(cfg.httpCheck);
            if (cfg.httpCheck) {
                httpProbe(socket, cfg, result);
            } else {
                result.httpOk = true;
            }
            return result;
        } catch (SocketTimeoutException e) {
            result.errorKind = ProbeResult.ERR_TIMEOUT;
            result.errorText = "timeout";
        } catch (SocketException e) {
            result.errorKind = result.tlsOk ? ProbeResult.ERR_HTTP : ProbeResult.ERR_REFUSED;
            result.errorText = message(e);
        } catch (SSLException e) {
            result.errorKind = ProbeResult.ERR_TLS;
            result.errorText = message(e);
        } catch (IOException e) {
            result.errorKind = result.tcpOk ? ProbeResult.ERR_HTTP : ProbeResult.ERR_OTHER;
            result.errorText = message(e);
        } catch (RuntimeException e) {
            result.errorKind = ProbeResult.ERR_OTHER;
            result.errorText = message(e);
        } finally {
            close(raw);
        }
        return result;
    }

    private static SSLSocket wrapTls(Socket raw, int port, ScanConfig cfg, ProbeResult result)
            throws IOException {
        long t0 = System.nanoTime();
        SSLSocketFactory factory = factory(cfg.verifyCert);
        SSLSocket ssl = (SSLSocket) factory.createSocket(raw, cfg.domain, port, true);
        SSLParameters params = ssl.getSSLParameters();
        try {
            List<SNIServerName> names = Collections.<SNIServerName>singletonList(new SNIHostName(cfg.domain));
            params.setServerNames(names);
        } catch (RuntimeException ignored) {
            // older runtimes: the host passed to createSocket() already carries the SNI
        }
        try {
            // the FRONTED-MEEK-CDN-* protocols of the tunnel use ALPN "http/1.1"
            params.setApplicationProtocols(new String[]{"http/1.1"});
        } catch (RuntimeException ignored) {
            // ignore
        }
        // "HTTPS" also verifies that the certificate matches the SNI host
        params.setEndpointIdentificationAlgorithm(cfg.verifyCert ? "HTTPS" : null);
        ssl.setSSLParameters(params);
        try {
            ssl.startHandshake();
        } catch (SSLException e) {
            result.tlsOk = false;
            result.errorKind = ProbeResult.ERR_TLS;
            result.errorText = message(e);
            close(ssl);
            return ssl;
        }
        result.tlsMs = elapsedMs(t0);
        result.tlsOk = true;
        result.certOk = true;
        SSLSession session = ssl.getSession();
        result.tlsVersion = session == null ? "" : session.getProtocol();
        try {
            result.alpn = ssl.getApplicationProtocol();
        } catch (Throwable ignored) {
            result.alpn = "";
        }
        try {
            if (session != null) {
                Certificate[] chain = session.getPeerCertificates();
                if (chain != null && chain.length > 0 && chain[0] instanceof X509Certificate) {
                    X509Certificate x509 = (X509Certificate) chain[0];
                    result.certSubject = shortName(x509.getSubjectDN().getName());
                    result.certIssuer = shortName(x509.getIssuerDN().getName());
                    result.certNotAfter = String.valueOf(x509.getNotAfter());
                    result.certOk = true;
                } else {
                    result.certOk = false;
                }
            }
        } catch (Throwable t) {
            // a certificate that cannot be read is treated like a broken route
            result.certOk = false;
            result.certSubject = "";
        }
        return ssl;
    }

    /** GET / with a short body read; fills status code, headers and Cloudflare markers. */
    private static void httpProbe(Socket socket, ScanConfig cfg, ProbeResult result) throws IOException {
        String request = "GET / HTTP/1.1\r\n"
                + "Host: " + cfg.domain + "\r\n"
                + "User-Agent: " + USER_AGENT + "\r\n"
                + "Accept: */*\r\n"
                + "Accept-Language: en-US,en;q=0.9\r\n"
                + "Accept-Encoding: identity\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        long deadline = System.currentTimeMillis() + cfg.timeoutMs;
        long t0 = System.nanoTime();
        out.write(request.getBytes(UTF8));
        out.flush();

        String statusLine = readLine(in, deadline);
        result.ttfbMs = elapsedMs(t0);
        if (statusLine == null || statusLine.length() == 0) {
            result.errorKind = ProbeResult.ERR_HTTP;
            result.errorText = "no response";
            return;
        }
        result.statusCode = parseStatus(statusLine);
        result.httpOk = result.statusCode > 0;

        List<String> headerLines = new ArrayList<String>();
        int guard = 0;
        while (guard++ < 100) {
            String line = readLine(in, deadline);
            if (line == null || line.length() == 0) {
                break;
            }
            headerLines.add(line);
        }
        for (String line : headerLines) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = line.substring(colon + 1).trim();
            if (key.equals("server")) {
                result.serverName = value;
            } else if (key.equals("cf-ray")) {
                result.cfRay = value;
            } else if (key.equals("content-type")) {
                result.contentType = value;
            }
        }

        // read a little of the body: proves real content, catches block pages
        StringBuilder body = new StringBuilder();
        byte[] buffer = new byte[1024];
        long bodyDeadline = Math.min(deadline, System.currentTimeMillis() + 400);
        while (body.length() < 1800 && System.currentTimeMillis() < bodyDeadline) {
            int read = in.read(buffer);
            if (read <= 0) {
                break;
            }
            result.bodyBytes += read;
            if (body.length() < 1800) {
                body.append(new String(buffer, 0, Math.min(read, 1800 - body.length()), UTF8));
            }
        }
        String lower = body.toString().toLowerCase(Locale.US);
        for (String marker : BLOCK_MARKERS) {
            if (lower.contains(marker.toLowerCase(Locale.US))) {
                result.errorKind = ProbeResult.ERR_BLOCKED;
                result.errorText = "block page";
                result.httpOk = false;
                break;
            }
        }
        if (result.httpOk && result.bodyBytes == 0) {
            // header only replies are still useful (e.g. 301/204)
            result.bodyBytes = 0;
        }
    }

    /**
     * Measures the download speed of one edge.
     *
     * @return the result object with speedBps / speedBytes filled (speedBps = -1 on failure)
     */
    public static ProbeResult speedTest(String ip, int port, ScanConfig cfg, int durationMs) {
        ProbeResult result = new ProbeResult(ip, port);
        Socket raw = null;
        try {
            raw = new Socket();
            raw.connect(new InetSocketAddress(ip, port), cfg.timeoutMs);
            raw.setSoTimeout(Math.max(1000, durationMs));
            raw.setTcpNoDelay(true);
            result.tcpOk = true;
            Socket socket = raw;
            if (cfg.isTlsPort(port)) {
                result.setTlsUsed(true);
                socket = wrapTls(raw, port, cfg, result);
                if (!result.tlsOk) {
                    return result;
                }
            }
            String path = cfg.speedPath();
            String request = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + cfg.domain + "\r\n"
                    + "User-Agent: " + USER_AGENT + "\r\n"
                    + "Accept: */*\r\n"
                    + "Accept-Encoding: identity\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(request.getBytes(UTF8));
            out.flush();

            long start = System.currentTimeMillis();
            long deadline = start + durationMs;
            byte[] buffer = new byte[16384];
            long total = 0;
            while (System.currentTimeMillis() < deadline) {
                int read = in.read(buffer);
                if (read <= 0) {
                    break;
                }
                total += read;
            }
            long spent = Math.max(1, System.currentTimeMillis() - start);
            result.speedMs = (int) spent;
            result.speedBytes = total;
            result.speedBps = total > 0 ? (total * 1000L) / spent : -1;
            result.httpOk = total > 0;
            result.tcpMs = 0;
            result.setHttpCheckUsed(true);
            return result;
        } catch (SocketTimeoutException e) {
            result.errorKind = ProbeResult.ERR_TIMEOUT;
            result.errorText = "timeout";
        } catch (Exception e) {
            result.errorKind = ProbeResult.ERR_OTHER;
            result.errorText = message(e);
        } finally {
            close(raw);
        }
        return result;
    }

    private static String readLine(InputStream in, long deadline) throws IOException {
        StringBuilder sb = new StringBuilder(96);
        while (System.currentTimeMillis() < deadline) {
            int c = in.read();
            if (c == -1) {
                return sb.length() == 0 ? null : sb.toString();
            }
            if (c == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') {
                    sb.setLength(len - 1);
                }
                return sb.toString();
            }
            sb.append((char) c);
            if (sb.length() > 4096) {
                break;
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static int parseStatus(String statusLine) {
        // "HTTP/1.1 403 Forbidden"
        int space = statusLine.indexOf(' ');
        if (space <= 0 || statusLine.length() < space + 4) {
            return -1;
        }
        try {
            return Integer.parseInt(statusLine.substring(space + 1, space + 4).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String shortName(String dn) {
        if (dn == null) {
            return "";
        }
        String[] parts = dn.split(",");
        for (String part : parts) {
            String p = part.trim();
            if (p.startsWith("CN=")) {
                return p.substring(3);
            }
        }
        return parts.length > 0 ? parts[0].trim() : dn;
    }

    private static String message(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.length() == 0) {
            m = t.getClass().getSimpleName();
        }
        return m.length() > 90 ? m.substring(0, 90) : m;
    }

    private static void close(Socket socket) {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // ignore
        }
    }

    private static int elapsedMs(long startNanos) {
        return (int) ((System.nanoTime() - startNanos) / 1000000L);
    }

    private static SSLContext trustAllContext;

    private static SSLSocketFactory factory(boolean verify) throws IOException {
        if (verify) {
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
        try {
            if (trustAllContext == null) {
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, new TrustManager[]{new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[0];
                    }
                }}, new java.security.SecureRandom());
                trustAllContext = context;
            }
            return trustAllContext.getSocketFactory();
        } catch (Exception e) {
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
    }
}
