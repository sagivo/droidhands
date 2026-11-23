package com.droidhands.android.ui.connect

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.core.animation.doOnEnd
import kotlin.random.Random

/**
 * A short confetti burst over the whole window, from the top edge. Drawn by a plain, non-clickable view on the
 * decor view, so touches go through to the screen below; it removes itself when done. Nothing happens when
 * animations are off (Settings > Accessibility > Remove animations).
 */
internal object Confetti {
    fun burst(anyView: View) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val root = anyView.rootView as? ViewGroup ?: return
        val confetti = ConfettiView(root.context)
        root.addView(confetti, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        confetti.start { root.removeView(confetti) }
    }
}

private class ConfettiView(context: Context) : View(context) {
    private class Piece(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var angle: Float,
        val spin: Float,
        val wobble: Float,
        val width: Float,
        val height: Float,
        val color: Int,
        val round: Boolean,
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var pieces = emptyList<Piece>()
    private var lastFrameMs = 0L
    private var alphaFraction = 1f

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun start(onDone: () -> Unit) {
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener { step(it.animatedFraction, it.currentPlayTime) }
            doOnEnd { onDone() }
        }
        // Size is known after layout; spawn then.
        post {
            pieces = List(COUNT) { spawn() }
            animator.start()
        }
    }

    private fun spawn(): Piece {
        val w = width.toFloat()
        val size = (6 + Random.nextFloat() * 6) * density
        return Piece(
            x = Random.nextFloat() * w,
            y = -Random.nextFloat() * height * 0.5f - size,
            vx = (Random.nextFloat() - 0.5f) * 120 * density,
            vy = (120 + Random.nextFloat() * 220) * density,
            angle = Random.nextFloat() * 360,
            spin = (Random.nextFloat() - 0.5f) * 720,
            wobble = Random.nextFloat() * 6.28f,
            width = size,
            height = size * (0.4f + Random.nextFloat() * 0.4f),
            color = COLORS.random(),
            round = Random.nextInt(4) == 0,
        )
    }

    private fun step(fraction: Float, playTimeMs: Long) {
        val dt = ((playTimeMs - lastFrameMs).coerceIn(0, 50)) / 1000f
        lastFrameMs = playTimeMs
        val gravity = 260 * density
        pieces.forEach { p ->
            p.vy += gravity * dt
            p.x += (p.vx + kotlin.math.sin(p.wobble + playTimeMs / 250f) * 40 * density) * dt
            p.y += p.vy * dt
            p.angle += p.spin * dt
        }
        alphaFraction = if (fraction < 0.75f) 1f else (1f - fraction) / 0.25f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        pieces.forEach { p ->
            if (p.y > height + p.width) return@forEach
            paint.color = p.color
            paint.alpha = (alphaFraction * 255).toInt()
            canvas.save()
            canvas.translate(p.x, p.y)
            canvas.rotate(p.angle)
            if (p.round) {
                canvas.drawCircle(0f, 0f, p.height / 2, paint)
            } else {
                canvas.drawRect(-p.width / 2, -p.height / 2, p.width / 2, p.height / 2, paint)
            }
            canvas.restore()
        }
    }

    private companion object {
        const val COUNT = 140
        const val DURATION_MS = 3_500L
        val COLORS = intArrayOf(
            0xFF3D5AFE.toInt(), 0xFF00BFA5.toInt(), 0xFFFFC400.toInt(),
            0xFFFF4081.toInt(), 0xFF7C4DFF.toInt(), 0xFF00B0FF.toInt(),
        )
    }
}
