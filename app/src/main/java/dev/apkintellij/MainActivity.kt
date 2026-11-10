package dev.apkintellij

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.util.Consumer
import androidx.window.java.layout.WindowInfoTrackerCallbackAdapter
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Two screens:
 *  - Setup: install or update the Linux image and IntelliJ from archives anywhere on the phone.
 *  - IDE: the IntelliJ desktop in a WebView (viewer.html), with a key panel for touch use.
 *
 * Galaxy Z Fold: in flex mode (half folded, hinge horizontal) the IDE takes the top half and the
 * key panel with IDE shortcuts takes the bottom half. Folding/unfolding and moving between the
 * cover and inner screens or into DeX resize the Linux desktop to match the window.
 */
class MainActivity : Activity(), KeyPanel.Actions {
  private val main = Handler(Looper.getMainLooper())
  private lateinit var linux: Linux
  private lateinit var installer: Installer
  private val prefs by lazy { getSharedPreferences("ui", MODE_PRIVATE) }

  private lateinit var root: FrameLayout

  // Setup screen
  private lateinit var setupView: ScrollView
  private lateinit var imageStatus: TextView
  private lateinit var ideStatus: TextView
  private lateinit var foundView: LinearLayout
  private lateinit var progress: ProgressBar
  private lateinit var status: TextView
  private lateinit var startButton: Button
  private var busy = false

  // IDE screen
  private lateinit var ideView: LinearLayout
  private lateinit var webView: WebView
  private lateinit var webFrame: FrameLayout
  private lateinit var hingeGap: View
  private lateinit var keyPanel: KeyPanel
  private lateinit var imeView: ImeView
  private lateinit var remote: Remote
  private lateinit var connecting: TextView
  private var ideShown = false
  private var keyboardShown = false
  private var lastBack = 0L

