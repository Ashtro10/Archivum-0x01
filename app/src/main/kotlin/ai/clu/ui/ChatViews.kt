package ai.clu.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.*
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.*
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.sin

/** Spannable text of inline Markdown. */
fun mdInline(s: String, base: Int = C.TEXT): CharSequence {
    val b = SpannableStringBuilder()
    for (part in Markdown.inline(s)) {
        val start = b.length
        when (part) {
            is Markdown.Inline.Text -> b.append(part.s)
            is Markdown.Inline.Bold -> { b.append(part.s); b.setSpan(StyleSpan(Typeface.BOLD), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); b.setSpan(ForegroundColorSpan(Color.WHITE), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            is Markdown.Inline.Italic -> { b.append(part.s); b.setSpan(StyleSpan(Typeface.ITALIC), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            is Markdown.Inline.Code -> {
                b.append(part.s)
                b.setSpan(TypefaceSpan("monospace"), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                b.setSpan(BackgroundColorSpan(Color.argb(150, 20, 40, 52)), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                b.setSpan(ForegroundColorSpan(C.ACCENT2), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            is Markdown.Inline.Link -> { b.append(part.label); b.setSpan(URLSpan(part.url), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); b.setSpan(ForegroundColorSpan(C.ACCENT), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
    }
    return b
}

/**
 * A reply rendered from Markdown: text blocks in one selectable TextView each, code blocks in their own panel with the
 * language, horizontal scrolling and a copy control. [cursor] adds the streaming caret after the last text.
 */
fun Context.markdownView(text: String, color: Int = C.TEXT, cursor: Boolean = false, onLong: (() -> Unit)? = null): View {
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val blocks = Markdown.parse(text)
    var sb = SpannableStringBuilder()
    fun flushText() {
        if (sb.isEmpty()) return
        while (sb.isNotEmpty() && sb[sb.length - 1] == '\n') sb.delete(sb.length - 1, sb.length)
        val tv = TextView(this).apply {
            this.text = sb; textSize = 15.5f; setTextColor(color); setLineSpacing(0f, 1.22f); typeface = Type.body
            linksClickable = true; movementMethod = LinkMovementMethod.getInstance(); highlightColor = Color.argb(80, 92, 225, 255)
            onLong?.let { f -> setOnLongClickListener { f(); true } }
        }
        box.addView(tv); sb = SpannableStringBuilder()
    }
    for (b in blocks) when (b) {
        is Markdown.Block.Para -> { sb.append(mdInline(b.text, color)); sb.append("\n\n") }
        is Markdown.Block.Heading -> {
            val s = sb.length; sb.append(mdInline(b.text))
            sb.setSpan(RelativeSizeSpan(if (b.level <= 1) 1.25f else if (b.level == 2) 1.12f else 1.04f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(C.ACCENT2), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("\n\n")
        }
        is Markdown.Block.Item -> {
            val s = sb.length; val lead = (resources.displayMetrics.density * (14 + 14 * b.depth)).toInt()
            sb.append(b.marker).append("  ").append(mdInline(b.text))
            sb.setSpan(ForegroundColorSpan(C.ACCENT), s, s + b.marker.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(LeadingMarginSpan.Standard(lead - (resources.displayMetrics.density * 10).toInt(), lead + (resources.displayMetrics.density * 6).toInt()), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("\n")
        }
        is Markdown.Block.Quote -> {
            val s = sb.length; sb.append(mdInline(b.text))
            sb.setSpan(QuoteSpan(C.ACCENT3), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); sb.setSpan(ForegroundColorSpan(C.MUTED), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("\n\n")
        }
        Markdown.Block.Rule -> { flushText(); box.addView(View(this).apply { setBackgroundColor(C.BORDER_HI) }, LinearLayout.LayoutParams(-1, 1).apply { setMargins(0, dp(8), 0, dp(8)) }) }
        is Markdown.Block.Code -> { flushText(); box.addView(codePanel(b.lang, b.code)) }
    }
    if (cursor) { val s = sb.length; sb.append(" ▍"); sb.setSpan(ForegroundColorSpan(C.ACCENT), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    flushText()
    return box
}

/** A code block: language label and copy control above, monospace text that scrolls sideways (lines never wrap). */
fun Context.codePanel(lang: String, code: String): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; background = panel(Color.argb(235, 4, 10, 14), C.BORDER_HI, 8f, ticks = true)
    setPadding(dp(12), dp(8), dp(12), dp(10))
    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, dp(10)) }
    addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply { text = lang.ifBlank { "code" }; label(C.ACCENT, 10f) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(context).apply {
            text = "COPY"; label(C.ACCENT2, 10f); setPadding(dp(10), dp(8), 0, dp(8)); contentDescription = "Copy code"
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon(Icon.Kind.COPY, C.ACCENT2, 14f), null, null, null); compoundDrawablePadding = dp(6)
            Motion.press(this)
            setOnClickListener {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("code", code))
                Sfx.play(SfxSynth.Sound.CLICK); Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
            }
        })
    })
    addView(HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(TextView(context).apply { text = code; typeface = Type.mono; textSize = 13f; setTextColor(Color.rgb(200, 236, 250)); setLineSpacing(0f, 1.18f); setTextIsSelectable(true) })
    })
}

/**
 * The "processing" indicator: a row of short segments that light up in turn, like a scan; animates only while shown.
 */
class ProcessingBar(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density
    private val t0 = System.nanoTime()
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((120 * d).toInt(), (6 * d).toInt())
    override fun onDraw(c: Canvas) {
        val t = (System.nanoTime() - t0) / 1e9f
        val n = 12; val gap = 3 * d; val w = (width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val ph = ((t * 1.6f - i / n.toFloat()) % 1f + 1f) % 1f
            val a = (40 + 215 * (1 - ph).let { it * it * it }).toInt()
            p.color = C.ACCENT; p.alpha = a
            val x = i * (w + gap)
            c.drawRect(x, 0f, x + w, height.toFloat(), p)
        }
        if (isShown) postInvalidateDelayed(33)
    }
}

/**
 * Start-up sequence (once per launch), after the reference's: a fast boot log of CLU's real state line by line, then
 * the name appears — filled, outlined, a brief derez flicker — and the overlay dissolves while the interface opens out.
 * About 1.5 s; never takes touches (a tap reaches the controls beneath and ends the sequence at once).
 */
class BootOverlay(context: Context, private val lines: List<String>, private val onDone: () -> Unit) : View(context) {
    private val d = resources.displayMetrics.density
    private val t0 = System.nanoTime()
    private val log = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Type.mono; textSize = 11f * d; color = C.ACCENT2 }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Type.heading; textSize = 64f * d; letterSpacing = 0.35f; textAlign = Paint.Align.CENTER }
    private val box = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = C.ACCENT }
    private var shown = 0
    private var finished = false
    private var skipAt = -1f
    private var started = false
    private val rnd = java.util.Random(3)

    init { isClickable = false; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    // The sequence ends on time even if no frame is drawn (window not yet focused, a slow device).
    override fun onAttachedToWindow() { super.onAttachedToWindow(); postDelayed({ finish() }, 1700) }

    /** Ends the sequence now (a tap, or reduced motion). */
    fun skip() { if (skipAt < 0) { skipAt = now(); invalidate(); postDelayed({ finish() }, 220) } }

    private fun finish() { if (!finished) { finished = true; visibility = GONE; onDone() } }
    private fun now() = (System.nanoTime() - t0) / 1e9f

    override fun onDraw(c: Canvas) {
        val t = now()
        val end = if (skipAt >= 0) skipAt + 0.15f else 1.55f
        val fade = if (t > end - 0.35f) ((end - t) / 0.35f).coerceIn(0f, 1f) else 1f
        c.drawColor(Color.argb((255 * fade).toInt(), 2, 4, 6))
        val logEnd = 0.62f
        if (skipAt < 0 && t < logEnd + 0.1f) {
            val n = ((t / logEnd) * lines.size).toInt().coerceAtMost(lines.size)
            if (n > shown) { if (n % 2 == 1) Sfx.play(SfxSynth.Sound.TICK); shown = n }
            val lh = 15f * d; var y = height - 40 * d - lh * (n - 1)
            for (i in 0 until n) { log.alpha = (if (i == n - 1) 255 else 170); c.drawText(lines[i], 22 * d, y, log); y += lh }
        }
        if (t >= logEnd && skipAt < 0) {
            if (!started) { started = true; Sfx.play(SfxSynth.Sound.STARTUP) }
            val p = t - logEnd
            val cy = height / 2f; val cx = width / 2f
            val tw = title.measureText("CLU") + 40 * d; val th = 86 * d
            val r = RectF(cx - tw / 2, cy - th / 2, cx + tw / 2, cy + th / 2)
            title.alpha = (255 * (p / 0.12f).coerceIn(0f, 1f) * fade).toInt()
            when {
                p < 0.22f -> { title.color = C.TEXT; title.alpha = (255 * (p / 0.12f).coerceIn(0f, 1f) * fade).toInt(); c.drawText("CLU", cx, cy + 22 * d, title); c.drawLine(r.left, r.bottom, r.right, r.bottom, line) }
                p < 0.32f -> { box.color = C.ACCENT; box.alpha = (255 * fade).toInt(); c.drawRect(r, box); title.color = Color.rgb(2, 6, 10); c.drawText("CLU", cx, cy + 22 * d, title) }
                p < 0.42f -> { line.alpha = (255 * fade).toInt(); c.drawRect(r, line); title.color = C.TEXT; c.drawText("CLU", cx, cy + 22 * d, title) }
                p < 0.62f -> {
                    // Derez: horizontal slices shifted sideways for a few frames.
                    title.color = C.ACCENT2
                    val slices = 6; val sh = r.height() / slices
                    for (k in 0 until slices) {
                        c.save(); c.clipRect(r.left - 40 * d, r.top + k * sh, r.right + 40 * d, r.top + (k + 1) * sh)
                        c.drawText("CLU", cx + (rnd.nextFloat() - 0.5f) * 18 * d, cy + 22 * d, title); c.restore()
                    }
                }
                else -> { line.alpha = (255 * fade).toInt(); c.drawRect(r, line); title.color = C.TEXT; c.drawText("CLU", cx, cy + 22 * d, title)
                    // A scan line sweeping down as the interface opens.
                    val sy = (p - 0.62f) / 0.3f * height; box.color = C.ACCENT; box.alpha = (90 * fade).toInt(); c.drawRect(0f, sy, width.toFloat(), sy + 1.5f * d, box) }
            }
        }
        if (t >= end) finish() else postInvalidateOnAnimation()
    }
}

/** Sine helper for small pulses. */
internal fun pulse(t: Float, hz: Float) = 0.5f + 0.5f * sin(t * hz * 2 * Math.PI.toFloat())
