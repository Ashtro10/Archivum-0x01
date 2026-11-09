package ai.clu.ui

import ai.clu.CluApp
import ai.clu.core.security.CredentialStore
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.ViewGroup
import android.widget.*
import kotlinx.coroutines.*

object C {
    // Graphite and black, lit by restrained cyan / ice blue (TRON-like), with CLU's teal kept for continuity.
    val BG = Color.parseColor("#020406")
    val SURFACE = Color.parseColor("#C80A1016")
    val SURFACE2 = Color.parseColor("#D9101820")
    val BORDER = Color.parseColor("#22303C")
    val BORDER_HI = Color.parseColor("#3A5464")
    val TEXT = Color.parseColor("#E4EEF4")
    val MUTED = Color.parseColor("#7D8D9B")
    val ACCENT = Color.parseColor("#5CE1FF")      // electric cyan
    val ACCENT2 = Color.parseColor("#A8E8FF")     // ice blue
    val ACCENT3 = Color.parseColor("#1E7BFF")     // electric blue
    val TEAL = Color.parseColor("#3FD0C0")        // CLU's original teal
    val OK = Color.parseColor("#3FD0A0")
    val WARN = Color.parseColor("#E3A72F")
    val ERROR = Color.parseColor("#FF5C5C")
}

/** Kept for screens that still ask for the old helpers: now the chamfered panel style. */
fun glassBg(radius: Float = 200f): android.graphics.drawable.Drawable = Panel(C.SURFACE2, C.BORDER_HI, 8f, C.ACCENT, android.content.res.Resources.getSystem().displayMetrics.density)
fun accentBg(radius: Float = 200f): android.graphics.drawable.Drawable = Panel(Color.argb(200, 6, 34, 48), Color.argb(230, 92, 225, 255), 8f, C.ACCENT,
    android.content.res.Resources.getSystem().displayMetrics.density, glow = true, fill2 = Color.argb(220, 4, 18, 30))

fun Context.dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun roundBg(color: Int, radius: Float = 24f, stroke: Int? = null): android.graphics.drawable.Drawable =
    Panel(color, stroke ?: Color.TRANSPARENT, (radius / 3f).coerceIn(4f, 12f), C.ACCENT, android.content.res.Resources.getSystem().displayMetrics.density)

/** Base activity: dark theme, coroutine scope, access to the Clu app. */
abstract class BaseActivity : Activity() {
    val app get() = application as CluApp
    val core get() = app.core
    val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge on every Android version (Android 15+ enforces it for targetSdk 35): the app lays itself out
        // around the status bar, display cutout, gesture bar and keyboard via applySystemInsets() below.
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.decorView.setBackgroundColor(C.BG)
        Motion.reduced = runCatching { ai.clu.core.config.UiPolicy.reducedMotion(core.settings) }.getOrDefault(false)
        // Screens hand over with a short fade; each screen's own content then rises in (see onPostCreate).
        @Suppress("DEPRECATION") overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /** Set by screens that run their own entrance (the chat and voice screens). */
    open val ownEntrance = false

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (!ownEntrance && savedInstanceState == null) (findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as? ViewGroup)?.let { root ->
            // Title bar opens out of a line; the rest rises in, column by column.
            for (i in 0 until root.childCount) { val v = root.getChildAt(i); if (i == 0) Motion.expand(v, 0, 360) else Motion.rise(v, 120L + 50 * i) }
            (root.getChildAt(1) as? ScrollView)?.getChildAt(0)?.let { (it as? ViewGroup)?.let { body -> Motion.stagger(body, 140, 35) } }
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION") overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /**
     * Pads [root] so nothing is drawn under the status bar, cutout, gesture bar or keyboard. [onIme] reports whether the
     * keyboard is visible (the chat screen hides its bottom navigation then, so the composer sits on the keyboard).
     */
    fun applySystemInsets(root: View, onIme: (Boolean) -> Unit = {}) {
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            onIme(insets.isVisible(WindowInsets.Type.ime()) && ime.bottom > 0)
            WindowInsets.CONSUMED
        }
        root.requestApplyInsets()
    }

