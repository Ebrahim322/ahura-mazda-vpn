import com.ahuramazda.cleanip.core.Base64Lite;
import com.ahuramazda.cleanip.core.CdnDefaults;
import com.ahuramazda.cleanip.core.ConfigLinks;
import com.ahuramazda.cleanip.core.Ipv4;
import com.ahuramazda.cleanip.core.Probe;
import com.ahuramazda.cleanip.core.ProbeResult;
import com.ahuramazda.cleanip.core.RangeStats;
import com.ahuramazda.cleanip.core.ScanConfig;
import com.ahuramazda.cleanip.core.Scanner;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/**
 * Headless tests for the scanner core: address handling, config links and - most
 * important - a real probe/scanner run against local TLS + plain HTTP servers.
 *
 * Usage: java CoreTest &lt;test-keystore.p12&gt;
 */
public class CoreTest {

    private static int checks;
    private static int failures;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String STORE_PASS = "changeit";

    public static void main(String[] args) throws Exception {
        testIpv4();
        testBase64();
        testConfigLinks();
        testRanges();

        String keystore = args.length > 0 ? args[0] : null;
        if (keystore == null) {
            System.out.println("!! no keystore given, skipping the network tests");
        } else {
            testProbeAgainstTlsServer(keystore);
            testScanner(keystore);
            testPlainHttp();
        }

        System.out.println();
        System.out.println("checks: " + checks + ", failures: " + failures);
        if (failures > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void check(String name, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL  " + name);
        } else {
            System.out.println("ok    " + name);
        }
    }

    private static void checkEquals(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) {
            failures++;
            System.out.println("FAIL  " + name + "  expected=" + expected + " actual=" + actual);
        } else {
            System.out.println("ok    " + name);
        }
        checks++;
    }

    // ------------------------------------------------------------------- tests

    private static void testIpv4() {
        checkEquals("ipv4 format", "104.16.5.9", Ipv4.format(Ipv4.toInt("104.16.5.9")));
        check("ipv4 valid", Ipv4.isValid("1.1.1.1"));
        check("ipv4 invalid letters", !Ipv4.isValid("1.1.1.a"));
        check("ipv4 invalid range", !Ipv4.isValid("256.1.1.1"));
        check("ipv4 invalid parts", !Ipv4.isValid("1.1.1"));
        checkEquals("cidr base", "104.16.0.0", Ipv4.format(Ipv4.cidr("104.16.5.9/13")[0]));
        checkEquals("cidr size", Integer.valueOf(1 << 16), Integer.valueOf(Ipv4.cidr("104.16.0.0/16")[1]));
        checkEquals("cidr cap", Integer.valueOf(Ipv4.MAX_PER_CIDR),
                Integer.valueOf(Ipv4.cidr("104.16.0.0/8")[1]));
        checkEquals("cidr invalid", null, Ipv4.cidr("104.16.0.0/33"));

        List<String> list = Ipv4.parseList("1.1.1.1, 8.8.8.8\n1.1.1.1 bad-input 172.64.0.0/30", 1000);
        checkEquals("parseList entries", Integer.valueOf(6), Integer.valueOf(list.size()));
        check("parseList dedupes", Collections.frequency(list, "1.1.1.1") == 1);
        check("parseList cidr", list.contains("172.64.0.2"));

        List<String> range = Ipv4.parseList("10.0.0.1-10.0.0.4", 100);
        checkEquals("parseList range", Integer.valueOf(4), Integer.valueOf(range.size()));

        List<String> block = new ArrayList<String>();
        for (int i = 0; i < 4; i++) {
            block.add("203.0.113." + i);
        }
        checkEquals("toCidr", "203.0.113.0/30", Ipv4.toCidr(block));
        checkEquals("toCidr singles", "203.0.113.1\n203.0.113.5", Ipv4.toCidr(
                java.util.Arrays.asList("203.0.113.1", "203.0.113.5")));
    }

