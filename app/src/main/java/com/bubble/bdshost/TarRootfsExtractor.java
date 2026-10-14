package com.bubble.bdshost;

import android.system.Os;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

public final class TarRootfsExtractor {
    public interface Log { void line(String text); }

    private static final class HardLink {
        final String dest;
        final String source;
        HardLink(String dest, String source) {
            this.dest = dest;
            this.source = source;
        }
    }

    private TarRootfsExtractor() {}

    public static void extractTarGz(File archive, File root, Log log) throws Exception {
        try (InputStream in = new GZIPInputStream(
                new BufferedInputStream(new FileInputStream(archive), 128 * 1024),
                128 * 1024)) {
            extractTar(in, root, 0, log);
        }
    }

    public static void extractTarXz(File archive, File root, int stripComponents, Log log) throws Exception {
        try (InputStream in = new XZInputStream(
                new BufferedInputStream(new FileInputStream(archive), 128 * 1024))) {
            extractTar(in, root, stripComponents, log);
        }
    }

    private static void extractTar(InputStream input, File root, int stripComponents, Log log) throws Exception {
        root.mkdirs();
        String rootBase = root.getCanonicalPath();
        String rootCanonical = rootBase + File.separator;
        List<HardLink> hardLinks = new ArrayList<>();
        long entries = 0;

        try (TarArchiveInputStream tin = new TarArchiveInputStream(input, StandardCharsets.UTF_8.name())) {
            TarArchiveEntry e;
            byte[] buf = new byte[128 * 1024];

            while ((e = tin.getNextTarEntry()) != null) {
                entries++;
                String name = cleanName(e.getName(), stripComponents);
                if (name == null || name.isBlank()) continue;

                File out = new File(root, name);
                String canonical = out.getCanonicalPath();
                if (!canonical.equals(rootBase) && !canonical.startsWith(rootCanonical)) {
                    throw new SecurityException("Entrada inválida no rootfs: " + name);
                }

                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();

                if (e.isDirectory()) {
                    out.mkdirs();
                    chmodSafe(out, e.getMode());
                } else if (e.isSymbolicLink()) {
                    deleteIfExists(out);
                    Os.symlink(e.getLinkName(), out.getAbsolutePath());
                } else if (e.isLink()) {
                    String sourceName = cleanName(e.getLinkName(), stripComponents);
                    if (sourceName != null && !sourceName.isBlank()) {
                        hardLinks.add(new HardLink(
                                out.getAbsolutePath(),
                                new File(root, sourceName).getAbsolutePath()));
                    }
                } else if (e.isFile()) {
                    deleteIfExists(out);
                    try (BufferedOutputStream fout =
                                 new BufferedOutputStream(new FileOutputStream(out), 128 * 1024)) {
                        int n;
                        while ((n = tin.read(buf)) >= 0) {
                            if (n > 0) fout.write(buf, 0, n);
                        }
                    }
                    chmodSafe(out, e.getMode());
                    if (e.getLastModifiedDate() != null) {
                        out.setLastModified(e.getLastModifiedDate().getTime());
                    }
                } else {
                    // Device nodes/FIFOs/sockets: /dev, /proc e /sys são bindados pelo PRoot.
                }

                if (log != null && entries % 250 == 0) {
                    log.line("Extraindo Debian... " + entries + " entradas");
                }
            }
        }

        for (HardLink h : hardLinks) {
            File d = new File(h.dest);
            File s = new File(h.source);
            if (!s.exists()) continue;

            String dc = d.getCanonicalPath();
            String sc = s.getCanonicalPath();
            if ((!dc.equals(rootBase) && !dc.startsWith(rootCanonical)) ||
                    (!sc.equals(rootBase) && !sc.startsWith(rootCanonical))) {
                throw new SecurityException("Hardlink inválido no rootfs");
            }

            deleteIfExists(d);
            try {
                Os.link(s.getAbsolutePath(), d.getAbsolutePath());
            } catch (Exception ex) {
                java.nio.file.Files.copy(
                        s.toPath(), d.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }

        if (log != null) {
            log.line("Debian extraído: " + entries + " entradas.");
        }
    }

    private static String cleanName(String input, int stripComponents) {
        if (input == null) return null;
        String name = input;
        while (name.startsWith("./")) name = name.substring(2);
        while (name.startsWith("/")) name = name.substring(1);

        for (int i = 0; i < stripComponents; i++) {
            int slash = name.indexOf('/');
            if (slash < 0) return "";
            name = name.substring(slash + 1);
        }
        return name;
    }

    private static void chmodSafe(File f, int mode) {
        int m = mode & 0777;
        if (m == 0) m = f.isDirectory() ? 0755 : 0644;
        try { Os.chmod(f.getAbsolutePath(), m); } catch (Exception ignored) {}
    }

    private static void deleteIfExists(File f) throws Exception {
        if (!f.exists() && !java.nio.file.Files.isSymbolicLink(f.toPath())) return;
        java.nio.file.Files.deleteIfExists(f.toPath());
    }
}
