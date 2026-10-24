package com.bubble.bdshost;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * BDS console -> Android -> one authenticated WebSocket -> Bubble API RAM.
 * Firebase RTDB receives only membership IDs through BubbleFirebaseSync.
 * Never persist coordinates on device or in Firebase; snapshots are ephemeral.
 */
public final class BubbleApiStream {
    private static final String WS_URL = "wss://bubble-api-8dyc.onrender.com/v2/live";
    private static final String PREF = "bubble_api_stream";
    private static final long RETRY_MS = 3000L;
    private static final long IDLE_REFRESH_MS = 900L;
    private static final String UUID_RE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final BubbleFirebaseSync firebase;
    private final SharedPreferences statusPrefs;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .pingInterval(15, java.util.concurrent.TimeUnit.SECONDS)
            .build();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bubble-api-sender");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<String> newest = new AtomicReference<>();
    private final AtomicBoolean scheduled = new AtomicBoolean(false);
    private final Map<String, String> lastSent = new HashMap<>();
    private final Map<String, Long> lastSentAt = new HashMap<>();

    private volatile WebSocket socket;
    private volatile boolean ready;
    private volatile boolean online;
    private volatile String connectedInstance = "";
    private volatile String connectedToken = "";
    private volatile long lastAttempt;
    private volatile long sent;
    private volatile long acked;
    private volatile long errors;
    private volatile long lastPingAt;
    private volatile long lastRttMs;

    public BubbleApiStream(Context c, BubbleFirebaseSync firebase) {
        this.firebase = firebase;
        this.statusPrefs = c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        setStatus("Desconectado");
    }

    public void onServerOnline() {
        online = true;
        setStatus("Aguardando manifesto e membros Firebase...");
    }

    public void onServerStopped() {
        online = false;
        newest.set(null);
        worker.execute(() -> disconnect("BDS desligado"));
    }