    private static void testBase64() {
        checkEquals("base64 encode", "aGVsbG8gd29ybGQ=", Base64Lite.encode("hello world"));
        checkEquals("base64 roundtrip", "سلام دنیا", Base64Lite.decode(Base64Lite.encode("سلام دنیا")));
        checkEquals("base64 no padding", "hello", Base64Lite.decode("aGVsbG8"));
        checkEquals("base64 invalid", null, Base64Lite.decode("!!!not-base64!!!"));
    }

    private static void testConfigLinks() {
        List<ProbeResult> results = new ArrayList<ProbeResult>();
        ProbeResult r = new ProbeResult("104.16.5.9", 443);
        r.tcpOk = true;
        r.tlsOk = true;
        r.certOk = true;
        r.httpOk = true;
        r.setHttpCheckUsed(true);
        r.tcpMs = 20;
        r.tlsMs = 60;
        r.ttfbMs = 80;
        r.statusCode = 200;
        r.cfRay = "abc123";
        r.speedBps = 2 * 1024 * 1024;
        results.add(r);

        check("usable result", r.isUsable());
        checkEquals("latency", Integer.valueOf(160), Integer.valueOf(r.latencyMs()));
        checkEquals("grade", Integer.valueOf(0), Integer.valueOf(r.grade()));
        check("ip list", ConfigLinks.ipList(results, true, false).equals("104.16.5.9"));
        check("endpoint list", ConfigLinks.endpointList(results, true).equals("104.16.5.9:443"));

        ConfigLinks.Profile profile = new ConfigLinks.Profile();
        profile.uuid = "b831381d-6324-4d53-ad4f-8cda48b30811";
        profile.path = "/?ed=2560";
        profile.limit = 5;

        String vless = ConfigLinks.buildLink(profile, r, "speed.cloudflare.com");
        check("vless link prefix", vless.startsWith("vless://b831381d-6324-4d53-ad4f-8cda48b30811@104.16.5.9:443?"));
        check("vless has sni", vless.contains("sni=speed.cloudflare.com"));
        check("vless has ws", vless.contains("type=ws"));
        ConfigLinks.Profile parsedVless = ConfigLinks.fromLink(vless);
        check("vless roundtrip uuid", parsedVless != null && parsedVless.uuid.equals(profile.uuid));
        check("vless roundtrip path", parsedVless != null && parsedVless.path.equals("/?ed=2560"));

        profile.protocol = "trojan";
        String trojan = ConfigLinks.buildLink(profile, r, "speed.cloudflare.com");
        check("trojan link prefix", trojan.startsWith("trojan://b831381d-6324-4d53-ad4f-8cda48b30811@104.16.5.9:443?"));
        ConfigLinks.Profile parsedTrojan = ConfigLinks.fromLink(trojan);
        check("trojan roundtrip", parsedTrojan != null && parsedTrojan.protocol.equals("trojan"));

        profile.protocol = "vmess";
        String vmess = ConfigLinks.buildLink(profile, r, "speed.cloudflare.com");
        check("vmess link prefix", vmess.startsWith("vmess://"));
        ConfigLinks.Profile parsedVmess = ConfigLinks.fromLink(vmess);
        check("vmess roundtrip", parsedVmess != null && parsedVmess.uuid.equals(profile.uuid)
                && "speed.cloudflare.com".equals(parsedVmess.sni));

        String all = ConfigLinks.buildAll(profile, results, "speed.cloudflare.com");
        check("buildAll", all.split("\n").length == 1);
        check("plain ip list has one line", ConfigLinks.ipList(results, true, false).split("\n").length == 1);
    }

