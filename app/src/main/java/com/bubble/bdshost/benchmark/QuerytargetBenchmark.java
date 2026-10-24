package com.bubble.bdshost.benchmark;

import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Measures command-to-complete-JSON round trips on the BDS console.
 * Caller MUST forward all original BDS stdout lines to acceptStdoutLine.
 * Tests are strictly sequential to preserve response correlation.
 * IMPORTANT: Do not run other querytarget commands during a benchmark.
 */
public final class QuerytargetBenchmark {
    private static final int COMMAND_TIMEOUT_MS = 2500;
    private static final int MAX_JSON_CHARS = 1024 * 1024;

    public interface ConsoleBridge {
        boolean isBdsOnline();
        /** Write into the BDS process stdin, appending a newline and flushing. */
        void sendCommand(String command) throws Exception;
    }

    public interface Listener {
        void onProgress(int finished, int requested);
        void onComplete(Report report);
    }

    public static final class Report {
        public final int requested, completed, errors, playersInLastResponse;
        public final long elapsedMs;
        public final boolean interrupted;
        public final String errorMessage;
        public final List<Double> latenciesMs;
        public final String createdAt;

        Report(int requested, int completed, int errors, int playersInLastResponse,
               long elapsedMs, boolean interrupted, String errorMessage,
               List<Double> latenciesMs) {
            this.requested = requested;
            this.completed = completed;
            this.errors = errors;
            this.playersInLastResponse = playersInLastResponse;
            this.elapsedMs = elapsedMs;
            this.interrupted = interrupted;
            this.errorMessage = errorMessage;
            this.latenciesMs = Collections.unmodifiableList(new ArrayList<>(latenciesMs));
            this.createdAt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        }

        public String format() {
            List<Double> sorted = new ArrayList<>(latenciesMs);
            Collections.sort(sorted);
            double sum = 0;
            for (double value : sorted) sum += value;
            double avg = sorted.isEmpty() ? 0 : sum / sorted.size();
            double median = percentile(sorted, 0.50);
            double p95 = percentile(sorted, 0.95);
            double rate = elapsedMs <= 0 ? 0 : (completed * 1000.0 / elapsedMs);
            StringBuilder result = new StringBuilder();
            result.append("BUBBLE STRESS TEST | ").append(createdAt).append('\n');
            result.append("Comando: querytarget @a\n");
            result.append("Modo: sequencial, sem pausa programada\n");
            result.append("Consultas solicitadas: ").append(requested).append('\n');
            result.append("Consultas respondidas: ").append(completed).append('\n');
            result.append("Erros: ").append(errors).append('\n');
            result.append("Duracao total: ").append(elapsedMs).append(" ms\n");
            result.append(String.format(Locale.US, "Taxa efetiva: %.2f respostas/s\n", rate));
            if (!sorted.isEmpty()) {
                result.append(String.format(Locale.US,
                    "Tempo por comando (ms): min=%.2f | medio=%.2f | mediana=%.2f | p95=%.2f | max=%.2f\n",
                    sorted.get(0), avg, median, p95, sorted.get(sorted.size() - 1)));
                result.append("Jogadores na ultima resposta: ").append(playersInLastResponse).append('\n');
            }
            if (interrupted) result.append("Teste interrompido.\n");
            if (errorMessage != null && !errorMessage.isEmpty())
                result.append("Ocorrencia: ").append(errorMessage).append('\n');
            result.append("Observacao: mede stdin ate stdout JSON completo, nao inclui Internet/API nem TPS.\n");
            return result.toString();
        }

        private static double percentile(List<Double> sorted, double p) {
            if (sorted.isEmpty()) return 0;
            int idx = Math.max(0, (int) Math.ceil(sorted.size() * p) - 1);
            return sorted.get(Math.min(idx, sorted.size() - 1));
        }
    }

    private static final class Pending {
        final CountDownLatch latch = new CountDownLatch(1);
        final StringBuilder json = new StringBuilder();
        boolean started = false;
        boolean inString = false;
        boolean escaped = false;
        int bracketDepth = 0;
        long sentNs;
        long completedNs;
        String completedJson;
        String parsingError;
    }

    private final ConsoleBridge bridge;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Pending pending;
    private volatile Thread worker;

    public QuerytargetBenchmark(ConsoleBridge bridge) {
        this.bridge = bridge;
    }

    public boolean isRunning() { return running.get(); }

