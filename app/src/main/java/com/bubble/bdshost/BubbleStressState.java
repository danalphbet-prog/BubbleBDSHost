package com.bubble.bdshost;

import android.content.Context;
import android.content.SharedPreferences;
import com.bubble.bdshost.benchmark.QuerytargetBenchmark;

/** Shared local benchmark results. No network requests and no Firebase writes. */
public final class BubbleStressState {
    public static final String PREFS = "bubble_stress_test";
    private BubbleStressState() { }

    public static SharedPreferences read(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void start(Context c, int count) {
        read(c).edit()
                .putBoolean("running", true)
                .putInt("requested", count)
                .putInt("completed", 0)
                .putString("status", "Executando")
                .putString("report", "")
                .apply();
    }

    public static void progress(Context c, int completed, int requested) {
        read(c).edit()
                .putBoolean("running", true)
                .putInt("completed", completed)
                .putInt("requested", requested)
                .apply();
    }

    public static void finish(Context c, QuerytargetBenchmark.Report report) {
        read(c).edit()
                .putBoolean("running", false)
                .putInt("completed", report.completed)
                .putInt("requested", report.requested)
                .putString("status", report.interrupted ? "Interrompido" : "Concluído")
                .putString("report", report.format())
                .apply();
    }

    public static void fail(Context c, String reason) {
        read(c).edit()
                .putBoolean("running", false)
                .putString("status", reason)
                .apply();
    }

    public static void interruptedByRestart(Context c) {
        SharedPreferences prefs = read(c);
        if (prefs.getBoolean("running", false)) {
            fail(c, "Teste anterior interrompido pela reinicialização do serviço");
        }
    }
}
