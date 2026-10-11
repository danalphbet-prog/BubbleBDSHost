package com.bubble.bdshost;

import android.system.Os;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ZipUtils {
    private ZipUtils() {}

    public static void validateBdsZip(File zip) throws Exception {
        boolean server = false;
        boolean props = false;
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String n = e.getName();
                if ("bedrock_server".equals(n) || n.endsWith("/bedrock_server")) server = true;
                if ("server.properties".equals(n) || n.endsWith("/server.properties")) props = true;
            }
        }
        if (!server || !props) throw new IllegalStateException("ZIP do BDS não possui bedrock_server/server.properties");
    }

    public static void extractBds(File zip, File dest) throws Exception {
        dest.mkdirs();
        String root = dest.getCanonicalPath() + File.separator;
        byte[] buf = new byte[128 * 1024];
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip), 128 * 1024))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                if (name == null || name.isBlank()) continue;
                File out = new File(dest, name);
                String c = out.getCanonicalPath();
                if (!c.startsWith(root) && !c.equals(dest.getCanonicalPath())) {
                    throw new SecurityException("Entrada ZIP inválida: " + name);
                }
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();

                // Preserve world/config files when reinstalling/updating.
                if (out.exists() && (name.startsWith("worlds/") || name.equals("server.properties") ||
                        name.equals("allowlist.json") || name.equals("permissions.json"))) {
                    continue;
                }

                try (BufferedOutputStream fout = new BufferedOutputStream(new FileOutputStream(out), 128 * 1024)) {
                    int n;
                    while ((n = zin.read(buf)) >= 0) if (n > 0) fout.write(buf, 0, n);
                }
            }
        }
        File server = new File(dest, "bedrock_server");
        if (!server.exists()) throw new IllegalStateException("bedrock_server não apareceu após extração");
        try { Os.chmod(server.getAbsolutePath(), 0755); } catch (Exception ignored) {}
    }
}
