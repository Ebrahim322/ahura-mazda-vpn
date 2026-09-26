package com.ahuramazda.cleanip.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs the probing (+ optional speed test) phase with a small thread pool. */
public final class Scanner {

    /** Callbacks, always fired from worker threads - the UI has to post them. */
    public interface Listener {
        void onPhase(String phase);

        void onProgress(int done, int total, int alive, long elapsedMs, int rate);

        void onFound(ProbeResult result);

        void onFinished(List<ProbeResult> results, boolean cancelled, boolean speedTested);
    }

    /** A single candidate. */
    public static final class Target {
        public final String ip;
        public final int port;

        public Target(String ip, int port) {
            this.ip = ip;
            this.port = port;
        }
    }

    private final ScanConfig config;
    private final List<Target> targets;
    private final Listener listener;
    private final List<ProbeResult> alive = Collections.synchronizedList(new ArrayList<ProbeResult>());
    /** Every probed target (dead ones included, capped) so the UI can explain failures. */
    private final List<ProbeResult> all = Collections.synchronizedList(new ArrayList<ProbeResult>());
    private final AtomicInteger done = new AtomicInteger();
    private final AtomicInteger found = new AtomicInteger();
    private volatile boolean cancelled;
    private volatile boolean running;
    private volatile boolean speedTested;
    private ExecutorService pool;
    private Thread controller;

    public Scanner(ScanConfig config, List<Target> targets, Listener listener) {
        this.config = config;
        this.targets = targets;
        this.listener = listener;
    }

    public static List<Target> buildTargets(List<String> ips, List<Integer> ports, long seed) {
        List<Target> out = new ArrayList<Target>(ips.size() * ports.size());
        for (String ip : ips) {
            for (Integer port : ports) {
                out.add(new Target(ip, port));
            }
        }
        Collections.shuffle(out, new Random(seed == 0L ? System.nanoTime() : seed));
        return out;
    }

    public int totalTargets() {
        return targets.size();
    }

    public boolean isRunning() {
        return running;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        controller = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runScan();
                } catch (Throwable t) {
                    if (listener != null) {
                        listener.onFinished(new ArrayList<ProbeResult>(all), cancelled, speedTested);
                    }
                } finally {
                    running = false;
                }
            }
        }, "cleanip-scanner");
        controller.start();
    }

    public void cancel() {
        cancelled = true;
        ExecutorService local = pool;
        if (local != null) {
            local.shutdownNow();
        }
    }

    private void runScan() {
        final int total = targets.size();
        final int threads = Math.max(4, Math.min(256, config.threads));
        pool = Executors.newFixedThreadPool(threads, new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "cleanip-probe-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
        if (listener != null) {
            listener.onPhase("probe");
        }
        final long start = System.currentTimeMillis();
        final long[] lastReport = {0L};
        final int reportEvery = Math.max(1, total / 200);

        for (final Target target : targets) {
            if (cancelled) {
                break;
            }
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    if (cancelled) {
                        return;
                    }
                    ProbeResult result;
                    try {
                        result = Probe.probe(target.ip, target.port, config);
                    } catch (Throwable t) {
                        result = new ProbeResult(target.ip, target.port);
                        result.errorKind = ProbeResult.ERR_OTHER;
                        result.errorText = String.valueOf(t);
                    }
                    int count = done.incrementAndGet();
                    if (result.isUsable()) {
                        found.incrementAndGet();
                        alive.add(result);
                        all.add(result);
                        if (listener != null) {
                            listener.onFound(result);
                        }
                    } else if (all.size() < 20000) {
                        all.add(result);
                    }
                    long now = System.currentTimeMillis();
                    if (listener != null
                            && (count % reportEvery == 0 || now - lastReport[0] > 150 || count == total)) {
                        lastReport[0] = now;
                        long spent = Math.max(1, now - start);
                        int rate = (int) (count * 1000L / spent);
                        listener.onProgress(count, total, found.get(), spent, rate);
                    }
                }
            });
        }

        pool.shutdown();
        try {
            while (!pool.awaitTermination(200, TimeUnit.MILLISECONDS)) {
                if (cancelled) {
                    pool.shutdownNow();
                }
                if (controller != null && controller.isInterrupted()) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        List<ProbeResult> sorted = new ArrayList<ProbeResult>(all);
        sort(sorted);

        if (!cancelled && config.speedTest && !sorted.isEmpty()) {
            speedTested = true;
            if (listener != null) {
                listener.onPhase("speed");
            }
            runSpeedTests(sorted);
            sort(sorted);
        }

        if (listener != null) {
            long spent = Math.max(1, System.currentTimeMillis() - start);
            listener.onProgress(total, total, found.get(), spent, (int) (total * 1000L / spent));
            listener.onFinished(sorted, cancelled, speedTested);
        }
    }

    private void runSpeedTests(List<ProbeResult> sorted) {
        int count = Math.min(config.speedTopN, sorted.size());
        int threads = Math.max(2, Math.min(8, config.threads / 8));
        ExecutorService pool = Executors.newFixedThreadPool(threads, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "cleanip-speed");
                t.setDaemon(true);
                return t;
            }
        });
        for (int i = 0; i < count; i++) {
            final ProbeResult candidate = sorted.get(i);
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        ProbeResult speed = Probe.speedTest(candidate.ip, candidate.port, config,
                                config.speedDurationMs);
                        if (speed.speedBps > 0) {
                            candidate.speedBps = speed.speedBps;
                            candidate.speedBytes = speed.speedBytes;
                            candidate.speedMs = speed.speedMs;
                        } else {
                            candidate.speedBps = 0L;
                        }
                    } catch (Throwable ignored) {
                        candidate.speedBps = 0L;
                    }
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(Math.max(5000, config.speedDurationMs * 4L), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pool.shutdownNow();
    }

    /** Fastest first; dead or unverified routes sink to the bottom. */
    public static void sort(List<ProbeResult> results) {
        Collections.sort(results, new Comparator<ProbeResult>() {
            @Override
            public int compare(ProbeResult a, ProbeResult b) {
                boolean ua = a.isUsable();
                boolean ub = b.isUsable();
                if (ua != ub) {
                    return ua ? -1 : 1;
                }
                long sa = a.score();
                long sb = b.score();
                if (sa != sb) {
                    return sa < sb ? -1 : 1;
                }
                return a.endPoint().compareTo(b.endPoint());
            }
        });
    }
}
