package dev.apkintellij

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Unpacks the Linux image and IntelliJ from archives the user keeps anywhere on the phone
 * (internal storage, SD card, USB drive, cloud folder via the file picker). The archive can be
 * moved or deleted afterwards. Updating one part keeps the other part and the user's home folder.
 */
class Installer(private val context: Context, private val linux: Linux) {

  /** An archive on the phone: a picked document or a file found in a known folder. */
  class Source(val name: String, val size: Long, val open: () -> InputStream) {
    companion object {
      fun of(file: File) = Source(file.name, file.length()) { file.inputStream() }

      fun of(context: Context, uri: Uri): Source {
        var name = uri.lastPathSegment ?: "archive"
        var size = -1L
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
          if (c.moveToFirst()) {
            c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
            c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
          }
        }
        return Source(name, size) {
          context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $name")
        }
      }
    }
  }

  /** Installs or replaces the Linux image, keeping /root, /home and the installed IntelliJ. */
  fun installImage(source: Source, progress: (Float, String) -> Unit) {
    val staging = File(context.filesDir, "rootfs.new")
    deleteRecursively(staging)
    staging.mkdirs()
    extract(source, staging, stripComponents = 0, progress)
    if (!File(staging, "bin").exists() || !File(staging, "usr/bin/Xvnc").exists()) {
      deleteRecursively(staging)
      throw IOException("${source.name} is not an APKintellij Linux image (no /usr/bin/Xvnc inside).")
    }
    progress(1f, "Keeping your files…")
    val old = linux.rootfs
    if (old.isDirectory) {
      for (keep in listOf("root", "home", "opt/idea")) {
        val from = File(old, keep)
        if (!from.exists()) continue
        val to = File(staging, keep)
        deleteRecursively(to)
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) from.copyRecursively(to, overwrite = true)
      }
      deleteRecursively(old)
    }
    if (!staging.renameTo(old)) throw IOException("Could not move the new Linux image into place.")
    finishSetup()
    linux.markImage(source.name)
  }

  /** Installs or replaces IntelliJ in /opt/idea from a JetBrains Linux ARM64 .tar.gz. */
  fun installIde(source: Source, progress: (Float, String) -> Unit) {
    if (linux.installedImage() == null) throw IOException("Install the Linux image first.")
    val staging = File(linux.rootfs, "opt/idea.new")
    deleteRecursively(staging)
    staging.mkdirs()
    extract(source, staging, stripComponents = 1, progress)
    if (!File(staging, "bin/idea.sh").isFile) {
      deleteRecursively(staging)
      throw IOException("${source.name} is not an IntelliJ Linux archive (no bin/idea.sh inside).")
    }
    deleteRecursively(linux.ideDir)
    if (!staging.renameTo(linux.ideDir)) throw IOException("Could not move IntelliJ into place.")
    linux.markIde(source.name)
  }

  /** Version 0.1 of the app shipped both parts inside the APK; adopt such an install. */
  fun adoptLegacyInstall() {
    val legacy = File(linux.rootfs, ".apkintellij-rootfs")
    if (!legacy.isFile) return
    if (linux.installedImage() == null && File(linux.rootfs, "bin").exists()) linux.markImage("bundled with app 0.1")
    if (linux.installedIde() == null && File(linux.ideDir, "bin/idea.sh").isFile) linux.markIde("bundled with app 0.1")
    legacy.delete()
  }

  private fun extract(source: Source, dest: File, stripComponents: Int, progress: (Float, String) -> Unit) {
    val dirModes = mutableListOf<Pair<File, Int>>()
    val hardLinks = mutableListOf<Pair<File, File>>()
    var lastReport = 0L
    progress(0f, "Opening ${source.name}…")

    source.open().use { raw ->
      val counting = CountingInputStream(BufferedInputStream(raw, 1 shl 20))
      TarArchiveInputStream(decompress(counting)).use { tar ->
        while (true) {
          val entry = tar.nextEntry ?: break
          val target = resolve(dest, entry.name, stripComponents) ?: continue
          when {
            entry.isDirectory -> {
              target.mkdirs()
              dirModes += target to entry.mode
            }
            entry.isSymbolicLink -> {
              target.parentFile?.mkdirs()
              if (exists(target)) deleteRecursively(target)
              Os.symlink(entry.linkName, target.path)
            }
            entry.isLink -> resolve(dest, entry.linkName, stripComponents)?.let { hardLinks += it to target }
            entry.isFile -> writeFile(tar, entry, target)
            else -> Unit // device nodes and fifos: proot binds the host /dev instead
          }
          if (counting.count - lastReport > (4 shl 20)) {
            lastReport = counting.count
            val fraction = if (source.size > 0) counting.count.toFloat() / source.size else 0f
            progress(fraction, "Unpacking ${source.name}… ${entry.name.take(60)}")
          }
        }
      }
    }

    // Android does not let apps create hard links, so hard-linked files become copies.
    for ((from, to) in hardLinks) {
      if (from.isFile) {
        to.parentFile?.mkdirs()
        from.copyTo(to, overwrite = true)
        Os.chmod(to.path, Os.stat(from.path).st_mode and 0x1ff)
      }
    }
    // Directory modes go last so read-only dirs don't block their own extraction.
    for ((dir, mode) in dirModes.asReversed()) {
      runCatching { Os.chmod(dir.path, (mode and 0x1ff) or 0x1c0) }
    }
  }

  /** Accepts .tar.xz, .tar.gz and plain .tar, whatever the file is called. */
  private fun decompress(input: InputStream): InputStream {
    input.mark(8)
    val magic = ByteArray(6)
    val read = input.readNBytesCompat(magic)
    input.reset()
    return when {
      read >= 6 && magic[0] == 0xFD.toByte() && magic[1] == '7'.code.toByte() && magic[2] == 'z'.code.toByte() ->
        XZCompressorInputStream(input)
      read >= 2 && magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte() -> GZIPInputStream(input, 1 shl 16)
      else -> input
    }
  }

  private fun InputStream.readNBytesCompat(buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
      val n = read(buffer, total, buffer.size - total)
      if (n < 0) break
      total += n
    }
    return total
  }

  private fun writeFile(tar: TarArchiveInputStream, entry: TarArchiveEntry, target: File) {
    target.parentFile?.mkdirs()
    if (exists(target)) deleteRecursively(target)
    target.outputStream().use { tar.copyTo(it, 1 shl 16) }
    // Owner must keep read/write access: proot runs everything as our single app uid.
    Os.chmod(target.path, (entry.mode and 0x1ff) or 0x180)
  }

  private fun finishSetup() {
    val root = linux.rootfs
    File(root, "tmp").apply { mkdirs(); Os.chmod(path, 0x1ff) }
    File(root, "root").mkdirs()
    linux.prootTmp.mkdirs()
    linux.fakeProc.mkdirs()
    for ((name, content) in Linux.FAKE_PROC_FILES) File(linux.fakeProc, name).writeText(content)
    // Docker leaves these as bind-mount placeholders; give Linux working network config.
    replaceFile(File(root, "etc/resolv.conf"), "nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
    replaceFile(File(root, "etc/hosts"), "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
    replaceFile(File(root, "etc/hostname"), "apkintellij\n")
  }

  private fun replaceFile(file: File, content: String) {
    file.parentFile?.mkdirs()
    if (exists(file)) file.delete()
    file.writeText(content)
  }

  /** Maps a tar path into [dest], dropping leading components and refusing anything that escapes. */
  private fun resolve(dest: File, name: String, stripComponents: Int): File? {
    val parts = name.removePrefix("./").split('/').filter { it.isNotEmpty() && it != "." }
    if (parts.any { it == ".." }) return null
    val kept = parts.drop(stripComponents)
    if (kept.isEmpty()) return null
    return File(dest, kept.joinToString("/"))
  }

  private fun isSymlink(file: File): Boolean =
    runCatching { OsConstants.S_ISLNK(Os.lstat(file.path).st_mode) }.getOrDefault(false)

  private fun exists(file: File) = file.exists() || isSymlink(file)

  private fun deleteRecursively(file: File) {
    if (!exists(file)) return
    if (file.isDirectory && !isSymlink(file)) {
      runCatching { Os.chmod(file.path, 0x1c0) }
      file.listFiles()?.forEach { deleteRecursively(it) }
    }
    file.delete()
  }

  private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var count = 0L
      private set
    private var markCount = 0L

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
      super.read(b, off, len).also { if (it > 0) count += it }

    override fun skip(n: Long): Long = super.skip(n).also { count += it }

    override fun mark(readlimit: Int) {
      super.mark(readlimit)
      markCount = count
    }

    override fun reset() {
      super.reset()
      count = markCount
    }
  }
}
