package com.bubble.bdshost;

import android.content.Context;
import android.os.Build;
import android.os.StatFs;
import android.system.Os;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.io.ByteArrayOutputStream;
import java.util.Locale;

public final class InstallerManager {
    public static final String UBUNTU_URL = "https://cdimages.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz";
    public static final String UBUNTU_SHA256 = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2";
    public static final String BDS_URL = "https://www.minecraft.net/bedrockdedicatedserver/bin-linux/bedrock-server-1.26.52.3.zip";
    public static final String BDS_VERSION = "1.26.52.3";

    public interface Callback { void log(String text); }

    private final Context context;
    private final Callback cb;

    public InstallerManager(Context context, Callback cb) {
        this.context = context.getApplicationContext();
        this.cb = cb;
        AppPaths.ensure(context);
    }

    private void log(String s) { if (cb != null) cb.log(s); }

    public void installAll() throws Exception {
        checkArchitecture();
        checkStorage();
        checkRuntime();
        installLinux();
        RuntimeEngine.Mode mode = diagnoseLinuxRuntime();
        prepareLinuxDependencies(mode);
        installBds();
        smokeTest(mode);
    }

    public void checkArchitecture() {
        boolean arm64 = false;
        for (String abi : Build.SUPPORTED_ABIS) if ("arm64-v8a".equalsIgnoreCase(abi)) arm64 = true;
        if (!arm64) throw new IllegalStateException("Esta versão requer Android ARM64 (arm64-v8a)");
        log("✓ Arquitetura ARM64 detectada");
    }

    public void checkStorage() {
        StatFs sf = new StatFs(context.getFilesDir().getAbsolutePath());
        long free = sf.getAvailableBytes();
        long min = 1024L * 1024L * 1024L;
        if (free < min) throw new IllegalStateException("Espaço livre insuficiente. Libere pelo menos 1 GB antes de instalar.");
        log(String.format(Locale.US, "✓ Espaço livre: %.1f GB", free / 1073741824.0));
    }

    public void checkRuntime() {
        if (!RuntimeEngine.runtimePresent(context)) {
            throw new IllegalStateException("Runtime proroot ausente. Compile pelo GitHub Actions incluído no projeto.");
        }
        log("✓ Runtime Linux presente no APK");
    }

    public void installLinux() throws Exception {
        File sh = new File(AppPaths.rootfs(context), "bin/sh");
        if (sh.exists() && AppPaths.box64(context).exists()) {
            log("✓ Ubuntu e Box64 já instalados");
            return;
        }

        File tgz = new File(AppPaths.downloads(context), "ubuntu-base-24.04.5-arm64.tar.gz");
        if (!tgz.exists() || !UBUNTU_SHA256.equalsIgnoreCase(HashUtils.sha256(tgz))) {
            log("Baixando Ubuntu Base 24.04.5 ARM64...");
            DownloadUtils.download(UBUNTU_URL, tgz, (done, total) -> {
                if (total > 0) log(String.format(Locale.US, "Ubuntu: %.0f%%", (done * 100.0) / total));
            });
        }

        String hash = HashUtils.sha256(tgz);
        if (!UBUNTU_SHA256.equalsIgnoreCase(hash)) throw new IllegalStateException("SHA-256 do Ubuntu inválido: " + hash);
        log("✓ SHA-256 do Ubuntu confirmado");

        File root = AppPaths.rootfs(context);
        if (root.exists()) deleteTree(root);
        root.mkdirs();
        TarRootfsExtractor.extractTarGz(tgz, root, this::log);
        if (!sh.exists()) throw new IllegalStateException("Rootfs extraído sem /bin/sh");

        File resolv = new File(root, "etc/resolv.conf");
        try { Files.deleteIfExists(resolv.toPath()); } catch (Exception ignored) {}
        writeText(resolv, "nameserver 1.1.1.1\nnameserver 8.8.8.8\n");

        installBox64Asset();
        log("✓ Ubuntu Base instalado");
    }

