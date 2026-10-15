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

    public interface Callback {
        void log(String text);
        default void progress(int percent, String stage) {}
    }

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

    private void progress(int percent, String stage) {
        if (cb != null) cb.progress(Math.max(0, Math.min(100, percent)), stage);
    }

    public void installAll() throws Exception {
        log("=== BUBBLE BDS HOST v0.3.1 ===");
        log("Fluxo: PRoot clássico → Debian Trixie ARM64 → Box64 local → BDS");
        progress(1, "Verificando Android");
        checkArchitecture();
        progress(5, "Verificando armazenamento");
        checkStorage();
        progress(10, "Verificando PRoot");
        checkRuntime();
        progress(15, "Preparando Debian Trixie");
        installDebian();
        progress(35, "Testando Debian");
        testDebian();
        progress(42, "Reparando dpkg/apt");
        repairDebianPackages();
        progress(50, "Preparando e compilando Box64");
        prepareDebianAndBuildBox64();
        progress(78, "Instalando BDS");
        installBds();
        progress(94, "Validando cadeia completa");
        smokeTest();
        progress(100, "Ambiente pronto");
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
            progress(35, "Debian Trixie já instalado");
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
                    double fraction = done * 1.0 / total;
                    progress(15 + (int)Math.round(fraction * 15.0), "Baixando Debian Trixie");
                    log(String.format(Locale.US, "Debian: %.0f%%", fraction * 100.0));
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
        progress(31, "Extraindo Debian Trixie");

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

    private void repairDebianPackages() throws Exception {
        log("Verificando consistência do dpkg/apt antes de continuar...");
        int configureRc = runGuest("/root",
                "export DEBIAN_FRONTEND=noninteractive; dpkg --configure -a");
        if (configureRc != 0) {
            log("dpkg encontrou instalação interrompida. Tentando reparo automático...");
            int updateRc = runGuest("/root",
                    "export DEBIAN_FRONTEND=noninteractive; apt-get update");
            if (updateRc != 0) {
                log("Aviso: apt-get update durante o reparo retornou código " +
                        updateRc + ". Continuando com o índice existente.");
            }
            int fixRc = runGuest("/root",
                    "export DEBIAN_FRONTEND=noninteractive; apt-get -f install -y");
            if (fixRc != 0) {
                throw new IllegalStateException(
                        "Reparo apt-get -f install falhou. Código " + fixRc);
            }
            configureRc = runGuest("/root",
                    "export DEBIAN_FRONTEND=noninteractive; dpkg --configure -a");
            if (configureRc != 0) {
                throw new IllegalStateException(
                        "dpkg continuou inconsistente após reparo. Código " + configureRc);
            }
        }
        log("✓ dpkg/apt consistente; instalação pode ser retomada");
    }

    public void prepareDebianAndBuildBox64() throws Exception {
        File box = AppPaths.box64(context);
        File marker = AppPaths.box64Marker(context);

        if (box.isFile() && marker.isFile()) {
            int rc = runGuest("/root",
                    "/usr/local/bin/box64 --version || /usr/local/bin/box64 -v");
            if (rc == 0) {
                log("[6/8] ✓ Box64 local já compilado e funcional");
                progress(78, "Box64 já preparado");
                return;
            }
            log("Box64 existente falhou; recompilando dentro do Debian...");
            try { Files.deleteIfExists(marker.toPath()); } catch (Exception ignored) {}
        }

        log("[6/8] Instalando toolchain Debian para compilar Box64 no aparelho...");
        progress(52, "Atualizando pacotes Debian");
        int updateRc = runGuest("/root",
                "export DEBIAN_FRONTEND=noninteractive; apt-get update");
        if (updateRc != 0) {
            throw new IllegalStateException("Falha no apt-get update. Código " + updateRc);
        }

        progress(56, "Instalando toolchain do Box64");
        String deps =
                "export DEBIAN_FRONTEND=noninteractive; " +
                "apt-get install -y --no-install-recommends " +
                "ca-certificates curl git build-essential cmake python3 unzip libstdc++6 libgcc-s1 && " +
                "(apt-get install -y --no-install-recommends libcurl4t64 || " +
                " apt-get install -y --no-install-recommends libcurl4 || true) && " +
                "(apt-get install -y --no-install-recommends libssl3t64 || " +
                " apt-get install -y --no-install-recommends libssl3 || true)";
        int depsRc = runGuest("/root", deps);
        if (depsRc != 0) {
            log("Toolchain retornou código " + depsRc +
                    ". Executando um reparo e tentando uma única vez novamente...");
            repairDebianPackages();
            depsRc = runGuest("/root", deps);
        }
        if (depsRc != 0) {
            throw new IllegalStateException(
                    "Falha ao instalar toolchain/dependências após reparo. Código " + depsRc);
        }

        File box64Source = new File(AppPaths.rootfs(context), "root/box64-src");
        File box64Build = new File(box64Source, "build");
        File box64CmakeCache = new File(box64Build, "CMakeCache.txt");

        boolean sourcePresent = new File(box64Source, ".git").isDirectory()
                && new File(box64Source, "CMakeLists.txt").isFile();
        boolean buildPresent = box64Build.isDirectory();

        if (sourcePresent && buildPresent) {
            log("Encontrado build parcial do Box64.");
            log("Retomando compilação a partir dos objetos já concluídos...");
            progress(64, "Retomando compilação do Box64");
        } else if (sourcePresent) {
            log("Código-fonte do Box64 já existe; preparando diretório de build...");
            progress(64, "Preparando build do Box64");
        } else {
            log("Baixando código-fonte oficial do Box64 " + BOX64_TAG + "...");
            progress(60, "Obtendo código-fonte do Box64");
        }

        String prepareSource =
                "set -e; " +
                "if [ -d /root/box64-src/.git ] && [ -f /root/box64-src/CMakeLists.txt ]; then " +
                "  cd /root/box64-src; " +
                "  HEAD=$(git rev-parse HEAD 2>/dev/null || true); " +
                "  if [ \"$HEAD\" != \"" + BOX64_COMMIT + "\" ]; then " +
                "    echo BUBBLE_BOX64_SOURCE_INVALID; " +
                "    cd /root; rm -rf /root/box64-src; " +
                "  else echo BUBBLE_BOX64_SOURCE_RESUME_OK; fi; " +
                "fi; " +
                "if [ ! -d /root/box64-src/.git ]; then " +
                "  git clone --depth 1 --branch " + BOX64_TAG + " " +
                "  https://github.com/ptitSeb/box64.git /root/box64-src; " +
                "fi; " +
                "cd /root/box64-src; " +
                "test \"$(git rev-parse HEAD)\" = \"" + BOX64_COMMIT + "\"; " +
                "test -f CMakeLists.txt";

        int sourceRc = runGuest("/root", prepareSource);
        if (sourceRc != 0) {
            throw new IllegalStateException(
                    "Não foi possível preparar o código-fonte do Box64. Código " + sourceRc);
        }

        if (box64CmakeCache.isFile()) {
            log("✓ Cache CMake encontrado; mantendo arquivos já compilados");
        } else {
            log("Configurando CMake do Box64...");
        }

        progress(66, buildPresent
                ? "Continuando make -j2 do Box64"
                : "Compilando Box64 no Android");

        String build =
                "set -e; " +
                "cd /root/box64-src; " +
                "mkdir -p build; cd build; " +
                "cmake .. -DARM64=1 -DARM_DYNAREC=ON -DBAD_SIGNAL=ON " +
                "-DCMAKE_BUILD_TYPE=RelWithDebInfo; " +
                "echo BUBBLE_BOX64_MAKE_RESUME; " +
                "make -j2; " +
                "echo BUBBLE_BOX64_MAKE_COMPLETE; " +
                "make install; ldconfig; " +
                "/usr/local/bin/box64 --version || /usr/local/bin/box64 -v";

        int buildRc = runGuest("/root", build);
        if (buildRc != 0) {
            log("Compilação do Box64 foi interrompida ou falhou.");
            log("Os objetos já concluídos foram preservados em /root/box64-src/build.");
            log("Toque em Instalar Tudo novamente para continuar do ponto salvo.");
            throw new IllegalStateException(
                    "Box64 não concluiu a compilação. Código " + buildRc +
                    ". O build parcial foi preservado para retomada.");
        }

        progress(74, "Verificando Box64");
        int verifyRc = runGuest("/root",
                "test -x /usr/local/bin/box64 && " +
                "(/usr/local/bin/box64 --version || /usr/local/bin/box64 -v)");
        if (verifyRc != 0) {
            throw new IllegalStateException("Box64 local não passou na verificação. Código " + verifyRc);
        }

        writeText(marker, "box64 " + BOX64_TAG + " commit " + BOX64_COMMIT + "\n");
        runGuest("/root", "apt-get clean || true");
        log("✓ Cache de compilação do Box64 preservado para futuras retomadas");
        log("[6/8] ✓ Box64 compilado LOCALMENTE dentro do Debian");
    }

    public void installBds() throws Exception {
        File server = new File(AppPaths.bds(context), "bedrock_server");
        if (server.exists()) {
            log("[7/8] ✓ BDS já instalado");
            progress(94, "BDS já instalado");
            return;
        }

        File zip = new File(AppPaths.downloads(context),
                "bedrock-server-" + BDS_VERSION + ".zip");
        log("[7/8] Baixando BDS oficial " + BDS_VERSION + "...");
        DownloadUtils.download(BDS_URL, zip, (done, total) -> {
            if (total > 0) {
                double fraction = done * 1.0 / total;
                progress(78 + (int)Math.round(fraction * 12.0),
                        "Baixando BDS " + BDS_VERSION);
                log(String.format(Locale.US, "BDS: %.0f%%", fraction * 100.0));
            }
        });
        ZipUtils.validateBdsZip(zip);
        log("✓ Estrutura do ZIP do BDS validada");
        progress(91, "Extraindo BDS");
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
            throw new IllegalStateException(
                    "Smoke test PRoot → Debian → Box64 → BDS falhou. Código " + rc);
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
