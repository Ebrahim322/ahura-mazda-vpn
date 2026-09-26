package com.ahuramazda.cleanip;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.ahuramazda.cleanip.core.CdnDefaults;
import com.ahuramazda.cleanip.core.CloudflareRanges;
import com.ahuramazda.cleanip.core.Ipv4;
import com.ahuramazda.cleanip.core.ScanConfig;
import com.ahuramazda.cleanip.core.Scanner;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Setup screen: domain, address source, ports and the advanced knobs. */
public class MainActivity extends Activity {

    private static final String[] COUNTS = {"200", "500", "1000", "2000", "4000"};
    private static final String[] RADII = {"24", "23", "22"};
    private static final String[] PORTS = {"443", "80", "8443", "2053", "2083", "2096", "8080", "8880"};
    private static final String[] TIMEOUTS = {"1000", "1500", "2500", "4000"};
    private static final String[] THREADS = {"32", "64", "128", "192"};
    private static final String[] SPEED_TOPS = {"6", "12", "24"};
    private static final String[] SPEED_TIMES = {"1500", "2500", "5000"};

    private Prefs prefs;
    private ScanConfig config;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText domainField;
    private EditText manualField;
    private EditText referenceField;
    private EditText speedPathField;
    private TextView targetsInfo;
    private TextView resolvedText;
    private TextView sourceNote;

    private List<Widgets.Chip> sourceChips;
    private List<Widgets.Chip> domainChips;
    private List<Widgets.Chip> countChips;
    private List<Widgets.Chip> radiusChips;
    private List<Widgets.Chip> portChips;
    private List<Widgets.Chip> timeoutChips;
    private List<Widgets.Chip> threadChips;
    private List<Widgets.Chip> speedTopChips;
    private List<Widgets.Chip> speedTimeChips;

    private Widgets.Toggle liveToggle;
    private Widgets.Toggle verifyToggle;
    private Widgets.Toggle speedToggle;

    private LinearLayout cfBox;
    private LinearLayout manualBox;
    private LinearLayout domainBox;
    private Button lastResultsButton;

