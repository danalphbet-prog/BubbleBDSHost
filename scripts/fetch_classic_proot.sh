#!/usr/bin/env bash
set -euo pipefail

REPO="https://packages.termux.dev/apt/termux-main"
INDEX_URL="$REPO/dists/stable/main/binary-aarch64/Packages.xz"
WORK=".ci-termux-proot"
STAGE="$WORK/stage"
DEBS="$WORK/debs"
JNI="app/src/main/jniLibs/arm64-v8a"
ASSETS="app/src/main/assets"

rm -rf "$WORK"
mkdir -p "$STAGE" "$DEBS" "$JNI" "$ASSETS/licenses"

curl -fL --retry 3 "$INDEX_URL" -o "$WORK/Packages.xz"
xz -dc "$WORK/Packages.xz" > "$WORK/Packages"

field_for_package() {
  local pkg="$1"
  local field="$2"
  awk -v pkg="$pkg" -v field="$field" '
    BEGIN { RS=""; FS="\n" }
    {
      found=0
      for (i=1; i<=NF; i++) if ($i == "Package: " pkg) { found=1; break }
      if (found) {
        for (i=1; i<=NF; i++) {
          prefix=field ": "
          if (index($i, prefix) == 1) {
            print substr($i, length(prefix)+1)
            exit
          }
        }
      }
    }
  ' "$WORK/Packages"
}

fetch_pkg() {
  local pkg="$1"
  local filename sha deb
  filename="$(field_for_package "$pkg" Filename)"
  sha="$(field_for_package "$pkg" SHA256)"
  test -n "$filename"
  test -n "$sha"
  deb="$DEBS/${pkg}.deb"
  echo "Baixando $pkg: $filename"
  curl -fL --retry 3 "$REPO/$filename" -o "$deb"
  echo "$sha  $deb" | sha256sum -c -
  dpkg-deb -x "$deb" "$STAGE"
}

fetch_pkg proot
fetch_pkg libandroid-shmem
fetch_pkg libtalloc

PREFIX="$STAGE/data/data/com.termux/files/usr"
PROOT_BIN="$PREFIX/bin/proot"
PROOT_LOADER="$PREFIX/libexec/proot/loader"
SHMEM_LIB="$(find "$PREFIX/lib" -maxdepth 1 -type f -name 'libandroid-shmem.so*' | head -n 1)"
TALLOC_LIB="$(find "$PREFIX/lib" -maxdepth 1 -type f -name 'libtalloc.so*' | head -n 1)"

test -s "$PROOT_BIN"
test -s "$PROOT_LOADER"
test -n "$SHMEM_LIB"
test -n "$TALLOC_LIB"

cp "$PROOT_BIN" "$JNI/libproot_classic.so"
cp "$PROOT_LOADER" "$JNI/libproot_loader.so"
cp "$SHMEM_LIB" "$JNI/libandroid-shmem.so"
cp "$TALLOC_LIB" "$JNI/libtalloc.so"

# Normaliza NEEDED versionados para os nomes empacotados no APK.
while read -r needed; do
  case "$needed" in
    libtalloc.so*)
      [ "$needed" = "libtalloc.so" ] || \
        patchelf --replace-needed "$needed" "libtalloc.so" "$JNI/libproot_classic.so"
      ;;
    libandroid-shmem.so*)
      [ "$needed" = "libandroid-shmem.so" ] || \
        patchelf --replace-needed "$needed" "libandroid-shmem.so" "$JNI/libproot_classic.so"
      ;;
  esac
done < <(readelf -d "$JNI/libproot_classic.so" | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p')

patchelf --set-rpath '$ORIGIN' "$JNI/libproot_classic.so"

file "$JNI/libproot_classic.so"
file "$JNI/libproot_loader.so"
readelf -d "$JNI/libproot_classic.so" | grep NEEDED || true

cat > "$ASSETS/licenses/classic-proot-build-info.txt" <<INFO
Bubble BDS Host - PRoot clássico ARM64

Origem dos binários: Termux package repository
Repositorio: $REPO
PRoot version: $(field_for_package proot Version)
PRoot package SHA256: $(field_for_package proot SHA256)
libandroid-shmem version: $(field_for_package libandroid-shmem Version)
libandroid-shmem SHA256: $(field_for_package libandroid-shmem SHA256)
libtalloc version: $(field_for_package libtalloc Version)
libtalloc SHA256: $(field_for_package libtalloc SHA256)

PRoot source: https://github.com/termux/proot
Termux packages source: https://github.com/termux/termux-packages
INFO

test -s "$JNI/libproot_classic.so"
test -s "$JNI/libproot_loader.so"
test -s "$JNI/libandroid-shmem.so"
test -s "$JNI/libtalloc.so"

curl -fL --retry 3 \
  https://raw.githubusercontent.com/termux/proot/master/COPYING \
  -o "$ASSETS/licenses/proot-COPYING.txt"

test -s "$ASSETS/licenses/proot-COPYING.txt"

echo "PRoot clássico ARM64 preparado."
