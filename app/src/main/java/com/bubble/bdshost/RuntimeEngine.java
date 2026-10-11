package com.bubble.bdshost;

import android.content.Context;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class RuntimeEngine {
    private RuntimeEngine() {}

    public static File runtimeBinary(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "libproroot.so");
    }

    public static boolean runtimePresent(Context c) {
        File f = runtimeBinary(c);
        return f.isFile() && f.canRead();
    }

    public static Process start(Context c, String workDirGuest, String shellCommand) throws Exception {
        File rootfs = AppPaths.rootfs(c);
        File runtime = runtimeBinary(c);
        if (!runtime.isFile()) throw new IllegalStateException("Runtime proroot ausente no APK");
        if (!new File(rootfs, "bin/sh").exists()) throw new IllegalStateException("Ubuntu ainda não instalado");

        AppPaths.ensure(c);
        File tmp = new File(c.getFilesDir(), "runtime-tmp");
        tmp.mkdirs();

        List<String> args = new ArrayList<>();
        args.add(runtime.getAbsolutePath());
        args.add("-r"); args.add(rootfs.getAbsolutePath());
        args.add("-0");
        args.add("--link2symlink");
        args.add("-w"); args.add(workDirGuest);
        args.add("/bin/sh");
        args.add("-lc");
        args.add(shellCommand);

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("PROROOT_TMP_DIR", tmp.getAbsolutePath());
        env.put("PROROOT_VERBOSE", "0");
        env.put("HOME", "/root");
        env.put("USER", "root");
        env.put("LANG", "C.UTF-8");
        env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        return pb.start();
    }
}
