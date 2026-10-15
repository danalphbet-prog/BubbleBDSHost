package com.bubble.bdshost;

import android.content.Context;
import android.os.Build;
import android.os.StatFs;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class InstallerManager {
    public static final String CHANNEL_STABLE = "stable";
    public static final String CHANNEL_PREVIEW = "preview";

    public static final String DEBIAN_URL =
            "https://github.com/termux/proot-distro/releases/download/v4.29.0/" +
            "debian-trixie-aarch64-pd-v4.29.0.tar.xz";
    public static final String DEBIAN_SHA256 =
            "3834a11cbc6496935760bdc20cca7e2c25724d0cd8f5e4926da8fd5ca1857918";

    public static final String BOX64_TAG = "v0.4.4";
    public static final String BOX64_COMMIT =
            "2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a";

    public static final String BDS_STABLE_VERSION = "1.26.52.3";
    public static final String BDS_STABLE_URL =
            "https://www.minecraft.net/bedrockdedicatedserver/bin-linux/" +
            "bedrock-server-" + BDS_STABLE_VERSION + ".zip";

    public static final String BDS_PREVIEW_VERSION = "1.26.60.29";
    public static final String BDS_PREVIEW_FALLBACK_VERSION = "1.26.60.28";
    public static final String BDS_PREVIEW_BASE =
            "https://www.minecraft.net/bedrockdedicatedserver/bin-linux-preview/";

    private static final Pattern MAKE_PERCENT = Pattern.compile("\\[\\s*(\\d{1,3})%\\]");

    public interface Callback {
        void log(String text);
        default void progress(int percent, String stage) {}
        default void stageProgress(int percent, String stage) {}
    }

    private final Context context;
    private final Callback cb;
    private final String bdsChannel;

    public InstallerManager(Context context, Callback cb) {
        this(context, cb, CHANNEL_STABLE);
    }

    public InstallerManager(Context context, Callback cb, String channel) {
        this.context = context.getApplicationContext();
        this.cb = cb;
        this.bdsChannel = CHANNEL_PREVIEW.equals(channel) ? CHANNEL_PREVIEW : CHANNEL_STABLE;
        AppPaths.ensure(context);
    }

    private void log(String s) {
        if (cb != null) cb.log(s);
        try {
            AppPaths.ensure(context);
            try (FileOutputStream out = new FileOutputStream(AppPaths.installLog(context), true)) {
                out.write((s + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    private void progress(int percent, String stage) {
        if (cb != null) cb.progress(clamp(percent), stage);
    }

    private void stageProgress(int percent, String stage) {
        if (cb != null) cb.stageProgress(clamp(percent), stage);
    }

    private int clamp(int p) { return Math.max(0, Math.min(100, p)); }

    public void installAll() throws Exception {
        log("=== BUBBLE BDS HOST v0.4.0 ===");
        log("Fluxo: PRoot clássico → Debian Trixie ARM64 → Box64 local → BDS");
        log("Canal BDS: " + channelLabel(bdsChannel));

        stageProgress(0, "Verificando Android"); progress(1, "Verificando Android");
        checkArchitecture(); stageProgress(100, "Android ARM64 pronto");

        stageProgress(0, "Verificando armazenamento"); progress(5, "Verificando armazenamento");
        checkStorage(); stageProgress(100, "Armazenamento pronto");

        stageProgress(0, "Verificando PRoot"); progress(10, "Verificando PRoot");
        checkRuntime(); stageProgress(100, "PRoot pronto");

        stageProgress(0, "Preparando Debian Trixie"); progress(15, "Preparando Debian Trixie");
        installDebian();

        stageProgress(0, "Testando Debian"); progress(35, "Testando Debian");
        testDebian(); stageProgress(100, "Debian validado");

        stageProgress(0, "Reparando dpkg/apt"); progress(42, "Reparando dpkg/apt");
        repairDebianPackages();

        stageProgress(0, "Preparando Box64"); progress(50, "Preparando e compilando Box64");
        prepareDebianAndBuildBox64();

        stageProgress(0, "Preparando BDS"); progress(78, "Instalando BDS");
        installBds();

        stageProgress(0, "Validando cadeia"); progress(94, "Validando cadeia completa");
        smokeTest(); stageProgress(100, "Validação concluída");

        progress(100, "Ambiente pronto"); stageProgress(100, "Ambiente pronto");
        log("✓ Ambiente completo preparado");
    }

    public void checkArchitecture() {
        boolean arm64 = false;
        for (String abi : Build.SUPPORTED_ABIS) if ("arm64-v8a".equalsIgnoreCase(abi)) arm64 = true;
        if (!arm64) throw new IllegalStateException("Esta versão requer Android ARM64 (arm64-v8a)");
        log("[1/8] ✓ Android ARM64 detectado");
    }

    public void checkStorage() {
        StatFs sf = new StatFs(context.getFilesDir().getAbsolutePath());
        long free = sf.getAvailableBytes();
        long min = 1300L * 1024L * 1024L;
        if (free < min) throw new IllegalStateException(String.format(Locale.US,
                "Espaço livre insuficiente: %.1f GB. Libere pelo menos 1.3 GB.",
                free / 1073741824.0));
        log(String.format(Locale.US, "[2/8] ✓ Espaço livre: %.1f GB", free / 1073741824.0));
    }

    public void checkRuntime() {
        if (!RuntimeEngine.runtimePresent(context)) throw new IllegalStateException(
                "PRoot clássico/loader ausente no APK. O GitHub Actions precisa preparar o runtime Termux.");
        log("[3/8] ✓ PRoot clássico presente no APK");
    }

    public void installDebian() throws Exception {
        File marker = AppPaths.debianMarker(context);
        File bash = new File(AppPaths.rootfs(context), "bin/bash");
        if (marker.isFile() && bash.isFile()) {
            log("[4/8] ✓ Debian Trixie já instalado");
            progress(35, "Debian Trixie já instalado");
            stageProgress(100, "Debian Trixie já instalado");
            return;
        }

        File legacy = AppPaths.legacyUbuntu(context);
        if (legacy.exists()) deleteTree(legacy);

        File archive = new File(AppPaths.downloads(context),
                "debian-trixie-aarch64-pd-v4.29.0.tar.xz");
        if (!archive.isFile() || !DEBIAN_SHA256.equalsIgnoreCase(HashUtils.sha256(archive))) {
            stageProgress(0, "Baixando Debian Trixie");
            DownloadUtils.download(DEBIAN_URL, archive, (done, total) -> {
                if (total > 0) {
                    int p = (int)Math.round(done * 100.0 / total);
                    stageProgress((int)Math.round(p * .72), "Baixando Debian Trixie");
                    progress(15 + (int)Math.round(p * .15), "Baixando Debian Trixie");
                }
            });
        }

        String actual = HashUtils.sha256(archive);
        if (!DEBIAN_SHA256.equalsIgnoreCase(actual))
            throw new IllegalStateException("SHA-256 do Debian inválido: " + actual);

        File root = AppPaths.rootfs(context);
        if (root.exists()) deleteTree(root);
        root.mkdirs();

        stageProgress(76, "Extraindo Debian Trixie"); progress(31, "Extraindo Debian Trixie");
        TarRootfsExtractor.extractTarXz(archive, root, 1, this::log);
        if (!new File(root, "bin/bash").exists()) throw new IllegalStateException("Debian extraído sem /bin/bash");

        File resolv = new File(root, "etc/resolv.conf");
        try { Files.deleteIfExists(resolv.toPath()); } catch (Exception ignored) {}
        writeText(resolv, "nameserver 1.1.1.1\nnameserver 8.8.8.8\n");
        writeText(marker, "debian-trixie proot-distro-v4.29.0\n");
        try { Files.deleteIfExists(archive.toPath()); } catch (Exception ignored) {}
        stageProgress(100, "Debian Trixie instalado");
        log("[4/8] ✓ Debian Trixie ARM64 instalado");
    }

    public void testDebian() throws Exception {
        int rc = runGuest("/root", "printf 'BUBBLE_PROOT_OK\\n'; uname -m; grep -E '^(ID|VERSION_CODENAME)=' /etc/os-release");
        if (rc != 0) throw new IllegalStateException("PRoot clássico não conseguiu executar Debian. Código " + rc);
        log("[5/8] ✓ Debian executando através do PRoot clássico");
    }

    private void repairDebianPackages() throws Exception {
        stageProgress(15, "dpkg --configure -a");
        int configureRc = runGuest("/root", "export DEBIAN_FRONTEND=noninteractive; dpkg --configure -a");
        if (configureRc != 0) {
            stageProgress(35, "Atualizando índice apt");
            int updateRc = runGuest("/root", "export DEBIAN_FRONTEND=noninteractive; apt-get update");
            if (updateRc != 0) log("Aviso: apt-get update retornou " + updateRc + ".");
            stageProgress(55, "Reparando dependências");
            int fixRc = runGuest("/root", "export DEBIAN_FRONTEND=noninteractive; apt-get -f install -y");
            if (fixRc != 0) throw new IllegalStateException("Reparo apt-get -f install falhou. Código " + fixRc);
            stageProgress(80, "Finalizando dpkg");
            configureRc = runGuest("/root", "export DEBIAN_FRONTEND=noninteractive; dpkg --configure -a");
            if (configureRc != 0) throw new IllegalStateException("dpkg continuou inconsistente. Código " + configureRc);
        }
        stageProgress(100, "dpkg/apt consistente");
        log("✓ dpkg/apt consistente; instalação pode ser retomada");
    }

    public void prepareDebianAndBuildBox64() throws Exception {
        File box = AppPaths.box64(context);
        File marker = AppPaths.box64Marker(context);
        if (box.isFile() && marker.isFile()) {
            stageProgress(75, "Verificando Box64 existente");
            int rc = runGuest("/root", "/usr/local/bin/box64 --version || /usr/local/bin/box64 -v");
            if (rc == 0) {
                stageProgress(100, "Box64 já preparado"); progress(78, "Box64 já preparado");
                log("[6/8] ✓ Box64 local já compilado e funcional");
                return;
            }
            try { Files.deleteIfExists(marker.toPath()); } catch (Exception ignored) {}
        }

        stageProgress(5, "Atualizando pacotes Debian");
        int updateRc = runGuest("/root", "export DEBIAN_FRONTEND=noninteractive; apt-get update");
        if (updateRc != 0) throw new IllegalStateException("Falha no apt-get update. Código " + updateRc);

        stageProgress(15, "Instalando toolchain do Box64");
        String deps = "export DEBIAN_FRONTEND=noninteractive; " +
                "apt-get install -y --no-install-recommends ca-certificates curl git build-essential cmake python3 unzip libstdc++6 libgcc-s1 && " +
                "(apt-get install -y --no-install-recommends libcurl4t64 || apt-get install -y --no-install-recommends libcurl4 || true) && " +
                "(apt-get install -y --no-install-recommends libssl3t64 || apt-get install -y --no-install-recommends libssl3 || true)";
        int depsRc = runGuest("/root", deps);
        if (depsRc != 0) { repairDebianPackages(); depsRc = runGuest("/root", deps); }
        if (depsRc != 0) throw new IllegalStateException("Falha ao instalar toolchain após reparo. Código " + depsRc);

        File box64Source = new File(AppPaths.rootfs(context), "root/box64-src");
        File box64Build = new File(box64Source, "build");
        boolean buildPresent = box64Build.isDirectory();
        stageProgress(25, buildPresent ? "Retomando source do Box64" : "Obtendo source do Box64");

        String prepareSource = "set -e; " +
                "if [ -d /root/box64-src/.git ] && [ -f /root/box64-src/CMakeLists.txt ]; then cd /root/box64-src; HEAD=$(git rev-parse HEAD 2>/dev/null || true); " +
                "if [ \"$HEAD\" != \"" + BOX64_COMMIT + "\" ]; then cd /root; rm -rf /root/box64-src; fi; fi; " +
                "if [ ! -d /root/box64-src/.git ]; then git clone --depth 1 --branch " + BOX64_TAG + " https://github.com/ptitSeb/box64.git /root/box64-src; fi; " +
                "cd /root/box64-src; test \"$(git rev-parse HEAD)\" = \"" + BOX64_COMMIT + "\"; test -f CMakeLists.txt";
        int sourceRc = runGuest("/root", prepareSource);
        if (sourceRc != 0) throw new IllegalStateException("Não foi possível preparar o source do Box64. Código " + sourceRc);

        stageProgress(32, buildPresent ? "Retomando compilação do Box64" : "Compilando Box64");
        progress(66, buildPresent ? "Retomando Box64" : "Compilando Box64");
        String build = "set -e; cd /root/box64-src; mkdir -p build; cd build; " +
                "cmake .. -DARM64=1 -DARM_DYNAREC=ON -DBAD_SIGNAL=ON -DCMAKE_BUILD_TYPE=RelWithDebInfo; " +
                "make -j2; make install; ldconfig; /usr/local/bin/box64 --version || /usr/local/bin/box64 -v";
        int buildRc = runGuestWithStage("/root", build, "Compilando Box64", 32, 92);
        if (buildRc != 0) {
            log("Build parcial preservado em /root/box64-src/build.");
            throw new IllegalStateException("Box64 não concluiu. Código " + buildRc + ". Toque em Instalar Tudo para retomar.");
        }

        stageProgress(95, "Verificando Box64");
        int verifyRc = runGuest("/root", "test -x /usr/local/bin/box64 && (/usr/local/bin/box64 --version || /usr/local/bin/box64 -v)");
        if (verifyRc != 0) throw new IllegalStateException("Box64 local não passou na verificação. Código " + verifyRc);

        writeText(marker, "box64 " + BOX64_TAG + " commit " + BOX64_COMMIT + "\n");
        runGuest("/root", "apt-get clean || true");
        stageProgress(100, "Box64 pronto");
        log("[6/8] ✓ Box64 compilado localmente");
    }

    public void installBds() throws Exception {
        String requestedVersion = requestedBdsVersion();
        File server = new File(AppPaths.bds(context), "bedrock_server");
        File marker = bdsMarker(context);
        String installed = readSmall(marker);
        String wanted = bdsChannel + "|" + requestedVersion;

        if (server.exists() && wanted.equals(installed)) {
            log("[7/8] ✓ BDS " + requestedVersion + " já instalado");
            stageProgress(100, "BDS já instalado"); progress(94, "BDS já instalado"); return;
        }
        if (server.exists() && installed.isBlank() && CHANNEL_STABLE.equals(bdsChannel)) {
            writeText(marker, wanted + "\n"); stageProgress(100, "BDS estável já instalado"); progress(94, "BDS já instalado"); return;
        }

        String resolvedVersion = requestedVersion;
        String url = requestedBdsUrl(resolvedVersion);
        File zip = new File(AppPaths.downloads(context), "bedrock-server-" + resolvedVersion + ".zip");
        stageProgress(0, "Baixando BDS " + resolvedVersion);
        try {
            downloadBds(url, zip, resolvedVersion);
        } catch (Exception first) {
            if (!CHANNEL_PREVIEW.equals(bdsChannel)) throw first;
            resolvedVersion = BDS_PREVIEW_FALLBACK_VERSION;
            url = requestedBdsUrl(resolvedVersion);
            zip = new File(AppPaths.downloads(context), "bedrock-server-" + resolvedVersion + ".zip");
            log("Preview " + requestedVersion + " indisponível; tentando " + resolvedVersion + "...");
            stageProgress(0, "Tentando Preview " + resolvedVersion);
            downloadBds(url, zip, resolvedVersion);
        }

        ZipUtils.validateBdsZip(zip);
        stageProgress(88, "Extraindo BDS " + resolvedVersion); progress(91, "Extraindo BDS");
        ZipUtils.extractBds(zip, AppPaths.bds(context));
        patchServerProperties(new File(AppPaths.bds(context), "server.properties"));
        writeText(marker, bdsChannel + "|" + resolvedVersion + "\n");
        stageProgress(100, "BDS " + resolvedVersion + " instalado");
        log("[7/8] ✓ BDS " + resolvedVersion + " instalado");
    }

    private void downloadBds(String url, File zip, String version) throws Exception {
        DownloadUtils.download(url, zip, (done, total) -> {
            if (total > 0) {
                int p = (int)Math.round(done * 100.0 / total);
                stageProgress((int)Math.round(p * .85), "Baixando BDS " + version);
                progress(78 + (int)Math.round(p * .12), "Baixando BDS " + version);
            }
        });
    }

    private String requestedBdsVersion() { return CHANNEL_PREVIEW.equals(bdsChannel) ? BDS_PREVIEW_VERSION : BDS_STABLE_VERSION; }
    private String requestedBdsUrl(String version) { return CHANNEL_PREVIEW.equals(bdsChannel) ? BDS_PREVIEW_BASE + "bedrock-server-" + version + ".zip" : BDS_STABLE_URL; }

    public void smokeTest() throws Exception {
        File server = new File(AppPaths.bds(context), "bedrock_server");
        if (!server.exists()) throw new IllegalStateException("BDS não instalado");
        int rc = runGuest("/root/bds", "set -e; test -x ./bedrock_server; test -x /usr/local/bin/box64; printf 'BUBBLE_BDS_CHAIN_OK\\n'; /usr/local/bin/box64 --version || /usr/local/bin/box64 -v");
        if (rc != 0) throw new IllegalStateException("Smoke test PRoot → Debian → Box64 → BDS falhou. Código " + rc);
        log("[8/8] ✓ Cadeia validada");
    }

    private int runGuest(String workdir, String command) throws Exception { return runGuestWithStage(workdir, command, null, -1, -1); }

    private int runGuestWithStage(String workdir, String command, String stage, int min, int max) throws Exception {
        Process p = RuntimeEngine.start(context, workdir, command);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                log("[debian] " + line);
                if (stage != null) {
                    Matcher m = MAKE_PERCENT.matcher(line);
                    if (m.find()) {
                        int raw = clamp(Integer.parseInt(m.group(1)));
                        int mapped = min + (int)Math.round((max - min) * (raw / 100.0));
                        stageProgress(mapped, stage + " • " + raw + "%");
                    }
                }
            }
        }
        return p.waitFor();
    }

    private void patchServerProperties(File f) throws Exception {
        if (!f.exists()) return;
        String text = readText(f);
        text = replaceProperty(text, "server-name", "Bubble BDS Host");
        text = replaceProperty(text, "server-port", "19132");
        text = replaceProperty(text, "server-portv6", "19133");
        text = replaceProperty(text, "max-players", "10");
        text = replaceProperty(text, "online-mode", "true");
        writeText(f, text);
    }

    public static File bdsMarker(Context context) { return new File(AppPaths.bds(context), ".bubble-bds-version"); }
    public static String readInstalledBdsVersion(Context context) {
        String marker = readSmall(bdsMarker(context));
        if (marker.contains("|")) return marker.substring(marker.indexOf('|') + 1).trim();
        return new File(AppPaths.bds(context), "bedrock_server").exists() ? BDS_STABLE_VERSION : "—";
    }
    public static String readInstalledBdsChannel(Context context) {
        String marker = readSmall(bdsMarker(context));
        if (marker.startsWith(CHANNEL_PREVIEW + "|")) return "Preview / Beta";
        if (new File(AppPaths.bds(context), "bedrock_server").exists()) return "Atual / Alpha";
        return "—";
    }
    public static String channelLabel(String channel) { return CHANNEL_PREVIEW.equals(channel) ? "Preview / Beta" : "Atual / Alpha"; }

    private static String readSmall(File f) {
        try { return f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim() : ""; }
        catch (Exception e) { return ""; }
    }

    private static String readText(File f) throws Exception {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) >= 0) if (n > 0) bos.write(b, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private String replaceProperty(String text, String key, String value) {
        boolean found = false; StringBuilder out = new StringBuilder();
        for (String line : text.split("\\R", -1)) {
            if (line.startsWith(key + "=")) { out.append(key).append('=').append(value).append('\n'); found = true; }
            else out.append(line).append('\n');
        }
        if (!found) out.append(key).append('=').append(value).append('\n');
        return out.toString();
    }

    private static void writeText(File f, String text) throws Exception {
        File parent = f.getParentFile(); if (parent != null) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
    }

    private static void deleteTree(File f) throws Exception {
        if (!f.exists() && !Files.isSymbolicLink(f.toPath())) return;
        if (Files.isSymbolicLink(f.toPath()) || f.isFile()) { Files.deleteIfExists(f.toPath()); return; }
        File[] children = f.listFiles(); if (children != null) for (File c : children) deleteTree(c);
        Files.deleteIfExists(f.toPath());
    }
}
