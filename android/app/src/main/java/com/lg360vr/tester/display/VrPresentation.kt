package com.lg360vr.tester.display

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Display
import android.view.View

/**
 * Full-screen test pattern shown on the external display (the headset, once it
 * enumerates as a Presentation display via USB-C DP-alt-mode).
 *
 * Draws SMPTE-style colour bars, a grid, a centre crosshair and live metrics so
 * you can confirm the headset actually receives and renders video.
 */
class VrPresentation(
    outerContext: Context,
    display: Display,
    private val pattern: TestPattern = TestPattern.BARS,
) : Presentation(outerContext, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(PatternView(context, pattern))
    }
}

enum class TestPattern { BARS, GRID, CROSSHAIR, SOLID_WHITE }

private class PatternView(
    context: Context,
    private val pattern: TestPattern,
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    private val bars = intArrayOf(
        Color.WHITE, Color.YELLOW, Color.CYAN, Color.GREEN,
        Color.MAGENTA, Color.RED, Color.BLUE, Color.BLACK,
    )

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        when (pattern) {
            TestPattern.SOLID_WHITE -> canvas.drawColor(Color.WHITE)
            TestPattern.BARS -> drawBars(canvas, w, h)
            TestPattern.GRID -> { canvas.drawColor(Color.BLACK); drawGrid(canvas, w, h) }
            TestPattern.CROSSHAIR -> { canvas.drawColor(Color.BLACK); drawCrosshair(canvas, w, h) }
        }
        drawInfo(canvas, w, h)
    }

    private fun drawBars(canvas: Canvas, w: Float, h: Float) {
        val bw = w / bars.size
        for (i in bars.indices) {
            paint.color = bars[i]
            canvas.drawRect(i * bw, 0f, (i + 1) * bw, h * 0.85f, paint)
        }
        // Gradient strip bottom
        for (x in 0 until width) {
            val g = (255 * x / width)
            paint.color = Color.rgb(g, g, g)
            canvas.drawRect(x.toFloat(), h * 0.85f, (x + 1).toFloat(), h, paint)
        }
    }

    private fun drawGrid(canvas: Canvas, w: Float, h: Float) {
        paint.color = Color.rgb(0, 200, 120)
        paint.strokeWidth = 2f
        val step = w / 24f
        var x = 0f
        while (x <= w) { canvas.drawLine(x, 0f, x, h, paint); x += step }
        var y = 0f
        while (y <= h) { canvas.drawLine(0f, y, w, y, paint); y += step }
    }

    private fun drawCrosshair(canvas: Canvas, w: Float, h: Float) {
        paint.color = Color.rgb(90, 200, 250)
        paint.strokeWidth = 3f
        canvas.drawLine(0f, h / 2f, w, h / 2f, paint)
        canvas.drawLine(w / 2f, 0f, w / 2f, h, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        var r = minOf(w, h) / 12f
        while (r < maxOf(w, h)) { canvas.drawCircle(w / 2f, h / 2f, r, paint); r += minOf(w, h) / 12f }
        paint.style = Paint.Style.FILL
    }

    private fun drawInfo(canvas: Canvas, w: Float, h: Float) {
        val info = "LG 360 VR  ${width}×${height}  •  Test: ${pattern.name}"
        canvas.drawText(info, 40f, 70f, text)
        canvas.drawText("Wenn du das siehst, empfängt die Brille Video ✓", 40f, h - 50f, text)
    }
}
