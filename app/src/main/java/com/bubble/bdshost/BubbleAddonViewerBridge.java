package com.bubble.bdshost;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Grants a short-lived, read-only Bubble API viewer token to the GameTest addon.
 * Does not log, persist or display the token. Only works if addon READY marker is seen.
 * The Firebase service-account key remains ONLY on the API server.
 */
public final class BubbleAddonViewerBridge {
    public interface ConsoleSender { void send(String command) throws Exception; }
    private static final String GRANT_URL = "https://bubble-api-8dyc.onrender.com/v2/access/grant";
    private static final String PREF = "bubble_addon_viewer_bridge";
    private final BubbleFirebaseSync firebase;
    private final ConsoleSender sender;
    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bubble-addon-viewer-grant");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean online;
    private volatile boolean addonReady;
    private volatile boolean busy;
    private volatile boolean closed;
    private volatile long nextAttemptMs;
    private volatile String lastInstance = "";

    public BubbleAddonViewerBridge(Context context, BubbleFirebaseSync firebase, ConsoleSender sender) {
        this.firebase = firebase;
        this.sender = sender;
        this.prefs = context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        status("Esperando addon Bubble API no BDS");
    }
    public void onServerOnline() {
        online = true;
        addonReady = false;
        nextAttemptMs = 0;
        lastInstance = "";
        status("Aguardando addon iniciar");
    }
    public void onAddonReady() {
        if (!online || closed) return;
        addonReady = true;
        nextAttemptMs = 0;
        status("Addon detectado; esperando membro Firebase");
    }
    public void onServerStopped() {
        online = false;
        addonReady = false;
        nextAttemptMs = 0;
        status("BDS offline");
    }
    /** Nonblocking, invoked by existing membership ticker. */
    public void tick() {
        if (!online || !addonReady || busy || closed) return;
        if (!firebase.hasUploadedMembers()) return;
        String instance = firebase.instanceId();
        String token = firebase.firebaseIdToken();
        if (instance.isEmpty() || token.isEmpty()) return;
        long now = SystemClock.elapsedRealtime();
        if (!instance.equals(lastInstance)) nextAttemptMs = 0;
        if (now < nextAttemptMs) return;
        busy = true;
        nextAttemptMs = now + 10000;
        worker.execute(() -> {
            try {
                if (!online || !addonReady) return;
                JSONObject grant = requestGrant(token, instance);
                String viewer = grant.getString("token");
                if (!viewer.matches("[A-Za-z0-9_-]{30,150}")) throw new IllegalStateException("viewer token inesperado");
                long expiry = grant.getLong("expiresAt");
                if (!online || !addonReady || !instance.equals(firebase.instanceId())) return;
                // Hidden from the Android console log. Never print the token.
                sender.send("bubble:tokenapi " + viewer);
                lastInstance = instance;
                long remain = expiry - System.currentTimeMillis() - 120000L;
                nextAttemptMs = SystemClock.elapsedRealtime() + Math.max(20000, Math.min(remain, 180000L));
                status("Token de leitura enviado • " + instance + " • renovacao automatica");
            } catch (Exception e) {
                nextAttemptMs = SystemClock.elapsedRealtime() + 10000;
                status("Falha grant: " + e.getClass().getSimpleName() + " (verificar API/instancia)");
            } finally {
                busy = false;
            }
        });
    }
    public void close() { closed = true; onServerStopped(); worker.shutdown(); }
    private void status(String value) { prefs.edit().putString("status", value).apply(); }
    public static String displayStatus(Context c) {
        return "Receptor: " + c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString("status", "Aguardando BDS");
    }
    private static JSONObject requestGrant(String token, String instance) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(GRANT_URL).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(6500);
            c.setReadTimeout(7500);
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            c.setRequestProperty("Accept", "application/json");
            c.setDoOutput(true);
            byte[] payload = new JSONObject().put("instanceId", instance).toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = c.getOutputStream()) { out.write(payload); }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[1024];int n;
                while ((n = in.read(buf)) > -1) {
                    if (out.size() + n > 8192) throw new IllegalStateException("Grant muito grande");
                    out.write(buf, 0, n);
                }
                return new JSONObject(out.toString("UTF-8"));
            }
        } finally { c.disconnect(); }
    }
}