    private static void testRanges() {
        List<String> targets = CdnDefaults.cloudflareTargets(200, 42L);
        checkEquals("cf targets count", Integer.valueOf(200), Integer.valueOf(targets.size()));
        check("cf targets unique", new java.util.HashSet<String>(targets).size() == 200);
        boolean allValid = true;
        for (String ip : targets) {
            allValid &= Ipv4.isValid(ip);
        }
        check("cf targets valid", allValid);

        ScanConfig config = new ScanConfig();
        check("default ports", config.ports.contains(443) && config.ports.contains(80));
        check("tls port 443", config.isTlsPort(443));
        check("plain port 80", !config.isTlsPort(80));
        checkEquals("speed path for cloudflare", "/__down?bytes=50000000", config.speedPath());
        config.domain = "pypi.org";
        check("speed path for pypi", config.speedPath().contains("pythonhosted") || config.speedPath().contains("six"));
    }

    private static void testProbeAgainstTlsServer(String keystore) throws Exception {
        TestServer server = new TestServer(keystore, 0, false);
        server.start();
        ScanConfig config = new ScanConfig();
        config.domain = "speed.cloudflare.com";
        config.timeoutMs = 3000;
        config.verifyCert = false;

        ProbeResult result = Probe.probe("127.0.0.1", server.port(), config);
        check("probe tcp", result.tcpOk);
        check("probe tls", result.tlsOk);
        check("probe http", result.httpOk);
        checkEquals("probe status", Integer.valueOf(200), Integer.valueOf(result.statusCode));
        checkEquals("probe cf-ray", "test-ray", result.cfRay);
        checkEquals("probe alpn", "http/1.1", result.alpn);
        check("probe usable", result.isUsable());
        check("probe latency sane", result.latencyMs() > 0 && result.latencyMs() < 3000);

        // with certificate verification the self signed test server must be rejected
        config.verifyCert = true;
        ProbeResult verified = Probe.probe("127.0.0.1", server.port(), config);
        check("probe rejects self signed cert", !verified.isUsable());

        // speed test
        config.verifyCert = false;
        ProbeResult speed = Probe.speedTest("127.0.0.1", server.port(), config, 600);
        check("speed test bytes", speed.speedBytes > 100000);
        check("speed test bps", speed.speedBps > 100000);

        // a port nobody listens on must fail cleanly
        ServerSocket socket = new ServerSocket(0);
        int deadPort = socket.getLocalPort();
        socket.close();
        ProbeResult dead = Probe.probe("127.0.0.1", deadPort, config);
        check("dead port not usable", !dead.isUsable());
        check("dead port error", dead.errorKind == ProbeResult.ERR_REFUSED
                || dead.errorKind == ProbeResult.ERR_TIMEOUT || dead.errorKind == ProbeResult.ERR_TLS);

        server.stop();
    }