    private String source = "cf";
    private int cfCount = 1000;
    private int radius = 24;
    private boolean fetching;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Theme.BG);
        getWindow().setNavigationBarColor(Theme.BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        prefs = new Prefs(this);
        config = prefs.loadConfig();
        source = prefs.source();
        cfCount = prefs.count();
        radius = prefs.radius();

        setContentView(buildUi());

        Store.load(this);
        updateTargetsInfo();
    }

    // ------------------------------------------------------------------ UI

    private View buildUi() {
        LinearLayout root = Widgets.column(this);
        int pad = Theme.dp(this, 14);
        root.setPadding(pad, pad, pad, Theme.dp(this, 28));
        root.setBackgroundColor(Theme.BG);

        TextView title = Widgets.text(this, Text.APP_NAME, 22f, Theme.GOLD, true);
        root.addView(title);
        root.addView(Widgets.text(this, Text.SUBTITLE, 12f, Theme.TEXT_DIM, false));
        TextView version = Widgets.text(this, Text.VERSION, 11f, 0xFF5B6981, false);
        version.setPadding(0, Theme.dp(this, 4), 0, 0);
        root.addView(version);

        Widgets.space(this, root, 10);
        root.addView(domainCard());
        root.addView(sourceCard());
        root.addView(portsCard());
        root.addView(advancedCard());

        Button start = Widgets.button(this, Text.START, true);
        LinearLayout.LayoutParams startParams = Widgets.matchWidth(this, 54);
        startParams.topMargin = Theme.dp(this, 4);
        start.setLayoutParams(startParams);
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan();
            }
        });
        root.addView(start);

        targetsInfo = Widgets.text(this, "", 12.5f, Theme.BLUE, true);
        targetsInfo.setPadding(0, Theme.dp(this, 10), 0, 0);
        root.addView(targetsInfo);

        LinearLayout actions = Widgets.row(this);
        actions.setPadding(0, Theme.dp(this, 10), 0, 0);
        lastResultsButton = Widgets.button(this, Text.LAST_RESULTS, false);
        Button help = Widgets.button(this, Text.HELP, false);
        Button about = Widgets.button(this, Text.ABOUT, false);
        lastResultsButton.setLayoutParams(Widgets.weighted());
        help.setLayoutParams(Widgets.weighted());
        about.setLayoutParams(Widgets.weighted());
        actions.addView(lastResultsButton);
        actions.addView(help);
        actions.addView(about);
        root.addView(actions);

        lastResultsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openResults();
            }
        });
        help.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Dialogs.help(MainActivity.this);
            }
        });
        about.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Dialogs.about(MainActivity.this);
            }
        });

        return Widgets.scroll(this, root);
    }

    private View domainCard() {
        LinearLayout card = Widgets.card(this);
        card.addView(Widgets.sectionTitle(this, Text.SEC_DOMAIN));
        domainField = Widgets.field(this, Text.DOMAIN_HINT, 1);
        domainField.setText(config.domain);
        card.addView(domainField);

        domainChips = Widgets.chipList(this, CdnDefaults.DOMAIN_PRESETS);
        card.addView(Widgets.chips(this, domainChips, new Widgets.ChipListener() {
            @Override
            public void onChip(Widgets.Chip chip) {
                domainField.setText(chip.value);
                for (Widgets.Chip other : domainChips) {
                    other.setSelectedChip(other == chip);
                }
                updateTargetsInfo();
            }
        }));
        card.addView(Widgets.note(this, Text.DOMAIN_NOTE));
        return card;
    }

    private View sourceCard() {
        LinearLayout card = Widgets.card(this);
        card.addView(Widgets.sectionTitle(this, Text.SEC_SOURCE));

        sourceChips = new ArrayList<Widgets.Chip>();
        sourceChips.add(new Widgets.Chip(this, Text.SRC_CF, "cf"));
        sourceChips.add(new Widgets.Chip(this, Text.SRC_MANUAL, "manual"));
        sourceChips.add(new Widgets.Chip(this, Text.SRC_DOMAIN, "domain"));
        card.addView(Widgets.chips(this, sourceChips, new Widgets.ChipListener() {
            @Override
            public void onChip(Widgets.Chip chip) {
                source = chip.value;
                Widgets.selectOnly(sourceChips, chip);
                applySourceVisibility();
                updateTargetsInfo();
            }
        }));

        sourceNote = Widgets.note(this, Text.CF_NOTE);
        card.addView(sourceNote);

        // ---- Cloudflare ranges
        cfBox = Widgets.column(this);
        liveToggle = Widgets.toggle(this, Text.CF_LIVE, prefs.liveRanges());
        cfBox.addView(liveToggle.view);
        cfBox.addView(Widgets.text(this, Text.CF_COUNT, 13f, Theme.TEXT, false));

        LinearLayout host = Widgets.row(this);
        host.setGravity(Gravity.CENTER);
        countChips = new ArrayList<Widgets.Chip>();
        for (String value : COUNTS) {
            countChips.add(new Widgets.Chip(this, Text.fa(value), value));
        }
        cfBox.addView(Widgets.chips(this, countChips, new Widgets.ChipListener() {
            @Override
            public void onChip(Widgets.Chip chip) {
                cfCount = parseInt(chip.value, 1000);
                Widgets.selectOnly(countChips, chip);
                updateTargetsInfo();
            }
        }));
        card.addView(cfBox);

        // ---- manual list
        manualBox = Widgets.column(this);
        manualField = Widgets.field(this, Text.MANUAL_HINT, 6);
        manualField.setText(prefs.manual());
        manualBox.addView(manualField);
        LinearLayout manualActions = Widgets.row(this);
        Button paste = Widgets.button(this, Text.PASTE, false);
        Button clear = Widgets.button(this, Text.CLEAR, false);
        paste.setLayoutParams(Widgets.weighted());
        clear.setLayoutParams(Widgets.weighted());
        manualActions.addView(paste);
        manualActions.addView(clear);
        manualBox.addView(manualActions);
        paste.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String clip = Saver.clipboard(MainActivity.this);
                if (clip.trim().isEmpty()) {
                    toast(Text.EMPTY_CLIPBOARD);
                    return;
                }
                manualField.setText(clip);
                updateTargetsInfo();
            }
        });
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manualField.setText("");
                updateTargetsInfo();
            }
        });
        card.addView(manualBox);

        // ---- around a domain
        domainBox = Widgets.column(this);
        resolvedText = Widgets.text(this, Text.DOMAIN_MODE_NOTE, 11.5f, Theme.TEXT_DIM, false);
        domainBox.addView(resolvedText);
        Button resolve = Widgets.button(this, Text.RESOLVE, false);
        resolve.setLayoutParams(Widgets.matchWidth(this, 0));
        resolve.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resolveDomain();
            }
        });
        domainBox.addView(resolve);

        referenceField = Widgets.field(this, Text.REF_IP, 1);
        referenceField.setText(prefs.referenceIp());
        domainBox.addView(referenceField);
        domainBox.addView(Widgets.text(this, Text.RADIUS, 13f, Theme.TEXT, false));

        radiusChips = new ArrayList<Widgets.Chip>();
        for (String value : RADII) {
            radiusChips.add(new Widgets.Chip(this, "‏/" + Text.fa(value), value));
        }
        domainBox.addView(Widgets.chips(this, radiusChips, new Widgets.ChipListener() {
            @Override
            public void onChip(Widgets.Chip chip) {
                radius = parseInt(chip.value, 24);
                Widgets.selectOnly(radiusChips, chip);
                updateTargetsInfo();
            }
        }));
        card.addView(domainBox);

        applySourceVisibility();
        return card;
    }

    private View portsCard() {
        LinearLayout card = Widgets.card(this);
        card.addView(Widgets.sectionTitle(this, Text.SEC_PORTS));
        portChips = new ArrayList<Widgets.Chip>();
        for (String port : PORTS) {
            portChips.add(new Widgets.Chip(this, Text.fa(port), port));
        }
        card.addView(Widgets.chips(this, portChips, new Widgets.ChipListener() {
            @Override
            public void onChip(Widgets.Chip chip) {
                chip.setSelectedChip(!chip.isChipSelected());
                int selected = 0;
                for (Widgets.Chip other : portChips) {
                    if (other.isChipSelected()) {
                        selected++;
                    }
                }
                if (selected == 0) {
                    chip.setSelectedChip(true);
                }
                updateTargetsInfo();
            }
        }));
        card.addView(Widgets.note(this, Text.PORTS_NOTE));
        return card;
    }

    private View advancedCard() {
        LinearLayout card = Widgets.card(this);
        card.addView(Widgets.sectionTitle(this, Text.SEC_ADVANCED));

        card.addView(Widgets.text(this, Text.TIMEOUT, 13f, Theme.TEXT, false));
        timeoutChips = new ArrayList<Widgets.Chip>();
        for (String value : TIMEOUTS) {
            timeoutChips.add(new Widgets.Chip(this, Text.fa(value) + " ms", value));
        }
        card.addView(Widgets.chips(this, timeoutChips, new Widgets.ChipListener() {
            @Override
            public void onChip(Widgets.Chip chip) {
                config.timeoutMs = parseInt(chip.value, 1500);
                Widgets.selectOnly(timeoutChips, chip);
            }
        }));

        card.addView(Widgets.text(this, Text.THREADS, 13f, Theme.TEXT, false));
        threadChips = new ArrayList<Widgets.Chip>();
        for (String value : THREADS) {
            threadChips.add(new Widgets.Chip(this, Text.fa(value), value));
        }
        card.addView(Widgets.chips(this, threadChips, new Widgets.ChipListener() {
            @Override
            public void onChip(Widgets.Chip chip) {
                config.threads = parseInt(chip.value, 64);
                Widgets.selectOnly(threadChips, chip);
            }
        }));

        verifyToggle = Widgets.toggle(this, Text.VERIFY_CERT, config.verifyCert);
        card.addView(verifyToggle.view);

        speedToggle = Widgets.toggle(this, Text.SPEED_TEST, config.speedTest);
        card.addView(speedToggle.view);

        card.addView(Widgets.text(this, Text.SPEED_TOP, 13f, Theme.TEXT, false));
        speedTopChips = new ArrayList<Widgets.Chip>();
        for (String value : SPEED_TOPS) {
            speedTopChips.add(new Widgets.Chip(this, Text.fa(value), value));
        }
        card.addView(Widgets.chips(this, speedTopChips, new Widgets.ChipListener() {
            @Override
            public void onChip(Widgets.Chip chip) {
                config.speedTopN = parseInt(chip.value, 12);
                Widgets.selectOnly(speedTopChips, chip);
            }
        }));

        card.addView(Widgets.text(this, Text.SPEED_DURATION, 13f, Theme.TEXT, false));
        speedTimeChips = new ArrayList<Widgets.Chip>();
        for (String value : SPEED_TIMES) {
            speedTimeChips.add(new Widgets.Chip(this, Text.fa(value) + " ms", value));
        }
        card.addView(Widgets.chips(this, speedTimeChips, new Widgets.ChipListener() {
            @Override
            public void onChip(Widgets.Chip chip) {
                config.speedDurationMs = parseInt(chip.value, 2500);
                Widgets.selectOnly(speedTimeChips, chip);
            }
        }));

        speedPathField = Widgets.field(this, Text.SPEED_PATH, 1);
        speedPathField.setText(config.speedPathOverride);
        card.addView(speedPathField);

        Button reset = Widgets.button(this, Text.RESET_DEFAULTS, false);
        reset.setLayoutParams(Widgets.matchWidth(this, 0));
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                config = new ScanConfig();
                applyConfigToUi();
            }
        });
        card.addView(reset);

        applyConfigToUi();
        return card;
    }

    private void applyConfigToUi() {
        selectOrDefault(timeoutChips, String.valueOf(config.timeoutMs), 1);
        selectOrDefault(threadChips, String.valueOf(config.threads), 1);
        selectOrDefault(speedTopChips, String.valueOf(config.speedTopN), 1);
        selectOrDefault(speedTimeChips, String.valueOf(config.speedDurationMs), 1);
        selectOrDefault(sourceChips, source, 0);
        selectOrDefault(countChips, String.valueOf(cfCount), 2);
        selectOrDefault(radiusChips, String.valueOf(radius), 0);
        for (Widgets.Chip chip : portChips) {
            chip.setSelectedChip(config.ports.contains(Integer.valueOf(parseInt(chip.value, 0))));
        }
        verifyToggle.setChecked(config.verifyCert);
        speedToggle.setChecked(config.speedTest);
        if (speedPathField != null) {
            speedPathField.setText(config.speedPathOverride);
        }
        applySourceVisibility();
        updateSourceNote();
    }

    private void selectOrDefault(List<Widgets.Chip> chips, String value, int fallbackIndex) {
        Widgets.Chip match = findChip(chips, value);
        if (match == null && chips != null && !chips.isEmpty()) {
            match = chips.get(Math.max(0, Math.min(fallbackIndex, chips.size() - 1)));
        }
        Widgets.selectOnly(chips, match);
    }

    private Widgets.Chip findChip(List<Widgets.Chip> chips, String value) {
        if (chips != null) {
            for (Widgets.Chip chip : chips) {
                if (chip.value.equals(value)) {
                    return chip;
                }
            }
        }
        return null;
    }

    private void applySourceVisibility() {
        if (cfBox == null) {
            return;
        }
        cfBox.setVisibility("cf".equals(source) ? View.VISIBLE : View.GONE);
        manualBox.setVisibility("manual".equals(source) ? View.VISIBLE : View.GONE);
        domainBox.setVisibility("domain".equals(source) ? View.VISIBLE : View.GONE);
    }

    private void updateSourceNote() {
        if (sourceNote == null) {
            return;
        }
        if ("cf".equals(source)) {
            sourceNote.setText(Text.CF_NOTE);
        } else if ("manual".equals(source)) {
            sourceNote.setText(Text.MANUAL_NOTE);
        } else {
            sourceNote.setText(Text.DOMAIN_MODE_NOTE);
        }
    }

    // -------------------------------------------------------------- actions

    private void updateTargetsInfo() {
        if (targetsInfo == null) {
            return;
        }
        List<String> ips = collectAddresses(false);
        int ports = selectedPorts().size();
        if (ips.isEmpty()) {
            targetsInfo.setText(Text.TARGETS.replace("%s", Text.fa(0)));
            return;
        }
        targetsInfo.setText("آماده: " + Text.fa(ips.size()) + " آی‌پی × " + Text.fa(ports)
                + " پورت = " + Text.fa(ips.size() * ports) + " تست");
    }

    private List<Integer> selectedPorts() {
        List<Integer> ports = new ArrayList<Integer>();
        for (Widgets.Chip chip : portChips) {
            if (chip.isChipSelected()) {
                ports.add(parseInt(chip.value, 0));
            }
        }
        if (ports.isEmpty()) {
            ports.add(443);
        }
        return ports;
    }

    /** Builds the address list for the current source (may do network access when asked). */
    private List<String> collectAddresses(boolean online) {
        List<String> ips = new ArrayList<String>();
        if ("manual".equals(source)) {
            ips.addAll(Ipv4.parseList(manualField == null ? "" : manualField.getText().toString(),
                    Ipv4.MAX_TARGETS));
        } else if ("domain".equals(source)) {
            String reference = referenceField == null ? "" : referenceField.getText().toString().trim();
            String normalized = Ipv4.normalize(reference);
            List<String> bases = new ArrayList<String>();
            if (normalized != null) {
                bases.add(normalized);
            } else if (online) {
                bases.addAll(resolveNow(domain()));
            }
            for (String base : bases) {
                ips.addAll(around(base, radius));
            }
        } else {
            List<String> ranges = CdnDefaults.cloudflareRanges();
            if (online) {
                ranges = CloudflareRanges.fetch(6000);
            }
            ips.addAll(CdnDefaults.sampleFromRanges(ranges, cfCount, 0L));
        }
        LinkedHashSet<String> unique = new LinkedHashSet<String>(ips);
        List<String> out = new ArrayList<String>(unique);
        if (out.size() > Ipv4.MAX_TARGETS) {
            return new ArrayList<String>(out.subList(0, Ipv4.MAX_TARGETS));
        }
        return out;
    }

    private static List<String> around(String ip, int prefix) {
        List<String> out = new ArrayList<String>();
        int[] block = Ipv4.cidr(ip + "/" + prefix);
        if (block == null) {
            return out;
        }
        for (int i = 0; i < block[1]; i++) {
            out.add(Ipv4.format(block[0] + i));
        }
        return out;
    }

    private List<String> resolveNow(String domain) {
        List<String> out = new ArrayList<String>();
        try {
            InetAddress[] addresses = InetAddress.getAllByName(domain);
            for (InetAddress address : addresses) {
                String text = address.getHostAddress();
                if (Ipv4.isValid(text)) {
                    out.add(text);
                }
            }
        } catch (Exception ignored) {
            // resolution failed - the caller falls back to the manual reference address
        }
        return out;
    }

    private void resolveDomain() {
        final String domain = domain();
        if (!isValidDomain(domain)) {
            toast(Text.INVALID_DOMAIN);
            return;
        }
        resolvedText.setText(Text.RESOLVING);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<String> found = resolveNow(domain);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (found.isEmpty()) {
                            resolvedText.setText(Text.RESOLVE_FAILED);
                        } else {
                            StringBuilder sb = new StringBuilder();
                            for (String ip : found) {
                                if (sb.length() > 0) {
                                    sb.append(" ، ");
                                }
                                sb.append(ip);
                            }
                            resolvedText.setText(String.format(Text.RESOLVED, Text.fa(sb.toString())));
                        }
                        updateTargetsInfo();
                    }
                });
            }
        }, "cleanip-resolve").start();
    }

    private void startScan() {
        final String domain = domain();
        if (!isValidDomain(domain)) {
            toast(Text.INVALID_DOMAIN);
            return;
        }
        config.domain = domain;
        config.verifyCert = verifyToggle.isChecked();
        config.speedTest = speedToggle.isChecked();
        config.speedPathOverride = speedPathField.getText().toString().trim();
        config.ports.clear();
        config.ports.addAll(selectedPorts());
        config.seed = 0L;

        prefs.saveConfig(config, source, cfCount, liveToggle.isChecked(),
                manualField.getText().toString(), radius, referenceField.getText().toString().trim());

        if ("manual".equals(source)) {
            List<String> ips = collectAddresses(false);
            if (ips.isEmpty()) {
                toast(Text.EMPTY_LIST);
                return;
            }
            launch(ips);
            return;
        }
        if ("domain".equals(source)) {
            String reference = Ipv4.normalize(referenceField.getText().toString().trim());
            if (reference != null) {
                launch(collectAddresses(false));
                return;
            }
            toast(Text.RESOLVING);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final List<String> ips = collectAddresses(true);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (ips.isEmpty()) {
                                toast(Text.RESOLVE_FAILED);
                            } else {
                                launch(ips);
                            }
                        }
                    });
                }
            }, "cleanip-resolve-scan").start();
            return;
        }

        // Cloudflare ranges, optionally refreshed from the network
        if (liveToggle.isChecked() && !fetching) {
            fetching = true;
            toast(Text.FETCHING_RANGES);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final List<String> ranges = CloudflareRanges.fetch(6000);
                    final List<String> ips = CdnDefaults.sampleFromRanges(ranges, cfCount, 0L);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            fetching = false;
                            toast(String.format(Text.RANGES_OK, Text.fa(ranges.size())));
                            launch(ips);
                        }
                    });
                }
            }, "cleanip-ranges").start();
            return;
        }
        launch(collectAddresses(false));
    }

    private void launch(List<String> ips) {
        if (ips.isEmpty()) {
            toast(Text.EMPTY_LIST);
            return;
        }
        ScanConfig copy = config;
        List<Scanner.Target> targets = Scanner.buildTargets(ips, copy.ports, copy.seed);
        Store.pendingConfig = copy;
        Store.pendingTargets = targets;
        Store.scannedCount = ips.size();
        startActivity(new Intent(this, ScanActivity.class));
    }

    private void openResults() {
        if (!Store.hasResults()) {
            toast(Text.NO_RESULT_YET);
            return;
        }
        startActivity(new Intent(this, ScanActivity.class));
    }

    private String domain() {
        return domainField.getText().toString().trim().toLowerCase(Locale.US);
    }

    private static boolean isValidDomain(String domain) {
        if (domain == null || domain.length() < 4 || domain.length() > 253) {
            return false;
        }
        if (domain.startsWith(".") || domain.endsWith(".") || domain.contains("..")) {
            return false;
        }
        for (int i = 0; i < domain.length(); i++) {
            char c = domain.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return domain.indexOf('.') > 0;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
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
