#!/usr/bin/env bash
# Builds the Linux image the app installs from a file on the phone:
#   Debian bookworm (arm64) + Xvnc + openbox + noVNC + the libraries IntelliJ needs.
# IntelliJ itself is NOT inside: the app installs it separately from a JetBrains (or locally built)
# Linux ARM64 .tar.gz, so the IDE and this image can each be updated without the other.
#
# Output: out/apkintellij-linux-<version>.tar.xz
#
# Needs Docker with arm64 emulation (docker/setup-qemu-action on CI, or
# `docker run --privileged --rm tonistiigi/binfmt --install arm64` locally) and xz.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT=out
NAME=apkintellij-rootfs-$$
VERSION="${IMAGE_VERSION:-$(date -u +%Y%m%d)}"
mkdir -p "$OUT"

PACKAGES=(
  tigervnc-standalone-server openbox novnc websockify python3-numpy
  xterm x11-xserver-utils fontconfig fonts-dejavu-core fonts-jetbrains-mono
  libxtst6 libxi6 libxrender1 libxrandr2 libfreetype6 libasound2
  git openssh-client ca-certificates curl wget unzip procps less nano
)

docker rm -f "$NAME" >/dev/null 2>&1 || true
docker create --platform linux/arm64 --name "$NAME" debian:bookworm sleep infinity
docker start "$NAME"
trap 'docker rm -f "$NAME" >/dev/null 2>&1 || true' EXIT

docker exec -e DEBIAN_FRONTEND=noninteractive "$NAME" bash -euc "
  apt-get update
  apt-get install -y --no-install-recommends ${PACKAGES[*]}
  apt-get clean
  rm -rf /var/lib/apt/lists/* /usr/share/doc/* /usr/share/man/*
  # Maximize IntelliJ windows without a frame, so it looks like a native app.
  sed -i 's|<applications>|<applications><application class=\"jetbrains-*\" type=\"normal\"><decor>no</decor><maximized>true</maximized></application>|' /etc/xdg/openbox/rc.xml
  mkdir -p /opt/apkintellij
  echo '$VERSION' > /etc/apkintellij-image
  test -x /usr/bin/Xvnc && test -d /usr/share/novnc/core
"
docker stop -t 1 "$NAME"

FILE="$OUT/apkintellij-linux-$VERSION.tar.xz"
echo "exporting and compressing the image (this takes a few minutes)"
docker export "$NAME" | xz -T0 -6 > "$FILE"
ls -lh "$FILE"
echo "image=$FILE" >> "${GITHUB_OUTPUT:-/dev/null}"
