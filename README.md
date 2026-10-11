# Bubble BDS Host v0.1

Standalone Android ARM64 prototype for running the official x86_64 Minecraft Bedrock Dedicated Server through:

Android -> proroot -> Ubuntu Base ARM64 -> Box64 -> bedrock_server x86_64

## What is already implemented

- Android UI with installer status, start/stop and live console.
- Ubuntu Base 24.04.5 ARM64 download after APK installation.
- Ubuntu SHA-256 verification before extraction.
- Rootfs extraction with symlinks, hardlinks and POSIX permission restoration where Android permits it.
- Embedded Android Linux runtime (`proroot` 1.2.8), downloaded and SHA-256 verified during GitHub Actions.
- Box64 0.4.4 built as an ARM64 Linux binary during GitHub Actions from `third_party/box64-0.4.4.zip`.
- Box64 SHA-256 generated during the build and verified again on the phone.
- Box64 x86_64 compatibility libraries from the pinned 0.4.4 source copied into `/usr/lib/box64-x86_64-linux-gnu`.
- Linux dependency install through APT.
- Official BDS 1.26.52.3 download from minecraft.net after APK installation.
- BDS ZIP layout validation.
- Foreground service + wake lock for the server.
- Server console input/output.

## Important

This is the first real hardware-validation version. The project is designed to fail with a precise log at the
stage that has a compatibility problem instead of hiding it. Runtime behavior still must be validated on the
actual Android device/kernel.

The APK is ARM64-only in v0.1.

## Build with GitHub Actions

Upload the complete project tree to a GitHub repository. The workflow `.github/workflows/build-apk.yml`:

1. Verifies the pinned Box64 source ZIP.
2. Uses an ARM64 Ubuntu 24.04 container under QEMU to build Box64 with `ARM64=1` and `BAD_SIGNAL=ON`.
3. Downloads the five official proroot 1.2.8 binaries.
4. Verifies all five proroot SHA-256 hashes from the upstream release.
5. Builds the Android APK with Java 17 / Gradle 8.9 / AGP 8.7.3.
6. Uploads `BubbleBDSHost-APK` as a workflow artifact.

No Gradle wrapper is required for the GitHub workflow because Gradle 8.9 is installed by the workflow itself.

## First launch

Tap `INSTALAR / PREPARAR TUDO`.

Expected stages:

- ARM64 check
- runtime check
- Ubuntu download
- Ubuntu SHA-256
- rootfs extraction
- Box64 install + SHA-256
- Box64 smoke test
- `apt-get update`
- BDS runtime libraries
- BDS download
- BDS ZIP validation
- final smoke test

When all stages pass, `INICIAR` becomes enabled.

## Data location

Runtime data stays in the app-private files directory. No OBB is used.

Logical layout:

```
files/
  downloads/
  ubuntu/
    usr/local/bin/box64
    root/bds/
      bedrock_server
      server.properties
      worlds/
  logs/bds.log
```