    private static void testScanner(String keystore) throws Exception {
        TestServer server = new TestServer(keystore, 0, false);
        server.start();
        ServerSocket socket = new ServerSocket(0);
        int deadPort = socket.getLocalPort();
        socket.close();

        ScanConfig config = new ScanConfig();
        config.domain = "speed.cloudflare.com";
        config.timeoutMs = 2000;
        config.verifyCert = false;
        config.speedTest = true;
        config.speedDurationMs = 300;
        config.speedTopN = 1;
        config.threads = 8;

        List<String> ips = new ArrayList<String>();
        ips.add("127.0.0.1");
        List<Integer> ports = new ArrayList<Integer>();
        ports.add(server.port());
        List<Scanner.Target> targets = Scanner.buildTargets(ips, ports, 1L);
        targets.add(new Scanner.Target("127.0.0.1", deadPort));

        final CountDownLatch done = new CountDownLatch(1);
        final List<ProbeResult> collected = Collections.synchronizedList(new ArrayList<ProbeResult>());
        final boolean[] finished = {false};
        final boolean[] speed = {false};
        Scanner scanner = new Scanner(config, targets, new Scanner.Listener() {
            @Override
            public void onPhase(String phase) {
            }

            @Override
            public void onProgress(int doneCount, int total, int alive, long elapsedMs, int rate) {
            }

            @Override
            public void onFound(ProbeResult result) {
                collected.add(result);
            }

            @Override
            public void onFinished(List<ProbeResult> results, boolean cancelled, boolean speedTested) {
                finished[0] = true;
                speed[0] = speedTested;
                collected.clear();
                collected.addAll(results);
                done.countDown();
            }
        });
        scanner.start();
        check("scanner finishes", done.await(30, TimeUnit.SECONDS));
        check("scanner reported finish", finished[0]);
        check("scanner ran the speed test", speed[0]);
        int usable = 0;
        for (ProbeResult result : collected) {
            if (result.isUsable()) {
                usable++;
            }
        }
        checkEquals("scanner result count", Integer.valueOf(2), Integer.valueOf(collected.size()));
        checkEquals("scanner found one usable", Integer.valueOf(1), Integer.valueOf(usable));
        check("scanner sorted usable first", !collected.isEmpty() && collected.get(0).isUsable());
        check("scanner measured speed", !collected.isEmpty() && collected.get(0).speedBps > 0);

        // cancellation has to return quickly
        final CountDownLatch cancelled = new CountDownLatch(1);
        List<Scanner.Target> many = new ArrayList<Scanner.Target>();
        for (int i = 0; i < 200; i++) {
            many.add(new Scanner.Target("10.255.255." + (i % 250 + 1), 443));
        }
        Scanner slow = new Scanner(config, many, new Scanner.Listener() {
            @Override
            public void onPhase(String phase) {
            }

            @Override
            public void onProgress(int d, int t, int a, long e, int r) {
            }

            @Override
            public void onFound(ProbeResult result) {
            }

            @Override
            public void onFinished(List<ProbeResult> results, boolean wasCancelled, boolean speedTested) {
                if (wasCancelled) {
                    cancelled.countDown();
                }
            }
        });
        slow.start();
        Thread.sleep(200);
        slow.cancel();
        check("scanner cancels", cancelled.await(15, TimeUnit.SECONDS));
        server.stop();
    }

    private static void testPlainHttp() throws Exception {
        // 8080 is one of the ports the tunnel may use without TLS (HTTP-OSSH)
        BlockPageServer friendly = new BlockPageServer("hello from a plain http edge");
        try {
            friendly.start(8080);
        } catch (Exception e) {
            System.out.println("!! port 8080 is busy, skipping the plain HTTP checks");
            return;
        }
        ScanConfig config = new ScanConfig();
        config.domain = "speed.cloudflare.com";
        config.timeoutMs = 2000;
        config.verifyCert = false;
        ProbeResult plain = Probe.probe("127.0.0.1", 8080, config);
        check("plain http probe usable", plain.isUsable());
        check("plain http no tls", !plain.tlsUsed);
        checkEquals("plain http status", Integer.valueOf(200), Integer.valueOf(plain.statusCode));
        friendly.stop();
        Thread.sleep(100);

        BlockPageServer blocked = new BlockPageServer("دسترسی به این سایت مسدود می‌باشد - 10.10.34.34");
        try {
            blocked.start(8080);
        } catch (Exception e) {
            System.out.println("!! port 8080 is busy, skipping the block page check");
            return;
        }
        ProbeResult result = Probe.probe("127.0.0.1", 8080, config);
        check("block page rejected", !result.isUsable());
        checkEquals("block page kind", Integer.valueOf(ProbeResult.ERR_BLOCKED),
                Integer.valueOf(result.errorKind));
        blocked.stop();

        List<ProbeResult> results = new ArrayList<ProbeResult>();
        ProbeResult good = new ProbeResult("104.16.1.1", 443);
        good.tcpOk = good.tlsOk = good.certOk = good.httpOk = true;
        good.setHttpCheckUsed(true);
        good.tcpMs = 10;
        good.tlsMs = 20;
        good.ttfbMs = 30;
        results.add(good);
        ProbeResult bad = new ProbeResult("104.16.1.2", 443);
        bad.errorKind = ProbeResult.ERR_TIMEOUT;
        results.add(bad);
        List<RangeStats.Block> blocks = RangeStats.group(results, 24);
        check("range stats groups", !blocks.isEmpty() && blocks.get(0).alive == 1);
    }

