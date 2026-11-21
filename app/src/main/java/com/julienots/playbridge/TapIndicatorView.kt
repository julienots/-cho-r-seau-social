package com.julienots.playbridge

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** Petit cercle qui montre à l'utilisateur où Claude vient de toucher. */
class TapIndicatorView(context: Context) : View(context) {
    private var cx = 0f
    private var cy = 0f
    private var progress = 1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D97757") }
    private var anim: ValueAnimator? = null

    init {
        isClickable = false
        isFocusable = false
    }

    fun show(x: Float, y: Float) {
        cx = x; cy = y
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (progress >= 1f) return
        val d = resources.displayMetrics.density
        paint.alpha = ((1f - progress) * 170).toInt()
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, (10 + 22 * progress) * d, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2 * d
        paint.alpha = ((1f - progress) * 255).toInt()
        canvas.drawCircle(cx, cy, (14 + 30 * progress) * d, paint)
    }
}
