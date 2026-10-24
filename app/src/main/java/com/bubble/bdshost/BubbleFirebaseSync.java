package com.bubble.bdshost;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bubble MVP: Firebase RTDB receives only BDS presence, manifest metadata and
 * membership IDs. Never uploads player positions/rotations or inventory.
 * Firebase Anonymous Auth must be enabled, with the bundled security rules.
 */
public final class BubbleFirebaseSync {
    private static final String API_KEY = "AIzaSyAdRbZeFvF3EQyj6hW-UM6yeX6TZ9BdMz4";
    private static final String DB_URL = "https://worknow-be4bd-default-rtdb.firebaseio.com";
    private static final String AUTH_BASE = "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=";
    private static final String REFRESH_BASE = "https://securetoken.googleapis.com/v1/token?key=";
    private static final String DATA_ROOT = "/bubble";
    private static final String PREF = "bubble_firebase_mvp";
    private static final String MANIFEST_ID = "cidade";
    private static final long PRESENCE_INTERVAL_MS = 30_000L;

    private final Context context;
    private final SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bubble-firebase-sync");
        t.setDaemon(true);
        return t;
    });
    private final Object lock = new Object();
    private Set<String> wantedMembers = new TreeSet<>();
    private Set<String> uploadedMembers;
    private volatile boolean online;
    private volatile int numericDimension = -1; // do not register until manifest read
    private volatile String instanceId = "";
    private volatile int manifestRevision;
    private volatile long lastHeartbeat;
    private volatile boolean retired;

    public BubbleFirebaseSync(Context appContext) {
        context = appContext.getApplicationContext();
        prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        status("Aguardando BDS online");
    }

    public int bubbleDimension() { return numericDimension; }

    // Local-only access for the authenticated API transport. No Admin key in APK.
    public String firebaseIdToken() {
        return System.currentTimeMillis() < prefs.getLong("token_expiry_ms", 0L) - 30000L
                ? prefs.getString("id_token", "") : "";
    }
    public String bdsUid() { return prefs.getString("uid", ""); }
    public String instanceId() { return instanceId; }
    public boolean hasUploadedMembers() {
        synchronized (lock) { return online && uploadedMembers != null && !uploadedMembers.isEmpty(); }
    }
    public boolean isMemberUploaded(String playerId) {
        synchronized (lock) {
            return online && uploadedMembers != null && uploadedMembers.contains(playerId);
        }
    }

    public void onServerOnline() {
        online = true;
        prefs.edit().putBoolean("presence_possible", true).apply();
        synchronized (lock) { uploadedMembers = null; wantedMembers = new TreeSet<>(); }
        status("Conectando ao Firebase...");
        lastHeartbeat = System.currentTimeMillis();
        submit(this::connectAndSync);
    }

    public void refreshManifest() {
        if (!online) { status("Inicie o BDS antes de consultar o manifesto"); return; }
        submit(this::connectAndSync);
    }

    /** Called each time the filtered BDS query returns a complete snapshot. */
    public void onPlayersSnapshot(Set<String> members) {
        if (!online || numericDimension < 0) return;
        synchronized (lock) {
            if (wantedMembers.equals(members)) return;
            wantedMembers = new TreeSet<>(members);
        }
        saveLocalPlayers(members.size());
        submit(this::flushMembers);
    }

    public void heartbeatIfDue() {
        long now = System.currentTimeMillis();
        if (!online || now - lastHeartbeat < PRESENCE_INTERVAL_MS) return;
        lastHeartbeat = now;
        submit(this::connectAndSync);
    }

    public void onServerStopped() {
        boolean needCleanup = online || prefs.getBoolean("presence_possible", false);
        online = false;
        numericDimension = -1;
        if (!needCleanup) { status("BDS offline"); return; }
        synchronized (lock) { wantedMembers = new TreeSet<>(); }
        status("BDS offline — removendo presença");
        submit(() -> {
            try {
                if (ensureAuth()) {
                    deleteOwnMembers();
                    putPresence(false);
                    prefs.edit().putBoolean("presence_possible", false).apply();
                    status("BDS offline • presença removida");
                }
            } catch (Exception e) {
                status("Offline; limpeza pendente: " + message(e));
            }
        });
    }

    /** Enqueues clean-up before stopping the network worker. */
    public void close() {
        if (retired) return;
        retired = true;
        io.shutdown();
    }

    private void submit(Runnable work) {
        if (retired) return;
        try { io.execute(work); }
        catch (RuntimeException ignored) { }
    }

    private void connectAndSync() {
        if (!online) return;
        try {
            if (!ensureAuth() || !online) return;
            readManifest();
            if (!online) return;
            putPresence(true);
            lastHeartbeat = System.currentTimeMillis();
            flushMembersInternal();
            status("Conectado • BDS " + shortId(prefs.getString("uid", ""))
                    + " • instância " + instanceId + " • v" + manifestRevision);
        } catch (Exception e) {
            status("Falha Firebase: " + message(e));
        }
    }

    private void flushMembers() {
        if (!online || numericDimension < 0) return;
        try {
            if (!ensureAuth()) return;
            flushMembersInternal();
        } catch (Exception e) {
            status("Erro ao registrar jogadores: " + message(e));
        }
    }

    private void flushMembersInternal() throws Exception {
        if (!online || instanceId.isEmpty()) return;
        Set<String> current;
        synchronized (lock) {
            current = new TreeSet<>(wantedMembers);
            if (uploadedMembers != null && uploadedMembers.equals(current)) return;
        }
        JSONObject value = new JSONObject();
        for (String playerId : current) value.put(playerId, true);
        request("PUT", DATA_ROOT + "/instances/" + instanceId + "/members/"
                + bdsId() + ".json", value.toString(), false);
        synchronized (lock) { uploadedMembers = current; }
        saveLocalPlayers(current.size());
    }

    private void deleteOwnMembers() throws Exception {
        String id = prefs.getString("instance_id", instanceId);
        if (id.isEmpty()) return;
        request("DELETE", DATA_ROOT + "/instances/" + id + "/members/"
                + bdsId() + ".json", null, false);
        saveLocalPlayers(0);
        synchronized (lock) { uploadedMembers = null; }
    }

    private void putPresence(boolean active) throws Exception {
        JSONObject presence = new JSONObject();
        presence.put("online", active);
        presence.put("manifestId", MANIFEST_ID);
        presence.put("instanceId", prefs.getString("instance_id", instanceId));
        // BDS id is the Firebase Authentication UID, not an entity/runtime ID.
        presence.put("lastSeen", new JSONObject().put(".sv", "timestamp"));
        request("PUT", DATA_ROOT + "/bds/" + bdsId() + ".json",
                presence.toString(), false);
    }

    private void readManifest() throws Exception {
        JSONObject obj = new JSONObject(request("GET", DATA_ROOT + "/manifests/"
                + MANIFEST_ID + ".json", null, false));
        if (!obj.optBoolean("enabled", false)) throw new IllegalStateException("Manifesto desativado");
        int nextDimension = obj.getInt("numericDimensionId");
        String nextInstance = obj.getString("initialInstanceId");
        if (nextDimension < 0 || !nextInstance.matches("[A-Za-z0-9_-]{1,80}"))
            throw new IllegalArgumentException("Manifesto inválido");
        int nextRevision = obj.optInt("revision", 1);
        if (!instanceId.isEmpty() && !instanceId.equals(nextInstance)) {
            // Do not leave stale membership in a different instance.
            deleteOwnMembers();
            synchronized (lock) { uploadedMembers = null; wantedMembers = new TreeSet<>(); }
        }
        numericDimension = nextDimension;
        instanceId = nextInstance;
        manifestRevision = nextRevision;
        prefs.edit().putString("instance_id", instanceId)
                .putInt("dimension", numericDimension)
                .putInt("revision", manifestRevision).apply();
    }

    private boolean ensureAuth() throws Exception {
        long exp = prefs.getLong("token_expiry_ms", 0);
        if (System.currentTimeMillis() < exp - 120_000L
                && !prefs.getString("id_token", "").isEmpty()) return true;
        String refresh = prefs.getString("refresh_token", "");
        if (!refresh.isEmpty()) {
            try {
                String form = "grant_type=refresh_token&refresh_token="
                        + URLEncoder.encode(refresh, "UTF-8");
                JSONObject result = new JSONObject(postForm(REFRESH_BASE + API_KEY, form));
                saveToken(result.getString("id_token"), result.getString("refresh_token"),
                        result.getString("user_id"), result.getString("expires_in"));
                return true;
            } catch (HttpError e) {
                if (e.code != 400) throw e;
                // Terminal invalid refresh token: don't silently change the BDS identity.
                throw new IllegalStateException("Credencial expirada; reinstale a sessão Firebase", e);
            }
        }
        // Requires Firebase Authentication > Anonymous sign-in to be enabled.
        JSONObject created = new JSONObject(rawRequest("POST", AUTH_BASE + API_KEY,
                "{\"returnSecureToken\":true}", "application/json"));
        saveToken(created.getString("idToken"), created.getString("refreshToken"),
                created.getString("localId"), created.getString("expiresIn"));
        return true;
    }

    private void saveToken(String idToken, String refresh, String uid, String seconds) {
        long expiresAt = System.currentTimeMillis() + Long.parseLong(seconds) * 1000L;
        prefs.edit().putString("id_token", idToken)
                .putString("refresh_token", refresh).putString("uid", uid)
                .putLong("token_expiry_ms", expiresAt).apply();
    }

    private String bdsId() {
        String uid = prefs.getString("uid", "");
        if (uid.isEmpty() || !uid.matches("[A-Za-z0-9_-]{1,128}"))
            throw new IllegalStateException("BDS ainda não autenticado");
        return uid;
    }

    private String request(String method, String path, String body, boolean ignored) throws Exception {
        String token = prefs.getString("id_token", "");
        String url = DB_URL + path + "?auth=" + URLEncoder.encode(token, "UTF-8");
        return rawRequest(method, url, body, "application/json");
    }

    private static String postForm(String url, String form) throws Exception {
        return rawRequest("POST", url, form, "application/x-www-form-urlencoded");
    }

    private static String rawRequest(String method, String path, String body, String type) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(6500);
        c.setReadTimeout(7500);
        c.setUseCaches(false);
        c.setRequestProperty("Accept", "application/json");
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", type + "; charset=UTF-8");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            String reply = read(in);
            if (code < 200 || code >= 300)
                throw new HttpError(code, "HTTP " + code + " " + reply.substring(0, Math.min(150, reply.length())));
            return reply;
        } finally { c.disconnect(); }
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) return "";
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = in.read(buffer)) != -1) out.write(buffer, 0, len);
            return out.toString("UTF-8");
        }
    }

    private static final class HttpError extends Exception {
        final int code;
        HttpError(int code, String message) { super(message); this.code = code; }
    }

    private void saveLocalPlayers(int count) {
        prefs.edit().putInt("member_count", count).apply();
    }
    private void status(String value) {
        prefs.edit().putString("status", value).apply();
    }
    private static String shortId(String id) {
        return id.length() > 10 ? id.substring(0, 10) + "…" : id;
    }
    private static String message(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }
    public static String displayStatus(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return p.getString("status", "Não iniciado")
                + "\nBDS: " + shortId(p.getString("uid", "—"))
                + "  |  Instância: " + p.getString("instance_id", "—")
                + "\nDimensão numérica: " + p.getInt("dimension", -1)
                + "  |  Membros locais: " + p.getInt("member_count", 0)
                + "\nDados enviados: somente presença e IDs (sem XYZ).";
    }
}
