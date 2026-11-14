package tv.aura.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/** Fundal animat: pete mari de culoare care se misca lent (efect "aurora"). */
class AuroraView(context: Context) : View(context) {

    private class Blob(val color: Int, val radius: Float, val sx: Float, val sy: Float, val phase: Float)

    private val blobs = listOf(
        Blob(Color.rgb(72, 40, 170), 0.75f, 0.110f, 0.070f, 0.0f),
        Blob(Color.rgb(14, 100, 180), 0.65f, 0.070f, 0.130f, 2.0f),
        Blob(Color.rgb(150, 36, 100), 0.60f, 0.050f, 0.090f, 4.0f),
        Blob(Color.rgb(16, 130, 112), 0.50f, 0.090f, 0.050f, 1.0f)
    )
    private val paints = blobs.map { Paint(Paint.ANTI_ALIAS_FLAG) }
    private var shaders: List<RadialGradient> = emptyList()
    private val scrim = Paint()
    private val matrix2 = Matrix()
    private val start = SystemClock.uptimeMillis()

    var running: Boolean = true
        set(value) {
            field = value
            if (value) invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        shaders = blobs.map {
            RadialGradient(
                0f, 0f, it.radius * w,
                it.color, it.color and 0x00FFFFFF,
                Shader.TileMode.CLAMP
            )
        }
        scrim.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            Color.argb(40, 0, 0, 0), Color.argb(150, 0, 0, 0),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(5, 6, 10))
        if (shaders.isEmpty()) return
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in blobs.indices) {
            val b = blobs[i]
            val x = w * (0.5f + 0.45f * sin(t * b.sx + b.phase))
            val y = h * (0.5f + 0.45f * cos(t * b.sy + b.phase * 1.7f))
            matrix2.setTranslate(x, y)
            val sh = shaders[i]
            sh.setLocalMatrix(matrix2)
            paints[i].shader = sh
            canvas.drawRect(0f, 0f, w, h, paints[i])
        }
        canvas.drawRect(0f, 0f, w, h, scrim)
        if (running) postInvalidateOnAnimation()
    }
}