    // ------------------------------------------------------------- test servers

    /** Minimal HTTPS server: answers almost anything with 200 and an optional big body. */
    static class TestServer {
        private final String keystore;
        private final int wantedPort;
        private final boolean bigBody;
        private SSLServerSocket server;
        private volatile boolean running = true;
        private Thread thread;

        TestServer(String keystore, int port, boolean bigBody) {
            this.keystore = keystore;
            this.wantedPort = port;
            this.bigBody = bigBody;
        }

        int port() {
            return server.getLocalPort();
        }

        void start() throws Exception {
            char[] pass = STORE_PASS.toCharArray();
            KeyStore store = KeyStore.getInstance("PKCS12");
            InputStream in = new FileInputStream(keystore);
            store.load(in, pass);
            in.close();
            KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            factory.init(store, pass);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(factory.getKeyManagers(), null, null);

            server = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(wantedPort);
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (running) {
                        try {
                            final SSLSocket client = (SSLSocket) server.accept();
                            Thread worker = new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    handle(client);
                                }
                            });
                            worker.setDaemon(true);
                            worker.start();
                        } catch (Exception e) {
                            return;
                        }
                    }
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        private void handle(SSLSocket client) {
            try {
                SSLParameters parameters = client.getSSLParameters();
                parameters.setApplicationProtocols(new String[]{"h2", "http/1.1"});
                client.setSSLParameters(parameters);
                client.startHandshake();
                OutputStream out = client.getOutputStream();
                InputStream in = client.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, UTF8));
                String requestLine = reader.readLine();
                String line;
                while ((line = reader.readLine()) != null && line.length() > 0) {
                    // drain headers
                }
                boolean download = requestLine != null && requestLine.contains("__down");
                byte[] body;
                if (download || bigBody) {
                    body = new byte[700000];
                    java.util.Arrays.fill(body, (byte) 'x');
                } else {
                    body = "hello from the test edge".getBytes(UTF8);
                }
                StringBuilder head = new StringBuilder();
                head.append("HTTP/1.1 200 OK\r\n")
                        .append("Server: cloudflare\r\n")
                        .append("cf-ray: test-ray\r\n")
                        .append("Content-Type: application/octet-stream\r\n")
                        .append("Content-Length: ").append(body.length).append("\r\n")
                        .append("Connection: close\r\n\r\n");
                out.write(head.toString().getBytes(UTF8));
                out.write(body);
                out.flush();
                client.close();
            } catch (Exception ignored) {
                // client went away
            }
        }

        void stop() {
            running = false;
            try {
                server.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    /** Simulates an ISP block page served over plain HTTP. */
    static class BlockPageServer {
        private ServerSocket server;
        private volatile boolean running = true;
        private final String body;

        BlockPageServer(String body) {
            this.body = body;
        }

        int port() {
            return server.getLocalPort();
        }

        void start(int wantedPort) throws Exception {
            server = new ServerSocket(wantedPort);
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (running) {
                        try {
                            Socket client = server.accept();
                            BufferedReader reader = new BufferedReader(
                                    new InputStreamReader(client.getInputStream(), UTF8));
                            String line;
                            while ((line = reader.readLine()) != null && line.length() > 0) {
                                // drain
                            }
                            String head = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: "
                                    + body.getBytes(UTF8).length + "\r\nConnection: close\r\n\r\n";
                            client.getOutputStream().write((head + body).getBytes(UTF8));
                            client.close();
                        } catch (Exception e) {
                            return;
                        }
                    }
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            running = false;
            try {
                server.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }
}
