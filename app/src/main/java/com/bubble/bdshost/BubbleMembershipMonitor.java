package com.bubble.bdshost;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Reads console querytarget snapshots. Does not upload coordinates to Firebase.
 * Bedrock Mirror 26.52 v1.2.2 tags its simulated mirror as bubble:mirror.
 */
public final class BubbleMembershipMonitor {
    public interface Sender { void send(String command) throws Exception; }
    private static final String COMMAND = "querytarget @a[tag=!bubble:mirror]";
    private static final long QUERY_TIMEOUT_MS = 3_000;
    private final BubbleFirebaseSync sync;
    private final Sender sender;
    private final BubbleApiStream apiStream;
    private long lastPollAt;
    private int lastMemberCount;
    private boolean waiting;
    private boolean collecting;
    private int bracketDepth;
    private boolean inString;
    private boolean escape;
    private final StringBuilder payload = new StringBuilder();
    private long sentAt;

    public BubbleMembershipMonitor(BubbleFirebaseSync sync, Sender sender, BubbleApiStream apiStream) {
        this.sync = sync;
        this.sender = sender;
        this.apiStream = apiStream;
    }

    public synchronized void poll(boolean bdsOnline, boolean benchmarkRunning) {
        sync.heartbeatIfDue();
        if (!bdsOnline || benchmarkRunning || sync.bubbleDimension() < 0) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (waiting && now - sentAt < QUERY_TIMEOUT_MS) return;
        // Idle BDS: 1 Hz. With Bubble members: 10 Hz (one in-flight query).
        long interval = lastMemberCount > 0 ? 100L : 1000L;
        if (now - lastPollAt < interval) return;
        lastPollAt = now;
        clearParser();
        try {
            // No auto log echo. Parsing suppresses only this command's JSON output.
            sentAt = now;
            waiting = true;
            sender.send(COMMAND);
        } catch (Exception ignored) { clearParser(); }
    }

    /** Rejects stress benchmark launch while an automatic query is outstanding. */
    public synchronized boolean canStartBenchmark() { return !waiting; }

    public synchronized void reset() { clearParser(); }

    private void clearParser() {
        waiting = false;
        collecting = false;
        bracketDepth = 0;
        inString = false;
        escape = false;
        payload.setLength(0);
    }

    /** Returns true if line is the automatic query response (hide console spam). */
    public synchronized boolean acceptLine(String line) {
        if (!waiting) return false;
        if (!collecting) {
            int at = line.indexOf("Target data:");
            if (at >= 0) {
                collecting = true;
                acceptChunk(line.substring(at + "Target data:".length()));
                return true;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("no targets matched")
                    || lower.contains("no target matched")
                    || lower.contains("no players found")) {
                clearParser();
                lastMemberCount = 0;
                sync.onPlayersSnapshot(new HashSet<>());
                apiStream.onSnapshot(new JSONArray(), sync.bubbleDimension());
                return true;
            }
            return false;
        }
        acceptChunk(line);
        return true;
    }

    private void acceptChunk(String chunk) {
        // Max 128 KiB per response; defensive against malformed/noisy server output.
        if (payload.length() + chunk.length() > 131072) { clearParser(); return; }
        for (int i = 0; i < chunk.length(); i++) {
            char ch = chunk.charAt(i);
            if (inString) {
                if (escape) escape = false;
                else if (ch == '\\') escape = true;
                else if (ch == '"') inString = false;
            } else {
                if (ch == '"') inString = true;
                else if (ch == '[') bracketDepth++;
                else if (ch == ']') bracketDepth--;
            }
        }
        payload.append(chunk).append('\n');
        if (bracketDepth == 0 && payload.indexOf("[") >= 0) {
            String finished = payload.toString().trim();
            clearParser();
            try {
                JSONArray result = new JSONArray(finished);
                int dimension = sync.bubbleDimension();
                Set<String> players = new HashSet<>();
                for (int i = 0; i < result.length(); i++) {
                    JSONObject player = result.optJSONObject(i);
                    if (player == null || player.optInt("dimension", -1) != dimension) continue;
                    String uuid = player.optString("uniqueId", "").toLowerCase(Locale.ROOT);
                    if (uuid.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                        players.add(uuid);
                }
                lastMemberCount = players.size();
                sync.onPlayersSnapshot(players);
                apiStream.onSnapshot(result, dimension);
            } catch (Exception ignored) { /* Never write a partial/invalid snapshot. */ }
        }
    }
}
