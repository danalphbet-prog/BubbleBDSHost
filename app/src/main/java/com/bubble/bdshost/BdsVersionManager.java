package com.bubble.bdshost;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BdsVersionManager {
    public static final String CHANNEL_STABLE = InstallerManager.CHANNEL_STABLE;
    public static final String CHANNEL_PREVIEW = InstallerManager.CHANNEL_PREVIEW;
    public static final String API_URL =
            "https://net-secondary.web.minecraft-services.net/api/v1.0/download/links";

    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "bedrock-server-([0-9]+(?:\\.[0-9]+){2,3})\\.zip",
            Pattern.CASE_INSENSITIVE);

    private static final String[] PRESERVE_FILES = new String[] {
            "server.properties",
            "permissions.json",
            "allowlist.json",
            "whitelist.json",
            "valid_known_packs.json"
    };

    public interface Progress {
        void onProgress(int percent, String stage);
    }

    public static final class VersionInfo {
        public String channel;
        public String version;
        public String url;
        public boolean remote;
        public boolean downloaded;
        public boolean installed;
        public File archive;

        public String channelLabel() {
            return CHANNEL_PREVIEW.equals(channel) ? "Prévia" : "Oficial";
        }

        public String key() {
            return channel + "|" + version;
        }
    }

    public static final class Catalog {
        public VersionInfo stable;
        public VersionInfo preview;
        public final List<VersionInfo> all = new ArrayList<>();
    }

    private BdsVersionManager() {}

    public static Catalog fetchCatalog(Context context) throws Exception {
        JSONObject root = new JSONObject(httpGet(API_URL));
        JSONObject result = root.optJSONObject("result");
        JSONArray links = result == null ? null : result.optJSONArray("links");
        if (links == null) throw new IllegalStateException("A lista oficial de versões não respondeu corretamente.");

        Catalog catalog = new Catalog();
        for (int i = 0; i < links.length(); i++) {
            JSONObject item = links.optJSONObject(i);
            if (item == null) continue;
            String type = item.optString("downloadType", "");
            String url = item.optString("downloadUrl", "");
            if (url.isEmpty()) continue;

            String channel;
            if ("serverBedrockLinux".equals(type)) channel = CHANNEL_STABLE;
            else if ("serverBedrockPreviewLinux".equals(type)) channel = CHANNEL_PREVIEW;
            else continue;

            String version = versionFromUrl(url);
            if (version == null) continue;
            VersionInfo info = makeInfo(context, channel, version, url, true);
            if (CHANNEL_PREVIEW.equals(channel)) catalog.preview = info;
            else catalog.stable = info;
        }

        Map<String, VersionInfo> merged = new LinkedHashMap<>();
        if (catalog.stable != null) merged.put(catalog.stable.key(), catalog.stable);
        if (catalog.preview != null) merged.put(catalog.preview.key(), catalog.preview);
        for (VersionInfo local : listDownloaded(context)) {
            VersionInfo existing = merged.get(local.key());
            if (existing != null) {
                existing.downloaded = true;
                existing.archive = local.archive;
            } else {
                merged.put(local.key(), local);
            }
        }

        String installedVersion = InstallerManager.readInstalledBdsVersion(context);
        String installedChannel = installedChannel(context);
        if (installedVersion != null && !"—".equals(installedVersion)) {
            String key = installedChannel + "|" + installedVersion;
            VersionInfo installed = merged.get(key);
            if (installed == null) {
                installed = makeInfo(context, installedChannel, installedVersion, null, false);
                merged.put(key, installed);
            }
            installed.installed = true;
        }

        catalog.all.addAll(merged.values());
        Collections.sort(catalog.all, (a, b) -> {
            if (a.installed != b.installed) return a.installed ? -1 : 1;
            if (!a.channel.equals(b.channel)) {
                if (CHANNEL_STABLE.equals(a.channel)) return -1;
                if (CHANNEL_STABLE.equals(b.channel)) return 1;
            }
            return -compareVersions(a.version, b.version);
        });
        return catalog;
    }

    public static Catalog localCatalog(Context context) {
        Catalog catalog = new Catalog();
        Map<String, VersionInfo> merged = new LinkedHashMap<>();
        for (VersionInfo local : listDownloaded(context)) merged.put(local.key(), local);
        String version = InstallerManager.readInstalledBdsVersion(context);
        String channel = installedChannel(context);
        if (version != null && !"—".equals(version)) {
            String key = channel + "|" + version;
            VersionInfo installed = merged.get(key);
            if (installed == null) {
                installed = makeInfo(context, channel, version, null, false);
                merged.put(key, installed);
            }
            installed.installed = true;
        }
        catalog.all.addAll(merged.values());
        return catalog;
    }

    public static String installedChannel(Context context) {
        String marker = readSmall(InstallerManager.bdsMarker(context));
        return marker.startsWith(CHANNEL_PREVIEW + "|") ? CHANNEL_PREVIEW : CHANNEL_STABLE;
    }

    public static boolean hasUpdate(Context context, Catalog catalog) {
        if (catalog == null) return false;
        String installed = InstallerManager.readInstalledBdsVersion(context);
        String channel = installedChannel(context);
        if (installed == null || "—".equals(installed)) return false;
        if (CHANNEL_PREVIEW.equals(channel)) {
            return catalog.preview != null && compareVersions(catalog.preview.version, installed) > 0;
        }
        return catalog.stable != null && compareVersions(catalog.stable.version, installed) > 0;
    }

    public static VersionInfo noticeCandidate(Context context, Catalog catalog) {
        if (catalog == null) return null;
        String installed = InstallerManager.readInstalledBdsVersion(context);
        String channel = installedChannel(context);
        if (installed == null || "—".equals(installed)) return null;

        if (CHANNEL_PREVIEW.equals(channel)) {
            if (catalog.preview != null && compareVersions(catalog.preview.version, installed) > 0) {
                return catalog.preview;
            }
            return null;
        }

        if (catalog.stable != null && compareVersions(catalog.stable.version, installed) > 0) {
            return catalog.stable;
        }
        if (catalog.preview != null && compareVersions(catalog.preview.version, installed) > 0) {
            return catalog.preview;
        }
        return null;
    }

    public static void download(Context context, VersionInfo info, Progress progress) throws Exception {
        if (info == null || info.version == null) throw new IllegalArgumentException("Versão inválida.");
        if (info.url == null || info.url.trim().isEmpty()) {
            throw new IllegalStateException("Essa versão não possui um link de download disponível.");
        }
        File dir = archiveDir(context);
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Não foi possível preparar o armazenamento de versões.");
        File target = archiveFile(context, info.channel, info.version);
        if (target.isFile()) {
            ZipUtils.validateBdsZip(target);
            info.downloaded = true;
            info.archive = target;
            if (progress != null) progress.onProgress(100, "Versão já baixada");
            return;
        }

        File partial = new File(target.getAbsolutePath() + ".part");
        if (partial.exists()) partial.delete();
        DownloadUtils.download(info.url, partial, (done, total) -> {
            if (progress == null) return;
            int p = total > 0 ? (int)Math.min(100, Math.round(done * 100.0 / total)) : 0;
            progress.onProgress(p, "Baixando " + info.channelLabel() + " " + info.version);
        });
        ZipUtils.validateBdsZip(partial);
        if (target.exists()) target.delete();
        if (!partial.renameTo(target)) {
            copyFile(partial, target);
            partial.delete();
        }
        info.downloaded = true;
        info.archive = target;
        if (progress != null) progress.onProgress(100, "Download concluído");
    }

    public static void activate(Context context, VersionInfo info, Progress progress) throws Exception {
        if (BdsService.isRunning(context)) {
            throw new IllegalStateException("Pare o servidor antes de alternar a versão.");
        }
        File archive = resolveArchive(context, info);
        if (archive == null || !archive.isFile()) {
            throw new IllegalStateException("Baixe essa versão antes de usá-la.");
        }
        ZipUtils.validateBdsZip(archive);

        Map<String, byte[]> backups = new LinkedHashMap<>();
        File bds = AppPaths.bds(context);
        for (String name : PRESERVE_FILES) {
            File f = new File(bds, name);
            if (f.isFile()) backups.put(name, readBytes(f));
        }

        if (progress != null) progress.onProgress(5, "Isolando conteúdo da versão anterior");
        ZipUtils.clearBundledPacksBeforeExtract(archive, bds);
        ZipUtils.extractBds(archive, bds);
        if (progress != null) progress.onProgress(82, "Restaurando configurações");

        for (Map.Entry<String, byte[]> e : backups.entrySet()) {
            writeBytes(new File(bds, e.getKey()), e.getValue());
        }
        File server = new File(bds, "bedrock_server");
        if (!server.isFile()) throw new IllegalStateException("Pacote extraído sem bedrock_server.");
        server.setExecutable(true, false);
        writeText(InstallerManager.bdsMarker(context), info.channel + "|" + info.version + "\n");
        info.installed = true;
        // Apenas o ZIP da versão em uso permanece em cache. Mundos e add-ons são preservados.
        try {
            cleanupInactiveArchives(context);
        } catch (Exception cleanupError) {
            if (progress != null) progress.onProgress(96,
                    "Versão ativa; limpeza de ZIPs antigos pendente: " + cleanupError.getMessage());
        }
        if (progress != null) progress.onProgress(100, "Versão ativa: " + info.version);
    }

    public static boolean repairBundledPacksIfNeeded(Context context, Progress progress)
            throws Exception {
        File bds = AppPaths.bds(context);
        File behaviorVanilla = new File(bds, "behavior_packs/vanilla/manifest.json");
        File resourceVanilla = new File(bds, "resource_packs/vanilla/manifest.json");

        boolean missingVanilla = !behaviorVanilla.isFile() || !resourceVanilla.isFile();
        String version = InstallerManager.readInstalledBdsVersion(context);
        String channel = installedChannel(context);
        File archive = findArchive(context, channel, version);

        if (archive == null || !archive.isFile()) {
            if (missingVanilla) {
                throw new IllegalStateException(
                        "Conteúdo padrão incompleto. Baixe novamente a versão ativa.");
            }
            return ZipUtils.pruneBundledPacksAboveVersion(bds, version) > 0;
        }

        ZipUtils.validateBdsZip(archive);
        int removed = ZipUtils.pruneBundledPacksForArchive(archive, bds);
        if (!missingVanilla && removed == 0) return false;
        if (progress != null) progress.onProgress(25, "Ajustando conteúdo padrão da versão ativa");
        ZipUtils.extractBds(archive, bds);
        if (!behaviorVanilla.isFile() || !resourceVanilla.isFile()) {
            throw new IllegalStateException("Pacote Vanilla não foi restaurado.");
        }
        if (progress != null) progress.onProgress(100, "Conteúdo padrão corrigido");
        return true;
    }

    /** Descarta ZIPs de versões não ativas após uma ativação concluída. */
    public static void cleanupInactiveArchives(Context context) throws Exception {
        if (BdsService.isRunning(context)) {
            throw new IllegalStateException("O servidor deve estar parado para limpar as versões.");
        }
        String activeVersion = InstallerManager.readInstalledBdsVersion(context);
        String activeChannel = installedChannel(context);
        if (activeVersion == null || "—".equals(activeVersion)) return;
        String activeArchiveName = activeChannel + "-" + activeVersion + ".zip";

        File[] managed = archiveDir(context).listFiles();
        if (managed != null) {
            for (File f : managed) {
                if (f.isFile() && f.getName().endsWith(".zip") &&
                        !f.getName().equals(activeArchiveName) && !f.delete()) {
                    throw new IllegalStateException("Falha ao limpar cache: " + f.getName());
                }
            }
        }
        File[] legacy = AppPaths.downloads(context).listFiles();
        if (legacy != null) {
            String current = "bedrock-server-" + activeVersion + ".zip";
            for (File f : legacy) {
                if (f.isFile() && f.getName().matches("bedrock-server-[0-9.]+\\.zip") &&
                        !f.getName().equals(current) && !f.delete()) {
                    throw new IllegalStateException("Falha ao limpar ZIP antigo: " + f.getName());
                }
            }
        }
    }

    public static void deleteDownloaded(Context context, VersionInfo info) throws Exception {
        if (info == null) return;
        String installed = InstallerManager.readInstalledBdsVersion(context);
        String installedChannel = installedChannel(context);
        if (info.version != null && info.version.equals(installed) && info.channel.equals(installedChannel)) {
            throw new IllegalStateException("A versão em uso não pode ser apagada.");
        }
        File f = resolveArchive(context, info);
        if (f != null && f.exists() && !f.delete()) {
            throw new IllegalStateException("Não foi possível apagar o arquivo da versão.");
        }
        info.downloaded = false;
        info.archive = null;
    }

    public static int compareVersions(String a, String b) {
        int[] aa = parts(a);
        int[] bb = parts(b);
        int n = Math.max(aa.length, bb.length);
        for (int i = 0; i < n; i++) {
            int av = i < aa.length ? aa[i] : 0;
            int bv = i < bb.length ? bb[i] : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static int[] parts(String version) {
        if (version == null || version.trim().isEmpty()) return new int[0];
        String[] split = version.trim().split("\\.");
        int[] out = new int[split.length];
        for (int i = 0; i < split.length; i++) {
            try { out[i] = Integer.parseInt(split[i]); } catch (Exception e) { out[i] = 0; }
        }
        return out;
    }

    private static VersionInfo makeInfo(Context context, String channel, String version, String url, boolean remote) {
        VersionInfo info = new VersionInfo();
        info.channel = CHANNEL_PREVIEW.equals(channel) ? CHANNEL_PREVIEW : CHANNEL_STABLE;
        info.version = version;
        info.url = url;
        info.remote = remote;
        File archive = findArchive(context, info.channel, version);
        info.downloaded = archive != null && archive.isFile();
        info.archive = archive;
        String installed = InstallerManager.readInstalledBdsVersion(context);
        info.installed = version != null && version.equals(installed)
                && info.channel.equals(installedChannel(context));
        return info;
    }

    private static List<VersionInfo> listDownloaded(Context context) {
        List<VersionInfo> out = new ArrayList<>();
        File dir = archiveDir(context);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.isFile() || !f.getName().endsWith(".zip")) continue;
                String n = f.getName();
                int dash = n.indexOf('-');
                if (dash <= 0) continue;
                String channel = n.substring(0, dash);
                String version = n.substring(dash + 1, n.length() - 4);
                if (!CHANNEL_STABLE.equals(channel) && !CHANNEL_PREVIEW.equals(channel)) continue;
                VersionInfo info = makeInfo(context, channel, version, null, false);
                info.downloaded = true;
                info.archive = f;
                out.add(info);
            }
        }

        String currentVersion = InstallerManager.readInstalledBdsVersion(context);
        if (currentVersion != null && !"—".equals(currentVersion)) {
            File legacy = new File(AppPaths.downloads(context), "bedrock-server-" + currentVersion + ".zip");
            if (legacy.isFile()) {
                String channel = installedChannel(context);
                boolean exists = false;
                for (VersionInfo i : out) if (i.version.equals(currentVersion) && i.channel.equals(channel)) exists = true;
                if (!exists) {
                    VersionInfo info = makeInfo(context, channel, currentVersion, null, false);
                    info.downloaded = true;
                    info.archive = legacy;
                    out.add(info);
                }
            }
        }
        return out;
    }

    private static File resolveArchive(Context context, VersionInfo info) {
        if (info == null) return null;
        if (info.archive != null && info.archive.isFile()) return info.archive;
        return findArchive(context, info.channel, info.version);
    }

    private static File findArchive(Context context, String channel, String version) {
        File modern = archiveFile(context, channel, version);
        if (modern.isFile()) return modern;
        File legacy = new File(AppPaths.downloads(context), "bedrock-server-" + version + ".zip");
        if (legacy.isFile() && version.equals(InstallerManager.readInstalledBdsVersion(context))) return legacy;
        return null;
    }

    private static File archiveDir(Context context) {
        File dir = new File(context.getFilesDir(), "bds_versions");
        dir.mkdirs();
        return dir;
    }

    private static File archiveFile(Context context, String channel, String version) {
        String safeChannel = CHANNEL_PREVIEW.equals(channel) ? CHANNEL_PREVIEW : CHANNEL_STABLE;
        String safeVersion = version == null ? "unknown" : version.replaceAll("[^0-9.]", "_");
        return new File(archiveDir(context), safeChannel + "-" + safeVersion + ".zip");
    }

    private static String versionFromUrl(String url) {
        Matcher m = VERSION_PATTERN.matcher(url == null ? "" : url);
        return m.find() ? m.group(1) : null;
    }

    private static String httpGet(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "BubbleBDSHost/0.7");
        c.setRequestProperty("Accept", "application/json");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new IllegalStateException("Consulta de versões retornou HTTP " + code + ".");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) if (n > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readBytes(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) if (n > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private static String readSmall(File f) {
        try { return f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim() : ""; }
        catch (Exception e) { return ""; }
    }

    private static void writeText(File f, String text) throws Exception {
        writeBytes(f, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(File f, byte[] data) throws Exception {
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        File parent = dst.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileInputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) if (n > 0) out.write(buf, 0, n);
        }
    }
}