    /** Begins a test on a worker thread; callback thread is NOT the Android UI thread. */
    public boolean start(int count, Listener listener) {
        if (count != 10 && count != 20 && count != 30 && count != 40)
            throw new IllegalArgumentException("Allowed batches: 10, 20, 30, 40");
        if (!running.compareAndSet(false, true)) return false;
        worker = new Thread(() -> runBatch(count, listener), "BubbleQuerytargetBenchmark");
        worker.start();
        return true;
    }

    public void cancel() {
        running.set(false);
        Pending waiting = pending;
        if (waiting != null) waiting.latch.countDown();
        Thread t = worker;
        if (t != null) t.interrupt();
    }

    private void runBatch(int count, Listener listener) {
        int completed = 0;
        int errors = 0;
        int lastPlayers = 0;
        String problem = null;
        List<Double> delays = new ArrayList<>();
        long startNs = SystemClock.elapsedRealtimeNanos();
        try {
            if (!bridge.isBdsOnline()) {
                problem = "BDS offline: inicie o servidor para testar.";
            } else {
                for (int i = 0; i < count && running.get(); i++) {
                    if (!bridge.isBdsOnline()) {
                        problem = "BDS ficou offline durante o teste.";
                        break;
                    }
                    Pending p = new Pending();
                    pending = p;
                    p.sentNs = SystemClock.elapsedRealtimeNanos();
                    try {
                        // Bridge sends actual console command, NOT the UI-only '!!' prefix.
                        bridge.sendCommand("querytarget @a");
                    } catch (Exception ex) {
                        errors++;
                        problem = "Falha ao enviar comando: " + ex.getMessage();
                        break;
                    }
                    boolean didAnswer;
                    try {
                        didAnswer = p.latch.await(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        problem = "Teste cancelado.";
                        break;
                    }
                    pending = null;
                    if (!running.get()) break;
                    if (!didAnswer || p.completedJson == null) {
                        errors++;
                        problem = p.parsingError != null ? p.parsingError : "Timeout esperando JSON completo (2500 ms).";
                        // Stop on first timeout to prevent late-response misattribution.
                        break;
                    }
                    try {
                        JSONArray parsed = new JSONArray(p.completedJson);
                        lastPlayers = parsed.length();
                    } catch (JSONException badJson) {
                        errors++;
                        problem = "JSON invalido: " + badJson.getMessage();
                        break;
                    }
                    delays.add((p.completedNs - p.sentNs) / 1_000_000.0);
                    completed++;
                    listener.onProgress(completed, count);
                }
            }
        } finally {
            pending = null;
            boolean interrupted = completed < count;
            long elapsed = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000;
            running.set(false);
            listener.onComplete(new Report(count, completed, errors, lastPlayers,
                    elapsed, interrupted, problem, delays));
        }
    }

    /**
     * Feed ONLY stdout lines from the BDS process, without consuming them:
     * continue showing the same lines in the existing console UI.
     */
    public void acceptStdoutLine(String line) {
        Pending p = pending;
        if (p == null || line == null) return;
        synchronized (p) {
            if (p.completedJson != null || p.parsingError != null) return;
            String segment;
            if (!p.started) {
                int marker = line.indexOf("Target data:");
                if (marker < 0) return;
                segment = line.substring(marker + "Target data:".length());
            } else {
                segment = line;
            }
            for (int i = 0; i < segment.length(); i++) {
                char ch = segment.charAt(i);
                if (!p.started) {
                    if (ch != '[') continue;
                    p.started = true;
                }
                p.json.append(ch);
                if (p.json.length() > MAX_JSON_CHARS) {
                    p.parsingError = "Resposta JSON excedeu 1 MB.";
                    p.latch.countDown();
                    return;
                }
                if (p.escaped) {
                    p.escaped = false;
                    continue;
                }
                if (p.inString && ch == '\\') {
                    p.escaped = true;
                    continue;
                }
                if (ch == '"') {
                    p.inString = !p.inString;
                    continue;
                }
                if (!p.inString) {
                    if (ch == '[') p.bracketDepth++;
                    if (ch == ']') p.bracketDepth--;
                    if (p.bracketDepth == 0) {
                        p.completedNs = SystemClock.elapsedRealtimeNanos();
                        p.completedJson = p.json.toString();
                        p.latch.countDown();
                        return;
                    }
                }
            }
            if (p.started) p.json.append('\n');
        }
    }
}
