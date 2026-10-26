package ai.clu.ui

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Clu's hexagonal HUD (voice screen): a flat-topped hexagon frame of dark translucent panels with teal circuit
 * details, white vertex nodes, corner stripes and small technical markings, after the reference design. The
 * interior stays dark for status text.
 *
 * The frame is drawn once into a cached bitmap; only the state layer is animated, and only while something is really
 * happening: [Mode.LISTENING] (inner edge brightens with the microphone level), [Mode.THINKING] (light segments run
 * around the inner edge), [Mode.SPEAKING] (the frame pulses and ripples with the level of Clu's actual audio). Idle and
 * offline draw a single static frame (no animation loop, no battery use); offline is desaturated.
 */
class HexHudView(context: Context) : View(context) {
    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING, OFFLINE }

    var mode: Mode = Mode.IDLE
        set(v) { if (field != v) { field = v; t0 = System.nanoTime(); invalidate() } }
    /** 0..1: microphone level while listening, Clu's playback level while speaking. */
    var level: Float = 0f

    private val d = resources.displayMetrics.density
    private var smooth = 0f
    private var t0 = System.nanoTime()
    private var frame: Bitmap? = null
    private val outer = Path(); private val inner = Path(); private val mid = Path()
    private val measure = PathMeasure()
    private val seg = Path()
    private var cx = 0f; private var cy = 0f; private var r = 0f; private var ri = 0f

    private val teal = Color.rgb(63, 208, 192)
    private val glowWide = stroke(6f, teal, 40)
    private val glowThin = stroke(1.6f, teal, 200)
    private val sweep = stroke(2.4f, Color.rgb(140, 255, 240), 230).apply { strokeCap = Paint.Cap.ROUND }
    private val ripple = stroke(1.2f, teal, 120)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val grey = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.1f) }); alpha = 150 }
    private val offlineDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 92, 92) }

    init { setLayerType(LAYER_TYPE_HARDWARE, null); contentDescription = "Clu" }

    private fun stroke(w: Float, color: Int, alpha: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = w * d; this.color = color; this.alpha = alpha; strokeJoin = Paint.Join.MITER
    }

    private fun vx(i: Int, rad: Float) = cx + rad * cos(PI / 3 * i).toFloat()
    private fun vy(i: Int, rad: Float) = cy + rad * sin(PI / 3 * i).toFloat()
    private fun hex(p: Path, rad: Float) { p.reset(); for (i in 0..5) if (i == 0) p.moveTo(vx(i, rad), vy(i, rad)) else p.lineTo(vx(i, rad), vy(i, rad)); p.close() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Keep the hexagon's true proportions (width : height = 2 : sqrt 3) inside whatever space it gets.
        val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
        val hh = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) (w * 0.95f).toInt() else h
        setMeasuredDimension(w, min(hh, (w * 0.95f).toInt()))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f; cy = h / 2f
        r = min(w * 0.46f, h * 0.46f * 2 / sqrt(3f))
        ri = r * 0.80f
        hex(outer, r); hex(inner, ri); hex(mid, (r + ri) / 2)
        frame?.recycle()
        frame = if (w > 0 && h > 0) Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { drawFrame(Canvas(it)) } else null
    }

    /** The static frame, after the reference: panels, circuit traces, accents, nodes, stripes, brackets and markings. */
    private fun drawFrame(c: Canvas) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        // Side brackets outside the upper-left and lower-right edges.
        val bracket = stroke(6f, Color.rgb(30, 42, 46), 255).apply { strokeCap = Paint.Cap.ROUND }
        for ((a, b) in listOf(3 to 4, 0 to 1)) {
            val nx = (vx(a, 1f) + vx(b, 1f)) / 2 - cx; val ny = (vy(a, 1f) + vy(b, 1f)) / 2 - cy
            val off = 0.075f * r; val len = sqrt(nx * nx + ny * ny)
            val ox = nx / len * off; val oy = ny / len * off
            c.drawLine(lerp(vx(a, r), vx(b, r), 0.22f) + ox, lerp(vy(a, r), vy(b, r), 0.22f) + oy, lerp(vx(a, r), vx(b, r), 0.62f) + ox, lerp(vy(a, r), vy(b, r), 0.62f) + oy, bracket)
            bracket.strokeWidth = 3 * d
            c.drawLine(lerp(vx(a, r), vx(b, r), 0.68f) + ox, lerp(vy(a, r), vy(b, r), 0.68f) + oy, lerp(vx(a, r), vx(b, r), 0.8f) + ox, lerp(vy(a, r), vy(b, r), 0.8f) + oy, bracket)
            bracket.strokeWidth = 6 * d
        }
        // Dark translucent band between the outer and inner hexagon, with a faint teal sheen.
        val band = Path().apply { fillType = Path.FillType.EVEN_ODD; addPath(outer); addPath(inner) }
        fill.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, intArrayOf(Color.argb(235, 14, 24, 28), Color.argb(235, 10, 18, 21), Color.argb(235, 16, 30, 33)), null, Shader.TileMode.CLAMP)
        c.drawPath(band, fill); fill.shader = null
        // Panels along each edge.
        val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(22, 36, 41) }
        val panelEdge = stroke(0.8f, Color.rgb(44, 64, 70), 255)
        for (i in 0..5) {
            val j = (i + 1) % 6
            for ((t0, t1, depth) in listOf(Triple(0.10f, 0.40f, 0.55f), Triple(0.46f, 0.66f, 0.75f), Triple(0.70f, 0.90f, 0.45f))) {
                val p = Path()
                val o0x = lerp(vx(i, r * 0.975f), vx(j, r * 0.975f), t0); val o0y = lerp(vy(i, r * 0.975f), vy(j, r * 0.975f), t0)
                val o1x = lerp(vx(i, r * 0.975f), vx(j, r * 0.975f), t1); val o1y = lerp(vy(i, r * 0.975f), vy(j, r * 0.975f), t1)
                val rr = r - (r - ri) * depth
                val i0x = lerp(vx(i, rr), vx(j, rr), t0 + 0.02f); val i0y = lerp(vy(i, rr), vy(j, rr), t0 + 0.02f)
                val i1x = lerp(vx(i, rr), vx(j, rr), t1 - 0.02f); val i1y = lerp(vy(i, rr), vy(j, rr), t1 - 0.02f)
                p.moveTo(o0x, o0y); p.lineTo(o1x, o1y); p.lineTo(i1x, i1y); p.lineTo(i0x, i0y); p.close()
                c.drawPath(p, panel); c.drawPath(p, panelEdge)
            }
        }
        // Teal accent hatches and translucent teal plates (as on the reference's upper and lower edges).
        val hatch = stroke(1.4f, teal, 120)
        val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = teal; alpha = 38 }
        for (i in listOf(0, 2, 3, 5)) {
            val j = (i + 1) % 6
            for (k in 0..5) {
                val t = 0.16f + k * 0.035f
                val ax = lerp(vx(i, r * 0.95f), vx(j, r * 0.95f), t); val ay = lerp(vy(i, r * 0.95f), vy(j, r * 0.95f), t)
                val bx = lerp(vx(i, r * 0.86f), vx(j, r * 0.86f), t + 0.03f); val by = lerp(vy(i, r * 0.86f), vy(j, r * 0.86f), t + 0.03f)
                c.drawLine(ax, ay, bx, by, hatch)
            }
            val p = Path()
            p.moveTo(lerp(vx(i, r * 0.93f), vx(j, r * 0.93f), 0.55f), lerp(vy(i, r * 0.93f), vy(j, r * 0.93f), 0.55f))
            p.lineTo(lerp(vx(i, r * 0.93f), vx(j, r * 0.93f), 0.85f), lerp(vy(i, r * 0.93f), vy(j, r * 0.93f), 0.85f))
            p.lineTo(lerp(vx(i, r * 0.84f), vx(j, r * 0.84f), 0.80f), lerp(vy(i, r * 0.84f), vy(j, r * 0.84f), 0.80f))
            p.lineTo(lerp(vx(i, r * 0.84f), vx(j, r * 0.84f), 0.58f), lerp(vy(i, r * 0.84f), vy(j, r * 0.84f), 0.58f)); p.close()
            c.drawPath(p, plate)
        }
        // Circuit traces running along the band, with right-angle steps.
        val trace = stroke(0.8f, teal, 70)
        for (i in 0..5) {
            val j = (i + 1) % 6
            val a = 0.83f; val b = 0.86f
            c.drawLine(lerp(vx(i, r * a), vx(j, r * a), 0.05f), lerp(vy(i, r * a), vy(j, r * a), 0.05f), lerp(vx(i, r * a), vx(j, r * a), 0.30f), lerp(vy(i, r * a), vy(j, r * a), 0.30f), trace)
            c.drawLine(lerp(vx(i, r * a), vx(j, r * a), 0.30f), lerp(vy(i, r * a), vy(j, r * a), 0.30f), lerp(vx(i, r * b), vx(j, r * b), 0.33f), lerp(vy(i, r * b), vy(j, r * b), 0.33f), trace)
            c.drawLine(lerp(vx(i, r * b), vx(j, r * b), 0.33f), lerp(vy(i, r * b), vy(j, r * b), 0.33f), lerp(vx(i, r * b), vx(j, r * b), 0.48f), lerp(vy(i, r * b), vy(j, r * b), 0.48f), trace)
        }
        // Thin outer frame and inner edge.
        c.drawPath(outer, stroke(1.1f, Color.rgb(110, 132, 138), 170))
        c.drawPath(inner, stroke(1f, Color.rgb(46, 84, 90), 220))
        // Bracket corners just outside the outer frame near each vertex.
        val corner = stroke(1.2f, Color.rgb(170, 186, 190), 180)
        for (i in 0..5) for (n in listOf((i + 1) % 6, (i + 5) % 6)) {
            c.drawLine(vx(i, r * 1.035f), vy(i, r * 1.035f), lerp(vx(i, r * 1.035f), vx(n, r * 1.035f), 0.08f), lerp(vy(i, r * 1.035f), vy(n, r * 1.035f), 0.08f), corner)
        }
        // Corner stripes beside the top and bottom vertices.
        val stripe = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 210 }
        for ((i, n) in listOf(4 to 5, 5 to 4, 1 to 2, 2 to 1)) {
            for (k in 0..3) {
                val t = 0.10f + k * 0.022f
                val x = lerp(vx(i, r * 1.035f), vx(n, r * 1.035f), t); val y = lerp(vy(i, r * 1.035f), vy(n, r * 1.035f), t)
                val dy = if (i == 4 || i == 5) -1 else 1
                val p = Path().apply { moveTo(x, y); lineTo(x + 3 * d, y + dy * 5 * d); lineTo(x + 5 * d, y + dy * 5 * d); lineTo(x + 2 * d, y); close() }
                c.drawPath(p, stripe)
            }
        }
        // Vertex nodes: ringed dots, with short horizontal ticks on the left and right vertices.
        val ring = stroke(1.4f, Color.WHITE, 220); val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        for (i in 0..5) {
            c.drawCircle(vx(i, r * 1.035f), vy(i, r * 1.035f), 4.5f * d, ring)
            c.drawCircle(vx(i, r * 1.035f), vy(i, r * 1.035f), 1.6f * d, dot)
        }
        for (i in listOf(0, 3)) {
            val dir = if (i == 0) 1 else -1
            c.drawLine(vx(i, r * 1.035f) + dir * 6 * d, vy(i, r), vx(i, r * 1.035f) + dir * 14 * d, vy(i, r), ring)
        }
        // Small markings and crosshairs (decorative telemetry, kept faint so the centre stays clear).
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(111, 168, 168); alpha = 150; textSize = 7.5f * d; typeface = Typeface.MONOSPACE }
        val labels = listOf("PD-3P", "A-78", "SYS·06", "CLU", "A-78", "PD-3P")
        for (i in 0..5) {
            val j = (i + 1) % 6
            val x = lerp(vx(i, (r + ri) / 2), vx(j, (r + ri) / 2), 0.5f); val y = lerp(vy(i, (r + ri) / 2), vy(j, (r + ri) / 2), 0.5f)
            var ang = (60.0 * i + 120).toFloat()
            if (ang > 90 && ang < 270) ang -= 180
            c.save(); c.rotate(ang, x, y); c.drawText(labels[i], x - text.measureText(labels[i]) / 2, y + 3 * d, text); c.restore()
        }
        val plus = stroke(0.9f, teal, 110)
        for ((fx, fy) in listOf(-0.62f to -0.05f, 0.62f to 0.05f, -0.30f to 0.52f, 0.34f to -0.52f, 0f to -0.58f, 0f to 0.6f, -0.45f to -0.36f, 0.45f to 0.38f)) {
            val x = cx + fx * r; val y = cy + fy * r
            c.drawLine(x - 3 * d, y, x + 3 * d, y, plus); c.drawLine(x, y - 3 * d, x, y + 3 * d, plus)
        }
    }

    override fun onDraw(c: Canvas) {
        val f = frame ?: return
        if (mode == Mode.OFFLINE) {
            c.drawBitmap(f, 0f, 0f, grey)
            c.drawCircle(vx(5, r * 1.035f) + 12 * d, vy(5, r * 1.035f), 3 * d, offlineDot)
            return
        }
        c.drawBitmap(f, 0f, 0f, bmpPaint)
        val t = (System.nanoTime() - t0) / 1e9f
        smooth += (level.coerceIn(0f, 1f) - smooth) * 0.25f
        when (mode) {
            Mode.IDLE -> { glowThin.alpha = 70; c.drawPath(inner, glowThin) }
            Mode.LISTENING -> {
                val a = 0.35f + 0.65f * smooth
                glowWide.alpha = (40 + 120 * smooth).toInt(); glowThin.alpha = (120 + 135 * a).toInt().coerceAtMost(255)
                c.drawPath(inner, glowWide); c.drawPath(inner, glowThin)
                hex(seg, ri * (0.97f - 0.05f * smooth)); ripple.alpha = (60 + 140 * smooth).toInt(); c.drawPath(seg, ripple)
            }
            Mode.THINKING -> {
                glowThin.alpha = 150; c.drawPath(inner, glowThin)
                measure.setPath(inner, true)
                val len = measure.length; val segLen = len * 0.14f
                for (k in 0..1) {
                    val start = ((t * 0.55f + k * 0.5f) % 1f) * len
                    seg.reset()
                    if (start + segLen <= len) measure.getSegment(start, start + segLen, seg, true)
                    else { measure.getSegment(start, len, seg, true); measure.getSegment(0f, start + segLen - len, seg, true) }
                    c.drawPath(seg, sweep)
                }
            }
            Mode.SPEAKING -> {
                glowWide.alpha = (50 + 150 * smooth).toInt(); glowThin.alpha = (150 + 105 * smooth).toInt()
                c.drawPath(inner, glowWide); c.drawPath(inner, glowThin)
                for (k in 0..1) {
                    val p = (t * 0.9f + k * 0.5f) % 1f
                    hex(seg, ri + (r - ri) * 1.15f * p)
                    ripple.alpha = ((1 - p) * (40 + 180 * smooth)).toInt().coerceIn(0, 255)
                    c.drawPath(seg, ripple)
                }
            }
            else -> {}
        }
        if (mode == Mode.LISTENING || mode == Mode.THINKING || mode == Mode.SPEAKING) postInvalidateOnAnimation()
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
