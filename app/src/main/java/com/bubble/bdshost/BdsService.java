package com.bubble.bdshost;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;

public class BdsService extends Service {
    public static final String ACTION_START = "com.bubble.bdshost.START";
    public static final String ACTION_STOP = "com.bubble.bdshost.STOP";
    public static final String ACTION_COMMAND = "com.bubble.bdshost.COMMAND";
    public static final String EXTRA_COMMAND = "command";

    private static final String PREFS = "bubble_server_state";
    private static final String CHANNEL = "bds_server";
    private static final int NOTIFICATION_ID = 19132;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Process process;
    private BufferedWriter stdin;
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        handler.post(notificationTicker);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_START.equals(action)) startServer();
        else if (ACTION_STOP.equals(action)) stopServerGracefully();
        else if (ACTION_COMMAND.equals(action))
            sendCommand(intent.getStringExtra(EXTRA_COMMAND));
        return START_NOT_STICKY;
    }

    private synchronized void startServer() {
        if (process != null && process.isAlive()) return;
        File server = new File(AppPaths.bds(this), "bedrock_server");
        if (!server.exists()) {
            appendLog("[bubble] Núcleo do servidor não instalado.\n");
            setState("stopped", 0);
            stopSelf();
            return;
        }

        long started = System.currentTimeMillis();
        setState("starting", started);
        startForeground(NOTIFICATION_ID, notification());
        acquireWakeLock();

        new Thread(() -> {
            try {
                String cmd = "export BOX64_NOBANNER=1 BOX64_LOG=0 " +
                        "BOX64_DYNAREC_BIGBLOCK=0 BOX64_DYNAREC_STRONGMEM=1; " +
                        "export LD_LIBRARY_PATH=.; exec box64 ./bedrock_server";
                process = RuntimeEngine.start(this, "/root/bds", cmd);
                stdin = new BufferedWriter(new OutputStreamWriter(
                        process.getOutputStream(), StandardCharsets.UTF_8));

                setState("running", started);
                appendLog("[bubble] Servidor iniciado.\n");
                updateNotification();

                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) appendLog(line + "\n");
                }

                int rc = process.waitFor();
                appendLog("[bubble] Servidor encerrado com código " + rc + ".\n");
            } catch (Exception e) {
                appendLog("[bubble] ERRO: " + e.getMessage() + "\n");
            } finally {
                synchronized (this) {
                    process = null;
                    stdin = null;
                }
                setState("stopped", 0);
                releaseWakeLock();
                if (Build.VERSION.SDK_INT >= 24) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                } else {
                    stopForeground(true);
                }
                stopSelf();
            }
        }, "bds-runtime").start();
    }

    private synchronized void sendCommand(String command) {
        if (command == null || command.isBlank()) return;
        if (stdin == null || process == null || !process.isAlive()) {
            appendLog("[bubble] Servidor não está em execução.\n");
            return;
        }
        try {
            stdin.write(command.trim());
            stdin.newLine();
            stdin.flush();
            appendLog("> " + command.trim() + "\n");
        } catch (Exception e) {
            appendLog("[bubble] Falha ao enviar comando: " + e.getMessage() + "\n");
        }
    }

    private synchronized void stopServerGracefully() {
        if (process == null || !process.isAlive()) {
            setState("stopped", 0);
            stopSelf();
            return;
        }
        setState("stopping", startedAt(this));
        try {
            if (stdin != null) {
                stdin.write("stop");
                stdin.newLine();
                stdin.flush();
            }
            appendLog("[bubble] Parando servidor...\n");
            updateNotification();
        } catch (Exception e) {
            process.destroy();
        }
    }

    public static boolean isRunning(Context c) {
        String state = state(c);
        return "starting".equals(state)
                || "running".equals(state)
                || "stopping".equals(state);
    }

    public static String state(Context c) {
        return prefs(c).getString("state", "stopped");
    }

    public static long startedAt(Context c) {
        return prefs(c).getLong("started_at", 0);
    }

    private void setState(String state, long startedAt) {
        prefs(this).edit()
                .putString("state", state)
                .putLong("started_at", startedAt)
                .apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void appendLog(String text) {
        try {
            AppPaths.ensure(this);
            try (FileOutputStream out =
                         new FileOutputStream(AppPaths.serverLog(this), true)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "BubbleBDSHost:server");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Exception ignored) {}
    }

    private void releaseWakeLock() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }
        catch (Exception ignored) {}
        wakeLock = null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "Bubble • Servidor", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Status do servidor local em segundo plano");
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification notification() {
        String serverName = property("server-name", "Bubble BDS Host");
        String world = property("level-name", "Mundo Bubble");
        String mode = property("gamemode", "survival");
        String difficulty = property("difficulty", "normal");
        String port = property("server-port", "19132");
        String maxPlayers = property("max-players", "8");
        String ip = getLocalIpv4();
        String state = state(this);
        long started = startedAt(this);

        Intent open = new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_TAB, "home")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent console = new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_TAB, "console")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent consolePi = PendingIntent.getActivity(
                this, 2, console,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stop = new Intent(this, BdsService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String stateLabel = "running".equals(state) ? "Online" :
                "starting".equals(state) ? "Iniciando" :
                "stopping".equals(state) ? "Parando" : "Offline";

        String line = stateLabel + " • " + prettyMode(mode)
                + " • " + ip + ":" + port;

        String big = "Servidor: " + serverName
                + "\nMundo: " + world
                + "\nModo: " + prettyMode(mode)
                + " • " + prettyDifficulty(difficulty)
                + "\nRede local: " + ip + ":" + port
                + "\nJogadores: até " + maxPlayers
                + "\nVersão: " + InstallerManager.readInstalledBdsVersion(this);

        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Bubble • " + world)
                .setContentText(line)
                .setSubText("Servidor local")
                .setStyle(new Notification.BigTextStyle().bigText(big))
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(Color.rgb(255,20,43))
                .setContentIntent(content)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_view, "CONSOLE", consolePi).build())
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_pause, "PARAR", stopPi).build());

        if (started > 0) {
            b.setWhen(started)
                    .setShowWhen(true)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(false);
        } else {
            b.setShowWhen(false);
        }
        return b.build();
    }

    private void updateNotification() {
        if (isRunning(this)) {
            getSystemService(NotificationManager.class)
                    .notify(NOTIFICATION_ID, notification());
        }
    }

    private final Runnable notificationTicker = new Runnable() {
        @Override public void run() {
            updateNotification();
            handler.postDelayed(this, 30000);
        }
    };

    private String property(String key, String fallback) {
        File f = new File(AppPaths.bds(this), "server.properties");
        if (!f.isFile()) return fallback;
        try {
            String text = readText(f);
            for (String line : text.split("\\R")) {
                if (line.startsWith(key + "="))
                    return line.substring(key.length() + 1).trim();
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    private String readText(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) >= 0) {
                if (n > 0) out.write(b,0,n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public static String getLocalIpv4() {
        try {
            for (NetworkInterface nif :
                    Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (java.net.InetAddress addr :
                        Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address
                            && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    private static String prettyMode(String mode) {
        return "creative".equalsIgnoreCase(mode) ? "Criativo" :
                "adventure".equalsIgnoreCase(mode) ? "Aventura" : "Survival";
    }

    private static String prettyDifficulty(String value) {
        if ("peaceful".equalsIgnoreCase(value)) return "Pacífico";
        if ("easy".equalsIgnoreCase(value)) return "Fácil";
        if ("hard".equalsIgnoreCase(value)) return "Difícil";
        return "Normal";
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(notificationTicker);
        try {
            if (process != null && process.isAlive()) process.destroy();
        } catch (Exception ignored) {}
        releaseWakeLock();
        setState("stopped", 0);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
