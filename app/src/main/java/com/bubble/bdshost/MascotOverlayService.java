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
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Opt-in floating companion. Never intercepts touches outside its own windows. */
public final class MascotOverlayService extends Service {
    public static final String ACTION_SHOW = "com.bubble.bdshost.MASCOT_SHOW";
    public static final String ACTION_HIDE = "com.bubble.bdshost.MASCOT_HIDE";
    public static final String ACTION_LOOK = "com.bubble.bdshost.MASCOT_LOOK";
    public static final String EXTRA_X = "touch_x";
    public static final String EXTRA_Y = "touch_y";
    private static final String CHANNEL = "bubble_mascot";
    private static final int NOTIFICATION_ID = 19134;
    private static final int WIDTH_DP = 112;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private WebView mascot;
    private TextView satellite;
    private LinearLayout panel;
    private TextView info;
    private WindowManager.LayoutParams bubbleParams, satelliteParams, panelParams;
    private boolean expanded = false;
    private boolean panelVisible = false;
    private float downX, downY;
    private int startX, startY;
    private boolean dragging;
    private long lastPlayerQuery;

    @Override public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "Bubble • Mascote flutuante", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_SHOW : intent.getAction();
        if (ACTION_HIDE.equals(action)) {
            getSharedPreferences("bubble_mascot", MODE_PRIVATE).edit()
                    .putBoolean("enabled", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (mascot == null) {
            startForeground(NOTIFICATION_ID, notification());
            attachBubble();
            handler.postDelayed(refreshTask, 1000);
        }
        if (ACTION_LOOK.equals(action) && intent != null) {
            gazeAt(intent.getFloatExtra(EXTRA_X, -1f),
                   intent.getFloatExtra(EXTRA_Y, -1f));
        }
        return START_STICKY;
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 42, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent hide = new Intent(this, MascotOverlayService.class).setAction(ACTION_HIDE);
        PendingIntent hideIntent = PendingIntent.getService(this, 43, hide,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.bubble_app_icon)
                .setContentTitle("Bubble")
                .setContentText("Mascote flutuante ativo • toque para voltar")
                .setContentIntent(content)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "Ocultar", hideIntent).build())
                .setOngoing(true)
                .build();
    }

    private int dp(float value) {
        return (int) (getResources().getDisplayMetrics().density * value + 0.5f);
    }

    private WindowManager.LayoutParams layout(int w, int h, int x, int y) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                dp(w), h < 0 ? h : dp(h),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = x;
        p.y = y;
        p.setTitle("Bubble companion");
        return p;
    }

    private int screenWidth() { return getResources().getDisplayMetrics().widthPixels; }
    private int screenHeight() { return getResources().getDisplayMetrics().heightPixels; }
    private int clamp(int n, int lo, int hi) { return Math.max(lo, Math.min(Math.max(lo, hi), n)); }

    private void attachBubble() {
        mascot = new WebView(this);
        mascot.setBackgroundColor(Color.TRANSPARENT);
        mascot.getSettings().setJavaScriptEnabled(true);
        mascot.getSettings().setDomStorageEnabled(false);
        mascot.getSettings().setMediaPlaybackRequiresUserGesture(true);
        mascot.setVerticalScrollBarEnabled(false);
        mascot.setHorizontalScrollBarEnabled(false);
        mascot.loadUrl("file:///android_asset/bubble_mascot.html");

        SharedPreferences prefs = getSharedPreferences("bubble_mascot", MODE_PRIVATE);
        int x = prefs.getInt("x", screenWidth() - dp(119));
        int y = prefs.getInt("y", dp(150));
        bubbleParams = layout(WIDTH_DP, WIDTH_DP,
                clamp(x, 0, screenWidth() - dp(WIDTH_DP)),
                clamp(y, dp(35), screenHeight() - dp(145)));

        mascot.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downX = event.getRawX();
                downY = event.getRawY();
                startX = bubbleParams.x;
                startY = bubbleParams.y;
                dragging = false;
                gazeAt(downX, downY);
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                float dx = event.getRawX() - downX;
                float dy = event.getRawY() - downY;
                if (Math.abs(dx) + Math.abs(dy) > dp(8)) dragging = true;
                if (dragging) {
                    bubbleParams.x = clamp(startX + Math.round(dx), 0, screenWidth() - dp(WIDTH_DP));
                    bubbleParams.y = clamp(startY + Math.round(dy), dp(35), screenHeight() - dp(145));
                    updateView(mascot, bubbleParams);
                    positionExtras();
                }
                return true;
            }
            if (action == MotionEvent.ACTION_UP) {
                if (!dragging) toggleExpanded();
                getSharedPreferences("bubble_mascot", MODE_PRIVATE).edit()
                        .putInt("x", bubbleParams.x)
                        .putInt("y", bubbleParams.y).apply();
                return true;
            }
            return action == MotionEvent.ACTION_CANCEL;
        });
        try {
            wm.addView(mascot, bubbleParams);
        } catch (RuntimeException ex) {
            mascot.destroy();
            mascot = null;
            stopSelf();
        }
    }

    private void gazeAt(float rawX, float rawY) {
        if (mascot == null || bubbleParams == null || rawX < 0 || rawY < 0) return;
        float centerX = bubbleParams.x + dp(WIDTH_DP / 2f);
        float centerY = bubbleParams.y + dp(WIDTH_DP / 2f);
        float x = Math.max(-1.6f, Math.min(1.6f, (rawX - centerX) / dp(34)));
        float y = Math.max(-1.4f, Math.min(1.4f, (rawY - centerY) / dp(34)));
        mascot.evaluateJavascript(String.format(Locale.US,
                "window.bubbleLookAt && bubbleLookAt(%.3f,%.3f)", x, y), null);
    }

    private GradientDrawable bg(int fill, int border, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radius));
        d.setStroke(dp(1), border);
        return d;
    }

    private void toggleExpanded() {
        if (expanded) {
            removePanel();
            removeSatellite();
            expanded = false;
            mascotModeChanged(false);
            return;
        }
        expanded = true;
        mascotModeChanged(true);
        satellite = new TextView(this);
        satellite.setText("▤⚙");
        satellite.setTextColor(Color.WHITE);
        satellite.setTextSize(20);
        satellite.setGravity(Gravity.CENTER);
        satellite.setContentDescription("Detalhes do servidor Bubble");
        satellite.setBackground(bg(Color.rgb(55, 38, 67), Color.rgb(161, 133, 190), 40));
        satellite.setOnClickListener(v -> {
            if (panelVisible) removePanel(); else showPanel();
        });
        satelliteParams = layout(58, 58, 0, 0);
        positionExtras();
        try {
            wm.addView(satellite, satelliteParams);
            satellite.setScaleX(.55f);
            satellite.setScaleY(.55f);
            satellite.setAlpha(0f);
            satellite.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(230).start();
        } catch (RuntimeException ignored) { satellite = null; }
    }

    /** Requests a mood transition; the HTML queues it until the current animation finishes. */
    private void mascotModeChanged(boolean opened) {
        if (mascot != null) {
            mascot.evaluateJavascript(
                    "window.bubbleModeChanged && window.bubbleModeChanged(" + opened + ")", null);
        }
    }

    private void positionExtras() {
        if (bubbleParams == null) return;
        if (satelliteParams != null) {
            satelliteParams.x = clamp(bubbleParams.x - dp(56), 0, screenWidth() - dp(58));
            satelliteParams.y = clamp(bubbleParams.y + dp(27), dp(35), screenHeight() - dp(65));
            updateView(satellite, satelliteParams);
        }
        if (panelParams != null) {
            panelParams.x = clamp(bubbleParams.x - dp(210), 0, screenWidth() - dp(290));
            panelParams.y = clamp(bubbleParams.y + dp(115), dp(40), screenHeight() - dp(315));
            updateView(panel, panelParams);
        }
    }

    private void updateView(View view, WindowManager.LayoutParams p) {
        if (view != null && view.getParent() != null) {
            try { wm.updateViewLayout(view, p); } catch (RuntimeException ignored) {}
        }
    }

    private void showPanel() {
        if (panelVisible) return;
        panelVisible = true;
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(15), dp(13), dp(15), dp(14));
        panel.setBackground(bg(Color.rgb(35, 27, 50), Color.rgb(139, 120, 171), 20));

        TextView title = new TextView(this);
        title.setText("▤  BUBBLE • MUNDO");
        title.setTextColor(Color.WHITE);
        title.setTextSize(13);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setOnClickListener(v -> removePanel());
        panel.addView(title);

        info = new TextView(this);
        info.setTextColor(Color.rgb(224, 216, 238));
        info.setTextSize(12);
        info.setLineSpacing(dp(3), 1f);
        info.setPadding(0, dp(10), 0, dp(12));
        panel.addView(info);

        TextView openApp = new TextView(this);
        openApp.setText("ABRIR BUBBLE     ↗");
        openApp.setTextColor(Color.WHITE);
        openApp.setTextSize(11);
        openApp.setGravity(Gravity.CENTER);
        openApp.setPadding(dp(8),dp(12),dp(8),dp(12));
        openApp.setBackground(bg(Color.rgb(81, 58, 113), Color.rgb(157, 132, 190), 12));
        openApp.setOnClickListener(v -> {
            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(open);
        });
        panel.addView(openApp);

        panelParams = layout(290, WindowManager.LayoutParams.WRAP_CONTENT, 0, 0);
        positionExtras();
        try {
            wm.addView(panel, panelParams);
            panel.setAlpha(0f);
            panel.setTranslationY(-dp(8));
            panel.animate().alpha(1f).translationY(0f).setDuration(240).start();
            updateInfo();
        } catch (RuntimeException ex) { removePanel(); }
    }

    private void removePanel() {
        panelVisible = false;
        if (panel != null) {
            try { wm.removeView(panel); } catch (RuntimeException ignored) {}
            panel = null;
        }
        info = null;
        panelParams = null;
    }

    private void removeSatellite() {
        if (satellite != null) {
            try { wm.removeView(satellite); } catch (RuntimeException ignored) {}
            satellite = null;
        }
        satelliteParams = null;
    }

    private String prop(String key, String fallback) {
        File f = new File(AppPaths.bds(this), "server.properties");
        if (!f.isFile()) return fallback;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] bytes = new byte[(int) Math.min(f.length(), 128 * 1024)];
            int count = in.read(bytes);
            if (count <= 0) return fallback;
            for (String line : new String(bytes, 0, count, StandardCharsets.UTF_8).split("\\R")) {
                if (line.trim().startsWith(key + "=")) return line.substring(line.indexOf('=') + 1).trim();
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    private String players() {
        File log = AppPaths.serverLog(this);
        if (!log.isFile()) return "—";
        try (RandomAccessFile raf = new RandomAccessFile(log, "r")) {
            long len = raf.length();
            int count = (int) Math.min(len, 65536);
            if (count == 0) return "—";
            raf.seek(len - count);
            byte[] bytes = new byte[count];
            raf.readFully(bytes);
            String lines = new String(bytes, StandardCharsets.UTF_8);
            Matcher m = Pattern.compile(
                    "There are\\s+(\\d+)(?:\\s*/\\s*\\d+|\\s+of a max of\\s+\\d+)\\s+players online",
                    Pattern.CASE_INSENSITIVE).matcher(lines);
            String last = "—";
            while (m.find()) last = m.group(1);
            return last;
        } catch (Exception ignored) { return "—"; }
    }

    private void updateInfo() {
        if (info == null || !panelVisible) return;
        boolean active = BdsService.isRunning(this);
        String version = InstallerManager.readInstalledBdsVersion(this);
        String world = prop("level-name", "Meu Mundo Bubble");
        String ip = BdsService.getLocalIpv4();
        String port = prop("server-port", "19132");
        String max = prop("max-players", "8");
        info.setText("Estado: " + (active ? "Online / inicializando" : "Desligado")
                + "\nMundo: " + world
                + "\nEndereço: " + ip + ":" + port
                + "\nJogadores: " + (active ? players() : "0") + " / " + max
                + "\nLatência: — (sem medição de cliente)"
                + "\nBolha: sessão local"
                + "\nID Bubble: — (API não vinculada)"
                + "\nVersão: " + (version == null ? "—" : version));
        if (active && System.currentTimeMillis() - lastPlayerQuery > 30000) {
            lastPlayerQuery = System.currentTimeMillis();
            Intent list = new Intent(this, BdsService.class)
                    .setAction(BdsService.ACTION_COMMAND)
                    .putExtra(BdsService.EXTRA_COMMAND, "list");
            startService(list);
        }
    }

    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            updateInfo();
            handler.postDelayed(this, 5000);
        }
    };

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        removePanel();
        removeSatellite();
        if (mascot != null) {
            try { wm.removeView(mascot); } catch (RuntimeException ignored) {}
            mascot.destroy();
            mascot = null;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
