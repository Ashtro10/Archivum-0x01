package ai.clu.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.TextView

/**
 * CLU's design system: graphite surfaces, restrained cyan / ice-blue light, chamfered panels with hairline borders,
 * and one motion vocabulary (expand from a line, rise-and-fade, press dip). Everything here is drawn with the platform
 * canvas: no images, no fonts or libraries added to the APK.
 */
object Type {
    /** Headings: condensed, light, spaced. Body: the system sans. Metadata and labels: monospace capitals. */
    val heading: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    val headingBold: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    val body: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val mono: Typeface = Typeface.MONOSPACE
    val monoBold: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
}

/** A small capitalised monospace label ("CLU · 14:02", "SETTINGS // VOICE"). */
fun TextView.label(color: Int = C.MUTED, size: Float = 10.5f): TextView = apply {
    typeface = Type.mono; textSize = size; setTextColor(color); letterSpacing = 0.18f; isAllCaps = true
}

/**
 * A panel: dark translucent fill, a 1 dp border, the top-right and bottom-left corners cut at 45°, and a faint highlight
 * along the top edge. Stateful: pressed brightens the border and adds a soft inner glow; disabled dims everything;
 * selected/activated uses the accent border. Drawn as one path per state, no bitmaps.
 */
class Panel(
    private val fill: Int = C.SURFACE,
    private val border: Int = C.BORDER,
    private val chamferDp: Float = 10f,
    private val accent: Int = C.ACCENT,
    private val density: Float,
    private val glow: Boolean = false,
    private val fill2: Int? = null,
    private val ticks: Boolean = false,
) : Drawable() {
    private val path = Path()
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density }
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * density }
    private val hiP = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = density }
    private var pressed = false; private var enabled = true; private var on = false
    private var a = 255

    override fun onBoundsChange(b: Rect) {
        super.onBoundsChange(b)
        val inset = density / 2
        val l = b.left + inset; val t = b.top + inset; val r = b.right - inset; val bt = b.bottom - inset
        val k = (chamferDp * density).coerceAtMost(minOf(b.width(), b.height()) / 3f)
        path.reset()
        path.moveTo(l, t); path.lineTo(r - k, t); path.lineTo(r, t + k); path.lineTo(r, bt); path.lineTo(l + k, bt); path.lineTo(l, bt - k); path.close()
        fillP.shader = if (fill2 != null) LinearGradient(0f, t, 0f, bt, fill, fill2, Shader.TileMode.CLAMP) else null
        hiP.shader = LinearGradient(l, t, l + (r - l) * 0.6f, t, Color.argb(70, 200, 240, 255), Color.argb(0, 200, 240, 255), Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        val b = bounds
        val dim = if (enabled) 1f else 0.45f
        fillP.color = fill; fillP.alpha = (Color.alpha(fill) * dim * a / 255).toInt()
        c.drawPath(path, fillP)
        if ((glow || pressed || on) && enabled) {
            glowP.color = accent; glowP.alpha = ((if (pressed) 70 else if (on) 45 else 28) * a / 255)
            c.save(); c.clipPath(path); c.drawPath(path, glowP); c.restore()
        }
        borderP.color = when { pressed && enabled -> accent; on -> accent; else -> border }
        borderP.alpha = (Color.alpha(borderP.color) * dim * (if (pressed) 1f else if (on) 0.85f else 1f) * a / 255).toInt()
        c.drawPath(path, borderP)
        // Highlight along the top edge, fading out to the right.
        hiP.alpha = (255 * dim * a / 255).toInt()
        c.drawLine(b.left + density, b.top + density * 1.5f, b.right - chamferDp * density, b.top + density * 1.5f, hiP)
        if (ticks) {
            // Corner ticks: two short accent marks at the uncut corners.
            borderP.color = accent; borderP.alpha = (160 * dim * a / 255).toInt()
            val k = 6 * density
            c.drawLine(b.left + density, b.top + density, b.left + k, b.top + density, borderP)
            c.drawLine(b.left + density, b.top + density, b.left + density, b.top + k, borderP)
            c.drawLine(b.right - density, b.bottom - density, b.right - k, b.bottom - density, borderP)
            c.drawLine(b.right - density, b.bottom - density, b.right - density, b.bottom - k, borderP)
        }
    }

    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        val p = android.R.attr.state_pressed in state
        // An enabled view always reports state_enabled; a drawable not yet attached to a view gets an empty state.
        val en = android.R.attr.state_enabled in state || state.isEmpty()
        val o = android.R.attr.state_selected in state || android.R.attr.state_activated in state
        val changed = p != pressed || en != enabled || o != on
        pressed = p; enabled = en; on = o
        if (changed) invalidateSelf()
        return changed
    }

    override fun getOutline(outline: Outline) { if (path.isConvex) outline.setPath(path) else outline.setRect(bounds) }
    override fun setAlpha(alpha: Int) { a = alpha; invalidateSelf() }
    override fun setColorFilter(cf: ColorFilter?) { fillP.colorFilter = cf; borderP.colorFilter = cf; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

fun Context.panel(fill: Int = C.SURFACE, border: Int = C.BORDER, chamfer: Float = 10f, glow: Boolean = false, fill2: Int? = null, ticks: Boolean = false,
                  accent: Int = C.ACCENT) =
    Panel(fill, border, chamfer, accent, resources.displayMetrics.density, glow, fill2, ticks)

/** The primary control style: deep-blue tint, cyan border and a soft inner glow. */
fun Context.accentPanel(chamfer: Float = 10f) = panel(Color.argb(200, 6, 34, 48), Color.argb(230, 92, 225, 255), chamfer, glow = true,
    fill2 = Color.argb(220, 4, 18, 30))

/** Hexagonal control (microphone, send, voice): flat-topped hexagon with the accent ring. */
class HexButtonBg(private val density: Float, private val active: Boolean) : Drawable() {
    private val path = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * density }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f * density }
    private var pressed = false
    /** 0..1: an outer ring that swells with the microphone level (listening). */
    var level = 0f; set(v) { if (kotlin.math.abs(v - field) > 0.01f) { field = v; invalidateSelf() } }

    override fun onBoundsChange(b: Rect) {
        val cx = b.exactCenterX(); val cy = b.exactCenterY(); val r = minOf(b.width(), b.height()) / 2f - 4 * density
        path.reset()
        for (i in 0..5) { val a = Math.PI / 3 * i; val x = cx + r * Math.cos(a).toFloat(); val y = cy + r * Math.sin(a).toFloat(); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        path.close()
        fill.shader = RadialGradient(cx, cy, r, if (active) intArrayOf(Color.argb(230, 10, 70, 96), Color.argb(230, 4, 22, 34)) else intArrayOf(Color.argb(220, 22, 30, 38), Color.argb(220, 10, 14, 19)),
            null, Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        c.drawPath(path, fill)
        if (active || pressed) { glow.color = C.ACCENT; glow.alpha = if (pressed) 90 else 45; c.drawPath(path, glow) }
        ring.color = if (active || pressed) C.ACCENT else C.BORDER_HI; ring.alpha = 255
        c.drawPath(path, ring)
        if (level > 0.02f) {
            val s = 1f + 0.16f * level
            c.save(); c.scale(s, s, bounds.exactCenterX(), bounds.exactCenterY())
            ring.alpha = (60 + 150 * level).toInt(); c.drawPath(path, ring); c.restore()
        }
    }

    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean { val p = android.R.attr.state_pressed in state; val ch = p != pressed; pressed = p; if (ch) invalidateSelf(); return ch }
    override fun setAlpha(alpha: Int) { fill.alpha = alpha; ring.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * Motion: one vocabulary for the whole app, after the reference's language (panels open out of a line, content fades in
 * column by column, controls answer a press immediately). Durations are short and nothing waits for an animation:
 * views are usable from the first frame. With "Reduced motion" (or the system's animations off) everything appears at once.
 */
object Motion {
    /** Ease used by the reference for fades (cubic-bezier(0.4, 0, 1, 1)) is accelerating; for entrances we decelerate. */
    val out = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    val inOut = PathInterpolator(0.65f, 0f, 0.35f, 1f)
    @Volatile var reduced = false

    fun enabled() = !reduced && ValueAnimator.areAnimatorsEnabled()

    /** Fade in while rising [dy] dp, after [delay] ms. */
    fun rise(v: View, delay: Long = 0, dy: Float = 10f, dur: Long = 320) {
        if (!enabled()) { v.alpha = 1f; v.translationY = 0f; return }
        v.alpha = 0f; v.translationY = dy * v.resources.displayMetrics.density
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(dur).setInterpolator(out).start()
    }

    /** The reference's panel opening: a horizontal line that widens, then grows to full height. */
    fun expand(v: View, delay: Long = 0, dur: Long = 420) {
        if (!enabled()) { v.alpha = 1f; v.scaleX = 1f; v.scaleY = 1f; return }
        v.alpha = 0f; v.scaleX = 0.04f; v.scaleY = 0.06f
        v.animate().alpha(1f).scaleX(1f).setStartDelay(delay).setDuration(dur * 5 / 9).setInterpolator(inOut).withEndAction {
            v.animate().scaleY(1f).setStartDelay(0).setDuration(dur * 4 / 9).setInterpolator(out).start()
        }.start()
    }

    /** Children of [g] rise in one after another ([step] ms apart). */
    fun stagger(g: ViewGroup, start: Long = 0, step: Long = 45, max: Int = 14) {
        for (i in 0 until g.childCount) rise(g.getChildAt(i), start + step * i.coerceAtMost(max))
    }

    /** Immediate press feedback: a short dip in scale; never consumes the touch, so clicks behave exactly as before. */
    fun press(v: View, onDown: (() -> Unit)? = null) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { if (enabled()) view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(70).setInterpolator(DecelerateInterpolator()).start(); onDown?.invoke() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (enabled()) view.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(out).start()
            }
            false
        }
    }
}

/** Switch colours: cyan when on, graphite when off. */
fun switchTints(): Pair<ColorStateList, ColorStateList> {
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    return ColorStateList(states, intArrayOf(C.ACCENT2, Color.parseColor("#6B7884"))) to
        ColorStateList(states, intArrayOf(Color.argb(140, 92, 225, 255), Color.parseColor("#26313B")))
}
