package com.ahuramazda.cleanip;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Small factory for the programmatic (layout-xml free) UI. */
public final class Widgets {

    private Widgets() {
    }

    public static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        Theme.setRtl(layout);
        return layout;
    }

    public static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        Theme.setRtl(layout);
        return layout;
    }

    public static LinearLayout.LayoutParams lp(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    public static LinearLayout.LayoutParams matchWidth(Context context, int heightDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                heightDp <= 0 ? ViewGroup.LayoutParams.WRAP_CONTENT : Theme.dp(context, heightDp));
        return params;
    }

    public static LinearLayout card(Context context) {
        LinearLayout card = column(context);
        card.setBackground(Theme.card(context));
        int pad = Theme.dp(context, 14);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams params = matchWidth(context, 0);
        params.bottomMargin = Theme.dp(context, 12);
        card.setLayoutParams(params);
        return card;
    }

    public static TextView text(Context context, CharSequence value, float sizeSp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Theme.bold());
        }
        view.setLineSpacing(Theme.dp(context, 3), 1f);
        return view;
    }

    public static TextView sectionTitle(Context context, String value) {
        TextView view = text(context, value, 15f, Theme.GOLD, true);
        view.setPadding(0, 0, 0, Theme.dp(context, 8));
        return view;
    }

    public static TextView note(Context context, String value) {
        TextView view = text(context, value, 11.5f, Theme.TEXT_DIM, false);
        view.setPadding(0, Theme.dp(context, 6), 0, 0);
        return view;
    }

    public static TextView header(Context context, String title, String subtitle) {
        TextView view = text(context, title + "\n" + subtitle, 13f, Theme.TEXT_DIM, false);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        view.setPadding(Theme.dp(context, 4), Theme.dp(context, 8), Theme.dp(context, 4), Theme.dp(context, 16));
        return view;
    }

    public static EditText field(Context context, String hint, int lines) {
        EditText field = new EditText(context);
        field.setHint(hint);
        field.setHintTextColor(0xFF5B6981);
        field.setTextColor(Theme.TEXT);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        field.setBackground(Theme.rounded(Theme.CARD_SOFT, 12, Theme.STROKE, context));
        int pad = Theme.dp(context, 12);
        field.setPadding(pad, pad, pad, pad);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        if (lines > 1) {
            field.setSingleLine(false);
            field.setMinLines(lines);
            field.setGravity(Gravity.TOP | Gravity.RIGHT);
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        }
        field.setLayoutParams(matchWidth(context, 0));
        return field;
    }

    public static Button button(Context context, String label, boolean primary) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, primary ? 16f : 13f);
        button.setTypeface(Theme.bold());
        if (primary) {
            button.setBackground(Theme.selectable(context, Theme.GOLD, Theme.GOLD_DEEP));
            button.setTextColor(Theme.ON_GOLD);
        } else {
            button.setBackground(Theme.selectable(context, Theme.CARD_SOFT, Theme.STROKE));
            button.setTextColor(Theme.TEXT);
        }
        button.setMinHeight(Theme.dp(context, primary ? 52 : 42));
        button.setPadding(Theme.dp(context, 10), Theme.dp(context, 6), Theme.dp(context, 10), Theme.dp(context, 6));
        return button;
    }

    /** A tappable pill used for choices (ports, presets, counts...). */
    public static class Chip extends TextView {
        public final String value;
        private boolean selected;

        Chip(Context context, String value) {
            this(context, value, value);
        }

        Chip(Context context, String label, String value) {
            super(context);
            this.value = value;
            setText(label);
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            setTypeface(Theme.bold());
            setGravity(Gravity.CENTER);
            setPadding(Theme.dp(context, 14), Theme.dp(context, 9), Theme.dp(context, 14), Theme.dp(context, 9));
            setBackground(Theme.selectable(context, Theme.CARD_SOFT, Theme.GOLD));
            setSelectedChip(false);
        }

        public boolean isChipSelected() {
            return selected;
        }

        public void setSelectedChip(boolean value) {
            selected = value;
            setSelected(value);
            setTextColor(value ? Theme.ON_GOLD : Theme.TEXT);
        }
    }

    public interface ChipListener {
        void onChip(Chip chip);
    }

    /** A horizontally scrollable row of chips; returns the row view. */
    public static HorizontalScrollView chips(Context context, List<Chip> chipList, final ChipListener listener) {
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout container = row(context);
        container.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        for (int i = 0; i < chipList.size(); i++) {
            final Chip chip = chipList.get(i);
            if (listener != null) {
                chip.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        listener.onChip(chip);
                    }
                });
            }
            LinearLayout.LayoutParams params = lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(Theme.dp(context, 4), Theme.dp(context, 4), Theme.dp(context, 4), Theme.dp(context, 4));
            container.addView(chip, params);
        }
        scroll.addView(container);
        scroll.setLayoutParams(matchWidth(context, 0));
        return scroll;
    }

    public static List<Chip> chipList(Context context, String[] values) {
        List<Chip> chips = new ArrayList<Chip>();
        for (String value : values) {
            chips.add(new Chip(context, value));
        }
        return chips;
    }

    /** Single choice inside a chip row. */
    public static void selectOnly(List<Chip> chips, Chip selected) {
        for (Chip chip : chips) {
            chip.setSelectedChip(chip == selected);
        }
    }

    public static void setChipValuesEnabled(List<Chip> chips, boolean enabled) {
        for (Chip chip : chips) {
            chip.setEnabled(enabled);
            chip.setAlpha(enabled ? 1f : 0.45f);
        }
    }

    /** Label + switch row. */
    public static class Toggle {
        public final LinearLayout view;
        public final Switch toggle;

        Toggle(Context context, String label, boolean checked) {
            view = row(context);
            view.setPadding(0, Theme.dp(context, 6), 0, Theme.dp(context, 6));
            toggle = new Switch(context);
            toggle.setChecked(checked);
            toggle.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            TextView text = text(context, label, 13f, Theme.TEXT, false);
            text.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            view.addView(toggle);
            view.addView(text);
        }

        public boolean isChecked() {
            return toggle.isChecked();
        }

        public void setChecked(boolean value) {
            toggle.setChecked(value);
        }
    }

    public static Toggle toggle(Context context, String label, boolean checked) {
        return new Toggle(context, label, checked);
    }

    /** Small badge (used for the quality of a result). */
    public static TextView badge(Context context, String label, int color) {
        TextView view = text(context, label, 12f, Theme.ON_GOLD, true);
        view.setPadding(Theme.dp(context, 10), Theme.dp(context, 5), Theme.dp(context, 10), Theme.dp(context, 5));
        view.setBackground(Theme.pill(color, context));
        view.setTextColor(0xFF0C1017);
        return view;
    }

    public static TextView value(Context context, String value, int color) {
        TextView view = text(context, value, 13f, color, true);
        return view;
    }

    public static LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    public static LinearLayout.LayoutParams weightedMatch() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
    }

    public static ScrollView scroll(Context context, View child) {
        ScrollView scroll = new ScrollView(context);
        scroll.addView(child);
        scroll.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.setBackgroundColor(Theme.BG);
        return scroll;
    }

    public static void margin(View view, Context context, int left, int top, int right, int bottom) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            margins.setMargins(Theme.dp(context, left), Theme.dp(context, top),
                    Theme.dp(context, right), Theme.dp(context, bottom));
            view.setLayoutParams(margins);
        }
    }

    public static void space(Context context, LinearLayout parent, int heightDp) {
        View spacer = new View(context);
        parent.addView(spacer, lp(ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(context, heightDp)));
    }

    public static Drawable transparent() {
        return Theme.rounded(Color.TRANSPARENT, 0, 0, null);
    }
}
