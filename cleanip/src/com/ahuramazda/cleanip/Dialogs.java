package com.ahuramazda.cleanip;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.ahuramazda.cleanip.core.ConfigLinks;
import com.ahuramazda.cleanip.core.ProbeResult;
import com.ahuramazda.cleanip.core.RangeStats;
import com.ahuramazda.cleanip.core.ScanConfig;

import java.util.ArrayList;
import java.util.List;

/** All the AlertDialogs: help, about, range summary, config builder and text exports. */
public final class Dialogs {

    private Dialogs() {
    }

    // ---------------------------------------------------------------- help

    public static void help(final Activity activity) {
        LinearLayout content = Widgets.column(activity);
        int pad = Theme.dp(activity, 16);
        content.setPadding(pad, pad, pad, pad);
        content.setBackgroundColor(Theme.CARD);
        TextView body = Widgets.text(activity, Text.HELP_BODY, 13f, Theme.TEXT, false);
        content.addView(body);

        final LinearLayout actions = Widgets.row(activity);
        actions.setPadding(0, pad, 0, 0);
        Button copyIps = Widgets.button(activity, Text.COPY_IPS, false);
        Button copySni = Widgets.button(activity, Text.COPY_SNI, false);
        copyIps.setLayoutParams(Widgets.weighted());
        copySni.setLayoutParams(Widgets.weighted());
        actions.addView(copyIps);
        actions.addView(copySni);
        content.addView(actions);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(Text.HELP_TITLE)
                .setView(scroll)
                .setPositiveButton("بستن", null)
                .create();

        copyIps.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String ips = ConfigLinks.ipList(Store.results, true, false);
                if (ips.isEmpty()) {
                    Toast.makeText(activity, Text.NOTHING_TO_COPY, Toast.LENGTH_SHORT).show();
                    return;
                }
                Saver.copy(activity, "cdn-edge-ips", ips);
                Toast.makeText(activity, Text.COPIED, Toast.LENGTH_SHORT).show();
            }
        });
        copySni.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Saver.copy(activity, "sni", Store.domain);
                Toast.makeText(activity, Text.COPIED, Toast.LENGTH_SHORT).show();
            }
        });
        dialog.show();
    }

    public static void about(Activity activity) {
        LinearLayout content = Widgets.column(activity);
        int pad = Theme.dp(activity, 16);
        content.setPadding(pad, pad, pad, pad);
        content.setBackgroundColor(Theme.CARD);
        content.addView(Widgets.text(activity, Text.ABOUT_BODY, 13f, Theme.TEXT, false));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        new AlertDialog.Builder(activity)
                .setTitle(Text.APP_NAME + " — " + Text.VERSION)
                .setView(scroll)
                .setPositiveButton("بستن", null)
                .show();
    }

    // ------------------------------------------------------------ exporters

    /** Generic text viewer with copy + share. */
    public static void text(final Activity activity, String title, final String body, final String label) {
        LinearLayout content = Widgets.column(activity);
        int pad = Theme.dp(activity, 12);
        content.setPadding(pad, pad, pad, pad);
        content.setBackgroundColor(Theme.CARD);
        TextView view = Widgets.text(activity, body, 12.5f, Theme.TEXT, false);
        view.setTypeface(Theme.mono());
        view.setTextIsSelectable(true);
        content.addView(view);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton("کپی", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Saver.copy(activity, label, body);
                        Toast.makeText(activity, Text.COPIED, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton(Text.SHARE, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Saver.share(activity, label, body);
                    }
                })
                .setNegativeButton("بستن", null)
                .show();
    }

    /** Which /24 (or /16) blocks look promising. */
    public static void blocks(final Activity activity, List<ProbeResult> results) {
        if (results == null || results.isEmpty()) {
            Toast.makeText(activity, Text.NOTHING_TO_COPY, Toast.LENGTH_SHORT).show();
            return;
        }
        List<RangeStats.Block> byBlock = RangeStats.group(results, 24);
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (RangeStats.Block block : byBlock) {
            if (block.alive == 0) {
                continue;
            }
            if (count++ >= 20) {
                break;
            }
            sb.append(block.cidr).append("  →  ").append(Text.fa(block.alive)).append(" سالم از ")
                    .append(Text.fa(block.total));
            if (block.bestLatency != Integer.MAX_VALUE) {
                sb.append(" · بهترین ").append(Text.fa(block.bestLatency)).append("ms");
            }
            sb.append('\n');
        }
        if (sb.length() == 0) {
            sb.append(Text.NO_ALIVE);
        }
        text(activity, Text.BLOCKS, sb.toString().trim(), "cleanip-blocks");
    }

    // -------------------------------------------------------------- configs

    public static void configs(final Activity activity, final ScanConfig config, final List<ProbeResult> results) {
        final List<ProbeResult> usable = ConfigLinks.usable(results);
        if (usable.isEmpty()) {
            Toast.makeText(activity, Text.CFG_NOTHING, Toast.LENGTH_SHORT).show();
            return;
        }
        final ConfigLinks.Profile profile = new ConfigLinks.Profile();
        profile.sni = config.domain;
        profile.host = config.domain;

        LinearLayout content = Widgets.column(activity);
        int pad = Theme.dp(activity, 14);
        content.setPadding(pad, pad, pad, pad);
        content.setBackgroundColor(Theme.CARD);
        content.addView(Widgets.note(activity, Text.CFG_NOTE));

        content.addView(Widgets.text(activity, Text.CFG_PROTOCOL, 13f, Theme.TEXT, false));
        final List<Widgets.Chip> protoChips = new ArrayList<Widgets.Chip>();
        protoChips.add(new Widgets.Chip(activity, Text.PROTO_VLESS, "vless"));
        protoChips.add(new Widgets.Chip(activity, Text.PROTO_TROJAN, "trojan"));
        protoChips.add(new Widgets.Chip(activity, Text.PROTO_VMESS, "vmess"));
        content.addView(Widgets.chips(activity, protoChips, new Widgets.ChipListener() {
            @Override
            public void onChip(Widgets.Chip chip) {
                profile.protocol = chip.value;
                Widgets.selectOnly(protoChips, chip);
            }
        }));

        final EditText uuid = Widgets.field(activity, Text.CFG_UUID, 1);
        content.addView(uuid);

        content.addView(Widgets.text(activity, Text.CFG_NETWORK, 13f, Theme.TEXT, false));
        final List<Widgets.Chip> netChips = new ArrayList<Widgets.Chip>();
        netChips.add(new Widgets.Chip(activity, Text.NET_WS, "ws"));
        netChips.add(new Widgets.Chip(activity, Text.NET_GRPC, "grpc"));
        netChips.add(new Widgets.Chip(activity, Text.NET_TCP, "tcp"));
        netChips.add(new Widgets.Chip(activity, Text.NET_HU, "httpupgrade"));
        content.addView(Widgets.chips(activity, netChips, new Widgets.ChipListener() {
            @Override
            public void onChip(Widgets.Chip chip) {
                profile.network = chip.value;
                Widgets.selectOnly(netChips, chip);
            }
        }));

        final EditText path = Widgets.field(activity, Text.CFG_PATH, 1);
        path.setText(profile.path);
        content.addView(path);
        final EditText host = Widgets.field(activity, Text.CFG_HOST, 1);
        host.setText(profile.host);
        content.addView(host);
        final EditText sni = Widgets.field(activity, Text.CFG_SNI, 1);
        sni.setText(profile.sni);
        content.addView(sni);
        final EditText fp = Widgets.field(activity, Text.CFG_FP, 1);
        fp.setText(profile.fingerprint);
        content.addView(fp);
        final EditText alpn = Widgets.field(activity, Text.CFG_ALPN, 1);
        content.addView(alpn);
        final EditText service = Widgets.field(activity, Text.CFG_SERVICE, 1);
        content.addView(service);

        content.addView(Widgets.text(activity, Text.CFG_COUNT, 13f, Theme.TEXT, false));
        final List<Widgets.Chip> countChips = new ArrayList<Widgets.Chip>();
        for (String value : new String[]{"5", "10", "20", "50"}) {
            countChips.add(new Widgets.Chip(activity, Text.fa(value), value));
        }
        content.addView(Widgets.chips(activity, countChips, new Widgets.ChipListener() {
            @Override
            public void onChip(Widgets.Chip chip) {
                try {
                    profile.limit = Integer.parseInt(chip.value);
                } catch (NumberFormatException ignored) {
                    profile.limit = 10;
                }
                Widgets.selectOnly(countChips, chip);
            }
        }));

        Widgets.selectOnly(protoChips, protoChips.get(0));
        Widgets.selectOnly(netChips, netChips.get(0));
        Widgets.selectOnly(countChips, countChips.get(1));

        final LinearLayout tools = Widgets.row(activity);
        tools.setPadding(0, pad, 0, 0);
        Button paste = Widgets.button(activity, Text.CFG_PRESET, false);
        Button bpb = Widgets.button(activity, Text.CFG_PRESET_BPB, false);
        paste.setLayoutParams(Widgets.weighted());
        bpb.setLayoutParams(Widgets.weighted());
        tools.addView(paste);
        tools.addView(bpb);
        content.addView(tools);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(Text.CFG_TITLE)
                .setView(scroll)
                .setPositiveButton(Text.CFG_BUILD, null)
                .setNegativeButton("بستن", null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        profile.uuid = uuid.getText().toString().trim();
                        profile.path = path.getText().toString().trim();
                        profile.host = host.getText().toString().trim();
                        profile.sni = sni.getText().toString().trim();
                        profile.fingerprint = fp.getText().toString().trim();
                        profile.alpn = alpn.getText().toString().trim();
                        profile.serviceName = service.getText().toString().trim();
                        if (profile.uuid.isEmpty()) {
                            Toast.makeText(activity, Text.CFG_NO_UUID, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String links = ConfigLinks.buildAll(profile, usable, config.domain);
                        if (links.isEmpty()) {
                            Toast.makeText(activity, Text.CFG_NOTHING, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Saver.copy(activity, "configs", links);
                        dialog.dismiss();
                        Toast.makeText(activity,
                                Text.CFG_BUILT.replace("%s", Text.fa(profile.limit)),
                                Toast.LENGTH_SHORT).show();
                        text(activity, Text.CONFIGS, links, "configs");
                    }
                });
            }
        });
        paste.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String clip = Saver.clipboard(activity);
                ConfigLinks.Profile parsed = ConfigLinks.fromLink(clip);
                if (parsed == null) {
                    Toast.makeText(activity, Text.CFG_PARSE_FAILED, Toast.LENGTH_SHORT).show();
                    return;
                }
                uuid.setText(parsed.uuid);
                path.setText(parsed.path);
                host.setText(parsed.host);
                sni.setText(parsed.sni);
                fp.setText(parsed.fingerprint);
                alpn.setText(parsed.alpn);
                service.setText(parsed.serviceName);
                profile.protocol = parsed.protocol;
                profile.network = parsed.network;
                Widgets.Chip protoChip = protoChips.get(0);
                for (Widgets.Chip chip : protoChips) {
                    if (chip.value.equals(parsed.protocol)) {
                        protoChip = chip;
                    }
                }
                Widgets.selectOnly(protoChips, protoChip);
                Widgets.Chip netChip = netChips.get(0);
                for (Widgets.Chip chip : netChips) {
                    if (chip.value.equals(parsed.network)) {
                        netChip = chip;
                    }
                }
                Widgets.selectOnly(netChips, netChip);
                Toast.makeText(activity, Text.CFG_PARSED, Toast.LENGTH_SHORT).show();
            }
        });
        bpb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                path.setText("/?ed=2560");
                host.setText(config.domain);
                sni.setText(config.domain);
                fp.setText("chrome");
                Widgets.Chip ws = netChips.get(0);
                Widgets.selectOnly(netChips, ws);
                profile.network = "ws";
            }
        });
        dialog.show();
    }
}
