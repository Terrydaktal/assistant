package com.example.earpieceai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.sin

class VoiceWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private var amplitude = 0f
    private var isActive = false

    fun setActive(active: Boolean) {
        isActive = active
        visibility = if (active) VISIBLE else INVISIBLE
        postInvalidateOnAnimation()
    }

    fun setAmplitude(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        amplitude = (amplitude * 0.65f) + (clamped * 0.35f)
        if (isActive) {
            alpha = (0.2f + amplitude * 0.8f).coerceIn(0.2f, 1f)
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        if (!isActive || visibility != VISIBLE) {
            return
        }

        val size = min(width, height).toFloat()
        val centerX = width / 2f
        val centerY = height / 2f
        val barCount = 5
        val gapRatio = 0.6f
        val availableWidth = size * 0.7f
        val barWidth = availableWidth / (barCount + (barCount - 1) * gapRatio)
        val gap = barWidth * gapRatio
        val totalWidth = barCount * barWidth + (barCount - 1) * gap
        val startX = centerX - totalWidth / 2f
        val baseHeight = size * 0.22f
        val maxHeight = size * 0.6f
        val time = SystemClock.uptimeMillis() / 140f

        for (i in 0 until barCount) {
            val wobble = 0.7f + 0.3f * sin(time + i)
            val height = baseHeight + (maxHeight * amplitude * wobble)
            val left = startX + i * (barWidth + gap)
            val right = left + barWidth
            val top = centerY - height / 2f
            val bottom = centerY + height / 2f
            val radius = barWidth / 2f
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint)
        }

        // Keep animating while active so the waveform isn't static when amplitude is steady.
        postInvalidateOnAnimation()
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }
}
