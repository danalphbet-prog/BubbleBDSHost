package com.bubble.bdshost;

import android.content.Context;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class RuntimeEngine {
    private RuntimeEngine() {}

    public static File runtimeBinary(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "libproot_classic.so");
    }

    public static File loaderBinary(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "libproot_loader.so");
    }

    public static boolean runtimePresent(Context c) {
        File p = runtimeBinary(c);
        File l = loaderBinary(c);
        return p.isFile() && p.canRead() && l.isFile() && l.canRead();
    }

    public static Process start(Context c, String workDirGuest, String shellCommand) throws Exception {
        File rootfs = AppPaths.rootfs(c);
        File proot = runtimeBinary(c);
        File loader = loaderBinary(c);

        if (!proot.isFile()) throw new IllegalStateException("PRoot clássico ausente no APK");
        if (!loader.isFile()) throw new IllegalStateException("Loader do PRoot ausente no APK");
        if (!new File(rootfs, "bin/bash").exists()) throw new IllegalStateException("Debian ainda não instalado");

        AppPaths.ensure(c);

        List<String> args = new ArrayList<>();
        args.add(proot.getAbsolutePath());
        args.add("--kill-on-exit");
        args.add("--link2symlink");
        args.add("--sysvipc");
        args.add("-0");
        args.add("-r");
        args.add(rootfs.getAbsolutePath());
        args.add("-b"); args.add("/dev");
        args.add("-b"); args.add("/proc");
        args.add("-b"); args.add("/sys");
        args.add("-w"); args.add(workDirGuest);
        args.add("/usr/bin/env");
        args.add("-i");
        args.add("HOME=/root");
        args.add("USER=root");
        args.add("LOGNAME=root");
        args.add("SHELL=/bin/bash");
        args.add("TERM=xterm-256color");
        args.add("LANG=C.UTF-8");
        args.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        args.add("/bin/bash");
        args.add("-lc");
        args.add(shellCommand);

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);

        Map<String, String> env = pb.environment();
        env.put("PROOT_LOADER", loader.getAbsolutePath());
        env.put("PROOT_NO_SECCOMP", "1");
        env.put("LD_LIBRARY_PATH", c.getApplicationInfo().nativeLibraryDir);
        env.put("TMPDIR", AppPaths.runtimeTmp(c).getAbsolutePath());
        env.put("PROOT_TMP_DIR", AppPaths.runtimeTmp(c).getAbsolutePath());
        env.put("ANDROID_DATA", "/data");
        env.put("ANDROID_ROOT", "/system");

        return pb.start();
    }
}
