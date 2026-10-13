package com.bubble.bdshost;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class RuntimeEngine {
    private RuntimeEngine() {}

    public enum Mode {
        DEFAULT("default", true, false),
        NO_STATIC_LOADER("no-static-loader", true, true),
        NO_STATIC_NO_LINK("no-static-no-link", false, true);

        public final String id;
        public final boolean link2symlink;
        public final boolean noStaticLoader;

        Mode(String id, boolean link2symlink, boolean noStaticLoader) {
            this.id = id;
            this.link2symlink = link2symlink;
            this.noStaticLoader = noStaticLoader;
        }

        public static Mode fromId(String id) {
            if (id != null) {
                for (Mode m : values()) {
                    if (m.id.equals(id.trim())) return m;
                }
            }
            return DEFAULT;
        }
    }

    public static File runtimeBinary(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "libproroot.so");
    }

    public static boolean runtimePresent(Context c) {
        File f = runtimeBinary(c);
        return f.isFile() && f.canRead();
    }

    private static String readUtf8(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void writeUtf8(File f, String text) throws Exception {
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(f, false)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    public static Mode savedMode(Context c) {
        try {
            File f = AppPaths.runtimeMode(c);
            if (f.isFile()) return Mode.fromId(readUtf8(f));
        } catch (Exception ignored) {}
        return Mode.DEFAULT;
    }

    public static void saveMode(Context c, Mode mode) throws Exception {
        AppPaths.ensure(c);
        writeUtf8(AppPaths.runtimeMode(c), mode.id + "\n");
    }

    public static Process start(Context c, String workDirGuest, String shellCommand) throws Exception {
        return start(c, workDirGuest, shellCommand, savedMode(c));
    }

    public static Process start(Context c, String workDirGuest, String shellCommand, Mode mode) throws Exception {
        File rootfs = AppPaths.rootfs(c);
        File runtime = runtimeBinary(c);

        if (!runtime.isFile()) throw new IllegalStateException("Runtime proroot ausente no APK");
        if (!new File(rootfs, "bin/sh").exists()) throw new IllegalStateException("Linux ainda não instalado");

        AppPaths.ensure(c);

        List<String> args = new ArrayList<>();
        args.add(runtime.getAbsolutePath());
        args.add("-r");
        args.add(rootfs.getAbsolutePath());
        args.add("-0");

        if (mode.link2symlink) args.add("--link2symlink");
        if (mode.noStaticLoader) args.add("--no-static-loader");

        args.add("-w");
        args.add(workDirGuest);
        args.add("/bin/sh");
        args.add("-c");
        args.add(shellCommand);

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);

        Map<String, String> env = pb.environment();
        env.put("PROROOT_TMP_DIR", c.getFilesDir().getAbsolutePath());
        env.put("PROROOT_LOG_APPEND", AppPaths.prorootLog(c).getAbsolutePath());
        env.put("PROROOT_VERBOSE", "0");
        env.put("HOME", "/root");
        env.put("USER", "root");
        env.put("LANG", "C.UTF-8");
        env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");

        return pb.start();
    }
}
