package com.bubble.bdshost;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private TextView status;
    private TextView console;
    private TextView progressText;
    private ProgressBar installProgress;
    private Button installButton;
    private Button startButton;
    private Button stopButton;
    private EditText command;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean installerBusy;
    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        AppPaths.ensure(this);
        buildUi();
        requestNotificationPermission();
        refreshStatus();
        handler.post(logUpdater);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("BUBBLE BDS HOST");
        title.setTextSize(27);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setPadding(0, 0, 0, dp(14));
        root.addView(title, matchWrap());

        status = new TextView(this);
        status.setTextSize(16);
        status.setTextColor(Color.DKGRAY);
        status.setPadding(dp(12), dp(12), dp(12), dp(12));
        status.setBackgroundColor(0xffeeeeee);
        root.addView(status, matchWrap());

        progressText = new TextView(this);
        progressText.setText("Pronto para preparar o ambiente");
        progressText.setTextSize(13);
        progressText.setTextColor(Color.DKGRAY);
        progressText.setPadding(dp(2), dp(14), dp(2), dp(4));
        root.addView(progressText, matchWrap());

        installProgress = new ProgressBar(
                this, null, android.R.attr.progressBarStyleHorizontal);
        installProgress.setMax(100);
        installProgress.setProgress(0);
        root.addView(installProgress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(14)));

        installButton = button("INSTALAR / PREPARAR TUDO");
        root.addView(installButton, marginParams(0, 10));
        installButton.setOnClickListener(v -> installAll());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        startButton = button("▶ INICIAR");
        stopButton = button("■ PARAR");
        row.addView(startButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(52), 1);
        sp.setMargins(dp(8), 0, 0, 0);
        row.addView(stopButton, sp);
        root.addView(row, marginParams(0, 12));

        startButton.setOnClickListener(v -> {
            Intent i = new Intent(this, BdsService.class).setAction(BdsService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        });
        stopButton.setOnClickListener(v ->
                startService(new Intent(this, BdsService.class).setAction(BdsService.ACTION_STOP)));

        TextView ct = new TextView(this);
        ct.setText("Console");
        ct.setTextSize(18);
        ct.setTextColor(Color.BLACK);
        ct.setPadding(0, dp(14), 0, dp(6));
        root.addView(ct, matchWrap());

        console = new TextView(this);
        console.setTextSize(12);
        console.setTextColor(0xffd8ffd8);
        console.setBackgroundColor(0xff101510);
        console.setPadding(dp(10), dp(10), dp(10), dp(10));
        console.setMinHeight(dp(260));
        console.setTextIsSelectable(true);
        root.addView(console, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));

        LinearLayout cmdRow = new LinearLayout(this);
        cmdRow.setOrientation(LinearLayout.HORIZONTAL);
        command = new EditText(this);
        command.setSingleLine(true);
        command.setHint("say Olá mundo");
        Button send = button("ENVIAR");
        cmdRow.addView(command, new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams sendP = new LinearLayout.LayoutParams(dp(100), dp(52));
        sendP.setMargins(dp(8), 0, 0, 0);
        cmdRow.addView(send, sendP);
        root.addView(cmdRow, marginParams(0, 8));
        send.setOnClickListener(v -> sendCommand());
        command.setOnEditorActionListener((v, actionId, event) -> {
            sendCommand();
            return true;
        });

        TextView note = new TextView(this);
        note.setText(
                "v0.2.1 • PRoot clássico • Debian Trixie ARM64 • " +
                "Box64 0.4.4 compilado localmente • BDS 1.26.52.3\n" +
                "Retomada automática após interrupções do dpkg/apt.\n" +
                "O Box64 é compilado dentro do Debian no próprio Android.\n" +
                "O BDS é baixado diretamente de minecraft.net.");
        note.setTextSize(12);
        note.setTextColor(Color.GRAY);
        note.setPadding(0, dp(16), 0, 0);
        root.addView(note, matchWrap());

        setContentView(scroll);
    }

    private void installAll() {
        if (installerBusy) return;
        installerBusy = true;
        installButton.setEnabled(false);
        installButton.setText("PREPARANDO...");
        installProgress.setProgress(0);
        progressText.setText("Iniciando...");
        console.setText("");

        executor.submit(() -> {
            try {
                InstallerManager.Callback callback = new InstallerManager.Callback() {
                    @Override public void log(String text) {
                        appendConsole(text);
                    }

                    @Override public void progress(int percent, String stage) {
                        runOnUiThread(() -> {
                            installProgress.setProgress(percent);
                            progressText.setText(stage + " • " + percent + "%");
                        });
                    }
                };

                InstallerManager im = new InstallerManager(this, callback);
                im.installAll();
                appendConsole("\n=== AMBIENTE PRONTO ===");
            } catch (Throwable e) {
                appendConsole("\nERRO: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                runOnUiThread(() -> progressText.setText(
                        "Interrompido — toque em INSTALAR / PREPARAR TUDO para retomar"));
            } finally {
                installerBusy = false;
                runOnUiThread(() -> {
                    installButton.setEnabled(true);
                    installButton.setText("INSTALAR / PREPARAR TUDO");
                    refreshStatus();
                });
            }
        });
    }

    private void sendCommand() {
        String text = command.getText().toString().trim();
        if (text.isEmpty()) return;
        Intent i = new Intent(this, BdsService.class).setAction(BdsService.ACTION_COMMAND);
        i.putExtra(BdsService.EXTRA_COMMAND, text);
        startService(i);
        command.setText("");
    }

    private void appendConsole(String text) {
        runOnUiThread(() -> {
            CharSequence old = console.getText();
            String next = old + (old.length() == 0 ? "" : "\n") + text;
            if (next.length() > 30000) next = next.substring(next.length() - 30000);
            console.setText(next);
        });
    }

    private void refreshStatus() {
        boolean runtime = RuntimeEngine.runtimePresent(this);
        boolean linux = new File(AppPaths.rootfs(this), "bin/bash").exists();
        boolean box64 = AppPaths.box64(this).exists();
        boolean bds = new File(AppPaths.bds(this), "bedrock_server").exists();
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?";
        status.setText("Arquitetura: " + abi + "\n" +
                "PRoot clássico: " + yes(runtime) + "\n" +
                "Debian Trixie: " + yes(linux) + "\n" +
                "Box64 local: " + yes(box64) + "\n" +
                "BDS: " + yes(bds) + "\n" +
                "Porta padrão: UDP 19132");
        startButton.setEnabled(linux && box64 && bds && runtime);
    }

    private final Runnable logUpdater = new Runnable() {
        @Override public void run() {
            try {
                File f = AppPaths.serverLog(MainActivity.this);
                if (f.exists() && !installerBusy) {
                    long len = f.length();
                    long start = Math.max(0, len - 16000);
                    byte[] data = new byte[(int)(len - start)];
                    try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                        raf.seek(start);
                        raf.readFully(data);
                    }
                    console.setText(new String(data, StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {}
            handler.postDelayed(this, 1000);
        }
    };

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    private String yes(boolean x) { return x ? "✓ PRONTO" : "— NÃO INSTALADO"; }
    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        return b;
    }
    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }
    private LinearLayout.LayoutParams marginParams(int horizontal, int top) {
        LinearLayout.LayoutParams p = matchWrap();
        p.setMargins(dp(horizontal), dp(top), dp(horizontal), 0);
        return p;
    }
    private int dp(int x) {
        return (int)(x * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(logUpdater);
        executor.shutdownNow();
        super.onDestroy();
    }
}
