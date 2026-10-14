#!/usr/bin/env bash
set -euo pipefail

WORK=".ci-termux-proot"
STAGE="$WORK/stage"
DEBS="$WORK/debs"
JNI="app/src/main/jniLibs/arm64-v8a"
ASSETS="app/src/main/assets"

rm -rf "$WORK"
mkdir -p "$STAGE" "$DEBS" "$JNI" "$ASSETS/licenses"

REPO_CANDIDATES=(
  "https://packages-cf.termux.dev/apt/termux-main"
  "https://packages.termux.dev/apt/termux-main"
)

REPO=""
INDEX_READY=0

download_index() {
  local repo="$1"
  local base="$repo/dists/stable/main/binary-aarch64"

  echo "Tentando índice Termux em: $repo"

  if curl -fL --retry 3 "$base/Packages" -o "$WORK/Packages"; then
    REPO="$repo"
    INDEX_READY=1
    echo "✓ Índice Termux obtido como Packages"
    return 0
  fi

  rm -f "$WORK/Packages"

  if curl -fL --retry 3 "$base/Packages.xz" -o "$WORK/Packages.xz"; then
    xz -dc "$WORK/Packages.xz" > "$WORK/Packages"
    REPO="$repo"
    INDEX_READY=1
    echo "✓ Índice Termux obtido como Packages.xz"
    return 0
  fi

  rm -f "$WORK/Packages" "$WORK/Packages.xz"

  if curl -fL --retry 3 "$base/Packages.bz2" -o "$WORK/Packages.bz2"; then
    bzip2 -dc "$WORK/Packages.bz2" > "$WORK/Packages"
    REPO="$repo"
    INDEX_READY=1
    echo "✓ Índice Termux obtido como Packages.bz2"
    return 0
  fi

  rm -f "$WORK/Packages" "$WORK/Packages.bz2"
  return 1
}

for candidate in "${REPO_CANDIDATES[@]}"; do
  if download_index "$candidate"; then
    break
  fi
done

if [ "$INDEX_READY" -ne 1 ] || [ ! -s "$WORK/Packages" ]; then
  echo "ERRO: não foi possível obter o índice ARM64 do repositório Termux." >&2
  exit 1
fi

echo "Mirror Termux selecionado: $REPO"

field_for_package() {
  local pkg="$1"
  local field="$2"

  awk -v pkg="$pkg" -v field="$field" '
    BEGIN { RS=""; FS="\n" }
    {
      found=0
      for (i=1; i<=NF; i++) {
        if ($i == "Package: " pkg) {
          found=1
          break
        }
      }

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
  local filename
  local sha
  local deb

  filename="$(field_for_package "$pkg" Filename)"
  sha="$(field_for_package "$pkg" SHA256)"

  if [ -z "$filename" ]; then
    echo "ERRO: pacote '$pkg' não encontrado no índice Termux." >&2
    exit 1
  fi

  if [ -z "$sha" ]; then
    echo "ERRO: SHA256 do pacote '$pkg' não encontrado." >&2
    exit 1
  fi

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

while read -r needed; do
  case "$needed" in
    libtalloc.so*)
      if [ "$needed" != "libtalloc.so" ]; then
        patchelf --replace-needed "$needed" "libtalloc.so" "$JNI/libproot_classic.so"
      fi
      ;;
    libandroid-shmem.so*)
      if [ "$needed" != "libandroid-shmem.so" ]; then
        patchelf --replace-needed "$needed" "libandroid-shmem.so" "$JNI/libproot_classic.so"
      fi
      ;;
  esac
done < <(
  readelf -d "$JNI/libproot_classic.so" |
  sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p'
)

patchelf --set-rpath '$ORIGIN' "$JNI/libproot_classic.so"

echo "Runtime PRoot preparado:"
file "$JNI/libproot_classic.so"
file "$JNI/libproot_loader.so"
readelf -d "$JNI/libproot_classic.so" | grep NEEDED || true

cat > "$ASSETS/licenses/classic-proot-build-info.txt" <<INFO
Bubble BDS Host - PRoot clássico ARM64

Origem dos binários: Termux package repository
Mirror usado: $REPO

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

echo "✓ PRoot clássico ARM64 preparado."
