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

public class InstallService extends Service {
    public static final String ACTION_START = "com.bubble.bdshost.INSTALL_START";
    public static final String EXTRA_CHANNEL = "bds_channel";

    private static final String PREFS = "bubble_install";
    private static final String CHANNEL_ID = "bubble_installation";
    private static final int NOTIFICATION_ID = 41040;

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

        setState(true, 0, 0, "Preparando instalação", selected, null);
        startForeground(NOTIFICATION_ID, notification("Preparando instalação", 0));
        acquireWakeLock();

        new Thread(() -> {
            try {
                InstallerManager.Callback callback = new InstallerManager.Callback() {
                    @Override public void log(String text) {}

                    @Override public void progress(int percent, String stage) {
                        int stagePercent = prefs(InstallService.this).getInt("stage_progress", 0);
                        setState(true, percent, stagePercent, stage, selected, null);
                        updateNotification(stage, percent);
                    }

                    @Override public void stageProgress(int percent, String stage) {
                        int general = prefs(InstallService.this).getInt("general_progress", 0);
                        setState(true, general, percent, stage, selected, null);
                    }
                };

                new InstallerManager(this, callback, selected).installAll();
                setState(false, 100, 100, "Ambiente pronto", selected, null);
                updateNotification("Instalação concluída", 100);
            } catch (Throwable e) {
                setState(false,
                        prefs(this).getInt("general_progress", 0),
                        prefs(this).getInt("stage_progress", 0),
                        "Instalação interrompida",
                        selected,
                        e.getClass().getSimpleName() + ": " + e.getMessage());
                updateNotification("Instalação interrompida — abra o app para retomar",
                        prefs(this).getInt("general_progress", 0));
            } finally {
                threadRunning = false;
                releaseWakeLock();
                stopForeground(false);
                stopSelf();
            }
        }, "bubble-installer").start();
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
    public static int generalProgress(Context c) { return prefs(c).getInt("general_progress", 0); }
    public static int stageProgress(Context c) { return prefs(c).getInt("stage_progress", 0); }
    public static String stage(Context c) { return prefs(c).getString("stage", "Pronto para instalar"); }
    public static String selectedChannel(Context c) { return prefs(c).getString("channel", InstallerManager.CHANNEL_STABLE); }
    public static String error(Context c) { return prefs(c).getString("error", ""); }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BubbleBDSHost:installer");
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
                    CHANNEL_ID, "Instalação Bubble", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Download e preparação do Debian, Box64 e BDS");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification notification(String text, int progress) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(
                this, 20, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Bubble BDS Host • instalação")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(progress < 100)
                .setOnlyAlertOnce(true)
                .setContentIntent(content);

        if (progress < 100) b.setProgress(100, progress, false);
        return b.build();
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
