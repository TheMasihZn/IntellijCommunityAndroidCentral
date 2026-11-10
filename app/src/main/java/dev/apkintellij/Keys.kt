package dev.apkintellij

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/** Sends X11 keysyms to the IDE through the viewer page (viewer.html, `window.apk`). */
class Remote(private val webView: WebView) {
  private val sticky = mutableSetOf<Int>()
  var onStickyChanged: () -> Unit = {}

  fun isSticky(modifier: Int) = modifier in sticky

  fun toggleSticky(modifier: Int) {
    if (!sticky.remove(modifier)) sticky += modifier
    onStickyChanged()
  }

  fun key(keysym: Int, down: Boolean) = webView.evaluateJavascript("window.apk && apk.key($keysym, $down)", null)

  /** Presses [keysym] with [modifiers] held, plus any armed sticky modifiers, which then release. */
  fun press(keysym: Int, vararg modifiers: Int) {
    val held = (sticky + modifiers.toList()).distinct()
    held.forEach { key(it, true) }
    key(keysym, true)
    key(keysym, false)
    held.asReversed().forEach { key(it, false) }
    if (sticky.isNotEmpty()) {
      sticky.clear()
      onStickyChanged()
    }
  }

  fun type(text: CharSequence) {
    var i = 0
    while (i < text.length) {
      val cp = Character.codePointAt(text, i)
      press(Keysym.forCodePoint(cp))
      i += Character.charCount(cp)
    }
  }

  fun setResize(on: Boolean) = webView.evaluateJavascript("window.apk && apk.setResize($on)", null)
  fun focus() = webView.evaluateJavascript("window.apk && apk.focus()", null)
}

object Keysym {
  const val BACKSPACE = 0xff08
  const val TAB = 0xff09
  const val ENTER = 0xff0d
  const val ESCAPE = 0xff1b
  const val DELETE = 0xffff
  const val HOME = 0xff50
  const val LEFT = 0xff51
  const val UP = 0xff52
  const val RIGHT = 0xff53
  const val DOWN = 0xff54
  const val PAGE_UP = 0xff55
  const val PAGE_DOWN = 0xff56
  const val END = 0xff57
  const val F1 = 0xffbe
  const val SHIFT = 0xffe1
  const val CTRL = 0xffe3
  const val ALT = 0xffe9
  const val SPACE = 0x20

  fun f(n: Int) = F1 + n - 1

  fun forCodePoint(cp: Int): Int = when {
    cp == '\n'.code -> ENTER
    cp == '\t'.code -> TAB
    cp in 0x20..0x7e || cp in 0xa0..0xff -> cp
    else -> 0x01000000 + cp
  }

  /** Android hardware/IME key codes the soft keyboard sends as key events. */
  fun forKeyCode(keyCode: Int): Int? = when (keyCode) {
    KeyEvent.KEYCODE_DEL -> BACKSPACE
    KeyEvent.KEYCODE_FORWARD_DEL -> DELETE
    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> ENTER
    KeyEvent.KEYCODE_TAB -> TAB
    KeyEvent.KEYCODE_ESCAPE -> ESCAPE
    KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
    KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
    KeyEvent.KEYCODE_DPAD_UP -> UP
    KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
    KeyEvent.KEYCODE_MOVE_HOME -> HOME
    KeyEvent.KEYCODE_MOVE_END -> END
    KeyEvent.KEYCODE_PAGE_UP -> PAGE_UP
    KeyEvent.KEYCODE_PAGE_DOWN -> PAGE_DOWN
    else -> null
  }
}

/**
 * Invisible view that owns the soft keyboard. Android keyboards can't type into the VNC canvas,
 * so this takes their input and turns it into key presses for IntelliJ. Suggestions and
 * autocorrect are off (password-style field), which also stops Samsung Keyboard from composing.
 */
class ImeView(context: Context, private val remote: Remote) : View(context) {
  private var composing = ""

  init {
    isFocusable = true
    isFocusableInTouchMode = true
  }

  override fun onCheckIsTextEditor() = true

  override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
    outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
      InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
      EditorInfo.IME_ACTION_NONE
    return object : BaseInputConnection(this, false) {
      override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        replaceComposing("")
        remote.type(text)
        return true
      }

      override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        replaceComposing(text.toString())
        return true
      }

      override fun finishComposingText(): Boolean {
        composing = ""
        return true
      }

      override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        repeat(beforeLength) { remote.press(Keysym.BACKSPACE) }
        repeat(afterLength) { remote.press(Keysym.DELETE) }
        return true
      }

      override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
          val keysym = Keysym.forKeyCode(event.keyCode)
          if (keysym != null) remote.press(keysym)
          else if (event.unicodeChar != 0) remote.press(Keysym.forCodePoint(event.unicodeChar))
        }
        return true
      }
    }
  }

  private fun replaceComposing(text: String) {
    // Keyboards that still compose: retype the changed word as it grows.
    val common = composing.commonPrefixWith(text).length
    repeat(composing.length - common) { remote.press(Keysym.BACKSPACE) }
    remote.type(text.substring(common))
    composing = text
  }

  fun show() {
    requestFocus()
    context.getSystemService(InputMethodManager::class.java).showSoftInput(this, 0)
  }

  fun hide() {
    context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
    composing = ""
  }
}

