package dev.apkintellij

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Our own on-screen keyboard, split like Samsung Keyboard's split layout, that floats over the IDE
 * instead of pushing it up. The middle is empty: the IDE's text stays visible there and taps in
 * it go straight to the IDE. The halves themselves can be made see-through too.
 *
 * Settings (⚙ on the keyboard, or ⋯ › Split keyboard settings): opacity of the halves, width of the
 * see-through middle, and key height. They are kept in [prefs].
 */
class SplitKeyboard(
  context: Context,
  private val remote: Remote,
  private val prefs: SharedPreferences,
  private val onHide: () -> Unit,
) : FrameLayout(context) {
  private val dp = resources.displayMetrics.density
  private val main = Handler(Looper.getMainLooper())
  private val left = half()
  private val right = half()
  private val charKeys = mutableListOf<Pair<TextView, Char>>()
  private val modifierKeys = mutableListOf<Pair<TextView, Int>>()

  var opacity: Float
    get() = prefs.getInt(PREF_OPACITY, 70) / 100f
    set(value) { prefs.edit().putInt(PREF_OPACITY, (value * 100).toInt()).apply(); applySettings() }

  /** Width of the see-through middle, as a fraction of the keyboard's width. */
  var gap: Float
    get() = prefs.getInt(PREF_GAP, 30) / 100f
    set(value) { prefs.edit().putInt(PREF_GAP, (value * 100).toInt()).apply(); applySettings() }

  var keyHeightDp: Int
    get() = prefs.getInt(PREF_KEY_HEIGHT, 40)
    set(value) { prefs.edit().putInt(PREF_KEY_HEIGHT, value).apply(); applySettings() }

  init {
    // Not clickable itself, so touches that miss both halves fall through to the IDE below.
    isClickable = false
    addView(left, LayoutParams(0, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
    addView(right, LayoutParams(0, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))

    row(left, chars("`12345"))
    row(right, chars("67890-=") + Key("⌫", Keysym.BACKSPACE, weight = 1.5f, repeat = true))
    row(left, listOf(Key("Tab", Keysym.TAB, weight = 1.4f)) + chars("qwert"))
    row(right, chars("yuiop[]\\"))
    row(left, listOf(Key("Esc", Keysym.ESCAPE, weight = 1.6f)) + chars("asdfg"))
    row(right, chars("hjkl;'") + Key("⏎", Keysym.ENTER, weight = 1.6f))
    row(left, listOf(Key("⇧", Keysym.SHIFT, weight = 2f, modifier = true)) + chars("zxcvb"))
    row(right, chars("nm,./") + Key("⇧", Keysym.SHIFT, weight = 1.6f, modifier = true))
    row(left, listOf(
      Key("Ctrl", Keysym.CTRL, weight = 1.3f, modifier = true),
      Key("Alt", Keysym.ALT, weight = 1.3f, modifier = true),
      Key("⚙", action = { showSettings() }),
      Key("␣", Keysym.SPACE, weight = 2.4f),
    ))
    row(right, listOf(
      Key("␣", Keysym.SPACE, weight = 2f),
      Key("←", Keysym.LEFT, repeat = true),
      Key("↑", Keysym.UP, repeat = true),
      Key("↓", Keysym.DOWN, repeat = true),
      Key("→", Keysym.RIGHT, repeat = true),
      Key("▾", action = onHide),
    ))

    remote.stickyListeners += { refreshModifiers() }
    applySettings()
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    // Changing layout params during layout is ignored, so apply on the next frame.
    post { applySettings() }
  }

  private fun applySettings() {
    val halfWidth = (width * (1f - gap) / 2f).toInt().coerceAtLeast(0)
    for (half in listOf(left, right)) {
      half.layoutParams = (half.layoutParams as LayoutParams).apply { width = halfWidth }
      half.alpha = opacity.coerceIn(MIN_OPACITY, 1f)
      for (i in 0 until half.childCount) {
        val row = half.getChildAt(i)
        row.layoutParams = (row.layoutParams as LinearLayout.LayoutParams).apply { height = (keyHeightDp * dp).toInt() }
      }
    }
  }

  private class Key(
    val label: String,
    val keysym: Int = 0,
    val weight: Float = 1f,
    val repeat: Boolean = false,
    val modifier: Boolean = false,
    val char: Char? = null,
    val action: (() -> Unit)? = null,
  )

  private fun chars(s: String) = s.map { Key(it.toString(), Keysym.forCodePoint(it.code), char = it) }

  private fun half() = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    background = GradientDrawable().apply {
      setColor(Color.parseColor("#2B2D30"))
      cornerRadius = 10 * resources.displayMetrics.density
    }
    val p = (3 * resources.displayMetrics.density).toInt()
    setPadding(p, p, p, p)
    isClickable = true // swallow taps between keys so they don't reach the IDE
  }

  private fun row(half: LinearLayout, keys: List<Key>) {
    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    for (key in keys) row.addView(keyView(key), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, key.weight))
    half.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (keyHeightDp * dp).toInt()))
  }

  private fun keyView(key: Key): View {
    val m = (2 * dp).toInt()
    val view = TextView(context).apply {
      text = key.label
      gravity = Gravity.CENTER
      setTextColor(Color.parseColor("#DFE1E5"))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, if (key.label.length > 1) 13f else 17f)
      isFocusable = false // keep keyboard focus in the IDE
      background = keyBackground(false)
    }
    val wrapper = FrameLayout(context).apply {
      setPadding(m, m, m, m)
      addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
    key.char?.let { charKeys += view to it }
    if (key.modifier) modifierKeys += view to key.keysym

    val repeater = object : Runnable {
      override fun run() {
        fire(key)
        main.postDelayed(this, REPEAT_EVERY_MS)
      }
    }
    view.setOnTouchListener { v, event ->
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
          if (!key.modifier) v.background = keyBackground(true)
          fire(key)
          if (key.repeat) main.postDelayed(repeater, REPEAT_AFTER_MS)
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
          main.removeCallbacks(repeater)
          if (!key.modifier) v.background = keyBackground(false)
        }
      }
      true
    }
    return wrapper
  }

  private fun fire(key: Key) {
    when {
      key.action != null -> key.action.invoke()
      key.modifier -> remote.toggleSticky(key.keysym)
      // Send the shifted character itself ("A", "!"): VNC servers match keysyms exactly.
      key.char != null && remote.isSticky(Keysym.SHIFT) -> remote.press(Keysym.forCodePoint(shifted(key.char).code))
      else -> remote.press(key.keysym)
    }
  }

  private fun keyBackground(pressed: Boolean) = GradientDrawable().apply {
    setColor(Color.parseColor(if (pressed) "#4E5157" else "#393B40"))
    cornerRadius = 6 * dp
  }

  private fun refreshModifiers() {
    for ((view, keysym) in modifierKeys) {
      view.background = GradientDrawable().apply {
        setColor(if (remote.isSticky(keysym)) Color.parseColor("#3574F0") else Color.parseColor("#393B40"))
        cornerRadius = 6 * dp
      }
    }
    val upper = remote.isSticky(Keysym.SHIFT)
    for ((view, c) in charKeys) view.text = (if (upper) shifted(c) else c).toString()
  }

  fun showSettings() {
    val column = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      val p = (20 * dp).toInt()
      setPadding(p, p / 2, p, 0)
    }
    fun slider(label: String, min: Int, max: Int, value: Int, unit: String, onChange: (Int) -> Unit) {
      val title = TextView(context).apply { text = "$label: $value$unit" }
      column.addView(title)
      column.addView(SeekBar(context).apply {
        this.max = max - min
        progress = value - min
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
          override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
            title.text = "$label: ${progress + min}$unit"
            onChange(progress + min)
          }
          override fun onStartTrackingTouch(bar: SeekBar) {}
          override fun onStopTrackingTouch(bar: SeekBar) {}
        })
      })
    }
    slider("Key opacity", (MIN_OPACITY * 100).toInt(), 100, (opacity * 100).toInt(), "%") { opacity = it / 100f }
    slider("See-through middle", 0, 60, (gap * 100).toInt(), "% of width") { gap = it / 100f }
    slider("Key height", 28, 64, keyHeightDp, " dp") { keyHeightDp = it }
    AlertDialog.Builder(context).setTitle("Split keyboard").setView(column).setPositiveButton("Done", null).show()
  }

  companion object {
    private const val UNSHIFTED = "`1234567890-=[]\\;',./"
    private const val SHIFTED = "~!@#$%^&*()_+{}|:\"<>?"

    /** The character Shift gives on a US layout. */
    fun shifted(c: Char): Char = UNSHIFTED.indexOf(c).let { if (it >= 0) SHIFTED[it] else c.uppercaseChar() }

    private const val PREF_OPACITY = "split_opacity"
    private const val PREF_GAP = "split_gap"
    private const val PREF_KEY_HEIGHT = "split_key_height"
    private const val MIN_OPACITY = 0.15f
    private const val REPEAT_AFTER_MS = 400L
    private const val REPEAT_EVERY_MS = 50L
  }
}
