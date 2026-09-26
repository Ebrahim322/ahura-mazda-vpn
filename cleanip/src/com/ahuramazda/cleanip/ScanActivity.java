package com.ahuramazda.cleanip;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.ahuramazda.cleanip.core.ConfigLinks;
import com.ahuramazda.cleanip.core.Probe;
import com.ahuramazda.cleanip.core.ProbeResult;
import com.ahuramazda.cleanip.core.ScanConfig;
import com.ahuramazda.cleanip.core.Scanner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Runs the scan, shows the live feed and exports the winners. */
public class ScanActivity extends Activity implements Scanner.Listener {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<ProbeResult> pending = Collections.synchronizedList(new ArrayList<ProbeResult>());
    private boolean flushScheduled;
    private boolean speedRunning;

    private ScanConfig config;
    private Scanner scanner;
    private ResultAdapter adapter;

    private TextView phaseText;
    private TextView testedValue;
    private TextView aliveValue;
    private TextView rateValue;
    private TextView timeValue;
    private TextView hintText;
    private ProgressBar progress;
    private Button stopButton;
    private Widgets.Chip onlyAliveChip;

    private long startedAt;
    private int total;
    private int alive;
    private boolean finished;
    private boolean speedTested;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Theme.BG);
        getWindow().setNavigationBarColor(Theme.BG);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        config = Store.pendingConfig != null ? Store.pendingConfig : new Prefs(this).loadConfig();
        setContentView(buildUi());

        List<Scanner.Target> targets = Store.pendingTargets;
        if (targets != null && !targets.isEmpty()) {
            Store.pendingTargets = null;
            Store.domain = config.domain;
            Store.results = new ArrayList<ProbeResult>();
            Store.speedTested = false;
            total = targets.size();
            startedAt = System.currentTimeMillis();
            phaseText.setText(Text.PHASE_PROBE);
            scanner = new Scanner(config, targets, this);
            scanner.start();
        } else {
            finished = true;
            speedTested = Store.speedTested;
            if (!Store.results.isEmpty()) {
                adapter.setAll(Store.results);
                total = Store.scannedCount > 0 ? Store.scannedCount : Store.results.size();
                alive = Store.usable().size();
                phaseText.setText(Text.PHASE_DONE + " — " + Text.fa(alive) + " سالم از "
                        + Text.fa(Store.scannedCount) + " آی‌پی");
                progress.setProgress(100);
                updateStats(total, alive, 0, 0);
                stopButton.setText("بازگشت");
                stopButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        finish();
                    }
                });
            } else {
                phaseText.setText(Text.READY);
                hintText.setText(Text.NO_RESULT_YET);
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (scanner != null && scanner.isRunning()) {
            scanner.cancel();
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ UI

    private View buildUi() {
        LinearLayout root = Widgets.column(this);
        int pad = Theme.dp(this, 12);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Theme.BG);

        LinearLayout header = Widgets.row(this);
        phaseText = Widgets.text(this, Text.READY, 14f, Theme.GOLD, true);
        phaseText.setLayoutParams(Widgets.weighted());
        stopButton = Widgets.button(this, Text.STOP, false);
        header.addView(phaseText);
        header.addView(stopButton);
        root.addView(header);

        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (finished) {
                    finish();
                } else if (scanner != null) {
                    scanner.cancel();
                    phaseText.setText(Text.PHASE_CANCELLED);
                }
            }
        });

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgress(0);
        progress.setLayoutParams(Widgets.matchWidth(this, 10));
        root.addView(progress);

        LinearLayout stats = Widgets.row(this);
        stats.setPadding(0, pad, 0, pad);
        testedValue = addStat(stats, Text.TESTED);
        aliveValue = addStat(stats, Text.ALIVE);
        rateValue = addStat(stats, Text.RATE);
        timeValue = addStat(stats, Text.ELAPSED);
        root.addView(stats);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(Theme.dp(this, 6));
        list.setCacheColorHint(0);
        list.setBackgroundColor(Theme.BG);
        adapter = new ResultAdapter(this, true);
        list.setAdapter(adapter);
        list.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(list);

        hintText = Widgets.text(this, Text.TAP_TO_COPY, 11f, Theme.TEXT_DIM, false);
        root.addView(hintText);

        List<Widgets.Chip> actions = new ArrayList<Widgets.Chip>();
        onlyAliveChip = new Widgets.Chip(this, Text.ONLY_ALIVE, "onlyAlive");
        onlyAliveChip.setSelectedChip(true);
        actions.add(onlyAliveChip);
        actions.add(new Widgets.Chip(this, Text.COPY_IPS, "copyIps"));
        actions.add(new Widgets.Chip(this, Text.COPY_SNI, "copySni"));
        actions.add(new Widgets.Chip(this, Text.COPY_ENDPOINTS, "copyEndpoints"));
        actions.add(new Widgets.Chip(this, Text.CONFIGS, "configs"));
        actions.add(new Widgets.Chip(this, Text.SPEED_BTN, "speed"));
        actions.add(new Widgets.Chip(this, Text.BLOCKS, "blocks"));
        actions.add(new Widgets.Chip(this, Text.SAVE, "save"));
        actions.add(new Widgets.Chip(this, Text.SHARE, "share"));
        actions.add(new Widgets.Chip(this, Text.COPY_REPORT, "report"));

        final Widgets.Chip copyIpsChip = actions.get(1);
        copyIpsChip.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                String text = ConfigLinks.ipList(adapter.items(), true, true);
                if (text.isEmpty()) {
                    toast(Text.NOTHING_TO_COPY);
                    return true;
                }
                Saver.copy(ScanActivity.this, "cdn-edge-ips-cidr", text);
                toast(Text.COPIED);
                return true;
            }
        });

        root.addView(Widgets.chips(this, actions, new Widgets.ChipListener() {
            @Override
            public void onChip(Widgets.Chip chip) {
                handleAction(chip);
            }
        }));
        return root;
    }

    private TextView addStat(LinearLayout parent, String label) {
        LinearLayout cell = Widgets.column(this);
        cell.setLayoutParams(Widgets.weighted());
        TextView value = Widgets.text(this, "—", 15f, Theme.TEXT, true);
        TextView caption = Widgets.text(this, label, 10.5f, Theme.TEXT_DIM, false);
        cell.addView(value);
        cell.addView(caption);
        parent.addView(cell);
        return value;
    }

    private void updateStats(int tested, int found, int rate, long elapsedMs) {
        testedValue.setText(Text.fa(tested));
        aliveValue.setText(Text.fa(found));
        aliveValue.setTextColor(found > 0 ? Theme.OK : Theme.TEXT);
        rateValue.setText(Text.fa(rate));
        String time = Text.elapsed(elapsedMs);
        if (rate > 0 && tested < total) {
            long remaining = (long) (total - tested) * 1000L / rate;
            time = time + " │ " + Text.ETA + " " + Text.elapsed(remaining);
        }
        timeValue.setText(time);
    }

    // ------------------------------------------------------------ callbacks

    @Override
    public void onPhase(final String phase) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if ("speed".equals(phase)) {
                    phaseText.setText(Text.PHASE_SPEED);
                } else {
                    phaseText.setText(Text.PHASE_PROBE);
                }
            }
        });
    }

    @Override
    public void onProgress(final int done, final int all, final int found, final long elapsed, final int rate) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                total = all;
                alive = found;
                progress.setProgress(all == 0 ? 0 : (int) (done * 1000L / all));
                updateStats(done, found, rate, elapsed);
            }
        });
    }

    @Override
    public void onFound(ProbeResult result) {
        pending.add(result);
        synchronized (this) {
            if (flushScheduled) {
                return;
            }
            flushScheduled = true;
        }
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                synchronized (ScanActivity.this) {
                    flushScheduled = false;
                }
                flushPending();
            }
        }, 300);
    }

    private void flushPending() {
        List<ProbeResult> batch = new ArrayList<ProbeResult>();
        synchronized (pending) {
            batch.addAll(pending);
            pending.clear();
        }
        if (!batch.isEmpty()) {
            adapter.addAll(batch);
        }
    }

    @Override
    public void onFinished(final List<ProbeResult> results, final boolean cancelled, final boolean speedDone) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                flushPending();
                finished = true;
                speedTested = speedDone;
                adapter.setAll(results);
                Store.save(ScanActivity.this, results, config.domain, total, speedDone);
                alive = ConfigLinks.usable(results).size();
                String title = (cancelled ? Text.PHASE_CANCELLED : Text.PHASE_DONE)
                        + " — " + Text.fa(alive) + " سالم از " + Text.fa(total);
                phaseText.setText(title);
                progress.setProgress(1000);
                updateStats(total, alive, 0, System.currentTimeMillis() - startedAt);
                stopButton.setText("بازگشت");
                stopButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        finish();
                    }
                });
                if (alive == 0) {
                    hintText.setText(Text.NO_ALIVE);
                    new AlertDialog.Builder(ScanActivity.this)
                            .setTitle(Text.PHASE_DONE)
                            .setMessage(Text.NO_ALIVE)
                            .setPositiveButton("باشه", null)
                            .show();
                } else {
                    hintText.setText(Text.TAP_TO_COPY + " — " + Text.fa(alive) + " آی‌پی سالم");
                }
            }
        });
    }

    // -------------------------------------------------------------- actions

    private void handleAction(Widgets.Chip chip) {
        String action = chip.value;
        if ("onlyAlive".equals(action)) {
            chip.setSelectedChip(!chip.isChipSelected());
            adapter.setOnlyAlive(chip.isChipSelected());
            return;
        }
        if ("copyIps".equals(action)) {
            String text = ConfigLinks.ipList(adapter.items(), true, false);
            if (text.isEmpty()) {
                toast(Text.NOTHING_TO_COPY);
                return;
            }
            Saver.copy(this, "cdn-edge-ips", text);
            toast(Text.COPIED + " — " + Text.fa(text.split("\n").length) + " آی‌پی");
            return;
        }
        if ("copySni".equals(action)) {
            Saver.copy(this, "sni", Store.domain);
            toast(Text.COPIED + " — " + Store.domain);
            return;
        }
        if ("copyEndpoints".equals(action)) {
            String text = ConfigLinks.endpointList(adapter.items(), true);
            if (text.isEmpty()) {
                toast(Text.NOTHING_TO_COPY);
                return;
            }
            Saver.copy(this, "endpoints", text);
            toast(Text.COPIED);
            return;
        }
        if ("configs".equals(action)) {
            Dialogs.configs(this, config, adapter.items());
            return;
        }
        if ("blocks".equals(action)) {
            Dialogs.blocks(this, adapter.items());
            return;
        }
        if ("report".equals(action)) {
            String report = buildReport();
            Saver.copy(this, "report", report);
            toast(Text.COPIED);
            Dialogs.text(this, Text.COPY_REPORT, report, "cleanip-report");
            return;
        }
        if ("share".equals(action)) {
            Saver.share(this, "Ahura Clean IP", buildReport());
            return;
        }
        if ("save".equals(action)) {
            save();
            return;
        }
        if ("speed".equals(action)) {
            runSpeedTest();
        }
    }

    private String buildReport() {
        List<ProbeResult> usable = ConfigLinks.usable(adapter.items());
        StringBuilder sb = new StringBuilder();
        sb.append("Ahura Clean IP (AhuraMazda)\n");
        sb.append("domain: ").append(Store.domain).append('\n');
        sb.append("alive: ").append(usable.size()).append(" / ").append(total).append('\n');
        sb.append("---- ips ----\n");
        sb.append(ConfigLinks.ipList(adapter.items(), true, false)).append('\n');
        sb.append("---- details ----\n");
        int limit = 0;
        for (ProbeResult r : usable) {
            if (limit++ > 300) {
                break;
            }
            sb.append(r.toExportLine()).append('\n');
        }
        return sb.toString();
    }

    private void save() {
        String content = buildReport();
        String name = "cleanip-" + Store.domain.replaceAll("[^a-zA-Z0-9._-]", "_") + "-"
                + Saver.timeStamp() + ".txt";
        Saver.save(this, name, content, new Saver.SaveCallback() {
            @Override
            public void onSaved(String location) {
                toast(Text.SAVED_TO.replace("%s", location));
            }

            @Override
            public void onError(String message) {
                toast(Text.SAVE_FAILED.replace("%s", message));
            }
        });
    }

    /** Re-runs the download test on the best addresses (useful on the "last results" screen). */
    private void runSpeedTest() {
        if (speedRunning) {
            return;
        }
        List<ProbeResult> targets = ConfigLinks.usable(adapter.items());
        if (targets.isEmpty()) {
            toast(Text.NOTHING_TO_COPY);
            return;
        }
        int count = Math.min(config.speedTopN, targets.size());
        final List<ProbeResult> selected = new ArrayList<ProbeResult>(targets.subList(0, count));
        speedRunning = true;
        phaseText.setText(Text.PHASE_SPEED);
        final ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, selected.size()));
        for (final ProbeResult target : selected) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        ProbeResult speed = Probe.speedTest(target.ip, target.port, config,
                                config.speedDurationMs);
                        target.speedBps = speed.speedBps > 0 ? speed.speedBps : 0L;
                        target.speedBytes = speed.speedBytes;
                        target.speedMs = speed.speedMs;
                    } catch (Throwable ignored) {
                        target.speedBps = 0L;
                    }
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            adapter.notifyDataSetChanged();
                        }
                    });
                }
            });
        }
        pool.shutdown();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    pool.awaitTermination(Math.max(10000, config.speedDurationMs * 4L), TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        speedRunning = false;
                        Store.save(ScanActivity.this, adapter.items(), Store.domain, total, true);
                        adapter.setAll(adapter.items());
                        phaseText.setText(Text.PHASE_DONE + " — " + Text.fa(ConfigLinks.usable(adapter.items()).size())
                                + " سالم از " + Text.fa(total));
                    }
                });
            }
        }, "cleanip-speed-waiter").start();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Saver.onPermissionResult(this, requestCode,
                grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED);
    }
}
