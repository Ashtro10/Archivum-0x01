package ai.clu.ui

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.*

/**
 * CLU's presence: a living field of light particles shaped like a head, with CLU's eyes in it — the sharp, slanted,
 * icy-blue eyes under angled brow plates, hexagonal irises with a vertical slit, circuit traces running outward (the
 * same eyes as the earlier hexagonal HUD, whose faint frame stays around the field).
 *
 * States come from the voice engine, nothing is invented, and blend smoothly into one another:
 *  - [Mode.IDLE]: slow breathing, the eyes drift and occasionally narrow;
 *  - [Mode.LISTENING]: the field draws in and brightens with the microphone level, the irises open;
 *  - [Mode.THINKING]: an orbit of particles circles the head, light runs along the eye traces;
 *  - [Mode.SPEAKING]: ripples run through the field with the level of CLU's real audio, the eyes pulse;
 *  - [Mode.OFFLINE]: the field greys and slows, the eyes dim.
 *
 * Cost: one pass over ~1,100 points per frame (a few multiply-adds each) drawn with 12 drawPoints calls, the frame in a
 * cached bitmap, no allocation per frame. 30 fps while idle, display rate only while something happens, nothing at all
 * while the view is hidden or detached. Decoration only: never takes touches.
 */
class EntityView(context: Context, private val n: Int = 1100) : View(context) {
    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING, OFFLINE }

    var mode: Mode = Mode.IDLE
        set(v) { if (field != v) { field = v; kick() } }
    /** 0..1: microphone level while listening, CLU's playback level while speaking. */
    var level: Float = 0f
        set(v) { field = v.coerceIn(0f, 1f); if (field > 0.02f) kick() }
    /** Smaller field and no frame (the chat header). */
    var compact = false
        set(v) { if (field != v) { field = v; requestLayout(); invalidate() } }

    /** Frames drawn and the cost of the last one (Diagnostics and tests). */
    var frames = 0L; private set
    var lastFrameMs = 0.0; private set

    private val d = resources.displayMetrics.density
    // Particle model: unit-sphere direction, radius, phase, band membership.
    private val px = FloatArray(n); private val py = FloatArray(n); private val pz = FloatArray(n)
    private val pr = FloatArray(n); private val ph = FloatArray(n); private val band = BooleanArray(n)
    private val buckets = 6; private val sizes = 2
    private val out = Array(buckets * sizes) { FloatArray(n * 2) }
    private val count = IntArray(buckets * sizes)
    private val paints = Array(buckets * sizes) { Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND } }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private var frame: Bitmap? = null
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val grey = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.1f) }); alpha = 140 }

    private var cx = 0f; private var cy = 0f; private var R = 0f
    private var t0 = System.nanoTime(); private var last = 0L
    // Blended state weights (0..1) and smoothed level.
    private var wL = 0f; private var wT = 0f; private var wS = 0f; private var wO = 0f; private var lv = 0f
    private var attached = false

    // Eyes (geometry after the HUD's eyes, in units of u).
    private val ice = Color.rgb(168, 232, 255)
    private val electric = Color.rgb(30, 123, 255)
    private val eyeShape = arrayOf(Path(), Path()); private val brow = arrayOf(Path(), Path()); private val iris = arrayOf(Path(), Path())
    private val traces = arrayOf(Path(), Path())
    private val irisBounds = arrayOf(RectF(), RectF()); private val eyeBounds = arrayOf(RectF(), RectF())
    private val eyeFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyeEdge = stroke(1.3f, ice, 230)
    private val eyeGlow = stroke(7f, electric, 60)
    private val eyeGlow2 = stroke(3f, electric, 110)
    private val browPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(8, 16, 22) }
    private val browEdge = stroke(1.1f, electric, 170)
    private val irisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ice }
    private val slit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(2, 6, 10) }
    private val tracePaint = stroke(0.9f, electric, 120)
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ice }
    private val pulsePaint = stroke(2.2f, ice, 230).apply { strokeCap = Paint.Cap.ROUND }
    private val ring = stroke(1.1f, C.ACCENT, 120)
    private val sweep = stroke(2.2f, Color.rgb(160, 245, 255), 220).apply { strokeCap = Paint.Cap.ROUND }
    private val measure = PathMeasure(); private val seg = Path(); private val hex = Path(); private val hexTmp = Path()
    private var u = 0f
    // Gaze: current and target offset of the irises (fraction of iris radius), next saccade time, narrowing ("blink").
    private var lookX = 0f; private var lookY = 0f; private var toX = 0f; private var toY = 0f; private var nextLook = 1.5f
    private var narrowAt = 6f
    private val rnd = java.util.Random(7)

    init {
        // Redrawn every frame while animating, so no offscreen layer (it would only add a pass); the canvas is GPU-drawn.
        contentDescription = "Clu"
        isClickable = false; isLongClickable = false; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        // Fibonacci sphere, most points on a soft shell, some inside; ~16% form the thinking orbit.
        val g = PI * (3 - sqrt(5.0))
        for (i in 0 until n) {
            val y = 1 - 2 * (i + 0.5) / n; val rr = sqrt(1 - y * y); val a = g * i
            px[i] = (cos(a) * rr).toFloat(); py[i] = y.toFloat(); pz[i] = (sin(a) * rr).toFloat()
            pr[i] = (0.80 + 0.20 * rnd.nextDouble().pow(0.35)).toFloat()
            ph[i] = (rnd.nextDouble() * 2 * PI).toFloat()
            band[i] = i % 6 == 0
        }
        val cols = intArrayOf(Color.rgb(20, 70, 170), Color.rgb(28, 110, 220), Color.rgb(40, 160, 240), Color.rgb(70, 200, 250), Color.rgb(120, 225, 255), Color.rgb(190, 242, 255))
        for (b in 0 until buckets) for (s in 0 until sizes) paints[b * sizes + s].apply { color = cols[b]; strokeWidth = (if (s == 0) 1.5f else 2.4f) * d }
    }

    private fun stroke(w: Float, color: Int, alpha: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = w * d; this.color = color; this.alpha = alpha; strokeJoin = Paint.Join.MITER
    }

    private fun kick() { if (attached) postInvalidateOnAnimation() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); attached = true; last = 0; invalidate() }
    override fun onDetachedFromWindow() { attached = false; super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); if (isVisible) { last = 0; invalidate() } }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f; cy = h / 2f
        R = min(w, h) * (if (compact) 0.40f else 0.34f)
        u = R * 1.02f
        glow.shader = RadialGradient(cx, cy, R * 1.6f, intArrayOf(Color.argb(70, 30, 123, 255), Color.argb(26, 92, 225, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        hexPath(hex, R * 1.42f)
        frame?.recycle()
        frame = if (w > 0 && h > 0 && !compact) Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { drawFrame(Canvas(it)) } else null
        buildEyes()
    }

    private fun hexPath(p: Path, rad: Float) {
        p.reset(); for (i in 0..5) { val a = PI / 3 * i; val x = cx + rad * cos(a).toFloat(); val y = cy + rad * sin(a).toFloat(); if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }; p.close()
    }

    /** The static frame: a faint hexagon with vertex nodes and corner ticks, kept from the HUD. No text anywhere. */
    private fun drawFrame(c: Canvas) {
        c.drawPath(hex, stroke(1f, Color.rgb(58, 84, 100), 150))
        val node = stroke(1.2f, Color.WHITE, 170); val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 200 }
        for (i in 0..5) {
            val a = PI / 3 * i; val x = cx + R * 1.42f * cos(a).toFloat(); val y = cy + R * 1.42f * sin(a).toFloat()
            c.drawCircle(x, y, 3.6f * d, node); c.drawCircle(x, y, 1.3f * d, dot)
        }
        val tick = stroke(0.9f, C.ACCENT, 90)
        for ((fx, fy) in listOf(-0.62f to 0.95f, 0.66f to -0.95f, 0f to -1.12f, 0f to 1.14f)) {
            val x = cx + fx * R; val y = cy + fy * R
            c.drawLine(x - 3 * d, y, x + 3 * d, y, tick); c.drawLine(x, y - 3 * d, x, y + 3 * d, tick)
        }
    }

    /** Eye geometry (mirrored; s = −1 left, +1 right): slanted lids, inner corner low, angled brow plate, hexagonal iris. */
    private fun buildEyes() {
        for ((k, sgn) in listOf(0 to -1f, 1 to 1f)) {
            fun x(v: Float) = cx + sgn * v * u
            fun y(v: Float) = cy + v * u
            eyeShape[k].apply {
                reset(); moveTo(x(0.09f), y(0.03f)); lineTo(x(0.20f), y(-0.075f)); lineTo(x(0.40f), y(-0.115f)); lineTo(x(0.53f), y(-0.085f))
                lineTo(x(0.43f), y(0.035f)); lineTo(x(0.24f), y(0.075f)); close()
                computeBounds(eyeBounds[k], true)
            }
            brow[k].apply { reset(); moveTo(x(0.06f), y(-0.04f)); lineTo(x(0.55f), y(-0.215f)); lineTo(x(0.57f), y(-0.165f)); lineTo(x(0.10f), y(-0.005f)); close() }
            iris[k].apply {
                reset(); val icx = x(0.31f); val icy = y(-0.02f); val ir = 0.075f * u
                for (i in 0..5) { val a = PI / 3 * i + PI / 6; val qx = icx + ir * cos(a).toFloat(); val qy = icy + ir * 0.92f * sin(a).toFloat(); if (i == 0) moveTo(qx, qy) else lineTo(qx, qy) }
                close(); computeBounds(irisBounds[k], true)
            }
            traces[k].apply {
                reset()
                moveTo(x(0.53f), y(-0.085f)); lineTo(x(0.64f), y(-0.085f)); lineTo(x(0.70f), y(-0.15f)); lineTo(x(0.84f), y(-0.15f))
                moveTo(x(0.43f), y(0.035f)); lineTo(x(0.52f), y(0.12f)); lineTo(x(0.74f), y(0.12f))
            }
        }
        eyeFill.shader = RadialGradient(cx, cy - 0.02f * u, 0.62f * u, intArrayOf(Color.argb(160, 40, 150, 255), Color.argb(80, 10, 60, 140), Color.TRANSPARENT),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        if (R <= 0f) return
        val start = System.nanoTime()
        val t = (start - t0) / 1e9f
        val dt = if (last == 0L) 0.016f else ((start - last) / 1e9f).coerceIn(0f, 0.1f)
        last = start
        // Blend the state weights toward the current mode (about 0.3 s).
        val k = 1 - exp(-dt * 6f)
        wL += ((if (mode == Mode.LISTENING) 1f else 0f) - wL) * k
        wT += ((if (mode == Mode.THINKING) 1f else 0f) - wT) * k
        wS += ((if (mode == Mode.SPEAKING) 1f else 0f) - wS) * k
        wO += ((if (mode == Mode.OFFLINE) 1f else 0f) - wO) * k
        lv += (level - lv) * (1 - exp(-dt * 14f))
        val live = 1f - wO

        // Frame and state ring.
        frame?.let { c.drawBitmap(it, 0f, 0f, if (wO > 0.5f) grey else bmpPaint) }
        glow.alpha = (255 * (0.55f + 0.25f * wL * (0.5f + lv) + 0.2f * wS * lv) * (1 - 0.7f * wO)).toInt().coerceIn(0, 255)
        c.drawCircle(cx, cy, R * 1.6f, glow)
        if (!compact) drawStateRing(c, t)

        // Particles.
        val yaw = 0.22f * sin(t * 0.13f)
        val pitch = 0.07f * sin(t * 0.09f)
        val cyw = cos(yaw); val syw = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        val breath = 1f + 0.018f * sin(t * 1.1f) * live
        val scale = R * breath * (1f - 0.05f * wL * (0.4f + lv) - 0.06f * wO)
        val amp = 0.025f + 0.02f * wT + 0.05f * wS * lv + 0.035f * wL * lv
        val orbit = t * 1.6f
        val wave = t * 2.4f
        count.fill(0)
        for (i in 0 until n) {
            var x = px[i]; var y = py[i]; var z = pz[i]; var r = pr[i]
            if (band[i]) {
                // Thinking orbit: these points leave the shell for a tilted ring circling the head.
                if (wT > 0.02f) {
                    val a = atan2(z, x) + orbit; val rx = cos(a) * 1.22f; val rz = sin(a) * 1.22f; val ry = y * 0.08f - 0.05f
                    x += (rx - x) * wT; y += (ry - y) * wT; z += (rz - z) * wT; r += (1f - r) * wT
                }
            }
            val n1 = sin(3.1f * x + t * 0.9f + ph[i]) * sin(2.7f * y - t * 0.7f)
            val ripple = if (wS > 0.02f) sin(9f * sqrt(x * x + (y - 0.35f) * (y - 0.35f)) - t * 11f) * wS * lv * 0.05f else 0f
            r *= 1f + amp * n1 + ripple
            // Head shape: a little taller than wide, slightly flattened front to back.
            var X = x * r * 0.9f; var Y = y * r * 1.06f; var Z = z * r * 0.86f
            // Rotate (yaw, then pitch).
            val x1 = X * cyw + Z * syw; val z1 = -X * syw + Z * cyw
            val y2 = Y * cp - z1 * sp; val z2 = Y * sp + z1 * cp
            X = x1; Y = y2; Z = z2
            val f = 3.2f / (3.2f - Z)
            val sx = cx + X * scale * f; val sy = cy + Y * scale * f
            // Depth and state brightness; points just in front of the eyes stay dark so the eyes read clearly.
            var b = 0.30f + 0.55f * (Z + 1f) / 2f
            if (wT > 0.02f) b += 0.25f * wT * max(0f, sin(atan2(Y, X) * 2f - wave))
            if (wL > 0.02f) b += 0.30f * wL * lv
            if (wS > 0.02f) b += 0.25f * wS * lv
            b *= (1f - 0.55f * wO)
            if (Z > 0.2f && abs(Y + 0.03f) < 0.16f && abs(X) > 0.05f && abs(X) < 0.6f) b *= 0.25f
            val bi = (b * buckets).toInt().coerceIn(0, buckets - 1)
            val si = if (Z > 0.45f) 1 else 0
            val idx = bi * sizes + si
            val o = out[idx]; val cnt = count[idx]
            o[cnt * 2] = sx; o[cnt * 2 + 1] = sy; count[idx] = cnt + 1
        }
        for (i in out.indices) if (count[i] > 0) {
            val p = paints[i]
            p.alpha = (255 * (0.45f + 0.55f * (i / sizes + 1) / buckets.toFloat())).toInt()
            if (wO > 0.5f) p.colorFilter = DESAT else p.colorFilter = null
            c.drawPoints(out[i], 0, count[i] * 2, p)
        }

        // Eyes.
        val g = when {
            wO > 0.5f -> 0f
            else -> (0.35f + 0.25f * wT + (0.15f + 0.5f * lv) * wL + (0.2f + 0.45f * lv) * wS + 0.06f * wS * sin(t * 7.5f)).coerceIn(0f, 1f)
        }
        val open = (0.2f + (0.15f + 0.65f * lv) * wL + 0.05f * wT + (0.2f + 0.4f * lv) * wS).coerceIn(0f, 1f)
        updateGaze(t, dt)
        drawEyes(c, g, open, dim = wO > 0.5f, narrow = narrowing(t))
        if (wT > 0.05f) drawTracePulses(c, t, wT)

        frames++; lastFrameMs = (System.nanoTime() - start) / 1e6
        schedule()
    }

    private fun drawStateRing(c: Canvas, t: Float) {
        if (wT > 0.02f) {
            measure.setPath(hex, true); val len = measure.length; val sl = len * 0.12f
            sweep.alpha = (220 * wT).toInt()
            for (k in 0..1) {
                val s = ((t * 0.5f + k * 0.5f) % 1f) * len
                seg.reset()
                if (s + sl <= len) measure.getSegment(s, s + sl, seg, true) else { measure.getSegment(s, len, seg, true); measure.getSegment(0f, s + sl - len, seg, true) }
                c.drawPath(seg, sweep)
            }
        }
        if (wL > 0.02f) { hexPath(hexTmp, R * (1.36f - 0.06f * lv)); ring.alpha = ((60 + 150 * lv) * wL).toInt(); c.drawPath(hexTmp, ring) }
        if (wS > 0.02f) for (k in 0..1) {
            val p = (t * 0.9f + k * 0.5f) % 1f
            hexPath(hexTmp, R * (1.42f + 0.25f * p)); ring.alpha = ((1 - p) * (40 + 170 * lv) * wS).toInt().coerceIn(0, 255); c.drawPath(hexTmp, ring)
        }
    }

    private fun updateGaze(t: Float, dt: Float) {
        if (t > nextLook) {
            // Watchful: mostly straight ahead, sometimes a slow glance aside.
            val aside = rnd.nextFloat() < 0.45f
            toX = if (aside) (rnd.nextFloat() - 0.5f) * 0.9f else 0f; toY = if (aside) (rnd.nextFloat() - 0.5f) * 0.4f else 0f
            nextLook = t + 2.2f + rnd.nextFloat() * 3.5f
        }
        val tx = if (wL > 0.5f) 0f else toX; val ty = if (wL > 0.5f) 0f else toY   // listening: looks straight at you
        val kk = 1 - exp(-dt * 5f)
        lookX += (tx - lookX) * kk; lookY += (ty - lookY) * kk
    }

    /** 1 = open, down to 0.25 during an occasional slow narrowing (not a cartoon blink). */
    private fun narrowing(t: Float): Float {
        if (t > narrowAt + 0.5f) narrowAt = t + 5f + rnd.nextFloat() * 6f
        val p = t - narrowAt
        return if (p in 0f..0.5f) 1f - 0.75f * sin(p / 0.5f * PI.toFloat()) else 1f
    }

    private fun drawEyes(c: Canvas, glowLevel: Float, open: Float, dim: Boolean, narrow: Float) {
        for (k in 0..1) {
            val eb = eyeBounds[k]
            tracePaint.alpha = if (dim) 35 else (60 + 90 * glowLevel).toInt()
            c.drawPath(traces[k], tracePaint)
            c.save(); c.scale(1f, narrow, eb.centerX(), eb.centerY())
            if (!dim) { eyeGlow.alpha = (25 + 80 * glowLevel).toInt(); eyeGlow2.alpha = (60 + 120 * glowLevel).toInt(); c.drawPath(eyeShape[k], eyeGlow); c.drawPath(eyeShape[k], eyeGlow2) }
            eyeFill.alpha = if (dim) 60 else (150 + 105 * glowLevel).toInt(); c.drawPath(eyeShape[k], eyeFill)
            eyeEdge.alpha = if (dim) 70 else (170 + 85 * glowLevel).toInt(); c.drawPath(eyeShape[k], eyeEdge)
            // Iris: follows the gaze inside the eye, scaled by `open`, with the vertical slit; clipped to the eye.
            c.save(); c.clipPath(eyeShape[k])
            val b = irisBounds[k]
            val s = if (dim) 0.7f else 0.8f + 0.35f * open
            val ox = lookX * b.width() * 0.6f; val oy = lookY * b.height() * 0.3f
            c.translate(ox, oy); c.scale(s, s, b.centerX(), b.centerY())
            irisPaint.alpha = if (dim) 70 else (200 + 55 * glowLevel).toInt(); c.drawPath(iris[k], irisPaint)
            val sw = b.width() * 0.16f
            c.drawRoundRect(b.centerX() - sw / 2, b.top + b.height() * 0.12f, b.centerX() + sw / 2, b.bottom - b.height() * 0.12f, sw / 2, sw / 2, slit)
            c.restore()
            c.restore()
            c.drawPath(brow[k], browPaint)
            browEdge.alpha = if (dim) 50 else (110 + 100 * glowLevel).toInt(); c.drawPath(brow[k], browEdge)
            if (!dim) for ((fx, fy) in listOf(0.84f to -0.15f, 0.74f to 0.12f)) {
                nodePaint.alpha = (120 + 120 * glowLevel).toInt()
                c.drawCircle(cx + (if (k == 0) -1 else 1) * fx * u, cy + fy * u, 1.8f * d, nodePaint)
            }
        }
    }

    private fun drawTracePulses(c: Canvas, t: Float, w: Float) {
        pulsePaint.alpha = (230 * w).toInt()
        for (k in 0..1) {
            measure.setPath(traces[k], false); var contour = 0
            do {
                val len = measure.length
                if (len > 0) { val p = ((t * 0.8f + contour * 0.33f) % 1f) * len; seg.reset(); measure.getSegment(p, (p + len * 0.18f).coerceAtMost(len), seg, true); c.drawPath(seg, pulsePaint) }
                contour++
            } while (measure.nextContour())
        }
    }

    private fun schedule() {
        if (!attached || !isShown) return
        val active = mode != Mode.IDLE && mode != Mode.OFFLINE
        val settling = abs(wL) + abs(wT) + abs(wS) > 0.01f || abs(wO - (if (mode == Mode.OFFLINE) 1f else 0f)) > 0.01f || lv > 0.01f
        when {
            active || settling -> postInvalidateOnAnimation()
            mode == Mode.OFFLINE -> {}                                        // dim and still: no frames at all
            Motion.reduced -> postInvalidateDelayed(250)                      // reduced motion: slow eye drift only
            else -> postInvalidateDelayed(33)                                 // idle breathing at ~30 fps
        }
    }

    companion object { private val DESAT = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.05f) }) }
}
