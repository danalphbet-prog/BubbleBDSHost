package com.bubble.bdshost;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.Locale;

public class InstallService extends Service {
    public static final String ACTION_START = "com.bubble.bdshost.INSTALL_START";
    public static final String EXTRA_CHANNEL = "bds_channel";

    private static final String PREFS = "bubble_install";
    private static final String CHANNEL_ID = "bubble_installation";
    private static final int NOTIFICATION_ID = 41040;
    private static final int DONE_NOTIFICATION_ID = 41041;

    private static volatile boolean threadRunning;
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String requested = intent != null ? intent.getStringExtra(EXTRA_CHANNEL)
                : prefs(this).getString("channel", InstallerManager.CHANNEL_STABLE);
        if (requested == null) requested = InstallerManager.CHANNEL_STABLE;
        if (!threadRunning) startInstall(requested);
        return START_STICKY;
    }

    private synchronized void startInstall(String channel) {
        if (threadRunning) return;
        threadRunning = true;

        final String selected = InstallerManager.CHANNEL_PREVIEW.equals(channel)
                ? InstallerManager.CHANNEL_PREVIEW : InstallerManager.CHANNEL_STABLE;

        setState(true, 0, 0, "Preparando ambiente sandbox", selected, null);
        startForeground(NOTIFICATION_ID, notification("Preparando ambiente sandbox", 0));
        acquireWakeLock();

        new Thread(() -> {
            boolean success = false;
            try {
                InstallerManager.Callback callback = new InstallerManager.Callback() {
                    @Override public void log(String text) {}

                    @Override public void progress(int percent, String stage) {
                        String friendly = friendlyStage(stage);
                        int stagePercent = prefs(InstallService.this)
                                .getInt("stage_progress", 0);
                        setState(true, percent, stagePercent, friendly, selected, null);
                        updateNotification(friendly, percent);
                    }

                    @Override public void stageProgress(int percent, String stage) {
                        String friendly = friendlyStage(stage);
                        int general = prefs(InstallService.this)
                                .getInt("general_progress", 0);
                        setState(true, general, percent, friendly, selected, null);
                    }
                };

                new InstallerManager(this, callback, selected).installAll();
                success = true;
                setState(false, 100, 100, "Ambiente pronto", selected, null);
            } catch (Throwable e) {
                setState(false,
                        prefs(this).getInt("general_progress", 0),
                        prefs(this).getInt("stage_progress", 0),
                        "Preparação interrompida",
                        selected,
                        userFriendlyError(e));
            } finally {
                threadRunning = false;
                releaseWakeLock();
                if (Build.VERSION.SDK_INT >= 24) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                } else {
                    stopForeground(true);
                }
                if (success) notifyDone(
                        "Ambiente pronto",
                        "A Bubble está pronta para criar e hospedar mundos.");
                else notifyDone(
                        "Preparação interrompida",
                        "Abra o app para verificar e retomar a etapa.");
                stopSelf();
            }
        }, "bubble-installer").start();
    }

    private String friendlyStage(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "Preparando ambiente";
        String s = raw.toLowerCase(Locale.ROOT);

        if (s.contains("android") || s.contains("arm64"))
            return "Validando dispositivo";
        if (s.contains("armazen") || s.contains("storage"))
            return "Verificando espaço disponível";
        if (s.contains("debian") || s.contains("proot") || s.contains("rootfs"))
            return "Preparando ambiente sandbox";
        if (s.contains("dpkg") || s.contains("apt") || s.contains("pacote"))
            return "Configurando ambiente sandbox";
        if (s.contains("box64") || s.contains("toolchain")
                || s.contains("cmake") || s.contains("compila"))
            return "Preparando compilador virtual";
        if (s.contains("bds") || s.contains("bedrock"))
            return "Instalando núcleo do servidor";
        if (s.contains("cadeia") || s.contains("valid") || s.contains("teste"))
            return "Validando ambiente";
        if (s.contains("pronto") || s.contains("conclu"))
            return "Ambiente pronto";
        return raw;
    }

    private String userFriendlyError(Throwable e) {
        String message = e == null ? "" : e.getMessage();
        if (message == null || message.isBlank()) return "Falha durante a preparação do ambiente.";
        String low = message.toLowerCase(Locale.ROOT);
        if (low.contains("box64") || low.contains("toolchain") || low.contains("cmake"))
            return "O compilador virtual não concluiu. Toque em Preparar Ambiente para retomar.";
        if (low.contains("debian") || low.contains("proot") || low.contains("rootfs")
                || low.contains("dpkg") || low.contains("apt"))
            return "O ambiente sandbox precisa ser reparado. Toque em Preparar Ambiente para retomar.";
        if (low.contains("bds") || low.contains("bedrock"))
            return "O núcleo do servidor não foi concluído. Tente novamente.";
        return message;
    }

    private void setState(boolean running, int general, int stage,
                          String label, String channel, String error) {
        prefs(this).edit()
                .putBoolean("running", running)
                .putInt("general_progress", clamp(general))
                .putInt("stage_progress", clamp(stage))
                .putString("stage", label == null ? "" : label)
                .putString("channel", channel)
                .putString("error", error == null ? "" : error)
                .apply();
    }

    private static int clamp(int n) { return Math.max(0, Math.min(100, n)); }

    public static boolean isRunning(Context c) {
        return prefs(c).getBoolean("running", false) || threadRunning;
    }
    public static int generalProgress(Context c) {
        return prefs(c).getInt("general_progress", 0);
    }
    public static int stageProgress(Context c) {
        return prefs(c).getInt("stage_progress", 0);
    }
    public static String stage(Context c) {
        return prefs(c).getString("stage", "Pronto para preparar");
    }
    public static String selectedChannel(Context c) {
        return prefs(c).getString("channel", InstallerManager.CHANNEL_STABLE);
    }
    public static String error(Context c) {
        return prefs(c).getString("error", "");
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "BubbleBDSHost:installer");
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
                    CHANNEL_ID, "Bubble • Preparação", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Progresso da preparação do ambiente local");
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification notification(String text, int progress) {
        Intent open = new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_TAB, "home")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(
                this, 20, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Bubble • Preparando ambiente")
                .setContentText(text)
                .setSubText(progress + "% concluído")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setShowWhen(false)
                .setContentIntent(content)
                .setProgress(100, clamp(progress), false);

        return b.build();
    }

    private void notifyDone(String title, String text) {
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Bubble • " + title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setAutoCancel(true)
                .setShowWhen(true)
                .setContentIntent(PendingIntent.getActivity(
                        this, 21,
                        new Intent(this, MainActivity.class)
                                .putExtra(MainActivity.EXTRA_OPEN_TAB, "home")
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                        | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_IMMUTABLE
                                | PendingIntent.FLAG_UPDATE_CURRENT))
                .build();
        getSystemService(NotificationManager.class).notify(DONE_NOTIFICATION_ID, n);
    }

    private void updateNotification(String text, int progress) {
        getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_ID, notification(text, progress));
    }

    @Override public void onDestroy() {
        releaseWakeLock();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
