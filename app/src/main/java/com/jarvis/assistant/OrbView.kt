package com.jarvis.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

class OrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING }

    var accentColor: Int = Prefs.DEFAULT_ACCENT_COLOR
        set(value) {
            field = value
            invalidate()
        }

    var state: OrbState = OrbState.IDLE
        set(value) {
            field = value
            updateAnimatorSpeed()
        }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private var pulsePhase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            pulsePhase = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        animator.start()
    }

    private fun updateAnimatorSpeed() {
        animator.duration = when (state) {
            OrbState.IDLE -> 3200L
            OrbState.LISTENING -> 1100L
            OrbState.THINKING -> 650L
            OrbState.SPEAKING -> 900L
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = minOf(width, height) / 2f * 0.5f

        val ringCount = 3
        for (i in 0 until ringCount) {
            val progress = (pulsePhase + i / ringCount.toFloat()) % 1f
            val radius = baseRadius * (0.95f + progress * 0.7f)
            val alpha = ((1f - progress) * 90).toInt().coerceIn(0, 90)
            ringPaint.color = accentColor
            ringPaint.alpha = alpha
            ringPaint.strokeWidth = 3f + (1f - progress) * 4f
            canvas.drawCircle(cx, cy, radius, ringPaint)
        }

        val coreRadius = baseRadius * (0.55f + 0.08f * sin(pulsePhase * 2 * Math.PI).toFloat())
        corePaint.shader = RadialGradient(
            cx, cy, coreRadius * 1.9f,
            intArrayOf(accentColor, adjustAlpha(accentColor, 0.35f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, coreRadius * 1.9f, corePaint)

        corePaint.shader = null
        corePaint.color = Color.argb(
            230,
            Color.red(accentColor),
            Color.green(accentColor),
            Color.blue(accentColor)
        )
        canvas.drawCircle(cx, cy, coreRadius * 0.45f, corePaint)
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).toInt()
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }
}
