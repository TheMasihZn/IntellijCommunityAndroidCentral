package dev.apkintellij

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.io.FileOutputStream
import kotlin.concurrent.thread

/**
 * Keeps the proot Linux session (VNC server + IntelliJ) running in the foreground, so Android and
 * Samsung's battery manager don't kill the IDE while the DeX window is in the background.
 */
class LinuxService : Service() {
  private var process: Process? = null
  private var wakeLock: PowerManager.WakeLock? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      stopLinux()
      stopSelf()
      return START_NOT_STICKY
    }
    startForegroundCompat()
    if (process?.isAlive != true) {
      val width = intent?.getIntExtra(EXTRA_WIDTH, 1920) ?: 1920
      val height = intent?.getIntExtra(EXTRA_HEIGHT, 1080) ?: 1080
      val dpi = intent?.getIntExtra(EXTRA_DPI, 110) ?: 110
      startLinux(width, height, dpi)
    }
    return START_STICKY
  }

  private fun startLinux(width: Int, height: Int, dpi: Int) {
    val linux = Linux(this)
    linux.syncOverlay()
    val builder = ProcessBuilder(linux.command(width, height, dpi))
      .directory(filesDir)
      .redirectErrorStream(true)
    builder.environment().putAll(linux.environment())
    Log.i(TAG, "Starting: ${builder.command().joinToString(" ")}")
    val started = builder.start()
    process = started
    running = true

    wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
      .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "apkintellij:linux")
      .apply { acquire() }

    thread(name = "linux-log", isDaemon = true) {
      FileOutputStream(linux.log, false).use { out ->
        started.inputStream.copyTo(out)
      }
    }
    thread(name = "linux-wait", isDaemon = true) {
      val code = started.waitFor()
      Log.i(TAG, "Linux exited with $code")
      if (process === started) {
        running = false
        stopSelf()
      }
    }
  }

  private fun stopLinux() {
    process?.let { p ->
      p.destroy()
      thread(isDaemon = true) {
        Thread.sleep(3000)
        if (p.isAlive) p.destroyForcibly()
      }
    }
    process = null
    running = false
    wakeLock?.takeIf { it.isHeld }?.release()
    wakeLock = null
  }

  override fun onDestroy() {
    stopLinux()
    super.onDestroy()
  }

  private fun startForegroundCompat() {
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(
      NotificationChannel(CHANNEL, "IntelliJ session", NotificationManager.IMPORTANCE_LOW)
    )
    val open = PendingIntent.getActivity(
      this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )
    val stop = PendingIntent.getService(
      this, 1, Intent(this, LinuxService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
    )
    val notification = Notification.Builder(this, CHANNEL)
      .setSmallIcon(R.drawable.ic_launcher)
      .setContentTitle("IntelliJ is running")
      .setContentText("Linux session is active")
      .setContentIntent(open)
      .setOngoing(true)
      .addAction(Notification.Action.Builder(null, "Stop", stop).build())
      .build()
    if (Build.VERSION.SDK_INT >= 34) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  companion object {
    private const val TAG = "APKintellij"
    private const val CHANNEL = "linux"
    private const val NOTIFICATION_ID = 1
    const val ACTION_STOP = "dev.apkintellij.STOP"
    const val EXTRA_WIDTH = "width"
    const val EXTRA_HEIGHT = "height"
    const val EXTRA_DPI = "dpi"

    @Volatile
    var running = false
      private set
  }
}
