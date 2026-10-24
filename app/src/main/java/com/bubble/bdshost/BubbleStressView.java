package com.bubble.bdshost;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Stress test panel reusable below the home console and inside the floating Bubble. */
public final class BubbleStressView extends LinearLayout {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Button[] options = new Button[4];
    private final LinearLayout body;
    private final TextView heading, status, result;
    private final Button run, copy;
    private int selected = 10;
    private boolean expanded;
    private boolean attached;
    private String report = "";

    public BubbleStressView(Context context, boolean initiallyExpanded) {
        super(context);
        expanded = initiallyExpanded;
        setOrientation(VERTICAL);
        setPadding(dp(12), dp(12), dp(12), dp(12));
        setBackground(background(Color.rgb(39, 13, 21), Color.rgb(184, 43, 66), 16));

        heading = text("◉  STRESS TEST   " + (expanded ? "▴" : "▾"), 15, Color.WHITE, true);
        heading.setPadding(dp(3), dp(3), dp(3), dp(9));
        heading.setOnClickListener(v -> {
            expanded = !expanded;
            updateExpanded();
        });
        addView(heading);

        body = new LinearLayout(context);
        body.setOrientation(VERTICAL);
        body.addView(text("Consultas querytarget @a", 12, Color.rgb(233, 213, 218), false));

        LinearLayout chips = new LinearLayout(context);
        chips.setOrientation(HORIZONTAL);
        int[] counts = {10, 20, 30, 40};
        for (int i = 0; i < counts.length; i++) {
            final int count = counts[i];
            Button choice = new Button(context);
            choice.setAllCaps(false);
            choice.setText(String.valueOf(count));
            choice.setTextSize(12);
            choice.setMinHeight(0);
            choice.setMinimumHeight(0);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(43), 1f);
            p.setMargins(dp(2), dp(5), dp(2), dp(4));
            chips.addView(choice, p);
            choice.setOnClickListener(v -> {
                if (BubbleStressState.read(getContext()).getBoolean("running", false)) return;
                selected = count;
                updateChips();
            });
            options[i] = choice;
        }
        body.addView(chips);
        updateChips();

        status = text("Requer BDS online. Teste sequencial.", 12, Color.rgb(231, 221, 223), false);
        status.setPadding(dp(3), dp(5), dp(3), dp(6));
        body.addView(status);

        run = new Button(context);
        run.setAllCaps(false);
        run.setText("▶  Iniciar teste");
        run.setTextSize(12);
        run.setTextColor(Color.WHITE);
        run.setBackground(background(Color.rgb(192, 27, 52), Color.rgb(234, 79, 100), 10));
        body.addView(run, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(45)));
        run.setOnClickListener(v -> launchTest());

        ScrollView results = new ScrollView(context);
        results.setFillViewport(false);
        result = text("Nenhum teste realizado.", 10, Color.rgb(233, 224, 225), false);
        result.setTypeface(Typeface.MONOSPACE);
        result.setTextIsSelectable(true);
        result.setPadding(dp(5), dp(8), dp(5), dp(8));
        results.addView(result);
        LinearLayout.LayoutParams resultsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(125));
        resultsParams.setMargins(0, dp(7), 0, dp(5));
        results.setBackground(background(Color.rgb(20, 9, 13), Color.rgb(93, 42, 52), 9));
        body.addView(results, resultsParams);

        copy = new Button(context);
        copy.setAllCaps(false);
        copy.setText("Copiar relatório");
        copy.setTextSize(12);
        copy.setTextColor(Color.WHITE);
        copy.setBackground(background(Color.rgb(91, 42, 54), Color.rgb(160, 86, 99), 9));
        body.addView(copy, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        copy.setOnClickListener(v -> {
            if (report.isEmpty()) return;
            ClipboardManager cm = (ClipboardManager) getContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Bubble Stress Test", report));
                Toast.makeText(getContext(), "Relatório copiado", Toast.LENGTH_SHORT).show();
            }
        });
        addView(body);
        updateExpanded();
        refresh();
    }

    private void launchTest() {
        if (!"running".equals(BdsService.state(getContext()))) {
            Toast.makeText(getContext(), "Inicie o BDS antes de testar", Toast.LENGTH_SHORT).show();
            return;
        }
        if (BubbleStressState.read(getContext()).getBoolean("running", false)) return;
        Intent intent = new Intent(getContext(), BdsService.class)
                .setAction(BdsService.ACTION_STRESS_TEST)
                .putExtra(BdsService.EXTRA_STRESS_COUNT, selected);
        try {
            getContext().startService(intent);
            status.setText("Solicitando teste de " + selected + " consultas...");
        } catch (Exception ex) {
            status.setText("Falha ao iniciar: " + ex.getMessage());
        }
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!attached) return;
            refresh();
            handler.postDelayed(this, 500);
        }
    };

    private void refresh() {
        SharedPreferences p = BubbleStressState.read(getContext());
        boolean busy = p.getBoolean("running", false);
        boolean online = "running".equals(BdsService.state(getContext()));
        int done = p.getInt("completed", 0);
        int requested = p.getInt("requested", 0);
        String statusMessage = p.getString("status", "Aguardando");
        status.setText(busy ? "Executando " + done + "/" + requested + "..."
                : statusMessage + (online ? " • BDS online" : " • BDS offline"));
        run.setEnabled(online && !busy);
        run.setAlpha(online && !busy ? 1f : .50f);
        for (Button option : options) option.setEnabled(!busy);
        String nextReport = p.getString("report", "");
        if (nextReport == null) nextReport = "";
        if (!nextReport.equals(report)) {
            report = nextReport;
            result.setText(report.isEmpty() ? "Nenhum relatório disponível." : report);
        }
        copy.setEnabled(!report.isEmpty());
        copy.setAlpha(report.isEmpty() ? .5f : 1f);
    }

    private void updateExpanded() {
        body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        heading.setText("◉  STRESS TEST   " + (expanded ? "▴" : "▾"));
    }

    private void updateChips() {
        int[] counts = {10, 20, 30, 40};
        for (int i = 0; i < counts.length; i++) {
            boolean selectedChoice = counts[i] == selected;
            options[i].setTextColor(selectedChoice ? Color.WHITE : Color.rgb(58, 21, 30));
            options[i].setBackground(background(selectedChoice
                    ? Color.rgb(200, 31, 57) : Color.rgb(234, 217, 220),
                    selectedChoice ? Color.rgb(255, 130, 144) : Color.rgb(215, 168, 178), 20));
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        handler.removeCallbacks(ticker);
        super.onDetachedFromWindow();
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView v = new TextView(getContext());
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(null, Typeface.BOLD);
        return v;
    }

    private GradientDrawable background(int fill, int border, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setStroke(dp(1), border);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(float value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
