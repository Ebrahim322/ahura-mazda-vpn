package com.ahuramazda.cleanip;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ahuramazda.cleanip.core.ProbeResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Row list of probe results (built in code, recycled by hand). */
public class ResultAdapter extends BaseAdapter {

    private final Context context;
    private final List<ProbeResult> all = new ArrayList<ProbeResult>();
    private final List<ProbeResult> shown = new ArrayList<ProbeResult>();
    private boolean onlyAlive;

    public ResultAdapter(Context context, boolean onlyAlive) {
        this.context = context;
        this.onlyAlive = onlyAlive;
    }

    public void setOnlyAlive(boolean value) {
        onlyAlive = value;
        rebuild();
    }

    public boolean isOnlyAlive() {
        return onlyAlive;
    }

    public void setAll(List<ProbeResult> results) {
        all.clear();
        all.addAll(results);
        rebuild();
    }

    public void addAll(List<ProbeResult> results) {
        if (results.isEmpty()) {
            return;
        }
        all.addAll(results);
        cull();
        rebuild();
    }

    private void cull() {
        if (all.size() > 2000) {
            com.ahuramazda.cleanip.core.Scanner.sort(all);
            List<ProbeResult> trimmed = new ArrayList<ProbeResult>(all.subList(0, 2000));
            all.clear();
            all.addAll(trimmed);
        }
    }

    private void rebuild() {
        shown.clear();
        for (ProbeResult result : all) {
            if (!onlyAlive || result.isUsable()) {
                shown.add(result);
            }
        }
        com.ahuramazda.cleanip.core.Scanner.sort(shown);
        notifyDataSetChanged();
    }

    public List<ProbeResult> items() {
        return all;
    }

    public List<ProbeResult> shown() {
        List<ProbeResult> copy = new ArrayList<ProbeResult>(shown);
        Collections.reverse(copy);
        return copy;
    }

    @Override
    public int getCount() {
        return shown.size();
    }

    @Override
    public Object getItem(int position) {
        return shown.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    private static final class Row {
        TextView rank;
        TextView title;
        TextView sub;
        TextView badge;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Row row;
        if (convertView == null) {
            LinearLayout line = Widgets.row(context);
            line.setBackground(Theme.rounded(Theme.CARD, 14, Theme.STROKE, context));
            int pad = Theme.dp(context, 12);
            line.setPadding(pad, pad, pad, pad);

            row = new Row();
            row.rank = Widgets.text(context, "", 13f, Theme.TEXT_DIM, true);
            row.rank.setGravity(Gravity.CENTER);
            row.rank.setWidth(Theme.dp(context, 34));
            line.addView(row.rank);

            LinearLayout column = Widgets.column(context);
            column.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.title = Widgets.text(context, "", 14.5f, Theme.TEXT, true);
            row.title.setTypeface(Theme.mono());
            row.sub = Widgets.text(context, "", 11f, Theme.TEXT_DIM, false);
            column.addView(row.title);
            column.addView(row.sub);
            line.addView(column);

            row.badge = Widgets.badge(context, "", Theme.TEXT_DIM);
            line.addView(row.badge);

            line.setMinimumHeight(Theme.dp(context, 56));
            convertView = line;
            convertView.setTag(row);
        } else {
            row = (Row) convertView.getTag();
        }

        final ProbeResult result = shown.get(position);
        row.rank.setText(Text.fa(position + 1));
        row.title.setText(result.endPoint());

        StringBuilder sub = new StringBuilder();
        if (result.isUsable()) {
            sub.append("پینگ ").append(Text.fa(result.latencyMs())).append("ms");
            if (result.speedBps > 0) {
                sub.append(" · ").append(Text.speed(result.speedBps));
            } else if (result.speedBps == 0) {
                sub.append(" · تست سرعت نشد");
            }
            if (result.isCloudflareEdge()) {
                sub.append(" · Cloudflare");
            } else if (result.isFastlyEdge()) {
                sub.append(" · Fastly");
            } else if (result.serverName.length() > 0) {
                sub.append(" · ").append(result.serverName);
            }
            if (result.statusCode > 0) {
                sub.append(" · ").append(Text.fa(result.statusCode));
            }
        } else {
            sub.append(Text.error(result.errorKind));
            if (result.tcpOk) {
                sub.append(" · TCP ok");
            }
        }
        row.sub.setText(sub.toString());
        row.badge.setText(Text.grade(result.grade()));
        row.badge.setBackground(Theme.pill(Theme.gradeColor(result.grade()), context));

        convertView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Saver.copy(context, "ip", result.ip);
                android.widget.Toast.makeText(context, result.ip + " — " + Text.COPIED,
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        return convertView;
    }
}
