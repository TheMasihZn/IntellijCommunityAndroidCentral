package dev.apkintellij

import android.content.Context
import android.system.Os
import java.io.File

/**
 * Where the Linux userspace lives on the device, what is installed in it, and how proot starts it.
 *
 * Two parts are installed separately from files the user keeps anywhere on the phone:
 *  - the Linux image (`apkintellij-linux-*.tar.xz`, built by scripts/build-rootfs.sh), and
 *  - IntelliJ itself (the JetBrains `*-aarch64.tar.gz`, or a build from this repository),
 * so updating the APK or IntelliJ never means downloading the other parts again.
 */
class Linux(private val context: Context) {
  val rootfs = File(context.filesDir, "rootfs")
  val ideDir = File(rootfs, "opt/idea")
  val fakeProc = File(context.filesDir, "fake-proc")
  val prootTmp = File(context.filesDir, "proot-tmp")
  val log = File(context.filesDir, "linux.log")
  private val imageMarker = File(context.filesDir, "installed-image.txt")
  private val ideMarker = File(context.filesDir, "installed-ide.txt")
  private val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)

  /** File name of the installed Linux image, or null. */
  fun installedImage(): String? =
    imageMarker.takeIf { it.isFile && File(rootfs, "bin").exists() }?.readText()?.trim()

  /** File name of the installed IntelliJ archive, or null. */
  fun installedIde(): String? =
    ideMarker.takeIf { it.isFile && File(ideDir, "bin/idea.sh").isFile }?.readText()?.trim()

  fun markImage(name: String?) = mark(imageMarker, name)
  fun markIde(name: String?) = mark(ideMarker, name)

  private fun mark(file: File, name: String?) {
    if (name == null) file.delete() else file.writeText(name)
  }

  fun isReady(): Boolean = installedImage() != null && installedIde() != null

  /**
   * Copies the app-owned files (start script, VM options, viewer page, menus) into the rootfs.
   * Done on every start, so an APK update can change them without a new Linux image.
   */
  fun syncOverlay() {
    copyAssetDir("overlay", rootfs)
    Os.chmod(File(rootfs, "opt/apkintellij/start.sh").path, 0x1ed) // 0755
  }

  private fun copyAssetDir(assetPath: String, target: File) {
    val children = context.assets.list(assetPath).orEmpty()
    if (children.isEmpty()) {
      target.parentFile?.mkdirs()
      if (target.isDirectory) return
      target.delete()
      context.assets.open(assetPath).use { input -> target.outputStream().use { input.copyTo(it) } }
      return
    }
    target.mkdirs()
    for (child in children) copyAssetDir("$assetPath/$child", File(target, child))
  }

  /**
   * The command that boots the rootfs and runs /opt/apkintellij/start.sh, which starts the
   * VNC server, the window manager, the viewer bridge on [VIEWER_PORT] and IntelliJ.
   */
  fun command(width: Int, height: Int, dpi: Int): List<String> {
    val cmd = mutableListOf(
      File(nativeLibDir, "libproot.so").path,
      "--kill-on-exit",
      "--link2symlink",
      "--sysvipc",
      "-L",
      "--kernel-release=6.2.1-PRoot-APKintellij",
      "-0",
      "-r", rootfs.path,
      "-b", "/dev",
      "-b", "/proc",
      "-b", "/sys",
      "-b", "/dev/urandom:/dev/random",
      "-b", "${File(rootfs, "tmp").path}:/dev/shm",
    )
    // Android hides parts of /proc from apps; the JVM and IntelliJ read these files.
    for (name in FAKE_PROC_FILES.keys) {
      if (!File("/proc/$name").canRead()) cmd += listOf("-b", "${File(fakeProc, name).path}:/proc/$name")
    }
    val sdcard = File("/storage/emulated/0")
    if (sdcard.canRead()) cmd += listOf("-b", "${sdcard.path}:/sdcard")
    cmd += listOf(
      "-w", "/root",
      "/usr/bin/env", "-i",
      "HOME=/root",
      "USER=root",
      "LANG=C.UTF-8",
      "TERM=xterm-256color",
      "TMPDIR=/tmp",
      "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
      "VNC_GEOMETRY=${width}x$height",
      "VNC_DPI=$dpi",
      "VIEWER_PORT=$VIEWER_PORT",
      "/bin/bash", "/opt/apkintellij/start.sh",
    )
    return cmd
  }

  fun environment(): Map<String, String> = mapOf(
    "LD_LIBRARY_PATH" to nativeLibDir.path,
    "PROOT_LOADER" to File(nativeLibDir, "libproot-loader.so").path,
    "PROOT_TMP_DIR" to prootTmp.path,
  )

  companion object {
    const val VIEWER_PORT = 6080
    const val VIEWER_URL = "http://127.0.0.1:$VIEWER_PORT/viewer.html"

    /** Stand-ins for /proc files Android denies to apps (same idea as proot-distro). */
    val FAKE_PROC_FILES = mapOf(
      "loadavg" to "0.12 0.07 0.02 2/165 765\n",
      "stat" to """
        cpu  1957 0 2877 93280 262 342 254 87 0 0
        cpu0 31 0 226 12027 82 10 4 9 0 0
        ctxt 140223
        btime 1680020856
        processes 772
        procs_running 2
        procs_blocked 0
        softirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677
      """.trimIndent() + "\n",
      "uptime" to "124.08 932.80\n",
      "version" to "Linux version 6.2.1-PRoot-APKintellij (proot@apkintellij) #1 SMP PREEMPT\n",
      "vmstat" to "nr_free_pages 1743136\nnr_zone_inactive_anon 179281\nnr_zone_active_anon 7183\n",
    )
  }
}
