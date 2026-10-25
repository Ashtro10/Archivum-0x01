package ai.clu.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Clu's animated particle orb: points on a slowly rotating, gently deforming sphere, coloured from blue (top) through
 * violet to orange-red (bottom), brighter towards the viewer. [level] (0..1) makes it swell and ripple with the voice;
 * [mode] changes the motion (idle, listening, thinking, speaking). Animates only while attached and visible.
 */
class OrbView(context: Context, private val points: Int = 1500) : View(context) {
    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING }

    var mode = Mode.IDLE
    /** Voice level 0..1 (microphone while listening). Smoothed internally. */
    var level = 0f
    private var smooth = 0f
    private val ux = FloatArray(points); private val uy = FloatArray(points); private val uz = FloatArray(points)
    private val buckets = 12
    private val depthLayers = 3
    private val arrays = Array(buckets * depthLayers) { FloatArray(points * 2) }
    private val counts = IntArray(buckets * depthLayers)
    private val paints = Array(buckets * depthLayers) { Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND } }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t0 = System.nanoTime()
    private var running = false

    init {
        // Fibonacci sphere: evenly spread points.
        val golden = PI * (3 - sqrt(5.0))
        for (i in 0 until points) {
            val y = 1 - 2.0 * (i + 0.5) / points
            val r = sqrt(1 - y * y)
            val th = golden * i
            ux[i] = (cos(th) * r).toFloat(); uy[i] = y.toFloat(); uz[i] = (sin(th) * r).toFloat()
        }
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private fun palette(u: Float): Int {
        // blue -> violet -> magenta -> orange
        val stops = intArrayOf(Color.rgb(80, 170, 255), Color.rgb(140, 90, 255), Color.rgb(220, 60, 200), Color.rgb(255, 95, 70), Color.rgb(255, 160, 60))
        val x = u.coerceIn(0f, 1f) * (stops.size - 1)
        val i = min(stops.size - 2, x.toInt()); val f = x - i
        val a = stops[i]; val b = stops[i + 1]
        return Color.rgb((Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(), (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt())
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); running = true; postInvalidateOnAnimation() }
    override fun onDetachedFromWindow() { running = false; super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        running = isVisible && isAttachedToWindow
        if (running) postInvalidateOnAnimation()
    }

    override fun onDraw(c: Canvas) {
        val t = ((System.nanoTime() - t0) / 1e9).toFloat()
        val target = when (mode) { Mode.LISTENING -> 0.25f + level; Mode.SPEAKING -> 0.55f + 0.25f * sin(t * 7f) * sin(t * 2.3f); Mode.THINKING -> 0.35f; Mode.IDLE -> 0.12f }
        smooth += (target.coerceIn(0f, 1.2f) - smooth) * 0.12f
        val cx = width / 2f; val cy = height / 2f
        val radius = min(width, height) * 0.36f * (1f + 0.06f * smooth)
        // soft glow behind the sphere
        glow.shader = RadialGradient(cx, cy, radius * 1.6f, intArrayOf(Color.argb((40 + 60 * smooth).toInt().coerceAtMost(110), 150, 70, 255), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, radius * 1.6f, glow)
        val spin = t * (if (mode == Mode.THINKING) 0.9f else 0.25f)
        val cs = cos(spin); val sn = sin(spin)
        val tilt = 0.35f; val ct = cos(tilt); val st = sin(tilt)
        val amp = 0.05f + 0.16f * smooth
        counts.fill(0)
        for (i in 0 until points) {
            var x = ux[i]; var y = uy[i]; var z = uz[i]
            // organic deformation: layered travelling waves over the sphere
            val d = 1f + amp * (sin(3f * y + t * 1.7f) * cos(2f * x - t * 1.1f) + 0.6f * sin(4f * z + t * 2.3f + y * 2f))
            x *= d; y *= d; z *= d
            val rx = x * cs + z * sn; val rz = -x * sn + z * cs           // rotate around Y
            val ry = y * ct - rz * st; val rz2 = y * st + rz * ct        // tilt around X
            val depth = ((rz2 + 1.2f) / 2.4f).coerceIn(0f, 1f)           // 0 back .. 1 front
            val layer = min(depthLayers - 1, (depth * depthLayers).toInt())
            val colU = ((-uy[i] * 0.75f + ux[i] * 0.25f) + 1f) / 2f      // by original position: blue top, orange bottom
            val bucket = min(buckets - 1, (colU * buckets).toInt())
            val k = bucket * depthLayers + layer
            val n = counts[k]
            arrays[k][n] = cx + rx * radius; arrays[k][n + 1] = cy - ry * radius // screen y grows downwards
            counts[k] = n + 2
        }
        val px = resources.displayMetrics.density
        for (k in arrays.indices) {
            if (counts[k] == 0) continue
            val p = paints[k]
            p.strokeWidth = px * (1.1f + 0.9f * (k % depthLayers)) * (0.9f + 0.3f * smooth)
            c.drawPoints(arrays[k], 0, counts[k], p)
        }
        if (running) postInvalidateOnAnimation()
    }

    init {
        // Paint per (colour bucket, depth layer): colours along the palette, back points dimmer than front points.
        for (i in paints.indices) {
            val col = palette((i / depthLayers) / (buckets - 1f))
            paints[i].color = Color.argb(intArrayOf(70, 150, 235)[i % depthLayers], Color.red(col), Color.green(col), Color.blue(col))
        }
    }
}

/** White microphone glyph (capsule, cradle and stand) drawn with paths, for the gradient mic buttons. */
class MicDrawable(private val stop: Boolean = false) : android.graphics.drawable.Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    override fun draw(c: Canvas) {
        val b = bounds; val s = min(b.width(), b.height()) * 0.42f; val cx = b.exactCenterX(); val cy = b.exactCenterY()
        if (stop) { c.drawRoundRect(cx - s * 0.42f, cy - s * 0.42f, cx + s * 0.42f, cy + s * 0.42f, s * 0.14f, s * 0.14f, f); return }
        p.strokeWidth = s * 0.13f
        c.drawRoundRect(cx - s * 0.26f, cy - s * 0.78f, cx + s * 0.26f, cy + s * 0.18f, s * 0.26f, s * 0.26f, f)
        c.drawArc(cx - s * 0.52f, cy - s * 0.45f, cx + s * 0.52f, cy + s * 0.42f, 10f, 160f, false, p)
        c.drawLine(cx, cy + s * 0.43f, cx, cy + s * 0.72f, p)
        c.drawLine(cx - s * 0.28f, cy + s * 0.74f, cx + s * 0.28f, cy + s * 0.74f, p)
    }
    override fun setAlpha(alpha: Int) { p.alpha = alpha; f.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { p.colorFilter = cf; f.colorFilter = cf }
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
