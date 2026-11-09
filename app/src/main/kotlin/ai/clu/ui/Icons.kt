package ai.clu.ui

import android.graphics.*
import android.graphics.drawable.Drawable

/**
 * CLU's line icons, drawn with thin strokes on a 24-unit grid (one consistent style instead of emoji): microphone,
 * microphone-on, send, stop, speaker on/off, attach, menu, hexagon (voice), back, close, keyboard, copy.
 */
class Icon(val kind: Kind, color: Int, private val sizeDp: Float, private val d: Float) : Drawable() {
    enum class Kind { MIC, MIC_ON, SEND, STOP, SPEAKER, SPEAKER_OFF, ATTACH, MENU, HEX, BACK, CLOSE, KEYBOARD, COPY, IMAGE }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; this.color = color }
    private val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
    private val path = Path()
    var color: Int get() = p.color; set(v) { p.color = v; f.color = v; invalidateSelf() }

    override fun getIntrinsicWidth() = (sizeDp * d).toInt()
    override fun getIntrinsicHeight() = (sizeDp * d).toInt()

    override fun draw(c: Canvas) {
        val b = bounds; val s = minOf(b.width(), b.height()) / 24f
        c.save(); c.translate(b.exactCenterX() - 12 * s, b.exactCenterY() - 12 * s); c.scale(s, s)
        p.strokeWidth = 1.6f * d / s
        path.reset()
        when (kind) {
            Kind.MIC, Kind.MIC_ON -> {
                c.drawRoundRect(9f, 3f, 15f, 14f, 3f, 3f, if (kind == Kind.MIC_ON) f else p)
                path.moveTo(6f, 11f); path.quadTo(6f, 17.5f, 12f, 17.5f); path.quadTo(18f, 17.5f, 18f, 11f); c.drawPath(path, p)
                c.drawLine(12f, 17.5f, 12f, 21f, p); c.drawLine(9f, 21f, 15f, 21f, p)
            }
            Kind.SEND -> { path.moveTo(12f, 20f); path.lineTo(12f, 5f); path.moveTo(6f, 11f); path.lineTo(12f, 5f); path.lineTo(18f, 11f); c.drawPath(path, p) }
            Kind.STOP -> c.drawRect(7.5f, 7.5f, 16.5f, 16.5f, f)
            Kind.SPEAKER, Kind.SPEAKER_OFF -> {
                path.moveTo(4f, 9.5f); path.lineTo(7.5f, 9.5f); path.lineTo(12f, 5.5f); path.lineTo(12f, 18.5f); path.lineTo(7.5f, 14.5f); path.lineTo(4f, 14.5f); path.close(); c.drawPath(path, p)
                if (kind == Kind.SPEAKER) { c.drawArc(11f, 8f, 18f, 16f, -50f, 100f, false, p); c.drawArc(11f, 5f, 21f, 19f, -50f, 100f, false, p) }
                else { c.drawLine(15.5f, 9.5f, 20.5f, 14.5f, p); c.drawLine(20.5f, 9.5f, 15.5f, 14.5f, p) }
            }
            Kind.ATTACH -> { c.drawLine(12f, 5f, 12f, 19f, p); c.drawLine(5f, 12f, 19f, 12f, p) }
            Kind.IMAGE -> { c.drawRect(4f, 5f, 20f, 19f, p); path.moveTo(4f, 16f); path.lineTo(9f, 11f); path.lineTo(13f, 15f); path.lineTo(15.5f, 12.5f); path.lineTo(20f, 17f); c.drawPath(path, p); c.drawCircle(15.5f, 8.8f, 1.3f, f) }
            Kind.MENU -> { c.drawLine(4f, 7f, 20f, 7f, p); c.drawLine(4f, 12f, 16f, 12f, p); c.drawLine(4f, 17f, 12f, 17f, p) }
            Kind.HEX -> {
                for (i in 0..5) { val a = Math.PI / 3 * i; val x = 12 + 8.5f * Math.cos(a).toFloat(); val y = 12 + 8.5f * Math.sin(a).toFloat(); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
                path.close(); c.drawPath(path, p)
                // Two slanted eye marks inside.
                c.drawLine(7.5f, 11f, 10.5f, 12.2f, p); c.drawLine(16.5f, 11f, 13.5f, 12.2f, p)
            }
            Kind.BACK -> { path.moveTo(14.5f, 5f); path.lineTo(7.5f, 12f); path.lineTo(14.5f, 19f); c.drawPath(path, p) }
            Kind.CLOSE -> { c.drawLine(6.5f, 6.5f, 17.5f, 17.5f, p); c.drawLine(17.5f, 6.5f, 6.5f, 17.5f, p) }
            Kind.KEYBOARD -> {
                c.drawRect(3f, 6f, 21f, 18f, p)
                for (row in 0..1) for (col in 0..4) c.drawCircle(6f + col * 3f, 9.5f + row * 3f, 0.7f, f)
                c.drawLine(8f, 15.5f, 16f, 15.5f, p)
            }
            Kind.COPY -> { c.drawRect(8f, 8f, 19f, 19f, p); path.moveTo(5f, 15f); path.lineTo(5f, 5f); path.lineTo(15f, 5f); c.drawPath(path, p) }
        }
        c.restore()
    }

    override fun setAlpha(alpha: Int) { p.alpha = alpha; f.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf; f.colorFilter = cf }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

fun android.content.Context.icon(kind: Icon.Kind, color: Int = C.ACCENT2, size: Float = 24f) = Icon(kind, color, size, resources.displayMetrics.density)
