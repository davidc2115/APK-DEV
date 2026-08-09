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
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

class OrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING }
    enum class VisualStyle { PULSE, NETWORK_SPHERE }

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

    var visualStyle: VisualStyle = VisualStyle.PULSE
        set(value) {
            field = value
            invalidate()
        }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f
    }

    private data class Point3D(val x: Float, val y: Float, val z: Float)

    private val spherePoints: List<Point3D> by lazy { generateSpherePoints(90) }

    private fun generateSpherePoints(count: Int): List<Point3D> {
        val points = mutableListOf<Point3D>()
        val golden = Math.PI * (3.0 - sqrt(5.0))
        for (i in 0 until count) {
            val y = 1f - (i / (count - 1f)) * 2f
            val radiusAtY = sqrt((1 - y * y).toDouble()).toFloat()
            val theta = golden * i
            val x = (cos(theta) * radiusAtY).toFloat()
            val z = (sin(theta) * radiusAtY).toFloat()
            points.add(Point3D(x, y, z))
        }
        return points
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
        when (visualStyle) {
            VisualStyle.PULSE -> drawPulseOrb(canvas)
            VisualStyle.NETWORK_SPHERE -> drawNetworkSphere(canvas)
        }
    }

    private fun drawPulseOrb(canvas: Canvas) {
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

    private fun drawNetworkSphere(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val scale = minOf(width, height) / 2f * 0.85f

        // vitesse de rotation liée à l'état (écoute/réflexion/parole = plus rapide)
        val speedFactor = when (state) {
            OrbState.IDLE -> 0.4f
            OrbState.LISTENING -> 1f
            OrbState.THINKING -> 1.8f
            OrbState.SPEAKING -> 1.3f
        }
        val angle = pulsePhase * 2f * Math.PI.toFloat() * speedFactor
        val cosA = cos(angle)
        val sinA = sin(angle)
        val wobble = 0.25f * sin(angle * 0.5f)
        val cosW = cos(wobble)
        val sinW = sin(wobble)

        val screenPoints = spherePoints.map { p ->
            // rotation autour de l'axe Y
            val rx = p.x * cosA - p.z * sinA
            val rz = p.x * sinA + p.z * cosA
            // léger balancement autour de l'axe X
            val ry = p.y * cosW - rz * sinW
            val rz2 = p.y * sinW + rz * cosW
            Triple(cx + rx * scale, cy + ry * scale, rz2)
        }

        val threshold = scale * 0.42f
        for (i in screenPoints.indices) {
            val (x1, y1, z1) = screenPoints[i]
            for (j in i + 1 until screenPoints.size) {
                val (x2, y2, z2) = screenPoints[j]
                val dist = hypot((x1 - x2).toDouble(), (y1 - y2).toDouble()).toFloat()
                if (dist < threshold) {
                    val avgZ = (z1 + z2) / 2f
                    val alpha = (((avgZ + 1f) / 2f) * 70).toInt().coerceIn(6, 70)
                    linePaint.color = accentColor
                    linePaint.alpha = alpha
                    canvas.drawLine(x1, y1, x2, y2, linePaint)
                }
            }
        }

        for ((x, y, z) in screenPoints) {
            val depth = (z + 1f) / 2f
            val alpha = (depth * 210 + 45).toInt().coerceIn(45, 255)
            dotPaint.color = accentColor
            dotPaint.alpha = alpha
            val r = 2f + depth * 2.6f
            canvas.drawCircle(x, y, r, dotPaint)
        }
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
