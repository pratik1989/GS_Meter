package com.example.gsmeter

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.res.ResourcesCompat

class GSSpeedometerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var progress: Float = 0f
    private var maxProgress: Float = 135f
    private val segmentsCount = 20
    private val segmentGap = 6f // Slightly larger gap for broader bar

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333")
        style = Paint.Style.STROKE
        strokeWidth = 250f // Doubled from 125f
    }

    private val maroonBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#441111") // Dark maroon
        style = Paint.Style.STROKE
        strokeWidth = 250f
    }

    private val greenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")
        style = Paint.Style.STROKE
        strokeWidth = 250f
    }

    private val orangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9800")
        style = Paint.Style.STROKE
        strokeWidth = 250f
    }

    private val darkOrangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5722")
        style = Paint.Style.STROKE
        strokeWidth = 250f
    }

    private val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F44336")
        style = Paint.Style.STROKE
        strokeWidth = 250f
    }

    private val amberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFBF00") // Amber
        style = Paint.Style.STROKE
        strokeWidth = 250f
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(61, 188, 219)
        textSize = 42f // Slightly larger labels
        textAlign = Paint.Align.CENTER
        // Set to BMW bold font
        typeface = ResourcesCompat.getFont(context, R.font.bmw_font_bold)
    }

    private val path = Path()
    private val measure = PathMeasure()
    private val labels = arrayOf("0", "20", "40", "60", "80", "100", "110", "135")

    fun setSpeed(speed: Float) {
        this.progress = speed.coerceIn(0f, maxProgress)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        
        // Increased padding and radius to fit the 250f stroke and labels
        val padding = 200f
        val radius = 150f

        path.reset()
        path.moveTo(padding, h - 50f) // Start closer to bottom edge
        path.lineTo(padding, padding + radius)
        path.arcTo(
            RectF(padding, padding, padding + radius * 2, padding + radius * 2),
            180f, 90f, false
        )
        path.lineTo(w - 50f, padding) // Extend closer to right edge

        measure.setPath(path, false)
        val totalLength = measure.length
        val segmentLength = (totalLength - (segmentsCount - 1) * segmentGap) / segmentsCount

        // Draw background segments
        for (i in 0 until segmentsCount) {
            val start = i * (segmentLength + segmentGap)
            val end = start + segmentLength
            val segmentPath = Path()
            measure.getSegment(start, end, segmentPath, true)
            
            // Apply dark maroon to the last 3 segments as requested
            val paint = if (i >= segmentsCount - 3) maroonBackgroundPaint else backgroundPaint
            canvas.drawPath(segmentPath, paint)
        }

        // Draw labels with increased offset for thicker bar
        val pos = FloatArray(2)
        val tan = FloatArray(2)
        val labelOffset = 180f // Increased to stay outside the 250f stroke (125f radius)
        for (i in labels.indices) {
            val distance = (i.toFloat() / (labels.size - 1)) * totalLength
            measure.getPosTan(distance, pos, tan)
            
            val nx = tan[1]
            val ny = -tan[0]
            
            val lx = pos[0] + nx * labelOffset
            val ly = pos[1] + ny * labelOffset
            
            canvas.drawText(labels[i], lx, ly + 15f, labelPaint)
        }

        val isFlashOn = (System.currentTimeMillis() / 300) % 2 == 0L

        // Draw progress segments
        val filledLength = totalLength * (progress / maxProgress)
        for (i in 0 until segmentsCount) {
            val start = i * (segmentLength + segmentGap)
            val end = start + segmentLength

            if (start < filledLength) {
                val segmentSpeed = (i.toFloat() / segmentsCount) * maxProgress

                val paint = when {
                    segmentSpeed <= 50f -> greenPaint
                    segmentSpeed <= 75f -> orangePaint
                    segmentSpeed <= 100f -> darkOrangePaint
                    else -> if (isFlashOn) redPaint else amberPaint
                }

                val segmentPath = Path()
                val actualEnd = if (end > filledLength) filledLength else end
                measure.getSegment(start, actualEnd, segmentPath, true)
                canvas.drawPath(segmentPath, paint)
            }
        }

        if (progress > 100f) {
            postInvalidateDelayed(300)
        }
    }
}