    override fun onResume() { super.onResume(); app.inForeground = true }
    override fun onPause() { super.onPause(); app.inForeground = false }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    /** Standard scrolling form screen with a title bar. Call again to rebuild. */
    fun screen(title: String, build: LinearLayout.() -> Unit) {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        root.addView(titleBar(title))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(32)) }
        body.build()
        root.addView(ScrollView(this).apply { addView(body); isFillViewport = true }, LinearLayout.LayoutParams(-1, 0, 1f))
        applySystemInsets(root)
        setContentView(root)
    }

    fun titleBar(title: String, actions: LinearLayout.() -> Unit = {}): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(10)); background = HairlineBg(C.ACCENT, resources.displayMetrics.density)
        addView(TextView(context).apply { text = "‹"; textSize = 24f; setTextColor(C.ACCENT2); gravity = Gravity.CENTER; background = panel(C.SURFACE2, C.BORDER_HI, 8f); contentDescription = "Back"
            Motion.press(this); setOnClickListener { Sfx.play(SfxSynth.Sound.CLICK); finish() } }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = "CLU // " + title.uppercase(); label(C.ACCENT, 9.5f); alpha = 0.8f; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            addView(TextView(context).apply { text = title; textSize = 21f; setTextColor(C.TEXT); typeface = Type.heading; letterSpacing = 0.04f })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions()
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    fun confirm(title: String, msg: String, ok: String = "OK", onOk: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton(ok) { _, _ -> onOk() }.setNegativeButton("Cancel", null).show().styled()
    }

    fun prompt(title: String, initial: String = "", hint: String = "", multiline: Boolean = false, numeric: Boolean = false, password: Boolean = false, onOk: (String) -> Unit) {
        val e = EditText(this).apply {
            setText(initial); this.hint = hint
            inputType = when {
                numeric && password -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (multiline) { minLines = 3; gravity = Gravity.TOP }
        }
        AlertDialog.Builder(this).setTitle(title).setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(e) })
            .setPositiveButton("OK") { _, _ -> onOk(e.text.toString()) }.setNegativeButton("Cancel", null).show().styled()
    }

    fun choose(title: String, items: List<String>, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setItems(items.toTypedArray()) { _, i -> onPick(i) }.show().styled()
    }

    /** Dialogs in CLU's style: a chamfered graphite panel with the cyan hairline, opening like the other panels. */
    fun AlertDialog.styled(): AlertDialog = apply {
        window?.setBackgroundDrawable(panel(Color.parseColor("#F00A1016"), C.BORDER_HI, 14f, ticks = true))
        window?.decorView?.let { Motion.expand(it, 0, 300) }
        Sfx.play(SfxSynth.Sound.ACTIVATE)
    }

    /**
     * Owner verification for sensitive actions: PIN (hashed, with temporary lockout) or the device's own screen lock.
     * Calls [onResult] with true only after successful verification, and records the time for the auth window.
     */
    fun verifyOwner(reason: String, onResult: (Boolean) -> Unit) {
        val creds = core.credentials
        // A refused or missing biometric service (some vendor builds) must never crash: the device lock is then just unavailable.
        val deviceLockAvailable = runCatching {
            getSystemService(BiometricManager::class.java)
                ?.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL or BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        }.onFailure { core.diag.w("auth", "device lock check failed: ${it.message}") }.getOrDefault(false)
        fun success() { app.verifiedAt = System.currentTimeMillis(); onResult(true) }
        fun deviceLock() = runCatching {
            BiometricPrompt.Builder(this).setTitle("Verify it's you").setSubtitle(reason)
                .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL or BiometricManager.Authenticators.BIOMETRIC_STRONG).build()
                .authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = success()
                    override fun onAuthenticationError(code: Int, msg: CharSequence) { toast(msg.toString()); onResult(false) }
                })
        }.onFailure { core.diag.w("auth", "device lock prompt failed: ${it.message}"); toast("The device lock could not be used here. Set a Clu PIN in People & security."); onResult(false) }
        if (System.currentTimeMillis() - app.verifiedAt < core.settings.int(ai.clu.core.config.Settings.AUTH_WINDOW) * 1000L) { onResult(true); return }
        if (!creds.isSet(CredentialStore.Kind.PIN)) {
            if (deviceLockAvailable) deviceLock()
            else AlertDialog.Builder(this).setTitle("Verification unavailable")
                .setMessage("Set a Clu PIN (People & security) or a device screen lock to protect sensitive actions.").setPositiveButton("OK") { _, _ -> onResult(false) }.show()
            return
        }
        val e = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; hint = "PIN" }
        val b = AlertDialog.Builder(this).setTitle("Enter your Clu PIN").setMessage(reason).setView(FrameLayout(this).apply { setPadding(dp(20), 0, dp(20), 0); addView(e) })
            .setPositiveButton("Verify") { _, _ ->
                when (val r = creds.verify(CredentialStore.Kind.PIN, e.text.toString())) {
                    CredentialStore.VerifyResult.Ok -> success()
                    is CredentialStore.VerifyResult.LockedOut -> { toast("Too many attempts. Try again in ${r.retryInMs / 1000} s or use the device lock."); onResult(false) }
                    else -> { toast("Wrong PIN"); onResult(false) }
                }
            }.setNegativeButton("Cancel") { _, _ -> onResult(false) }
        if (deviceLockAvailable) b.setNeutralButton("Use device lock") { _, _ -> deviceLock() }
        b.show()
    }
}