/**
 * Extra keys a phone keyboard lacks, plus common IntelliJ shortcuts. Ctrl, Alt and Shift are
 * sticky: tap Ctrl, then a key on the bar or the keyboard.
 */
class KeyPanel(context: Context, private val remote: Remote, private val actions: Actions) : LinearLayout(context) {
  interface Actions {
    fun toggleKeyboard()
    fun toggleFullscreen()
    fun openMenu()
  }

  private val modifierButtons = mutableMapOf<Int, TextView>()
  private val shortcuts: GridLayout
  private val dp = resources.displayMetrics.density

  init {
    orientation = VERTICAL
    setBackgroundColor(Color.parseColor("#2B2D30"))

    val bar = LinearLayout(context).apply { orientation = HORIZONTAL }
    bar.addView(key("⌨") { actions.toggleKeyboard() })
    bar.addView(key("Esc") { remote.press(Keysym.ESCAPE) })
    bar.addView(key("Tab") { remote.press(Keysym.TAB) })
    bar.addView(modifier("Ctrl", Keysym.CTRL))
    bar.addView(modifier("Alt", Keysym.ALT))
    bar.addView(modifier("Shift", Keysym.SHIFT))
    bar.addView(key("←") { remote.press(Keysym.LEFT) })
    bar.addView(key("↑") { remote.press(Keysym.UP) })
    bar.addView(key("↓") { remote.press(Keysym.DOWN) })
    bar.addView(key("→") { remote.press(Keysym.RIGHT) })
    bar.addView(key("Home") { remote.press(Keysym.HOME) })
    bar.addView(key("End") { remote.press(Keysym.END) })
    bar.addView(key("PgUp") { remote.press(Keysym.PAGE_UP) })
    bar.addView(key("PgDn") { remote.press(Keysym.PAGE_DOWN) })
    bar.addView(key("Del") { remote.press(Keysym.DELETE) })
    for (n in 1..12) bar.addView(key("F$n") { remote.press(Keysym.f(n)) })
    bar.addView(key("⛶") { actions.toggleFullscreen() })
    bar.addView(key("⋯") { actions.openMenu() })
    addView(HorizontalScrollView(context).apply {
      isHorizontalScrollBarEnabled = false
      addView(bar)
    })

    // Shown in flex mode, on the bottom half of the Fold.
    shortcuts = GridLayout(context).apply {
      columnCount = 4
      useDefaultMargins = false
      visibility = GONE
    }
    shortcut("Search everywhere") { remote.press(Keysym.SHIFT); remote.press(Keysym.SHIFT) }
    shortcut("Find action") { remote.press('a'.code, Keysym.CTRL, Keysym.SHIFT) }
    shortcut("Complete") { remote.press(Keysym.SPACE, Keysym.CTRL) }
    shortcut("Quick fix") { remote.press(Keysym.ENTER, Keysym.ALT) }
    shortcut("Go to declaration") { remote.press('b'.code, Keysym.CTRL) }
    shortcut("Find in files") { remote.press('f'.code, Keysym.CTRL, Keysym.SHIFT) }
    shortcut("Recent files") { remote.press('e'.code, Keysym.CTRL) }
    shortcut("Reformat") { remote.press('l'.code, Keysym.CTRL, Keysym.ALT) }
    shortcut("Run") { remote.press(Keysym.f(10), Keysym.SHIFT) }
    shortcut("Debug") { remote.press(Keysym.f(9), Keysym.SHIFT) }
    shortcut("Comment line") { remote.press('/'.code, Keysym.CTRL) }
    shortcut("Undo") { remote.press('z'.code, Keysym.CTRL) }
    shortcut("Copy") { remote.press('c'.code, Keysym.CTRL) }
    shortcut("Paste") { remote.press('v'.code, Keysym.CTRL) }
    shortcut("Save all") { remote.press('s'.code, Keysym.CTRL) }
    shortcut("Terminal") { remote.press(Keysym.f(12), Keysym.ALT) }
    addView(shortcuts, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

    remote.onStickyChanged = { refreshModifiers() }
  }

  fun setExpanded(expanded: Boolean) {
    shortcuts.visibility = if (expanded) VISIBLE else GONE
  }

  private fun key(label: String, onClick: () -> Unit) = TextView(context).apply {
    text = label
    setTextColor(Color.parseColor("#DFE1E5"))
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    gravity = Gravity.CENTER
    minWidth = (44 * dp).toInt()
    minHeight = (40 * dp).toInt()
    setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
    isFocusable = false // keep keyboard focus in the IDE
    setOnClickListener { onClick() }
  }

  private fun modifier(label: String, keysym: Int) = key(label) { remote.toggleSticky(keysym) }
    .also { modifierButtons[keysym] = it }

  private fun refreshModifiers() {
    for ((keysym, view) in modifierButtons) {
      view.setBackgroundColor(if (remote.isSticky(keysym)) Color.parseColor("#3574F0") else Color.TRANSPARENT)
    }
  }

  private fun shortcut(label: String, onClick: () -> Unit) {
    shortcuts.addView(key(label, onClick).apply {
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
      minHeight = (52 * dp).toInt()
      layoutParams = GridLayout.LayoutParams(
        GridLayout.spec(GridLayout.UNDEFINED, 1f),
        GridLayout.spec(GridLayout.UNDEFINED, 1f),
      ).apply { width = 0 }
    })
  }
}
