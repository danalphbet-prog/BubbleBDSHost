package com.bubble.bdshost;

import android.content.Context;
import java.io.File;

public final class AppPaths {
    private AppPaths() {}

    public static File base(Context c) { return c.getFilesDir(); }
    public static File downloads(Context c) { return new File(base(c), "downloads"); }
    public static File rootfs(Context c) { return new File(base(c), "debian"); }
    public static File legacyUbuntu(Context c) { return new File(base(c), "ubuntu"); }
    public static File bds(Context c) { return new File(rootfs(c), "root/bds"); }
    public static File logs(Context c) { return new File(base(c), "logs"); }
    public static File serverLog(Context c) { return new File(logs(c), "bds.log"); }
    public static File installLog(Context c) { return new File(logs(c), "install.log"); }
    public static File runtimeTmp(Context c) { return new File(base(c), "runtime-tmp"); }
    public static File box64(Context c) { return new File(rootfs(c), "usr/local/bin/box64"); }
    public static File debianMarker(Context c) { return new File(rootfs(c), ".bubble-debian-trixie-v429"); }
    public static File box64Marker(Context c) { return new File(rootfs(c), ".bubble-box64-local-v044"); }

    public static void ensure(Context c) {
        downloads(c).mkdirs();
        logs(c).mkdirs();
        runtimeTmp(c).mkdirs();
    }
}
