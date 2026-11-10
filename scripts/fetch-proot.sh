#!/usr/bin/env bash
# Downloads proot + libtalloc (aarch64, built for Android by the Termux project) and installs them
# as app/src/main/jniLibs/arm64-v8a/lib*.so, so Android extracts them to nativeLibraryDir where
# apps are allowed to execute files.
# Needs: curl, dpkg-deb, patchelf, readelf.
set -euo pipefail
cd "$(dirname "$0")/.."

REPO="${TERMUX_REPO:-https://packages-cf.termux.dev/apt/termux-main}"
OUT=app/src/main/jniLibs/arm64-v8a
WORK=work/proot
rm -rf "$WORK" "$OUT"
mkdir -p "$WORK" "$OUT"

curl -fsSL "$REPO/dists/stable/main/binary-aarch64/Packages" -o "$WORK/Packages"

fetch() {
  local pkg="$1" file
  file=$(awk -v p="$pkg" '$1=="Package:"{cur=$2} cur==p && $1=="Filename:"{print $2; exit}' "$WORK/Packages")
  [ -n "$file" ] || { echo "package $pkg not found in Termux repo" >&2; exit 1; }
  echo "fetching $file"
  curl -fsSL "$REPO/$file" -o "$WORK/$pkg.deb"
  dpkg-deb -x "$WORK/$pkg.deb" "$WORK/$pkg"
}
fetch proot
fetch libtalloc
fetch libandroid-shmem

PREFIX=data/data/com.termux/files/usr
cp "$WORK/proot/$PREFIX/bin/proot" "$OUT/libproot.so"
cp "$WORK/proot/$PREFIX/libexec/proot/loader" "$OUT/libproot-loader.so"
cp "$(find "$WORK/libtalloc/$PREFIX/lib" -name 'libtalloc.so.2*' -type f | head -n1)" "$OUT/libtalloc.so"
# System V shared memory for --sysvipc (Xvnc, the JVM); Android has no shmget of its own.
cp "$WORK/libandroid-shmem/$PREFIX/lib/libandroid-shmem.so" "$OUT/libandroid-shmem.so"

# Android only extracts files named lib*.so, so the talloc soname has to match that name.
patchelf --set-soname libtalloc.so "$OUT/libtalloc.so"
patchelf --replace-needed libtalloc.so.2 libtalloc.so "$OUT/libproot.so"
for lib in libproot.so libtalloc.so libandroid-shmem.so; do patchelf --remove-rpath "$OUT/$lib" || true; done

# Fail loudly if proot needs a Termux library we don't ship.
allowed=' libc.so libdl.so libm.so liblog.so libtalloc.so libandroid-shmem.so '
for lib in libproot.so libtalloc.so libandroid-shmem.so; do
  for needed in $(readelf -d "$OUT/$lib" | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p'); do
    case "$allowed" in *" $needed "*) ;; *) echo "$lib needs $needed, which is not bundled" >&2; exit 1 ;; esac
  done
done
chmod 755 "$OUT"/*.so
ls -l "$OUT"
