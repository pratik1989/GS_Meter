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
    private val segmentGap = 6f

    // Base Colors
    private val colorGrey = Color.parseColor("#333333")
    private val colorMaroon = Color.parseColor("#441111")
    private val colorGreen = Color.parseColor("#4CAF50")
    private val colorOrange = Color.parseColor("#FF9800")
    private val colorDarkOrange = Color.parseColor("#FF5722")
    private val colorRed = Color.parseColor("#F44336")
    private val colorAmber = Color.parseColor("#FFBF00")

    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 250f
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(61, 188, 219)
        textSize = 42f
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.bmw_font_bold)
    }

    private val path = Path()
    private val measure = PathMeasure()
    private val labels = arrayOf("0", "20", "40", "60", "80", "100", "110", "135")

    fun setSpeed(speed: Float) {
        this.progress = speed.coerceIn(0f, maxProgress)
        invalidate()
    }

    private fun adjustBrightness(color: Int, factor: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = (hsv[2] * factor).coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }

    /**
     * Applies a LinearGradient perpendicular to the path direction to create a 3D tube effect.
     */
    private fun apply3DGradient(paint: Paint, x: Float, y: Float, nx: Float, ny: Float, baseColor: Int) {
        val dark = adjustBrightness(baseColor, 0.6f)
        val midDark = adjustBrightness(baseColor, 0.85f)
        val highlight = adjustBrightness(baseColor, 1.4f)
        
        val halfWidth = paint.strokeWidth / 2f
        
        // Start and end points of the gradient (across the stroke)
        val x0 = x - nx * halfWidth
        val y0 = y - ny * halfWidth
        val x1 = x + nx * halfWidth
        val y1 = y + ny * halfWidth
        
        paint.shader = LinearGradient(
            x0, y0, x1, y1,
            intArrayOf(dark, midDark, highlight, midDark, dark),
            floatArrayOf(0f, 0.2f, 0.5f, 0.8f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        
        val padding = 200f
        val radius = 150f

        path.reset()
        path.moveTo(padding, h - 50f)
        path.lineTo(padding, padding + radius)
        path.arcTo(
            RectF(padding, padding, padding + radius * 2, padding + radius * 2),
            180f, 90f, false
        )
        path.lineTo(w - 50f, padding)

        measure.setPath(path, false)
        val totalLength = measure.length
        val segmentLength = (totalLength - (segmentsCount - 1) * segmentGap) / segmentsCount

        val pos = FloatArray(2)
        val tan = FloatArray(2)

        // Draw background segments with 3D gradient
        for (i in 0 until segmentsCount) {
            val start = i * (segmentLength + segmentGap)
            val end = start + segmentLength
            
            measure.getPosTan(start + segmentLength / 2, pos, tan)
            val nx = tan[1]  // Normal X
            val ny = -tan[0] // Normal Y
            
            val baseColor = if (i >= segmentsCount - 3) colorMaroon else colorGrey
            apply3DGradient(segmentPaint, pos[0], pos[1], nx, ny, baseColor)
            
            val segmentPath = Path()
            measure.getSegment(start, end, segmentPath, true)
            canvas.drawPath(segmentPath, segmentPaint)
        }

        // Draw labels
        val labelOffset = 180f
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

        // Draw progress segments with 3D gradient
        val filledLength = totalLength * (progress / maxProgress)
        for (i in 0 until segmentsCount) {
            val start = i * (segmentLength + segmentGap)
            val end = start + segmentLength

            if (start < filledLength) {
                val segmentSpeed = (i.toFloat() / segmentsCount) * maxProgress
                val baseColor = when {
                    segmentSpeed <= 50f -> colorGreen
                    segmentSpeed <= 75f -> colorOrange
                    segmentSpeed <= 100f -> colorDarkOrange
                    else -> if (isFlashOn) colorRed else colorAmber
                }

                measure.getPosTan(start + segmentLength / 2, pos, tan)
                val nx = tan[1]
                val ny = -tan[0]

                apply3DGradient(segmentPaint, pos[0], pos[1], nx, ny, baseColor)

                val segmentPath = Path()
                val actualEnd = if (end > filledLength) filledLength else end
                measure.getSegment(start, actualEnd, segmentPath, true)
                canvas.drawPath(segmentPath, segmentPaint)
            }
        }

        if (progress > 100f) {
            postInvalidateDelayed(300)
        }
    }
}
