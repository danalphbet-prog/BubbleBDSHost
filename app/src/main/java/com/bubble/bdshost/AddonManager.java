package com.bubble.bdshost;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class AddonManager {
    public static final String TYPE_BEHAVIOR = "behavior";
    public static final String TYPE_RESOURCE = "resource";

    public static final class PackInfo {
        public String type;
        public String uuid;
        public String name;
        public String description;
        public int[] version = new int[]{1, 0, 0};
        public File directory;
        public File iconFile;
        public long sizeBytes;
        public boolean active;

        public String versionLabel() {
            return version[0] + "." + version[1] + "." + version[2];
        }
    }

    private AddonManager() {}

    public static List<PackInfo> importArchive(Context context, Uri uri) throws Exception {
        if (BdsService.isRunning(context)) {
            throw new IllegalStateException("Pare o servidor antes de importar add-ons.");
        }
        if (uri == null) throw new IllegalArgumentException("Arquivo não selecionado.");

        File imports = new File(context.getFilesDir(), "addon_imports");
        if (!imports.exists() && !imports.mkdirs()) {
            throw new IllegalStateException("Não foi possível preparar a área de importação.");
        }

        File incoming = new File(imports, "incoming-" + System.currentTimeMillis() + ".zip");
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("Não foi possível abrir o arquivo selecionado.");
            copy(in, incoming);
        }

        File stage = new File(imports, "stage-" + System.currentTimeMillis());
        if (!stage.mkdirs()) throw new IllegalStateException("Não foi possível criar a área temporária.");

        List<PackInfo> imported = new ArrayList<>();
        try {
            extractZip(incoming, stage);
            expandNestedArchives(stage, 0);

            List<File> roots = new ArrayList<>();
            findPackRoots(stage, roots);
            if (roots.isEmpty()) {
                throw new IllegalArgumentException(
                        "Nenhum manifest.json de Behavior Pack ou Resource Pack foi encontrado.");
            }

            for (File root : roots) {
                PackInfo info = parsePack(root, null);
                if (info == null) continue;

                File base = TYPE_RESOURCE.equals(info.type)
                        ? resourceRoot(context) : behaviorRoot(context);
                if (!base.exists() && !base.mkdirs()) {
                    throw new IllegalStateException("Não foi possível preparar a pasta de add-ons.");
                }

                String folderName = "bubble_" + info.uuid.replace("-", "");
                File destination = new File(base, folderName);
                if (destination.exists()) deleteTree(destination);
                copyTree(root, destination);

                PackInfo installed = parsePack(destination, info.type);
                if (installed != null) imported.add(installed);
            }

            if (imported.isEmpty()) {
                throw new IllegalArgumentException("O arquivo não contém um add-on compatível.");
            }
            return imported;
        } finally {
            incoming.delete();
            deleteTree(stage);
        }
    }

    public static List<PackInfo> listPacks(Context context, String worldName, String type)
            throws Exception {
        File base = TYPE_RESOURCE.equals(type) ? resourceRoot(context) : behaviorRoot(context);
        if (!base.exists()) base.mkdirs();

        Set<String> activeIds = readActiveIds(context, worldName, type);
        List<PackInfo> result = new ArrayList<>();
        File[] children = base.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!child.isDirectory()) continue;
                PackInfo info = parsePack(child, type);
                if (info == null) continue;
                info.active = activeIds.contains(info.uuid.toLowerCase(Locale.ROOT));
                result.add(info);
            }
        }
        Collections.sort(result, Comparator.comparing(
                p -> p.name == null ? "" : p.name.toLowerCase(Locale.ROOT)));
        return result;
    }

    public static void setActive(Context context, String worldName, PackInfo pack, boolean active)
            throws Exception {
        if (BdsService.isRunning(context)) {
            throw new IllegalStateException("Pare o servidor antes de alterar add-ons.");
        }
        if (pack == null || pack.uuid == null) throw new IllegalArgumentException("Add-on inválido.");
        String cleanWorld = sanitizeWorldName(worldName);
        File world = new File(new File(AppPaths.bds(context), "worlds"), cleanWorld);
        if (!world.exists() && !world.mkdirs()) {
            throw new IllegalStateException("Não foi possível preparar a pasta do mundo.");
        }

        File target = new File(world,
                TYPE_RESOURCE.equals(pack.type)
                        ? "world_resource_packs.json" : "world_behavior_packs.json");
        JSONArray array = readPackArray(target);
        JSONArray next = new JSONArray();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("pack_id", "");
            if (!pack.uuid.equalsIgnoreCase(id)) next.put(item);
        }
        if (active) {
            JSONObject item = new JSONObject();
            item.put("pack_id", pack.uuid);
            JSONArray version = new JSONArray();
            version.put(pack.version[0]);
            version.put(pack.version[1]);
            version.put(pack.version[2]);
            item.put("version", version);
            next.put(item);
        }
        writeText(target, next.toString(2) + "\n");
    }

    public static void removePack(Context context, String worldName, PackInfo pack) throws Exception {
        if (BdsService.isRunning(context)) {
            throw new IllegalStateException("Pare o servidor antes de remover add-ons.");
        }
        setActive(context, worldName, pack, false);
        if (pack != null && pack.directory != null) deleteTree(pack.directory);
    }

    private static File behaviorRoot(Context context) {
        return new File(AppPaths.bds(context), "behavior_packs");
    }

    private static File resourceRoot(Context context) {
        return new File(AppPaths.bds(context), "resource_packs");
    }

    private static Set<String> readActiveIds(Context context, String worldName, String type)
            throws Exception {
        String cleanWorld = sanitizeWorldName(worldName);
        File file = new File(new File(new File(AppPaths.bds(context), "worlds"), cleanWorld),
                TYPE_RESOURCE.equals(type)
                        ? "world_resource_packs.json" : "world_behavior_packs.json");
        JSONArray array = readPackArray(file);
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("pack_id", "").trim();
            if (!id.isEmpty()) ids.add(id.toLowerCase(Locale.ROOT));
        }
        return ids;
    }

    private static JSONArray readPackArray(File file) throws Exception {
        if (!file.isFile() || file.length() == 0) return new JSONArray();
        String text = readText(file).trim();
        if (text.isEmpty()) return new JSONArray();
        try {
            return new JSONArray(text);
        } catch (Exception e) {
            throw new IllegalStateException("Lista de add-ons do mundo está inválida: " + file.getName());
        }
    }

    private static PackInfo parsePack(File root, String forcedType) {
        try {
            File manifestFile = new File(root, "manifest.json");
            if (!manifestFile.isFile()) return null;
            JSONObject manifest = new JSONObject(readText(manifestFile));
            JSONObject header = manifest.optJSONObject("header");
            if (header == null) return null;

            String uuid = header.optString("uuid", "").trim();
            if (uuid.isEmpty()) return null;
            try { UUID.fromString(uuid); } catch (Exception e) { return null; }

            String detected = detectType(manifest);
            String type = forcedType != null ? forcedType : detected;
            if (type == null) return null;

            PackInfo info = new PackInfo();
            info.type = type;
            info.uuid = uuid;
            info.name = resolveLang(root, header.optString("name", "Add-on sem nome"));
            info.description = resolveLang(root, header.optString("description", "Sem descrição"));
            info.version = parseVersion(header.optJSONArray("version"));
            info.directory = root;
            info.sizeBytes = directorySize(root);
            File icon = new File(root, "pack_icon.png");
            if (!icon.isFile()) icon = new File(root, "pack_icon.jpg");
            info.iconFile = icon.isFile() ? icon : null;
            return info;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String detectType(JSONObject manifest) {
        JSONArray modules = manifest.optJSONArray("modules");
        boolean resource = false;
        boolean behavior = false;
        if (modules != null) {
            for (int i = 0; i < modules.length(); i++) {
                JSONObject module = modules.optJSONObject(i);
                if (module == null) continue;
                String t = module.optString("type", "").toLowerCase(Locale.ROOT);
                if ("resources".equals(t)) resource = true;
                if ("data".equals(t) || "script".equals(t)) behavior = true;
            }
        }
        if (behavior) return TYPE_BEHAVIOR;
        if (resource) return TYPE_RESOURCE;
        return null;
    }

    private static int[] parseVersion(JSONArray version) {
        int[] out = new int[]{1, 0, 0};
        if (version == null) return out;
        for (int i = 0; i < 3 && i < version.length(); i++) out[i] = version.optInt(i, 0);
        return out;
    }

    private static String resolveLang(File root, String raw) {
        if (raw == null || raw.trim().isEmpty()) return "Sem informação";
        if (!raw.contains(".")) return raw;
        File lang = new File(root, "texts/en_US.lang");
        if (!lang.isFile()) return raw;
        try {
            String prefix = raw + "=";
            for (String line : readText(lang).split("\\R")) {
                if (line.startsWith(prefix)) return line.substring(prefix.length()).trim();
            }
        } catch (Exception ignored) {}
        return raw;
    }

    private static long directorySize(File file) {
        if (file == null || !file.exists()) return 0L;
        if (file.isFile()) return Math.max(0L, file.length());

        long total = 0L;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                total += directorySize(child);
                if (total < 0L) return Long.MAX_VALUE;
            }
        }
        return total;
    }

    private static String sanitizeWorldName(String worldName) {
        String w = worldName == null ? "" : worldName.trim();
        if (w.isEmpty()) w = "Meu Mundo Bubble";
        w = w.replace('/', '_').replace('\\', '_');
        while (w.contains("..")) w = w.replace("..", "_");
        return w;
    }

    private static void findPackRoots(File dir, List<File> result) {
        if (new File(dir, "manifest.json").isFile()) {
            result.add(dir);
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) if (f.isDirectory()) findPackRoots(f, result);
    }

    private static void expandNestedArchives(File dir, int depth) throws Exception {
        if (depth > 3) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                expandNestedArchives(f, depth + 1);
                continue;
            }
            String name = f.getName().toLowerCase(Locale.ROOT);
            if (!(name.endsWith(".mcpack") || name.endsWith(".mcaddon") || name.endsWith(".zip"))) {
                continue;
            }
            File nested = new File(f.getParentFile(), f.getName() + "_expanded");
            if (!nested.exists()) nested.mkdirs();
            try {
                extractZip(f, nested);
                expandNestedArchives(nested, depth + 1);
            } catch (Exception ignored) {
                deleteTree(nested);
            }
        }
    }

    private static void extractZip(File archive, File destination) throws Exception {
        String base = destination.getCanonicalPath() + File.separator;
        byte[] buffer = new byte[32768];
        long extractedBytes = 0;
        int entries = 0;
        final long maxExtractedBytes = 512L * 1024L * 1024L;
        final int maxEntries = 10000;

        try (ZipInputStream zin = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                entries++;
                if (entries > maxEntries) {
                    throw new SecurityException("Pacote possui arquivos demais.");
                }
                if (entry.getName() == null || entry.getName().isEmpty()) continue;
                File out = new File(destination, entry.getName());
                String canonical = out.getCanonicalPath();
                if (!canonical.startsWith(base)) {
                    throw new SecurityException("Arquivo compactado contém caminho inválido.");
                }
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                        int n;
                        while ((n = zin.read(buffer)) >= 0) {
                            if (n <= 0) continue;
                            extractedBytes += n;
                            if (extractedBytes > maxExtractedBytes) {
                                throw new SecurityException("Pacote excede o limite de 512 MB extraídos.");
                            }
                            os.write(buffer, 0, n);
                        }
                    }
                }
                zin.closeEntry();
            }
        }
    }

    private static void copy(InputStream in, File destination) throws Exception {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        byte[] buffer = new byte[32768];
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(destination))) {
            int n;
            while ((n = in.read(buffer)) >= 0) if (n > 0) out.write(buffer, 0, n);
        }
    }

    private static void copyTree(File source, File destination) throws Exception {
        if (source.isDirectory()) {
            if (!destination.exists() && !destination.mkdirs()) {
                throw new IllegalStateException("Não foi possível copiar o add-on.");
            }
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) copyTree(child, new File(destination, child.getName()));
            }
        } else {
            try (InputStream in = new BufferedInputStream(new FileInputStream(source))) {
                copy(in, destination);
            }
        }
    }

    private static String readText(File file) throws Exception {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) if (n > 0) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void writeText(File file, String text) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        file.delete();
    }
}