// ------------------------------------------------------------------ view builders
/** Section header: a short cyan tick, then the title in spaced capitals. */
fun LinearLayout.h1(t: String) = addView(TextView(context).apply {
    text = t; textSize = 13f; setTextColor(C.ACCENT2); typeface = Type.headingBold; letterSpacing = 0.16f; isAllCaps = true
    setPadding(0, context.dp(18), 0, context.dp(6)); compoundDrawablePadding = context.dp(8)
    setCompoundDrawablesRelative(TickDrawable(context.resources.displayMetrics.density), null, null, null)
})
fun LinearLayout.p(t: String, color: Int = C.MUTED, size: Float = 14f): TextView = TextView(context).apply { text = t; textSize = size; setTextColor(color); setPadding(0, context.dp(2), 0, context.dp(4)); setTextIsSelectable(true) }.also { addView(it) }

fun LinearLayout.button(t: String, primary: Boolean = false, onClick: () -> Unit): Button = Button(context).apply {
    text = t; isAllCaps = false; setTextColor(if (primary) C.ACCENT2 else C.TEXT); typeface = Type.heading; textSize = 15f; letterSpacing = 0.03f
    background = if (primary) context.accentPanel() else context.panel(C.SURFACE2, C.BORDER_HI); stateListAnimator = null
    minHeight = context.dp(48); Motion.press(this)
    setOnClickListener { Sfx.play(SfxSynth.Sound.CLICK); onClick() }
}.also { addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) }) }

fun LinearLayout.row(build: LinearLayout.() -> Unit): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; build()
}.also { addView(it, LinearLayout.LayoutParams(-1, -2)) }

fun LinearLayout.smallButton(t: String, onClick: () -> Unit): Button = Button(context).apply {
    text = t; isAllCaps = false; textSize = 13f; setTextColor(C.ACCENT2); typeface = Type.heading; background = context.panel(C.SURFACE2, C.BORDER_HI, 6f); minWidth = 0; minimumWidth = 0
    stateListAnimator = null; Motion.press(this)
    setPadding(context.dp(12), 0, context.dp(12), 0); setOnClickListener { Sfx.play(SfxSynth.Sound.CLICK); onClick() }
}.also { addView(it, LinearLayout.LayoutParams(-2, context.dp(38)).apply { marginEnd = context.dp(6); topMargin = context.dp(4) }) }

/**
 * Makes a container (e.g. a [card] of [p] lines) one tappable control. [p] text is selectable, and a selectable
 * TextView is clickable, so it consumed every tap on the text and the container's listener never ran (taps only worked
 * on the padding). Here the text stops taking touches, the whole area is a ≥48 dp target with touch feedback, and
 * accessibility services announce it as one button.
 */
fun LinearLayout.tappable(onClick: () -> Unit): LinearLayout = apply {
    fun release(v: View) {
        if (v is TextView) { v.setTextIsSelectable(false); v.isClickable = false; v.isLongClickable = false; v.isFocusable = false }
        if (v is ViewGroup) for (i in 0 until v.childCount) release(v.getChildAt(i))
    }
    for (i in 0 until childCount) release(getChildAt(i))
    isClickable = true; isFocusable = true
    minimumHeight = context.dp(48)
    foreground = android.util.TypedValue().let { tv ->
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) context.getDrawable(tv.resourceId) else null }
    contentDescription = (0 until childCount).mapNotNull { (getChildAt(it) as? TextView)?.text?.toString() }.joinToString(". ")
    Motion.press(this)
    setOnClickListener { Sfx.play(SfxSynth.Sound.CLICK); onClick() }
}

