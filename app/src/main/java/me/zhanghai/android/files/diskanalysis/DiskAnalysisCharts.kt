package me.zhanghai.android.files.diskanalysis

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

class StorageDonutChartView(context: Context) : View(context) {
    var usedFraction: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var primaryColor: Int = Color.parseColor("#4285F4")
    var trackColor: Int = Color.parseColor("#26888888")
    var centerTitle: String = "0%"
        set(value) {
            field = value
            invalidate()
        }
    var centerSubtitle: String = ""
        set(value) {
            field = value
            invalidate()
        }
    var textColor: Int = Color.WHITE

    private val density = resources.displayMetrics.density
    private val strokePx = 14f * density
    private val rectF = RectF()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        strokeCap = Paint.Cap.ROUND
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        strokeCap = Paint.Cap.ROUND
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 20f * resources.displayMetrics.scaledDensity
        isFakeBoldText = true
    }

    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 11f * resources.displayMetrics.scaledDensity
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        if (size <= 0f) return
        val pad = strokePx / 2f + 4f * density
        val cx = width / 2f
        val cy = height / 2f
        val radius = (size / 2f) - pad
        rectF.set(cx - radius, cy - radius, cx + radius, cy + radius)

        trackPaint.color = trackColor
        progressPaint.color = primaryColor
        titlePaint.color = textColor
        subtitlePaint.color = (textColor and 0x00FFFFFF) or 0xB3000000.toInt()

        canvas.drawOval(rectF, trackPaint)
        val sweep = usedFraction * 360f
        if (sweep > 0f) {
            canvas.drawArc(rectF, -90f, sweep, false, progressPaint)
        }

        if (centerSubtitle.isNotEmpty()) {
            canvas.drawText(centerTitle, cx, cy + 2f * density, titlePaint)
            canvas.drawText(centerSubtitle, cx, cy + 18f * density, subtitlePaint)
        } else {
            canvas.drawText(centerTitle, cx, cy + 7f * density, titlePaint)
        }
    }
}

class CategoryStackedBarView(context: Context) : View(context) {
    data class Segment(val color: Int, val weight: Float)

    var segments: List<Segment> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#22888888")
    }
    private val rectF = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = h / 2f
        rectF.set(0f, 0f, w, h)
        canvas.drawRoundRect(rectF, r, r, bgPaint)

        val total = segments.sumOf { it.weight.toDouble() }.toFloat()
        if (total <= 0f) return

        val saveCount = canvas.save()
        var currentX = 0f
        for (segment in segments) {
            if (segment.weight <= 0f) continue
            val segWidth = (segment.weight / total) * w
            paint.color = segment.color
            canvas.drawRect(currentX, 0f, currentX + segWidth, h, paint)
            currentX += segWidth
        }
        canvas.restoreToCount(saveCount)
    }
}
