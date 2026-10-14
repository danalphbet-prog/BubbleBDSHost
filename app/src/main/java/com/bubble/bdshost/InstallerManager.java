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

public final class InstallerManager {
    public static final String DEBIAN_URL =
            "https://github.com/termux/proot-distro/releases/download/v4.29.0/" +
            "debian-trixie-aarch64-pd-v4.29.0.tar.xz";
    public static final String DEBIAN_SHA256 =
            "3834a11cbc6496935760bdc20cca7e2c25724d0cd8f5e4926da8fd5ca1857918";

    public static final String BOX64_TAG = "v0.4.4";
    public static final String BOX64_COMMIT =
            "2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a";

    public static final String BDS_URL =
            "https://www.minecraft.net/bedrockdedicatedserver/bin-linux/" +
            "bedrock-server-1.26.52.3.zip";
    public static final String BDS_VERSION = "1.26.52.3";

    public interface Callback { void log(String text); }

    private final Context context;
    private final Callback cb;

    public InstallerManager(Context context, Callback cb) {
        this.context = context.getApplicationContext();
        this.cb = cb;
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

    public void installAll() throws Exception {
        log("=== BUBBLE BDS HOST v0.2.0 ===");
        log("Fluxo: PRoot clássico → Debian Trixie ARM64 → Box64 local → BDS");
        checkArchitecture();
        checkStorage();
        checkRuntime();
        installDebian();
        testDebian();
        prepareDebianAndBuildBox64();
        installBds();
        smokeTest();
        log("✓ Ambiente completo preparado");
    }

    public void checkArchitecture() {
        boolean arm64 = false;
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equalsIgnoreCase(abi)) arm64 = true;
        }
        if (!arm64) {
            throw new IllegalStateException("Esta versão requer Android ARM64 (arm64-v8a)");
        }
        log("[1/8] ✓ Android ARM64 detectado");
    }

    public void checkStorage() {
        StatFs sf = new StatFs(context.getFilesDir().getAbsolutePath());
        long free = sf.getAvailableBytes();
        long min = 1300L * 1024L * 1024L;
        if (free < min) {
            throw new IllegalStateException(String.format(Locale.US,
                    "Espaço livre insuficiente: %.1f GB. Libere pelo menos 1.3 GB.",
                    free / 1073741824.0));
        }
        log(String.format(Locale.US, "[2/8] ✓ Espaço livre: %.1f GB", free / 1073741824.0));
    }

    public void checkRuntime() {
        if (!RuntimeEngine.runtimePresent(context)) {
            throw new IllegalStateException(
                    "PRoot clássico/loader ausente no APK. O GitHub Actions precisa preparar o runtime Termux.");
        }
        log("[3/8] ✓ PRoot clássico presente no APK");
    }

    public void installDebian() throws Exception {
        File marker = AppPaths.debianMarker(context);
        File bash = new File(AppPaths.rootfs(context), "bin/bash");
        if (marker.isFile() && bash.isFile()) {
            log("[4/8] ✓ Debian Trixie já instalado");
            return;
        }

        File legacy = AppPaths.legacyUbuntu(context);
        if (legacy.exists()) {
            log("Removendo Ubuntu legado da v0.1.x para liberar espaço...");
            deleteTree(legacy);
        }
        try {
            Files.deleteIfExists(new File(AppPaths.downloads(context),
                    "ubuntu-base-24.04.5-arm64.tar.gz").toPath());
        } catch (Exception ignored) {}

        File archive = new File(AppPaths.downloads(context),
                "debian-trixie-aarch64-pd-v4.29.0.tar.xz");
        if (!archive.isFile() || !DEBIAN_SHA256.equalsIgnoreCase(HashUtils.sha256(archive))) {
            log("Baixando Debian Trixie ARM64 do proot-distro...");
            DownloadUtils.download(DEBIAN_URL, archive, (done, total) -> {
                if (total > 0) {
                    log(String.format(Locale.US, "Debian: %.0f%%", done * 100.0 / total));
                }
            });
        }

        String actual = HashUtils.sha256(archive);
        if (!DEBIAN_SHA256.equalsIgnoreCase(actual)) {
            throw new IllegalStateException("SHA-256 do Debian inválido: " + actual);
        }
        log("✓ SHA-256 do Debian confirmado");

        File root = AppPaths.rootfs(context);
        if (root.exists()) deleteTree(root);
        root.mkdirs();

        // Os rootfs v4.29.0 do proot-distro são empacotados sob um diretório superior.
        TarRootfsExtractor.extractTarXz(archive, root, 1, this::log);
        if (!new File(root, "bin/bash").exists()) {
            throw new IllegalStateException("Debian extraído sem /bin/bash");
        }

        File resolv = new File(root, "etc/resolv.conf");
        try { Files.deleteIfExists(resolv.toPath()); } catch (Exception ignored) {}
        writeText(resolv, "nameserver 1.1.1.1\nnameserver 8.8.8.8\n");
        writeText(marker, "debian-trixie proot-distro-v4.29.0\n");

        try { Files.deleteIfExists(archive.toPath()); } catch (Exception ignored) {}
        log("[4/8] ✓ Debian Trixie ARM64 instalado");
    }

    public void testDebian() throws Exception {
        log("[5/8] Testando PRoot clássico + Debian...");
        int rc = runGuest("/root",
                "printf 'BUBBLE_PROOT_OK\\n'; uname -m; " +
                "grep -E '^(ID|VERSION_CODENAME)=' /etc/os-release");
        if (rc != 0) {
            throw new IllegalStateException("PRoot clássico não conseguiu executar Debian. Código " + rc);
        }
        log("[5/8] ✓ Debian executando através do PRoot clássico");
    }

    public void prepareDebianAndBuildBox64() throws Exception {
        File box = AppPaths.box64(context);
        File marker = AppPaths.box64Marker(context);

        if (box.isFile() && marker.isFile()) {
            int rc = runGuest("/root",
                    "/usr/local/bin/box64 --version || /usr/local/bin/box64 -v");
            if (rc == 0) {
                log("[6/8] ✓ Box64 local já compilado e funcional");
                return;
            }
            log("Box64 existente falhou; recompilando dentro do Debian...");
            try { Files.deleteIfExists(marker.toPath()); } catch (Exception ignored) {}
        }

        log("[6/8] Instalando toolchain Debian para compilar Box64 no aparelho...");
        String deps =
                "export DEBIAN_FRONTEND=noninteractive; " +
                "apt-get update && " +
                "apt-get install -y --no-install-recommends " +
                "ca-certificates curl git build-essential cmake python3 unzip libstdc++6 libgcc-s1 && " +
                "(apt-get install -y --no-install-recommends libcurl4t64 || " +
                " apt-get install -y --no-install-recommends libcurl4 || true) && " +
                "(apt-get install -y --no-install-recommends libssl3t64 || " +
                " apt-get install -y --no-install-recommends libssl3 || true)";
        int depsRc = runGuest("/root", deps);
        if (depsRc != 0) {
            throw new IllegalStateException("Falha ao instalar toolchain/dependências. Código " + depsRc);
        }

        log("Clonando Box64 oficial " + BOX64_TAG + " e compilando localmente...");
        String build =
                "set -e; " +
                "rm -rf /root/box64-src; " +
                "git clone --depth 1 --branch " + BOX64_TAG + " " +
                "https://github.com/ptitSeb/box64.git /root/box64-src; " +
                "cd /root/box64-src; " +
                "test \"$(git rev-parse HEAD)\" = \"" + BOX64_COMMIT + "\"; " +
                "mkdir -p build; cd build; " +
                "cmake .. -DARM64=1 -DARM_DYNAREC=ON -DBAD_SIGNAL=ON " +
                "-DCMAKE_BUILD_TYPE=RelWithDebInfo; " +
                "make -j2; make install; ldconfig; " +
                "/usr/local/bin/box64 --version || /usr/local/bin/box64 -v";
        int buildRc = runGuest("/root", build);
        if (buildRc != 0) {
            throw new IllegalStateException("Box64 não compilou/executou dentro do Debian. Código " + buildRc);
        }

        int verifyRc = runGuest("/root",
                "test -x /usr/local/bin/box64 && " +
                "(/usr/local/bin/box64 --version || /usr/local/bin/box64 -v)");
        if (verifyRc != 0) {
            throw new IllegalStateException("Box64 local não passou na verificação. Código " + verifyRc);
        }

        writeText(marker, "box64 " + BOX64_TAG + " commit " + BOX64_COMMIT + "\n");
        runGuest("/root", "rm -rf /root/box64-src; apt-get clean || true");
        log("[6/8] ✓ Box64 compilado LOCALMENTE dentro do Debian");
    }

    public void installBds() throws Exception {
        File server = new File(AppPaths.bds(context), "bedrock_server");
        if (server.exists()) {
            log("[7/8] ✓ BDS já instalado");
            return;
        }

        File zip = new File(AppPaths.downloads(context),
                "bedrock-server-" + BDS_VERSION + ".zip");
        log("[7/8] Baixando BDS oficial " + BDS_VERSION + "...");
        DownloadUtils.download(BDS_URL, zip, (done, total) -> {
            if (total > 0) {
                log(String.format(Locale.US, "BDS: %.0f%%", done * 100.0 / total));
            }
        });
        ZipUtils.validateBdsZip(zip);
        log("✓ Estrutura do ZIP do BDS validada");
        ZipUtils.extractBds(zip, AppPaths.bds(context));
        patchServerProperties(new File(AppPaths.bds(context), "server.properties"));
        log("[7/8] ✓ BDS " + BDS_VERSION + " instalado");
    }

    public void smokeTest() throws Exception {
        File server = new File(AppPaths.bds(context), "bedrock_server");
        if (!server.exists()) throw new IllegalStateException("BDS não instalado");

        int rc = runGuest("/root/bds",
                "set -e; test -x ./bedrock_server; test -x /usr/local/bin/box64; " +
                "printf 'BUBBLE_BDS_CHAIN_OK\\n'; " +
                "/usr/local/bin/box64 --version || /usr/local/bin/box64 -v");
        if (rc != 0) {
            throw new IllegalStateException("Smoke test PRoot → Debian → Box64 → BDS falhou. Código " + rc);
        }
        log("[8/8] ✓ Cadeia PRoot → Debian → Box64 → BDS validada");
    }

    private int runGuest(String workdir, String command) throws Exception {
        Process p = RuntimeEngine.start(context, workdir, command);
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) log("[debian] " + line);
        }
        return p.waitFor();
    }

    private void patchServerProperties(File f) throws Exception {
        if (!f.exists()) return;
        String text;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) >= 0) if (n > 0) bos.write(b, 0, n);
            text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }

        text = replaceProperty(text, "server-name", "Bubble BDS Host");
        text = replaceProperty(text, "server-port", "19132");
        text = replaceProperty(text, "server-portv6", "19133");
        text = replaceProperty(text, "max-players", "10");
        text = replaceProperty(text, "online-mode", "true");

        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String replaceProperty(String text, String key, String value) {
        String[] lines = text.split("\\R", -1);
        boolean found = false;
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith(key + "=")) {
                out.append(key).append('=').append(value).append('\n');
                found = true;
            } else {
                out.append(line).append('\n');
            }
        }
        if (!found) out.append(key).append('=').append(value).append('\n');
        return out.toString();
    }

    private static void writeText(File f, String text) throws Exception {
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void deleteTree(File f) throws Exception {
        if (!f.exists() && !Files.isSymbolicLink(f.toPath())) return;
        if (Files.isSymbolicLink(f.toPath()) || f.isFile()) {
            Files.deleteIfExists(f.toPath());
            return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteTree(c);
        }
        Files.deleteIfExists(f.toPath());
    }
}
