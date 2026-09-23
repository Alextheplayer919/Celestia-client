package com.proxy.mcbedrock.hud

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * The small bar graph module: recent ping samples, drawn without allocation.
 *
 * Draws from a plain `List<Int>` of heights, so the maths is in [SampleRing.bars]
 * where it can be tested.
 */
class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF")
        strokeWidth = 1f
    }
    private val bar = RectF()

    private var heights: List<Int> = emptyList()
    private var barColor: Int = Color.parseColor("#4DA3FF")

    /** `heights` are pixel heights for each bar, oldest first. */
    fun submit(heights: List<Int>, color: Int) {
        this.heights = heights
        this.barColor = color
        barPaint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val count = heights.size
        if (count == 0) return

        val width = width.toFloat()
        val height = height.toFloat()
        val slot = width / count
        val gap = if (slot > 3f) 1.5f else 0f
        val barWidth = (slot - gap).coerceAtLeast(1f)

        for (index in 0 until count) {
            val barHeight = heights[index].coerceAtMost(height.toInt()).toFloat()
            if (barHeight <= 0f) continue
            val left = index * slot
            bar.top = height - barHeight
            bar.left = left
            bar.right = left + barWidth
            bar.bottom = height
            canvas.drawRoundRect(bar, 1f, 1f, barPaint)
        }
        canvas.drawLine(0f, height, width, height, baselinePaint)
    }
}
