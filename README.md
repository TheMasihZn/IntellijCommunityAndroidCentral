# APKintellij: IntelliJ IDEA on Android (Galaxy Z Fold, Samsung DeX)

A small Android app that runs IntelliJ IDEA in a Debian Linux userspace (proot) and shows it as a
normal resizable window. It is made of three parts that update independently:

| Part | File | Where it comes from | How often it changes |
|---|---|---|---|
| App | `APKintellij-<version>.apk` (a few MB) | *Android APK* workflow / Releases | often |
| Linux image | `apkintellij-linux-<n>.tar.xz` (~300 MB) | *Linux image* workflow / Releases | rarely |
| IntelliJ | `ideaIC-<build>-aarch64.tar.gz` (~1 GB) | `ide-*` Releases here, built from source in [APKintellij](https://github.com/TheMasihZn/APKintellij) (a JetBrains Linux ARM64 download works too) | when you upgrade the IDE |

Updating the app never re-downloads Linux or IntelliJ: they are unpacked once into the app's
storage, and the archives can then be moved, kept on an SD card, or deleted.

## First install

1. Install the APK.
2. Put the Linux image and the IntelliJ archive anywhere on the phone. Files named like the table
   above in **Download/** or an **APKintellij/** folder are found automatically (after you allow
   storage access); anything else, including SD cards and USB drives, can be chosen with the
   file picker.
3. In the app's setup screen install the Linux image first, then IntelliJ, then tap **Start IntelliJ**.

To update IntelliJ or the Linux image later, open **⋯ › Linux and IntelliJ: install or update**
and pick the new file. Your projects in `/root` and IntelliJ's settings are kept.

## Galaxy Z Fold and touch use

* **Flex mode** (half folded, laptop posture): IntelliJ takes the top half; the bottom half shows
  the key bar and a grid of IDE shortcuts (Search everywhere, Find action, Complete, Quick fix,
  Run, Debug, …).
* **Cover screen / inner screen / DeX**: switching screens, folding, unfolding, split screen and
  pop-up windows resize the Linux desktop to the new window size.
* **Soft keyboard**: tap ⌨ on the key bar. Typing goes straight into IntelliJ (no autocorrect);
  while it is open the desktop keeps its size and scrolls instead of re-laying out.
* **Key bar**: Esc, Tab, sticky Ctrl/Alt/Shift (tap Ctrl, then a key), arrows, Home/End,
  PgUp/PgDn, Del, F1–F12, fullscreen (⛶) and the menu (⋯). Hide it from the menu when using a
  hardware keyboard.
* **Fullscreen** (⛶) hides Android's status and navigation bars; swipe from an edge to peek.
* Touch: tap to click, drag to select, long-press for right click, two-finger drag to scroll.

## How it works

```
MainActivity ── WebView ──► http://127.0.0.1:6080/viewer.html (noVNC core, our minimal UI)
LinuxService ── proot ──► Debian arm64 rootfs
                           ├─ Xvnc :1 (resizes with the window)   ├─ openbox
                           ├─ websockify (127.0.0.1 only)          └─ /opt/idea/bin/idea.sh
```

The start script, VM options and viewer page ship in the APK (`app/src/main/assets/overlay`) and
are copied into Linux on every start, so they can change with an app update.

Android only lets system apps start real virtual machines and Samsung does not offer one, so the
app uses proot (from Termux's Android builds), which runs Linux programs at native speed on
Android's own kernel.

## Building

* APK: `./scripts/fetch-proot.sh && ./gradlew assembleRelease` (JDK 17, Android SDK 35).
* Linux image: `./scripts/build-rootfs.sh` (Docker with arm64 emulation) → `out/`.

Both run in GitHub Actions; on `main` each publishes a GitHub Release. IntelliJ itself is built from
source by the *IntelliJ for Android (ARM64)* workflow in APKintellij, which publishes `ide-*`
releases to this repository.

## Recommended phone settings

* **Settings › Battery › Background usage limits**: add the app to *Never sleeping apps*.
* **Android 14+:** *Developer options › Disable child process restrictions*. On Android 12–13:
  `adb shell settings put global settings_enable_monitor_phantom_procs false`.
* 8 GB of RAM or more; IntelliJ gets a 2 GB heap (`assets/overlay/opt/apkintellij/idea.vmoptions`).