fun LinearLayout.card(build: LinearLayout.() -> Unit): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL; background = context.panel(C.SURFACE, C.BORDER, 12f); setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12)); build()
}.also { addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) }) }

fun LinearLayout.field(label: String, value: String, multiline: Boolean = false, hint: String = ""): EditText {
    p(label, C.TEXT, 13f)
    return EditText(context).apply {
        setText(value); this.hint = hint; setTextColor(C.TEXT); setHintTextColor(C.MUTED); background = context.panel(C.SURFACE2, C.BORDER_HI, 6f)
        textCursorDrawable?.setTint(C.ACCENT); highlightColor = Color.argb(90, 92, 225, 255)
        setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
        if (multiline) { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 3; gravity = Gravity.TOP }
    }.also { addView(it, LinearLayout.LayoutParams(-1, -2)) }
}

fun LinearLayout.check(label: String, checked: Boolean, onChange: (Boolean) -> Unit = {}): Switch = Switch(context).apply {
    text = label; isChecked = checked; setTextColor(C.TEXT); setPadding(0, context.dp(8), 0, context.dp(8)); minHeight = context.dp(48)
    switchTints().let { (thumb, track) -> thumbTintList = thumb; trackTintList = track }
    setOnCheckedChangeListener { _, v -> Sfx.play(SfxSynth.Sound.CLICK); onChange(v) }
}.also { addView(it, LinearLayout.LayoutParams(-1, -2)) }

fun LinearLayout.spinner(options: List<String>, selected: Int, onChange: (Int) -> Unit = {}): Spinner = Spinner(context).apply {
    adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, options) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup) = (super.getView(position, convertView, parent) as TextView).apply { setTextColor(C.TEXT) }
    }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    setSelection(selected.coerceAtLeast(0), false)
    background = context.panel(C.SURFACE2, C.BORDER_HI, 6f)
    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        var first = true
        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { if (first) { first = false; return }; onChange(pos) }
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }
}.also { addView(it, LinearLayout.LayoutParams(-1, context.dp(44))) }

/** ListView adapter for programmatic rows. */
class RowAdapter<T>(var items: List<T>, private val bind: (T, Context) -> View) : BaseAdapter() {
    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = bind(items[position], parent.context)
    fun update(newItems: List<T>) { items = newItems; notifyDataSetChanged() }
}

fun fmtTime(ms: Long): String = java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.ENGLISH).format(java.util.Date(ms))
fun fmtBytes(b: Long): String = when { b >= 1e9 -> "%.2f GB".format(b / 1e9); b >= 1e6 -> "%.1f MB".format(b / 1e6); else -> "${b / 1000} KB" }

/** The cyan tick before section headers. */
class TickDrawable(private val d: Float) : android.graphics.drawable.Drawable() {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = C.ACCENT }
    init { setBounds(0, 0, (3 * d).toInt(), (12 * d).toInt()) }
    override fun draw(c: android.graphics.Canvas) { c.drawRect(bounds, p) }
    override fun getIntrinsicWidth() = (3 * d).toInt(); override fun getIntrinsicHeight() = (12 * d).toInt()
    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** Background with a hairline along the bottom that fades out to both sides (title bars). */
class HairlineBg(private val color: Int, private val d: Float) : android.graphics.drawable.Drawable() {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = d }
    override fun onBoundsChange(b: android.graphics.Rect) {
        p.shader = android.graphics.LinearGradient(b.left.toFloat(), 0f, b.right.toFloat(), 0f,
            intArrayOf(Color.TRANSPARENT, Color.argb(150, Color.red(color), Color.green(color), Color.blue(color)), Color.TRANSPARENT), null, android.graphics.Shader.TileMode.CLAMP)
    }
    override fun draw(c: android.graphics.Canvas) { c.drawColor(C.BG); c.drawLine(bounds.left.toFloat(), bounds.bottom - d, bounds.right.toFloat(), bounds.bottom - d, p) }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
}
