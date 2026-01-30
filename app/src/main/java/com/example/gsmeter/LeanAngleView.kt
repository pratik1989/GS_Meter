package com.example.gsmeter

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class LeanAngleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentAngle: Float = 0f
    private var targetAngle: Float = 0f
    private var label: String = "LEAN"
    private var showSign: Boolean = false
    private var iconResId: Int = R.drawable.ic_motorcycle_rear
    
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var motorcycleIcon: Drawable? = null
    
    // Smoothing factor for visual interpolation (0.0 to 1.0)
    private val interpolationFactor = 0.15f

    companion object {
        const val MODE_LEAN = 0
        const val MODE_INCLINE = 1
    }

    init {
        loadIcon()
    }

    private fun loadIcon() {
        try {
            motorcycleIcon = ContextCompat.getDrawable(context, iconResId)
            motorcycleIcon?.let {
                it.setTint(Color.WHITE)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setConfiguration(label: String, showSign: Boolean, iconResId: Int = this.iconResId) {
        this.label = label
        this.showSign = showSign
        this.iconResId = iconResId
        loadIcon()
        invalidate()
    }

    fun setAngle(newAngle: Float) {
        this.targetAngle = newAngle
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Visual smoothing: Interpolate currentAngle towards targetAngle
        if (Math.abs(targetAngle - currentAngle) > 0.01f) {
            currentAngle += (targetAngle - currentAngle) * interpolationFactor
            postInvalidateOnAnimation()
        } else {
            currentAngle = targetAngle
        }

        val centerX = width / 2f
        val centerY = height / 2f
        val radius = (Math.min(width, height) / 2f) * 0.8f

        // Draw outer ring
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = Color.parseColor("#40FFFFFF")
        canvas.drawCircle(centerX, centerY, radius, paint)

        // Draw horizontal artificial horizon line
        canvas.save()
        canvas.rotate(currentAngle, centerX, centerY)
        
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.parseColor("#80FFFFFF") // semi-transparent white
        canvas.drawLine(centerX - radius * 0.8f, centerY, centerX + radius * 0.8f, centerY, paint)
        
        // Draw Center Motorcycle Icon
        motorcycleIcon?.let { icon ->
            val iconSize = (radius * 1.0f).toInt()
            val left = (centerX - iconSize / 2).toInt()
            val top = (centerY - iconSize / 2).toInt()
            icon.setBounds(left, top, left + iconSize, top + iconSize)
            icon.draw(canvas)
        }

        canvas.restore()

        // Draw ticks
        paint.strokeWidth = 2f
        paint.color = Color.parseColor("#40FFFFFF")
        for (i in -60..60 step 10) {
            val tickAngle = i.toFloat()
            val startRadius = radius * 0.9f
            
            val stopX = centerX + radius * Math.sin(Math.toRadians(tickAngle.toDouble())).toFloat()
            val stopY = centerY - radius * Math.cos(Math.toRadians(tickAngle.toDouble())).toFloat()
            
            val startX = centerX + startRadius * Math.sin(Math.toRadians(tickAngle.toDouble())).toFloat()
            val startY = centerY - startRadius * Math.cos(Math.toRadians(tickAngle.toDouble())).toFloat()
            
            canvas.drawLine(startX, startY, stopX, stopY, paint)
        }

        // Draw Value text inside the gauge at the bottom
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textSize = 34f
        paint.textAlign = Paint.Align.CENTER
        
        val displayValue = if (showSign) {
            Math.round(currentAngle).toString()
        } else {
            Math.abs(Math.round(currentAngle)).toString()
        }
        
        canvas.drawText("${displayValue}°", centerX, centerY + radius * 0.7f, paint)
        
        // Draw Label
        paint.textSize = 14f
        paint.color = Color.parseColor("#3DBCDB")
        canvas.drawText(label, centerX, centerY - radius - 10f, paint)
    }
}
