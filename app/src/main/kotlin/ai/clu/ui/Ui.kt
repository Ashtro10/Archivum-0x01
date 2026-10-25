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
    val BG = Color.parseColor("#000000")
    val SURFACE = Color.parseColor("#121214")
    val SURFACE2 = Color.parseColor("#1C1C20")
    val BORDER = Color.parseColor("#2C2C32")
    val TEXT = Color.parseColor("#F2F2F5")
    val MUTED = Color.parseColor("#8E8E96")
    val ACCENT = Color.parseColor("#B54BFF")      // violet
    val ACCENT2 = Color.parseColor("#FF3D7F")     // pink
    val ACCENT3 = Color.parseColor("#FF5A36")     // red-orange
    val OK = Color.parseColor("#3FB950")
    val WARN = Color.parseColor("#E3A72F")
}

/** Dark "glass" circle/pill: near-black fill with a subtle light border (header and control buttons). */
fun glassBg(radius: Float = 200f) = GradientDrawable().apply {
    colors = intArrayOf(Color.parseColor("#26262B"), Color.parseColor("#141417")); orientation = GradientDrawable.Orientation.TOP_BOTTOM
    cornerRadius = radius; setStroke(2, C.BORDER)
}

/** Clu's accent gradient (violet -> pink -> red), used for the microphone and send buttons. */
fun accentBg(radius: Float = 200f) = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(C.ACCENT, C.ACCENT2, C.ACCENT3)).apply { cornerRadius = radius }

fun Context.dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun roundBg(color: Int, radius: Float = 24f, stroke: Int? = null) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius; stroke?.let { setStroke(2, it) }
}

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
        setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundColor(C.BG)
        addView(TextView(context).apply { text = "←"; textSize = 20f; setTextColor(C.TEXT); gravity = Gravity.CENTER; background = glassBg(); contentDescription = "Back"
            setOnClickListener { finish() } }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
        addView(TextView(context).apply { text = title; textSize = 19f; setTextColor(C.TEXT); typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, -2, 1f))
        actions()
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    fun confirm(title: String, msg: String, ok: String = "OK", onOk: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton(ok) { _, _ -> onOk() }.setNegativeButton("Cancel", null).show()
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
            .setPositiveButton("OK") { _, _ -> onOk(e.text.toString()) }.setNegativeButton("Cancel", null).show()
    }

    fun choose(title: String, items: List<String>, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setItems(items.toTypedArray()) { _, i -> onPick(i) }.show()
    }

    /**
     * Owner verification for sensitive actions: PIN (hashed, with temporary lockout) or the device's own screen lock.
     * Calls [onResult] with true only after successful verification, and records the time for the auth window.
     */
    fun verifyOwner(reason: String, onResult: (Boolean) -> Unit) {
        val creds = core.credentials
        val deviceLockAvailable = getSystemService(BiometricManager::class.java)
            .canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL or BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        fun success() { app.verifiedAt = System.currentTimeMillis(); onResult(true) }
        fun deviceLock() {
            BiometricPrompt.Builder(this).setTitle("Verify it's you").setSubtitle(reason)
                .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL or BiometricManager.Authenticators.BIOMETRIC_STRONG).build()
                .authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = success()
                    override fun onAuthenticationError(code: Int, msg: CharSequence) { toast(msg.toString()); onResult(false) }
                })
        }
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
fun LinearLayout.h1(t: String) = addView(TextView(context).apply { text = t; textSize = 18f; setTextColor(C.TEXT); typeface = Typeface.DEFAULT_BOLD; setPadding(0, context.dp(14), 0, context.dp(6)) })
fun LinearLayout.p(t: String, color: Int = C.MUTED, size: Float = 14f): TextView = TextView(context).apply { text = t; textSize = size; setTextColor(color); setPadding(0, context.dp(2), 0, context.dp(4)); setTextIsSelectable(true) }.also { addView(it) }

fun LinearLayout.button(t: String, primary: Boolean = false, onClick: () -> Unit): Button = Button(context).apply {
    text = t; isAllCaps = false; setTextColor(C.TEXT); background = if (primary) accentBg(80f) else roundBg(C.SURFACE2, 80f, C.BORDER)
    setOnClickListener { onClick() }
}.also { addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(6) }) }

fun LinearLayout.row(build: LinearLayout.() -> Unit): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; build()
}.also { addView(it, LinearLayout.LayoutParams(-1, -2)) }

fun LinearLayout.smallButton(t: String, onClick: () -> Unit): Button = Button(context).apply {
    text = t; isAllCaps = false; textSize = 13f; setTextColor(C.TEXT); background = roundBg(C.SURFACE2, 80f, C.BORDER); minWidth = 0; minimumWidth = 0
    setPadding(context.dp(10), 0, context.dp(10), 0); setOnClickListener { onClick() }
}.also { addView(it, LinearLayout.LayoutParams(-2, context.dp(38)).apply { marginEnd = context.dp(6); topMargin = context.dp(4) }) }

fun LinearLayout.card(build: LinearLayout.() -> Unit): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL; background = roundBg(C.SURFACE, 36f, C.BORDER); setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(10)); build()
}.also { addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) }) }

fun LinearLayout.field(label: String, value: String, multiline: Boolean = false, hint: String = ""): EditText {
    p(label, C.TEXT, 13f)
    return EditText(context).apply {
        setText(value); this.hint = hint; setTextColor(C.TEXT); setHintTextColor(C.MUTED); background = roundBg(C.SURFACE2, 16f)
        setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
        if (multiline) { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 3; gravity = Gravity.TOP }
    }.also { addView(it, LinearLayout.LayoutParams(-1, -2)) }
}

fun LinearLayout.check(label: String, checked: Boolean, onChange: (Boolean) -> Unit = {}): Switch = Switch(context).apply {
    text = label; isChecked = checked; setTextColor(C.TEXT); setPadding(0, context.dp(6), 0, context.dp(6))
    setOnCheckedChangeListener { _, v -> onChange(v) }
}.also { addView(it, LinearLayout.LayoutParams(-1, -2)) }

fun LinearLayout.spinner(options: List<String>, selected: Int, onChange: (Int) -> Unit = {}): Spinner = Spinner(context).apply {
    adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, options) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup) = (super.getView(position, convertView, parent) as TextView).apply { setTextColor(C.TEXT) }
    }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    setSelection(selected.coerceAtLeast(0), false)
    background = roundBg(C.SURFACE2, 16f)
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
