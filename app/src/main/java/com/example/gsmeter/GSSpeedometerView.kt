package com.example.gsmeter

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.res.ResourcesCompat

class GSSpeedometerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var progress: Float = 0f
    private var targetProgress: Float = 0f
    private var maxProgress: Float = 135f
    private val segmentsCount = 20
    private val segmentGap = 6f
    
    private var speedAnimator: ValueAnimator? = null

    // Pre-allocated objects for drawing
    private val drawPos = FloatArray(2)
    private val drawTan = FloatArray(2)
    private val arcRect = RectF()
    private val segmentPath = Path()
    private val hsv = FloatArray(3)

    // Base Colors
    private val colorGrey = Color.parseColor("#333333")
    private val colorMaroon = Color.parseColor("#441111")
    private val colorElectricBlue = Color.parseColor("#2A9AB8") // Darker shade of #3DBCDB
    private val colorAmber = Color.parseColor("#FFBF00")

    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 225f // Reduced by 10% from 250f
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(61, 188, 219)
        textSize = 42f
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.bmw_font_bold)
    }

    private val endMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(128, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 20f
        strokeCap = Paint.Cap.BUTT
    }

    private val path = Path()
    private val measure = PathMeasure()
    private val labels = arrayOf("0", "20", "30", "40", "60", "80", "100", "120", "135")

    fun setSpeed(speed: Float) {
        val newTarget = speed.coerceIn(0f, maxProgress)
        if (Math.abs(newTarget - targetProgress) < 0.1f) return
        
        targetProgress = newTarget
        
        speedAnimator?.cancel()
        
        // To mimic an analogue speedometer, we increase the duration and use an
        // AccelerateDecelerateInterpolator to simulate physical inertia.
        val delta = Math.abs(newTarget - progress)
        val dynamicDuration = (delta * 30 + 600).toLong().coerceIn(1000, 3000)

        speedAnimator = ValueAnimator.ofFloat(progress, targetProgress).apply {
            duration = dynamicDuration
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun adjustBrightness(color: Int, factor: Float): Int {
        Color.colorToHSV(color, hsv)
        hsv[2] = (hsv[2] * factor).coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }

    private fun apply3DGradient(paint: Paint, x: Float, y: Float, nx: Float, ny: Float, baseColor: Int) {
        val dark = adjustBrightness(baseColor, 0.6f)
        val midDark = adjustBrightness(baseColor, 0.85f)
        val highlight = adjustBrightness(baseColor, 1.4f)
        val halfWidth = paint.strokeWidth / 2f
        val x0 = x - nx * halfWidth
        val y0 = y - ny * halfWidth
        val x1 = x + nx * halfWidth
        val y1 = y + ny * halfWidth
        paint.shader = LinearGradient(x0, y0, x1, y1,
            intArrayOf(dark, midDark, highlight, midDark, dark),
            floatArrayOf(0f, 0.2f, 0.5f, 0.8f, 1f), Shader.TileMode.CLAMP)
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
        arcRect.set(padding, padding, padding + radius * 2, padding + radius * 2)
        path.arcTo(arcRect, 180f, 90f, false)
        path.lineTo(w - 50f, padding)

        measure.setPath(path, false)
        val totalLength = measure.length
        val segmentLength = (totalLength - (segmentsCount - 1) * segmentGap) / segmentsCount

        // Draw background segments
        for (i in 0 until segmentsCount) {
            val start = i * (segmentLength + segmentGap)
            measure.getPosTan(start + segmentLength / 2, drawPos, drawTan)
            val baseColor = if (i >= segmentsCount - 3) colorMaroon else colorGrey
            apply3DGradient(segmentPaint, drawPos[0], drawPos[1], drawTan[1], -drawTan[0], baseColor)
            segmentPath.reset()
            measure.getSegment(start, start + segmentLength, segmentPath, true)
            canvas.drawPath(segmentPath, segmentPaint)
        }

        // Draw labels
        val labelOffset = 180f
        for (label in labels) {
            val speedValue = label.toFloatOrNull() ?: 0f
            val distance = (speedValue / maxProgress) * totalLength
            measure.getPosTan(distance, drawPos, drawTan)
            canvas.drawText(label, drawPos[0] + drawTan[1] * labelOffset, drawPos[1] - drawTan[0] * labelOffset + 15f, labelPaint)
        }

        // Draw progress segments
        val filledLength = totalLength * (progress / maxProgress)
        val isFlashOn = (System.currentTimeMillis() / 300) % 2 == 0L
        
        for (i in 0 until segmentsCount) {
            val start = i * (segmentLength + segmentGap)
            val isLastThree = i >= segmentsCount - 3
            val isSpeedHigh = progress > 100f
            
            // Draw only up to filledLength (where the white line is)
            if (start < filledLength) {
                val baseColor = if (!isLastThree) {
                    colorElectricBlue
                } else {
                    // Only last three segments flash when speed is high (> 100)
                    if (isSpeedHigh) {
                        if (isFlashOn) colorAmber else colorMaroon
                    } else {
                        colorElectricBlue
                    }
                }
                
                measure.getPosTan(start + segmentLength / 2, drawPos, drawTan)
                apply3DGradient(segmentPaint, drawPos[0], drawPos[1], drawTan[1], -drawTan[0], baseColor)
                segmentPath.reset()
                
                // Draw partial segment up to filledLength
                val end = Math.min(start + segmentLength, filledLength)
                measure.getSegment(start, end, segmentPath, true)
                canvas.drawPath(segmentPath, segmentPaint)
            }
        }

        // Draw end marker line logic
        if (filledLength > 0) {
             measure.getPosTan(filledLength, drawPos, drawTan)
             val nx = drawTan[1]
             val ny = -drawTan[0]
             val hw = segmentPaint.strokeWidth / 2f
             canvas.drawLine(drawPos[0] - nx * hw, drawPos[1] - ny * hw, drawPos[0] + nx * hw, drawPos[1] + ny * hw, endMarkerPaint)
        }

        if (progress > 100f) postInvalidateDelayed(300)
    }

    override fun onDetachedFromWindow() {
        speedAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