    public void close() {
        online = false;
        newest.set(null);
        worker.execute(() -> disconnect("Desligado"));
        worker.shutdown();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    /** Nonblocking: the BDS stdout reader never performs network I/O. */
    public void onSnapshot(JSONArray players, int dimension) {
        if (!online || dimension < 0) return;
        newest.set(players.toString());
        scheduleDrain();
    }

    private void scheduleDrain() {
        if (!scheduled.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try {
                    String value = newest.getAndSet(null);
                    if (value != null) process(value);
                } catch (Exception e) {
                    errors++;
                    setStatus("Envio falhou: " + e.getClass().getSimpleName());
                } finally {
                    scheduled.set(false);
                    if (newest.get() != null && online) scheduleDrain();
                }
            });
        } catch (RuntimeException e) { scheduled.set(false); }
    }

    private void process(String source) throws Exception {
        if (!online) return;
        String instance = firebase.instanceId();
        String token = firebase.firebaseIdToken();
        String bds = firebase.bdsUid();
        int dimension = firebase.bubbleDimension();
        if (instance.isEmpty() || token.isEmpty() || bds.isEmpty() || dimension < 0) {
            setStatus("Aguardando autenticação e manifesto...");
            return;
        }
        JSONArray entries = new JSONArray(source);
        int eligible = 0;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject p = entries.optJSONObject(i);
            if (p == null || p.optInt("dimension", -1) != dimension) continue;
            String id = p.optString("uniqueId", "").toLowerCase(Locale.ROOT);
            if (id.matches(UUID_RE) && firebase.isMemberUploaded(id)) eligible++;
        }
        if (eligible == 0) return;
        if (socket == null || !connectedInstance.equals(instance) || !connectedToken.equals(token)) {
            if (socket != null) disconnect("Reconectando...");
            connect(instance, token);
            return;
        }
        if (!ready) return;

        final long t = android.os.SystemClock.elapsedRealtime();
        if (t - lastPingAt > 5000L && socket != null) {
            lastPingAt = t;
            socket.send("{\"type\":\"ping\"}");
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject p = entries.optJSONObject(i);
            if (p == null || p.optInt("dimension", -1) != dimension) continue;
            String id = p.optString("uniqueId", "").toLowerCase(Locale.ROOT);
            if (!id.matches(UUID_RE) || !firebase.isMemberUploaded(id)) continue;
            JSONObject xyz = p.optJSONObject("position");
            if (xyz == null || !finite(xyz.optDouble("x", Double.NaN)) ||
                    !finite(xyz.optDouble("y", Double.NaN)) ||
                    !finite(xyz.optDouble("z", Double.NaN)) ||
                    !finite(p.optDouble("yRot", Double.NaN))) continue;
            seen.add(id);
            JSONObject state = new JSONObject();
            state.put("uniqueId", id);
            state.put("dimension", dimension);
            state.put("position", xyz);
            state.put("yRot", p.getDouble("yRot"));
            if (p.has("id") && p.opt("id") instanceof Number) state.put("id", p.getLong("id"));

            String fingerprint = state.toString();
            if (fingerprint.equals(lastSent.get(id)) && t-lastSentAt.getOrDefault(id, 0L)<IDLE_REFRESH_MS) continue;
            JSONObject message = new JSONObject();
            message.put("type", "publish");
            message.put("instanceId", instance);
            message.put("bdsId", bds);
            message.put("playerId", id);
            message.put("state", state);
            WebSocket ws = socket;
            if (ws == null || !ready || ws.queueSize() > 128 * 1024 || !ws.send(message.toString())) {
                setStatus("WebSocket congestionado ou desconectado");
                return;
            }
            lastSent.put(id, fingerprint);
            lastSentAt.put(id, t);
            sent++;
        }
        lastSent.keySet().retainAll(seen);
        lastSentAt.keySet().retainAll(seen);
        statusPrefs.edit().putLong("sent", sent).apply();
    }

    private static boolean finite(double v) { return !Double.isNaN(v) && !Double.isInfinite(v); }

    private void connect(String instance, String token) {
        long t = android.os.SystemClock.elapsedRealtime();
        if (t - lastAttempt < RETRY_MS) return;
        lastAttempt = t;
        ready = false;
        connectedInstance = instance;
        connectedToken = token;
        lastSent.clear();
        lastSentAt.clear();
        setStatus("Conectando WebSocket...");
        WebSocket ws = client.newWebSocket(new Request.Builder().url(WS_URL).build(), new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                JSONObject auth = new JSONObject();
                try {
                    auth.put("type", "auth");
                    auth.put("instanceId", instance);
                    auth.put("token", token);
                    webSocket.send(auth.toString());
                } catch (Exception ex) { webSocket.close(1000, "auth failed"); }
            }
            @Override public void onMessage(WebSocket webSocket, String text) {
                try {
                    JSONObject msg = new JSONObject(text);
                    String type = msg.optString("type", "");
                    if ("ready".equals(type)) {
                        if (socket == webSocket && online) {
                            ready = true;
                            setStatus("WebSocket conectado • " + instance);
                        }
                    } else if ("ack".equals(type)) {
                        acked++;
                        statusPrefs.edit().putLong("acked", acked).apply();
                    } else if ("pong".equals(type)) {
                        lastRttMs = Math.max(0L, android.os.SystemClock.elapsedRealtime() - lastPingAt);
                        statusPrefs.edit().putLong("rtt_ms", lastRttMs).apply();
                    } else if ("error".equals(type)) {
                        errors++;
                        setStatus("API: " + msg.optString("code", "ERROR"));
                        statusPrefs.edit().putLong("errors", errors).apply();
                        if (!ready) webSocket.close(1008, "auth rejected");
                    }
                } catch (Exception ignored) { }
            }
            @Override public void onMessage(WebSocket webSocket, ByteString bytes) { }
            @Override public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(code, "");
            }
            @Override public void onClosed(WebSocket webSocket, int code, String reason) { disconnected(webSocket); }
            @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                disconnected(webSocket);
                errors++;
                statusPrefs.edit().putLong("errors", errors).apply();
                setStatus("Falha WebSocket: " + t.getClass().getSimpleName());
            }
            private void disconnected(WebSocket webSocket) {
                if (socket == webSocket) {
                    socket = null;
                    ready = false;
                }
            }
        });
        socket = ws;
    }

    private void disconnect(String reason) {
        ready = false;
        WebSocket ws = socket;
        socket = null;
        connectedInstance = "";
        connectedToken = "";
        if (ws != null) ws.close(1000, "Bubble disconnect");
        lastSent.clear();
        lastSentAt.clear();
        setStatus(reason);
    }

    private void setStatus(String value) { statusPrefs.edit().putString("status", value).apply(); }
    public static String displayStatus(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return "API: " + p.getString("status", "Não iniciado")
                + " | envios=" + p.getLong("sent", 0)
                + " | ACK=" + p.getLong("acked", 0)
                + " | RTT=" + p.getLong("rtt_ms", -1) + "ms"
                + " | erros=" + p.getLong("errors", 0);
    }
}