    private void installBox64Asset() throws Exception {
        File out = AppPaths.box64(context);
        out.getParentFile().mkdirs();

        try (InputStream in = context.getAssets().open("box64-aarch64")) {
            Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new IllegalStateException("Asset Box64 ausente. O GitHub Actions precisa gerar o APK completo.", e);
        }

        try { Os.chmod(out.getAbsolutePath(), 0755); } catch (Exception ignored) {}

        String expected;
        try (InputStream in = context.getAssets().open("box64.sha256")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) >= 0) if (n > 0) bos.write(b, 0, n);
            expected = new String(bos.toByteArray(), StandardCharsets.UTF_8).trim().split("\\s+")[0];
        }

        String actual = HashUtils.sha256(out);
        if (!expected.equalsIgnoreCase(actual)) throw new IllegalStateException("Box64 corrompido no APK");

        File x64libs = new File(AppPaths.rootfs(context), "usr/lib/box64-x86_64-linux-gnu");
        copyAssetTree("box64-x64lib", x64libs);
        log("✓ Box64 + bibliotecas x86_64 instalados e verificados");
    }

    private void copyAssetTree(String assetPath, File dest) throws Exception {
        String[] children = context.getAssets().list(assetPath);
        if (children == null) throw new IllegalStateException("Asset ausente: " + assetPath);

        if (children.length == 0) {
            File parent = dest.getParentFile();
            if (parent != null) parent.mkdirs();
            try (InputStream in = context.getAssets().open(assetPath);
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[128 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) if (n > 0) out.write(buf, 0, n);
            }
            try { Os.chmod(dest.getAbsolutePath(), 0644); } catch (Exception ignored) {}
            return;
        }

        dest.mkdirs();
        try { Os.chmod(dest.getAbsolutePath(), 0755); } catch (Exception ignored) {}
        for (String child : children) copyAssetTree(assetPath + "/" + child, new File(dest, child));
    }

    public RuntimeEngine.Mode diagnoseLinuxRuntime() throws Exception {
        try { Files.deleteIfExists(AppPaths.prorootLog(context).toPath()); } catch (Exception ignored) {}

        RuntimeEngine.Mode[] modes = {
                RuntimeEngine.Mode.DEFAULT,
                RuntimeEngine.Mode.NO_STATIC_LOADER,
                RuntimeEngine.Mode.NO_STATIC_NO_LINK
        };

        log("Testando runtime Linux antes do Box64...");

        for (RuntimeEngine.Mode mode : modes) {
            log("→ Tentativa proroot: " + mode.id);
            int rc = runGuest("/root", "printf 'BUBBLE_LINUX_OK\\n'", mode);

            if (rc == 0) {
                RuntimeEngine.saveMode(context, mode);
                log("✓ Linux iniciou com modo: " + mode.id);

                int unameRc = runGuest("/root", "uname -m", mode);
                if (unameRc != 0) throw new IllegalStateException("Linux iniciou, mas uname falhou. Código " + unameRc);

                int boxRc = runGuest("/root", "box64 --version || box64 -v", mode);
                if (boxRc != 0) throw new IllegalStateException("Linux funciona, mas Box64 falhou. Código " + boxRc);

                log("✓ Linux ARM64 e Box64 validados separadamente");
                return mode;
            }

            log("⚠ proroot modo " + mode.id + " retornou código " + rc);
        }

        throw new IllegalStateException(
                "O proroot falhou nos 3 modos antes do Box64. Consulte: " +
                AppPaths.prorootLog(context).getAbsolutePath()
        );
    }

    public void prepareLinuxDependencies() throws Exception {
        prepareLinuxDependencies(RuntimeEngine.savedMode(context));
    }

    public void prepareLinuxDependencies(RuntimeEngine.Mode mode) throws Exception {
        File marker = new File(AppPaths.rootfs(context), ".bubble-bds-deps-ready");
        if (marker.exists()) {
            log("✓ Dependências Linux já preparadas");
            return;
        }

        log("Atualizando índices APT e instalando bibliotecas do BDS...");
        String cmd =
                "export DEBIAN_FRONTEND=noninteractive; " +
                "apt-get update && " +
                "apt-get install -y --no-install-recommends ca-certificates curl unzip libcurl4t64 libssl3t64 libstdc++6 libgcc-s1 && " +
                "apt-get clean";

        int rc = runGuest("/root", cmd, mode);
        if (rc != 0) throw new IllegalStateException("APT/dependências falharam. Código " + rc);

        writeText(marker, "ok\n");
        log("✓ Dependências Linux prontas");
    }

    public void installBds() throws Exception {
        File server = new File(AppPaths.bds(context), "bedrock_server");
        if (server.exists()) {
            log("✓ BDS já instalado");
            return;
        }

        File zip = new File(AppPaths.downloads(context), "bedrock-server-" + BDS_VERSION + ".zip");
        log("Baixando BDS oficial " + BDS_VERSION + "...");

        DownloadUtils.download(BDS_URL, zip, (done, total) -> {
            if (total > 0) log(String.format(Locale.US, "BDS: %.0f%%", (done * 100.0) / total));
        });

        ZipUtils.validateBdsZip(zip);
        log("✓ Estrutura do ZIP do BDS validada");
        ZipUtils.extractBds(zip, AppPaths.bds(context));
        patchServerProperties(new File(AppPaths.bds(context), "server.properties"));
        log("✓ BDS " + BDS_VERSION + " instalado");
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

    public void smokeTest() throws Exception {
        smokeTest(RuntimeEngine.savedMode(context));
    }

    public void smokeTest(RuntimeEngine.Mode mode) throws Exception {
        File bds = new File(AppPaths.bds(context), "bedrock_server");
        if (!bds.exists()) throw new IllegalStateException("BDS não instalado");

        int rc = runGuest("/root/bds", "test -x ./bedrock_server && box64 --version >/dev/null 2>&1", mode);
        if (rc != 0) throw new IllegalStateException("Smoke test falhou. Código " + rc);
        log("✓ Smoke test final concluído");
    }

    private int runGuest(String workdir, String command, RuntimeEngine.Mode mode) throws Exception {
        Process p = RuntimeEngine.start(context, workdir, command, mode);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) log("[linux][" + mode.id + "] " + line);
        }
        return p.waitFor();
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
        if (children != null) for (File c : children) deleteTree(c);
        Files.deleteIfExists(f.toPath());
    }
}
