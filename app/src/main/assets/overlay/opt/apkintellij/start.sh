#!/bin/bash
# Runs inside the proot Debian rootfs. Started by the Android app (LinuxService).
# Brings up: Xvnc (display :1) -> openbox -> websockify/noVNC on 127.0.0.1:$VIEWER_PORT -> IntelliJ.
set -u
export DISPLAY=:1
export XDG_RUNTIME_DIR=/tmp/runtime-root
export IDEA_VM_OPTIONS=/opt/apkintellij/idea.vmoptions
mkdir -p "$XDG_RUNTIME_DIR" /tmp/.X11-unix && chmod 700 "$XDG_RUNTIME_DIR" && chmod 1777 /tmp/.X11-unix
rm -f /tmp/.X1-lock /tmp/.X11-unix/X1

echo "[apkintellij] starting Xvnc ${VNC_GEOMETRY:-1920x1080}"
Xvnc :1 -geometry "${VNC_GEOMETRY:-1920x1080}" -depth 24 -dpi "${VNC_DPI:-110}" \
  -SecurityTypes None -localhost -rfbport 5901 -AlwaysShared -AcceptSetDesktopSize \
  -nolisten tcp &

for _ in $(seq 1 100); do [ -e /tmp/.X11-unix/X1 ] && break; sleep 0.2; done

echo "[apkintellij] starting openbox"
openbox &

echo "[apkintellij] starting noVNC on 127.0.0.1:${VIEWER_PORT:-6080}"
# viewer.html comes from the app; the noVNC library itself comes from the Linux image.
ln -sfn /usr/share/novnc/core /opt/apkintellij/web/core
ln -sfn /usr/share/novnc/vendor /opt/apkintellij/web/vendor
websockify --web /opt/apkintellij/web "127.0.0.1:${VIEWER_PORT:-6080}" 127.0.0.1:5901 &

if [ ! -x /opt/idea/bin/idea.sh ]; then
  echo "[apkintellij] IntelliJ is not installed in /opt/idea; opening a terminal instead"
  exec xterm -fa Monospace -fs 11
fi

# Keep IntelliJ up: if it is closed (File | Exit), start it again. A terminal is on the
# desktop's right-click menu for everything else.
while true; do
  echo "[apkintellij] starting IntelliJ"
  /opt/idea/bin/idea.sh
  echo "[apkintellij] IntelliJ exited with $?, restarting in 3s"
  sleep 3
done
