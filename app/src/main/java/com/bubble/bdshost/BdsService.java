package com.bubble.bdshost;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

public class BdsService extends Service {
    public static final String ACTION_START = "com.bubble.bdshost.START";
    public static final String ACTION_STOP = "com.bubble.bdshost.STOP";
    public static final String ACTION_COMMAND = "com.bubble.bdshost.COMMAND";
    public static final String EXTRA_COMMAND = "command";
    private static final String CHANNEL = "bds_server";
    private static final int NOTIFICATION_ID = 19132;

    private Process process;
    private BufferedWriter stdin;
    private PowerManager.WakeLock wakeLock;
    private volatile boolean stopping;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_START.equals(action)) startServer();
        else if (ACTION_STOP.equals(action)) stopServerGracefully();
        else if (ACTION_COMMAND.equals(action)) sendCommand(intent.getStringExtra(EXTRA_COMMAND));
        return START_NOT_STICKY;
    }

    private synchronized void startServer() {
        if (process != null && process.isAlive()) return;
        File server = new File(AppPaths.bds(this), "bedrock_server");
        if (!server.exists()) {
            appendLog("[app] BDS não instalado.\n");
            stopSelf();
            return;
        }

        startForeground(NOTIFICATION_ID, notification("Servidor iniciando..."));
        acquireWakeLock();
        stopping = false;

        new Thread(() -> {
            try {
                String cmd = "export BOX64_NOBANNER=1 BOX64_LOG=0 " +
                        "BOX64_DYNAREC_BIGBLOCK=0 BOX64_DYNAREC_STRONGMEM=1; " +
                        "export LD_LIBRARY_PATH=.; exec box64 ./bedrock_server";
                process = RuntimeEngine.start(this, "/root/bds", cmd);
                stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
                updateNotification("Bedrock Server em execução");
                appendLog("\n[app] Processo BDS iniciado.\n");

                try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) appendLog(line + "\n");
                }
                int rc = process.waitFor();
                appendLog("[app] BDS encerrou com código " + rc + "\n");
            } catch (Exception e) {
                appendLog("[app] ERRO: " + e + "\n");
            } finally {
                synchronized (this) {
                    process = null;
                    stdin = null;
                }
                releaseWakeLock();
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }, "bds-runtime").start();
    }

    private synchronized void sendCommand(String command) {
        if (command == null || command.isBlank()) return;
        if (stdin == null || process == null || !process.isAlive()) {
            appendLog("[app] Servidor não está em execução.\n");
            return;
        }
        try {
            stdin.write(command.trim());
            stdin.newLine();
            stdin.flush();
            appendLog("> " + command.trim() + "\n");
        } catch (Exception e) {
            appendLog("[app] Falha ao enviar comando: " + e.getMessage() + "\n");
        }
    }

    private synchronized void stopServerGracefully() {
        if (process == null || !process.isAlive()) {
            stopSelf();
            return;
        }
        stopping = true;
        try {
            if (stdin != null) {
                stdin.write("stop");
                stdin.newLine();
                stdin.flush();
            }
            updateNotification("Encerrando servidor...");
        } catch (Exception e) {
            process.destroy();
        }
    }

    private void appendLog(String text) {
        try {
            AppPaths.ensure(this);
            File f = AppPaths.serverLog(this);
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BubbleBDSHost:server");
            wakeLock.acquire();
        } catch (Exception ignored) {}
    }

    private void releaseWakeLock() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        wakeLock = null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Servidor Bedrock", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Mantém o servidor Bedrock ativo em segundo plano");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, BdsService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("Bubble BDS Host")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setContentIntent(content)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "PARAR", stopPi).build())
                .build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text));
    }

    @Override public void onDestroy() {
        try { if (process != null && process.isAlive()) process.destroy(); } catch (Exception ignored) {}
        releaseWakeLock();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