  private lateinit var windowInfo: WindowInfoTrackerCallbackAdapter
  private val layoutListener = Consumer<WindowLayoutInfo> { applyFoldLayout(it) }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    linux = Linux(this)
    installer = Installer(this, linux)
    installer.adoptLegacyInstall()
    windowInfo = WindowInfoTrackerCallbackAdapter(WindowInfoTracker.getOrCreate(this))
    buildUi()
    askNotificationPermission()
    if (linux.isReady()) startIde() else showSetup()
  }

  override fun onStart() {
    super.onStart()
    windowInfo.addWindowLayoutInfoListener(this, mainExecutor, layoutListener)
  }

  override fun onStop() {
    windowInfo.removeWindowLayoutInfoListener(layoutListener)
    super.onStop()
  }

  override fun onResume() {
    super.onResume()
    if (!ideShown) refreshSetup()
    applyFullscreen()
  }

  // ---------------------------------------------------------------- setup screen

  private fun showSetup() {
    ideShown = false
    imeView.hide()
    ideView.visibility = View.GONE
    setupView.visibility = View.VISIBLE
    refreshSetup()
  }

  private fun refreshSetup() {
    imageStatus.text = "Installed: " + (linux.installedImage() ?: "nothing yet")
    ideStatus.text = "Installed: " + (linux.installedIde() ?: "nothing yet")
    startButton.isEnabled = linux.isReady() && !busy
    foundView.removeAllViews()
    if (busy) return
    if (!hasStorageAccess()) {
      foundView.addView(button("Allow access to phone storage (to find files automatically)") { askStorageAccess() })
      return
    }
    for (found in Archives.find()) {
      val installed = if (found.isImage) linux.installedImage() else linux.installedIde()
      if (installed == found.file.name) continue
      val what = if (found.isImage) "Linux image" else "IntelliJ"
      foundView.addView(button("Install $what from ${found.file.parentFile?.name}/${found.file.name}") {
        install(found.isImage, Installer.Source.of(found.file))
      })
    }
  }

  private fun pick(isImage: Boolean) {
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
      .addCategory(Intent.CATEGORY_OPENABLE)
      .setType("*/*")
    startActivityForResult(intent, if (isImage) PICK_IMAGE else PICK_IDE)
  }

  @Deprecated("Deprecated in Java")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    val uri = data?.data ?: return
    if (resultCode != RESULT_OK) return
    when (requestCode) {
      PICK_IMAGE -> install(true, Installer.Source.of(this, uri))
      PICK_IDE -> install(false, Installer.Source.of(this, uri))
    }
  }

  private fun install(isImage: Boolean, source: Installer.Source) {
    if (busy) return
    busy = true
    stopLinux()
    refreshSetup()
    progress.visibility = View.VISIBLE
    thread(name = "install") {
      val error = runCatching {
        if (isImage) installer.installImage(source, ::onProgress) else installer.installIde(source, ::onProgress)
      }.exceptionOrNull()
      main.post {
        busy = false
        progress.visibility = View.GONE
        status.text = error?.let { "Could not install ${source.name}:\n${it.message ?: it}" }
          ?: "Installed ${source.name}. You can move or delete that file now."
        refreshSetup()
      }
    }
  }

  private fun onProgress(fraction: Float, text: String) = main.post {
    progress.isIndeterminate = fraction <= 0f
    progress.progress = (fraction * 1000).toInt()
    status.text = text
  }

  // ---------------------------------------------------------------- IDE screen

  private fun startIde() {
    ideShown = true
    setupView.visibility = View.GONE
    ideView.visibility = View.VISIBLE
    connecting.visibility = View.VISIBLE
    connecting.text = "Starting Linux and IntelliJ…"
    if (!LinuxService.running) {
      val density = resources.displayMetrics.density
      val width = (root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) / density
      val height = (root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels) / density
      startForegroundService(
        Intent(this, LinuxService::class.java)
          .putExtra(LinuxService.EXTRA_WIDTH, width.toInt().coerceAtLeast(640))
          .putExtra(LinuxService.EXTRA_HEIGHT, height.toInt().coerceAtLeast(480))
          // Desktop is sized in dp ("CSS pixels"), which is what 96 dpi means to X and IntelliJ.
          .putExtra(LinuxService.EXTRA_DPI, 96)
      )
    }
    waitForViewer(System.currentTimeMillis())
  }

  private fun waitForViewer(startedAt: Long) {
    thread(name = "wait-viewer") {
      val up = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", Linux.VIEWER_PORT), 500) }
      }.isSuccess
      main.post {
        if (!ideShown) return@post
        val elapsed = System.currentTimeMillis() - startedAt
        when {
          up -> webView.loadUrl(Linux.VIEWER_URL)
          elapsed > 180_000 || (!LinuxService.running && elapsed > 5_000) -> {
            connecting.text = "The Linux session did not come up. Open ⋯ › Show log to see why."
          }
          else -> main.postDelayed({ waitForViewer(startedAt) }, 1000)
        }
      }
    }
  }

  override fun toggleKeyboard() {
    keyboardShown = !keyboardShown
    // While typing on the soft keyboard, taps on the IDE must not steal focus (that would close it).
    webView.isFocusableInTouchMode = !keyboardShown
    if (keyboardShown) imeView.show() else {
      imeView.hide()
      webView.requestFocus()
      remote.focus()
    }
  }

  override fun toggleFullscreen() {
    prefs.edit().putBoolean("fullscreen", !prefs.getBoolean("fullscreen", false)).apply()
    applyFullscreen()
  }

  /** Native fullscreen: hides Android's status and navigation bars (swipe from an edge to peek). */
  private fun applyFullscreen() {
    val on = prefs.getBoolean("fullscreen", false)
    if (Build.VERSION.SDK_INT >= 30) {
      val controller = window.insetsController ?: return
      if (on) {
        controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsets.Type.systemBars())
      } else {
        controller.show(WindowInsets.Type.systemBars())
      }
    } else {
      @Suppress("DEPRECATION")
      window.decorView.systemUiVisibility = if (on) {
        View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
      } else 0
    }
  }

  override fun openMenu() {
    val keyBarVisible = prefs.getBoolean("keybar", true)
    val items = arrayOf(
      "Linux and IntelliJ: install or update",
      if (keyBarVisible) "Hide the key bar" else "Show the key bar",
      "Show log",
      "Restart Linux and IntelliJ",
      "Stop and close",
    )
    AlertDialog.Builder(this).setItems(items) { _, which ->
      when (which) {
        0 -> showSetup()
        1 -> { prefs.edit().putBoolean("keybar", !keyBarVisible).apply(); applyFoldLayout(null) }
        2 -> showLog()
        3 -> { stopLinux(); main.postDelayed({ startIde() }, 1500) }
        4 -> { stopLinux(); finishAndRemoveTask() }
      }
    }.show()
  }

  private var lastLayout: WindowLayoutInfo? = null

  private fun applyFoldLayout(info: WindowLayoutInfo?) {
    if (info != null) lastLayout = info
    val fold = lastLayout?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull()
    val flex = fold != null && fold.state == FoldingFeature.State.HALF_OPENED &&
      fold.orientation == FoldingFeature.Orientation.HORIZONTAL

    val webParams = webFrame.layoutParams as LinearLayout.LayoutParams
    val gapParams = hingeGap.layoutParams as LinearLayout.LayoutParams
    val panelParams = keyPanel.layoutParams as LinearLayout.LayoutParams
    if (flex) {
      // Laptop posture: IDE above the hinge, keys and shortcuts below it.
      val location = IntArray(2).also { ideView.getLocationInWindow(it) }
      val top = (fold!!.bounds.top - location[1]).coerceAtLeast(0)
      webParams.height = top
      webParams.weight = 0f
      gapParams.height = fold.bounds.height()
      panelParams.height = 0
      panelParams.weight = 1f
      keyPanel.visibility = View.VISIBLE
      keyPanel.setExpanded(true)
    } else {
      webParams.height = 0
      webParams.weight = 1f
      gapParams.height = 0
      panelParams.height = WRAP_CONTENT
      panelParams.weight = 0f
      keyPanel.visibility = if (prefs.getBoolean("keybar", true)) View.VISIBLE else View.GONE
      keyPanel.setExpanded(false)
    }
    webFrame.layoutParams = webParams
    hingeGap.layoutParams = gapParams
    keyPanel.layoutParams = panelParams
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    // Fold/unfold, cover screen <-> inner screen, DeX: the WebView resizes and the viewer asks the
    // X server for the new size. Re-apply fullscreen because some transitions reset it.
    applyFullscreen()
  }

  // ---------------------------------------------------------------- shared

  private fun stopLinux() {
    if (LinuxService.running) {
      startService(Intent(this, LinuxService::class.java).setAction(LinuxService.ACTION_STOP))
    }
    webView.loadUrl("about:blank")
  }

  private fun showLog() {
    val text = if (linux.log.isFile) linux.log.readText().takeLast(20_000) else "No log yet."
    val view = ScrollView(this).apply {
      addView(TextView(context).apply {
        this.text = text
        setTextIsSelectable(true)
        typeface = Typeface.MONOSPACE
        setPadding(32, 32, 32, 32)
      })
    }
    AlertDialog.Builder(this).setTitle("Linux log").setView(view).setPositiveButton("Close", null).show()
  }

  private fun hasStorageAccess(): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED

  private fun askStorageAccess() {
    if (Build.VERSION.SDK_INT >= 30) {
      runCatching {
        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
      }
    } else {
      requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
    }
  }

  private fun askNotificationPermission() {
    if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
  }

  @Deprecated("Deprecated in Java")
  override fun onBackPressed() {
    if (!ideShown) {
      if (linux.isReady() && !busy) startIde() else super.onBackPressed()
      return
    }
    if (keyboardShown) { toggleKeyboard(); return }
    // In DeX the right mouse button can arrive as Back; never let a stray Back close the IDE.
    val now = System.currentTimeMillis()
    if (now - lastBack < 1500) moveTaskToBack(true) else lastBack = now
  }

  // ---------------------------------------------------------------- views

  @SuppressLint("SetJavaScriptEnabled")
  private fun buildUi() {
    val dp = resources.displayMetrics.density
    root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#1E1F22")) }

    // IDE screen
    webView = WebView(this).apply {
      settings.javaScriptEnabled = true
      settings.domStorageEnabled = true
      settings.setSupportZoom(false)
      isFocusable = true
      isFocusableInTouchMode = true
      webChromeClient = WebChromeClient()
      webViewClient = object : WebViewClient() {
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
          if (request.isForMainFrame && ideShown) main.postDelayed({ if (ideShown) view.loadUrl(Linux.VIEWER_URL) }, 2000)
        }
      }
      addJavascriptInterface(object {
        @JavascriptInterface fun onConnected() = main.post { connecting.visibility = View.GONE }
        @JavascriptInterface fun onDisconnected() = main.post {
          connecting.visibility = View.VISIBLE
          connecting.text = "Reconnecting…"
        }
      }, "Android")
    }
    remote = Remote(webView)
    hingeGap = View(this).apply { setBackgroundColor(Color.BLACK) }
    keyPanel = KeyPanel(this, remote, this)
    imeView = ImeView(this, remote)
    connecting = TextView(this).apply {
      setTextColor(Color.parseColor("#BCBEC4"))
      gravity = Gravity.CENTER
      setBackgroundColor(Color.parseColor("#1E1F22"))
    }
    webFrame = FrameLayout(this).apply {
      addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
      addView(connecting, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
      addView(imeView, FrameLayout.LayoutParams(1, 1))
    }
    ideView = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      addView(webFrame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
      addView(hingeGap, LinearLayout.LayoutParams(MATCH_PARENT, 0))
      addView(keyPanel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }
    root.addView(ideView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

    // Setup screen
    val pad = (24 * dp).toInt()
    val column = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(pad, pad, pad, pad)
    }
    column.addView(text(getString(R.string.app_name), 26f, Color.WHITE))
    column.addView(text(
      "Linux and IntelliJ are installed from files you keep anywhere on the phone, SD card or USB drive, " +
        "so updating the app never downloads them again. Get both from this project's GitHub Releases. " +
        "Files named apkintellij-linux-*.tar.xz and ideaIC-*-aarch64.tar.gz in Download or an " +
        "APKintellij folder are found automatically.",
      14f, Color.parseColor("#BCBEC4"),
    ))
    column.addView(text("1. Linux image", 18f, Color.WHITE))
    imageStatus = text("", 14f, Color.parseColor("#BCBEC4"))
    column.addView(imageStatus)
    column.addView(button("Choose Linux image file…") { pick(true) })
    column.addView(text("2. IntelliJ IDEA (Linux ARM64 .tar.gz)", 18f, Color.WHITE))
    ideStatus = text("", 14f, Color.parseColor("#BCBEC4"))
    column.addView(ideStatus)
    column.addView(button("Choose IntelliJ file…") { pick(false) })
    foundView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    column.addView(foundView)
    progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
      max = 1000
      visibility = View.GONE
    }
    column.addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    status = text("", 14f, Color.parseColor("#BCBEC4"))
    column.addView(status)
    startButton = button("Start IntelliJ") { startIde() }
    column.addView(startButton)
    column.addView(button("Show log") { showLog() })
    setupView = ScrollView(this).apply { addView(column) }
    root.addView(setupView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

    if (Build.VERSION.SDK_INT >= 30) {
      // Draw edge to edge on every version (Android 15 forces it) and keep content clear of the
      // status bar, navigation bar, camera cutout and soft keyboard ourselves.
      window.setDecorFitsSystemWindows(false)
      root.setOnApplyWindowInsetsListener { v, insets ->
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val ime = insets.getInsets(WindowInsets.Type.ime())
        v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
        if (ideShown) {
          // Soft keyboard up: keep the Linux desktop size (scroll instead) so IntelliJ doesn't re-layout.
          val imeVisible = insets.isVisible(WindowInsets.Type.ime())
          keyboardShown = imeVisible
          webView.isFocusableInTouchMode = !imeVisible
          remote.setResize(!imeVisible)
        }
        main.post { applyFoldLayout(null) }
        insets
      }
    }

    setContentView(root)
    applyFoldLayout(null)
  }

  private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
    text = value
    textSize = size
    setTextColor(color)
    setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
  }

  private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
    text = label
    isAllCaps = false
    setOnClickListener { onClick() }
  }

  companion object {
    private const val PICK_IMAGE = 1
    private const val PICK_IDE = 2
  }
}

/** Finds archives the user dropped in the usual places, newest first. */
object Archives {
  class Found(val file: File, val isImage: Boolean)

  private val imagePattern = Regex("""apkintellij-linux-.*\.tar(\.xz|\.gz)?""")
  private val idePattern = Regex("""(idea|ideaIC|ideaIU|apkintellij-ide)[-.].*(aarch64|arm64).*\.tar\.gz""")

  fun find(): List<Found> {
    val storage = Environment.getExternalStorageDirectory()
    val dirs = listOf(
      File(storage, "APKintellij"),
      File(storage, Environment.DIRECTORY_DOWNLOADS),
      File(storage, "${Environment.DIRECTORY_DOWNLOADS}/APKintellij"),
    )
    val files = dirs.flatMap { it.listFiles()?.toList().orEmpty() }.filter { it.isFile }
    val images = files.filter { imagePattern.matches(it.name) }.maxByOrNull { it.lastModified() }
    val ides = files.filter { idePattern.matches(it.name) }.maxByOrNull { it.lastModified() }
    return listOfNotNull(images?.let { Found(it, true) }, ides?.let { Found(it, false) })
  }
}
