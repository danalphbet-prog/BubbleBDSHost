package com.bubble.bdshost;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int RED = Color.rgb(255, 20, 43);
    private static final int WHITE = Color.WHITE;
    private static final int CARD = Color.rgb(46, 14, 18);
    private static final int TERMINAL = Color.rgb(14, 13, 15);
    private static final int MUTED = Color.rgb(210, 198, 202);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private TextView consoleText, consoleToggle, primaryButton, stageText;
    private ScrollView consoleScroll, fullConsoleScroll;
    private TextView fullConsoleText;
    private ProgressBar installProgress, spinner;
    private EditText worldName, seed;
    private Switch cheats;
    private TextView survival, creative;
    private boolean creativeMode, collapsed, installerBusy;
    private Dialog fullDialog;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(RED);
        getWindow().setNavigationBarColor(RED);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        AppPaths.ensure(this);
        requestNotificationPermission();
        render();
        handler.post(logUpdater);
    }

    @Override protected void onResume() {
        super.onResume();
        renderIfNeeded();
    }

    private boolean ready() {
        return RuntimeEngine.runtimePresent(this)
                && new File(AppPaths.rootfs(this), "bin/bash").exists()
                && AppPaths.box64(this).exists()
                && new File(AppPaths.bds(this), "bedrock_server").exists();
    }

    private void renderIfNeeded() {
        Object tag = getWindow().getDecorView().getTag();
        boolean r = ready();
        if (!(tag instanceof Boolean) || ((Boolean) tag) != r) render();
    }

    private void render() {
        boolean r = ready();
        getWindow().getDecorView().setTag(r);
        if (r) dashboard();
        else installer();
    }

    private GradientDrawable pageBg() {
        return gradient(new int[]{
                Color.rgb(255,34,56), Color.rgb(248,9,38), Color.rgb(220,0,27)}, 0);
    }

    private GradientDrawable gradient(int[] colors, float radius) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, colors);
        g.setCornerRadius(dp(radius));
        return g;
    }

    private GradientDrawable box(int fill, int stroke, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        g.setStroke(dp(1), stroke);
        return g;
    }

    private LinearLayout root() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(18), dp(18), dp(18), dp(28));
        r.setBackground(pageBg());
        return r;
    }

    private ScrollView page(LinearLayout root) {
        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        s.addView(root);
        return s;
    }

    private TextView tv(String text, float size, int color, int style) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setTypeface(Typeface.create("sans-serif", style));
        return v;
    }

    private LinearLayout header() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(18));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.bubble_app_icon);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        row.addView(logo, new LinearLayout.LayoutParams(dp(78), dp(78)));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(12), 0, 0, 0);
        words.addView(tv("BUBBLE", 31, WHITE, Typeface.BOLD));
        words.addView(tv("B D S   H O S T", 16, WHITE, Typeface.NORMAL));
        words.addView(tv("Minecraft Bedrock Dedicated Server Host",
                11, Color.argb(215,255,235,238), Typeface.NORMAL));
        row.addView(words, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private TextView smallButton(String glyph) {
        TextView b = tv(glyph, 23, WHITE, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackground(box(Color.argb(48,255,255,255),
                Color.argb(55,255,255,255), 13));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private TextView mainButton(String label) {
        TextView b = tv(label, 21, WHITE, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(18), dp(16), dp(18));
        b.setBackground(gradient(new int[]{
                Color.rgb(255,54,76), Color.rgb(255,20,43),
                Color.rgb(237,18,48)}, 36));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private void installer() {
        LinearLayout root = root();
        root.addView(header());

        root.addView(consoleCard(true), match(0,0,0,16));

        primaryButton = mainButton("⇩    Instalar Tudo    ›");
        primaryButton.setOnClickListener(v -> installAll());
        root.addView(primaryButton, match(0,0,0,10));

        LinearLayout loading = new LinearLayout(this);
        loading.setGravity(Gravity.CENTER);
        spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(WHITE));
        spinner.setVisibility(View.INVISIBLE);
        loading.addView(spinner, new LinearLayout.LayoutParams(dp(30), dp(30)));

        stageText = tv("Pronto para preparar o ambiente",
                14, Color.argb(230,255,239,242), Typeface.NORMAL);
        stageText.setPadding(dp(10),0,0,0);
        loading.addView(stageText);
        root.addView(loading, match(0,0,0,8));

        installProgress = new ProgressBar(
                this, null, android.R.attr.progressBarStyleHorizontal);
        installProgress.setMax(100);
        installProgress.setProgressTintList(ColorStateList.valueOf(WHITE));
        installProgress.setProgressBackgroundTintList(
                ColorStateList.valueOf(Color.argb(70,255,255,255)));
        root.addView(installProgress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));

        setContentView(page(root));
        refreshLog(AppPaths.installLog(this));
    }

    private LinearLayout consoleCard(boolean installation) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(12));
        card.setBackground(box(Color.rgb(36,15,18),
                Color.argb(125,255,72,94), 22));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView term = smallButton(">_");
        head.addView(term, new LinearLayout.LayoutParams(dp(56), dp(52)));

        LinearLayout title = new LinearLayout(this);
        title.setOrientation(LinearLayout.VERTICAL);
        title.setPadding(dp(12),0,0,0);
        title.addView(tv(installation ? "Console de Instalação" : "Console do Servidor",
                18, WHITE, Typeface.BOLD));
        title.addView(tv(installation ? "Logs da preparação do ambiente" : "Logs em tempo real",
                12, MUTED, Typeface.NORMAL));
        head.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        consoleToggle = smallButton("⌄");
        TextView expand = smallButton("↗");
        TextView full = smallButton("⛶");
        head.addView(consoleToggle, new LinearLayout.LayoutParams(dp(52),dp(52)));
        LinearLayout.LayoutParams xp = new LinearLayout.LayoutParams(dp(52),dp(52));
        xp.setMargins(dp(6),0,0,0);
        head.addView(expand, xp);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(dp(52),dp(52));
        fp.setMargins(dp(6),0,0,0);
        head.addView(full, fp);
        card.addView(head);

        consoleScroll = new ScrollView(this);
        consoleScroll.setFillViewport(true);
        consoleScroll.setBackground(box(TERMINAL, Color.rgb(45,40,43), 14));

        consoleText = tv("", 11.5f, Color.rgb(235,231,233), Typeface.NORMAL);
        consoleText.setTypeface(Typeface.MONOSPACE);
        consoleText.setTextIsSelectable(true);
        consoleText.setPadding(dp(12),dp(12),dp(12),dp(12));
        consoleScroll.addView(consoleText);

        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(installation ? 420 : 210));
        cp.setMargins(0,dp(10),0,0);
        card.addView(consoleScroll, cp);

        consoleToggle.setOnClickListener(v -> collapse(!collapsed, installation));
        expand.setOnClickListener(v -> {
            collapsed = false;
            ViewGroup.LayoutParams p = consoleScroll.getLayoutParams();
            p.height = dp(installation ? 520 : 340);
            consoleScroll.setLayoutParams(p);
            consoleToggle.setText("⌃");
            bottom();
        });
        full.setOnClickListener(v -> fullscreen(
                installation ? AppPaths.installLog(this) : AppPaths.serverLog(this)));
        return card;
    }

    private void collapse(boolean value, boolean installation) {
        collapsed = value;
        ViewGroup.LayoutParams p = consoleScroll.getLayoutParams();
        p.height = dp(value ? 72 : (installation ? 420 : 210));
        consoleScroll.setLayoutParams(p);
        consoleToggle.setText(value ? "⌃" : "⌄");
        bottom();
    }

    private void fullscreen(File source) {
        fullDialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout root = root();

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(tv("Console",22,WHITE,Typeface.BOLD),
                new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
        TextView close = smallButton("×");
        top.addView(close,new LinearLayout.LayoutParams(dp(54),dp(54)));
        root.addView(top, match(0,0,0,10));

        fullConsoleScroll = new ScrollView(this);
        fullConsoleScroll.setBackground(box(TERMINAL, Color.rgb(45,40,43),18));
        fullConsoleText = tv("",12,Color.rgb(235,231,233),Typeface.NORMAL);
        fullConsoleText.setTypeface(Typeface.MONOSPACE);
        fullConsoleText.setPadding(dp(14),dp(14),dp(14),dp(14));
        fullConsoleText.setTextIsSelectable(true);
        fullConsoleScroll.addView(fullConsoleText);
        root.addView(fullConsoleScroll,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,0,1));

        close.setOnClickListener(v -> fullDialog.dismiss());
        fullDialog.setContentView(root);
        Window w = fullDialog.getWindow();
        if (w != null) {
            w.setStatusBarColor(RED);
            w.setNavigationBarColor(RED);
        }
        fullDialog.setOnDismissListener(d -> {
            fullDialog = null;
            fullConsoleText = null;
            fullConsoleScroll = null;
        });
        fullDialog.show();
        refreshLog(source);
    }

    private void dashboard() {
        LinearLayout root = root();
        root.addView(header());
        root.addView(statusCard(), match(0,0,0,14));
        root.addView(worldCard(), match(0,0,0,14));
        root.addView(consoleCard(false));

        setContentView(page(root));
        loadConfig();
        refreshLog(AppPaths.serverLog(this));
    }

    private View statusCard() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18),dp(16),dp(18),dp(16));
        row.setBackground(box(Color.argb(42,255,255,255),
                Color.argb(65,255,255,255),22));

        row.addView(tv("●",26,Color.rgb(72,244,151),Typeface.BOLD));
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(dp(10),0,0,0);
        left.addView(tv("Servidor Pronto",20,WHITE,Typeface.BOLD));
        left.addView(tv("BDS preparado no aparelho",12,
                Color.argb(225,255,236,239),Typeface.NORMAL));
        row.addView(left,new LinearLayout.LayoutParams(
                0,ViewGroup.LayoutParams.WRAP_CONTENT,1));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        TextView port = tv("19132",18,WHITE,Typeface.BOLD);
        port.setGravity(Gravity.END);
        TextView label = tv("Porta UDP",11,
                Color.argb(220,255,236,239),Typeface.NORMAL);
        label.setGravity(Gravity.END);
        right.addView(port);
        right.addView(label);
        row.addView(right);
        return row;
    }

    private View worldCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18),dp(18),dp(18),dp(18));
        card.setBackground(box(CARD,Color.argb(78,255,88,108),24));

        card.addView(tv("◈   Configurações do Mundo",23,WHITE,Typeface.BOLD));
        TextView sub = tv("Edite as opções do seu mundo Minecraft",
                13,MUTED,Typeface.NORMAL);
        sub.setPadding(0,dp(2),0,dp(16));
        card.addView(sub);

        card.addView(label("Nome do mundo"));
        worldName = field("Meu Mundo Bubble");
        card.addView(worldName, match(0,6,0,14));

        card.addView(label("Modo de jogo"));
        LinearLayout modes = new LinearLayout(this);
        survival = segment("⚔   Survival",true);
        creative = segment("◇   Criativo",false);
        survival.setOnClickListener(v -> mode(false));
        creative.setOnClickListener(v -> mode(true));
        modes.addView(survival,new LinearLayout.LayoutParams(0,dp(62),1));
        LinearLayout.LayoutParams cr = new LinearLayout.LayoutParams(0,dp(62),1);
        cr.setMargins(dp(8),0,0,0);
        modes.addView(creative,cr);
        card.addView(modes, match(0,6,0,14));

        LinearLayout cheatRow = new LinearLayout(this);
        cheatRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(tv("Cheats",16,MUTED,Typeface.BOLD));
        words.addView(tv("Permite o uso de comandos no servidor",
                12,MUTED,Typeface.NORMAL));
        cheatRow.addView(words,new LinearLayout.LayoutParams(
                0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
        cheats = new Switch(this);
        cheats.setThumbTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},
                new int[]{WHITE,Color.rgb(210,205,207)}));
        cheats.setTrackTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},
                new int[]{RED,Color.rgb(92,72,78)}));
        cheatRow.addView(cheats);
        card.addView(cheatRow, match(0,0,0,14));

        card.addView(label("Seed"));
        seed = field("");
        seed.setInputType(InputType.TYPE_CLASS_TEXT);
        card.addView(seed, match(0,6,0,4));
        card.addView(tv("Deixe em branco para gerar um mundo aleatório",
                11,MUTED,Typeface.NORMAL), match(0,0,0,16));

        primaryButton = mainButton("▶    Iniciar Servidor    ›");
        primaryButton.setOnClickListener(v -> startServer());
        card.addView(primaryButton);
        return card;
    }

    private TextView label(String s) {
        return tv(s,15,MUTED,Typeface.BOLD);
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setTextColor(WHITE);
        e.setHintTextColor(Color.rgb(150,138,143));
        e.setHint(hint);
        e.setTextSize(16);
        e.setPadding(dp(14),0,dp(14),0);
        e.setBackground(box(Color.rgb(39,24,27),Color.rgb(111,90,96),14));
        return e;
    }

    private TextView segment(String s, boolean active) {
        TextView b = tv(s,16,active?WHITE:MUTED,Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setBackground(box(active?Color.rgb(175,22,43):Color.rgb(45,28,32),
                active?Color.rgb(255,68,89):Color.rgb(91,72,77),15));
        return b;
    }

    private void mode(boolean creativeValue) {
        creativeMode = creativeValue;
        survival.setBackground(box(creativeValue?Color.rgb(45,28,32):Color.rgb(175,22,43),
                creativeValue?Color.rgb(91,72,77):Color.rgb(255,68,89),15));
        creative.setBackground(box(creativeValue?Color.rgb(175,22,43):Color.rgb(45,28,32),
                creativeValue?Color.rgb(255,68,89):Color.rgb(91,72,77),15));
        survival.setTextColor(creativeValue?MUTED:WHITE);
        creative.setTextColor(creativeValue?WHITE:MUTED);
    }

    private void installAll() {
        if (installerBusy) return;
        installerBusy = true;
        primaryButton.setEnabled(false);
        primaryButton.setAlpha(.7f);
        spinner.setVisibility(View.VISIBLE);
        stageText.setText("Preparando ambiente...");
        installProgress.setProgress(0);

        executor.submit(() -> {
            try {
                InstallerManager.Callback cb = new InstallerManager.Callback() {
                    @Override public void log(String s) {
                        appendLine(s);
                    }
                    @Override public void progress(int percent, String stage) {
                        runOnUiThread(() -> {
                            installProgress.setProgress(percent);
                            stageText.setText(stage + "  •  " + percent + "%");
                        });
                    }
                };
                new InstallerManager(this,cb).installAll();
                appendLine("=== AMBIENTE PRONTO ===");
                runOnUiThread(() -> {
                    installProgress.setProgress(100);
                    stageText.setText("Ambiente pronto");
                });
                handler.postDelayed(this::render,700);
            } catch (Throwable e) {
                appendLine("ERRO: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                runOnUiThread(() ->
                        stageText.setText("Interrompido — toque em Instalar Tudo para retomar"));
            } finally {
                installerBusy = false;
                runOnUiThread(() -> {
                    if (primaryButton != null) {
                        primaryButton.setEnabled(true);
                        primaryButton.setAlpha(1f);
                    }
                    if (spinner != null) spinner.setVisibility(View.INVISIBLE);
                });
            }
        });
    }

    private void startServer() {
        try {
            saveConfig();
        } catch (Exception e) {
            appendLine("[app] Falha ao salvar configurações: " + e.getMessage());
            return;
        }
        Intent i = new Intent(this,BdsService.class).setAction(BdsService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        primaryButton.setText("✓    Servidor iniciado");
        primaryButton.setEnabled(false);
        handler.postDelayed(() -> {
            if (primaryButton != null) {
                primaryButton.setText("▶    Iniciar Servidor    ›");
                primaryButton.setEnabled(true);
            }
        },2500);
    }

    private void loadConfig() {
        File f = new File(AppPaths.bds(this),"server.properties");
        if (!f.isFile()) return;
        try {
            String t = read(f);
            worldName.setText(prop(t,"level-name","Meu Mundo Bubble"));
            seed.setText(prop(t,"level-seed",""));
            cheats.setChecked("true".equalsIgnoreCase(prop(t,"allow-cheats","false")));
            mode("creative".equalsIgnoreCase(prop(t,"gamemode","survival")));
        } catch (Exception ignored) {}
    }

    private void saveConfig() throws Exception {
        File f = new File(AppPaths.bds(this),"server.properties");
        if (!f.isFile()) throw new IllegalStateException("server.properties não encontrado");
        String t = read(f);
        String name = worldName.getText().toString().trim();
        if (name.isEmpty()) name = "Meu Mundo Bubble";
        t = replace(t,"level-name",name);
        t = replace(t,"gamemode",creativeMode?"creative":"survival");
        t = replace(t,"allow-cheats",cheats.isChecked()?"true":"false");
        t = replace(t,"level-seed",seed.getText().toString().trim());
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(t.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String prop(String text,String key,String fallback) {
        for (String line:text.split("\\R"))
            if (line.startsWith(key+"=")) return line.substring(key.length()+1).trim();
        return fallback;
    }

    private String replace(String text,String key,String value) {
        StringBuilder out = new StringBuilder();
        boolean found = false;
        for (String line:text.split("\\R",-1)) {
            if (line.startsWith(key+"=")) {
                out.append(key).append('=').append(value).append('\n');
                found = true;
            } else out.append(line).append('\n');
        }
        if (!found) out.append(key).append('=').append(value).append('\n');
        return out.toString();
    }

    private String read(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n=in.read(b))>=0) if (n>0) out.write(b,0,n);
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }

    private void appendLine(String s) {
        runOnUiThread(() -> {
            if (consoleText == null) return;
            String old = consoleText.getText().toString();
            String next = old + (old.isEmpty()?"":"\n") + s;
            if (next.length()>50000) next = next.substring(next.length()-50000);
            consoleText.setText(next);
            if (fullConsoleText != null) fullConsoleText.setText(next);
            bottom();
            fullBottom();
        });
    }

    private void refreshLog(File f) {
        try {
            if (!f.isFile()) return;
            long len = f.length();
            long start = Math.max(0,len-30000);
            byte[] data = new byte[(int)(len-start)];
            try (RandomAccessFile raf = new RandomAccessFile(f,"r")) {
                raf.seek(start);
                raf.readFully(data);
            }
            String s = new String(data,StandardCharsets.UTF_8);
            runOnUiThread(() -> {
                if (consoleText != null) consoleText.setText(s);
                if (fullConsoleText != null) fullConsoleText.setText(s);
                bottom();
                fullBottom();
            });
        } catch (Exception ignored) {}
    }

    private void bottom() {
        if (consoleScroll != null)
            consoleScroll.post(() -> consoleScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void fullBottom() {
        if (fullConsoleScroll != null)
            fullConsoleScroll.post(() -> fullConsoleScroll.fullScroll(View.FOCUS_DOWN));
    }

    private final Runnable logUpdater = new Runnable() {
        @Override public void run() {
            if (!installerBusy)
                refreshLog(ready()?AppPaths.serverLog(MainActivity.this):
                        AppPaths.installLog(MainActivity.this));
            handler.postDelayed(this,900);
        }
    };

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},100);
    }

    private LinearLayout.LayoutParams match(int l,int t,int r,int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(l),dp(t),dp(r),dp(b));
        return p;
    }

    private int dp(float x) {
        return (int)(x*getResources().getDisplayMetrics().density+.5f);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(logUpdater);
        executor.shutdownNow();
        super.onDestroy();
    }
}
