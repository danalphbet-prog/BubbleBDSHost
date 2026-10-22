package com.bubble.bdshost;

import android.system.Os;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    /** Remove somente packs internos do BDS; nunca remove packs bubble_* ou worlds. */
    public static int clearBundledPacksBeforeExtract(File zip, File dest) throws Exception {
        Set<String> bundled = bundledNamesInArchive(zip);
        if (!bundled.contains("behavior_packs/vanilla") ||
                !bundled.contains("resource_packs/vanilla")) {
            throw new IllegalStateException("O ZIP não contém os packs Vanilla esperados; nenhum conteúdo foi removido.");
        }
        int removed = 0;
        for (String root : new String[]{"behavior_packs", "resource_packs"}) {
            File base = new File(dest, root);
            File[] entries = base.listFiles();
            if (entries == null) continue;
            for (File entry : entries) {
                if (entry.isDirectory() && isBundledPackName(entry.getName())) {
                    deleteTree(entry);
                    removed++;
                }
            }
        }
        return removed;
    }

    /** Limpa resíduos de outras versões mesmo em instalações já existentes. */
    public static int pruneBundledPacksForArchive(File zip, File dest) throws Exception {
        Set<String> expected = bundledNamesInArchive(zip);
        if (!expected.contains("behavior_packs/vanilla") ||
                !expected.contains("resource_packs/vanilla")) {
            throw new IllegalStateException("ZIP sem Vanilla esperado; limpeza cancelada.");
        }
        int removed = 0;
        for (String root : new String[]{"behavior_packs", "resource_packs"}) {
            File[] entries = new File(dest, root).listFiles();
            if (entries == null) continue;
            for (File entry : entries) {
                if (entry.isDirectory() && isBundledPackName(entry.getName()) &&
                        !expected.contains(root + "/" + entry.getName())) {
                    deleteTree(entry);
                    removed++;
                }
            }
        }
        return removed;
    }

    /** Sem ZIP, remove apenas packs internos comprovadamente incompatíveis pelo manifesto. */
    public static int pruneBundledPacksAboveVersion(File dest, String version) throws Exception {
        int removed = 0;
        for (String root : new String[]{"behavior_packs", "resource_packs"}) {
            File[] entries = new File(dest, root).listFiles();
            if (entries == null) continue;
            for (File entry : entries) {
                if (!entry.isDirectory() || !isBundledPackName(entry.getName()) ||
                        "vanilla".equals(entry.getName())) continue;
                File manifest = new File(entry, "manifest.json");
                if (!manifest.isFile()) continue;
                JSONObject header = new JSONObject(new String(
                        Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8))
                        .optJSONObject("header");
                JSONArray min = header == null ? null : header.optJSONArray("min_engine_version");
                if (min == null || min.length() < 3) continue;
                String required = min.optInt(0) + "." + min.optInt(1) + "." + min.optInt(2);
                if (BdsVersionManager.compareVersions(required, version) > 0) {
                    deleteTree(entry);
                    removed++;
                }
            }
        }
        return removed;
    }

    private static Set<String> bundledNamesInArchive(File zip) throws Exception {
        Set<String> found = new HashSet<>();
        try (ZipInputStream zin = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                String[] parts = name == null ? new String[0] : name.split("/");
                if (parts.length == 3 && "manifest.json".equals(parts[2]) &&
                        ("behavior_packs".equals(parts[0]) || "resource_packs".equals(parts[0])) &&
                        isBundledPackName(parts[1])) {
                    found.add(parts[0] + "/" + parts[1]);
                }
            }
        }
        return found;
    }

    private static boolean isBundledPackName(String name) {
        return "vanilla".equals(name) || name.startsWith("vanilla_") ||
                "chemistry".equals(name) || "experimental_nether_spawning".equals(name);
    }

    private static void deleteTree(File file) throws Exception {
        if (Files.isSymbolicLink(file.toPath()) || file.isFile()) {
            Files.deleteIfExists(file.toPath());
            return;
        }
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children == null) throw new IllegalStateException("Não foi possível listar " + file.getName());
        for (File child : children) deleteTree(child);
        Files.deleteIfExists(file.toPath());
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
