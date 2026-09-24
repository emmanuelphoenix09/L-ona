package com.example.assistant

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Petit cercle violet 🟣 qui flotte au-dessus de toutes les applis.
 * - Toucher : Léona écoute
 * - Glisser : déplace la bulle (elle se colle au bord de l'écran)
 * Nécessite la permission « Afficher par-dessus les autres applis » (API 26+).
 */
class FloatingBubble(
    private val context: Context,
    private val onTap: () -> Unit
) {
    enum class Phase { IDLE, LISTENING, WORKING }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density
    private val dotSize = (52 * density).toInt()
    private val windowSize = (72 * density).toInt() // marge pour l'animation de pulsation

    private val circle = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor("#7C4DFF"))
        setStroke((2 * density).toInt(), Color.parseColor("#66FFFFFF"))
    }
    private val dot = View(context).apply {
        background = circle
        alpha = 0.8f
        elevation = 6 * density
    }
    private val container = FrameLayout(context).apply {
        addView(dot, FrameLayout.LayoutParams(dotSize, dotSize, Gravity.CENTER))
    }
    private val params = WindowManager.LayoutParams(
        windowSize, windowSize,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = (220 * density).toInt()
    }

    private var pulse: ObjectAnimator? = null
    private var shown = false

    init {
        container.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var downX = 0f
            private var downY = 0f
            private var moved = false
            private val slop = 8 * density

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x; startY = params.y
                        downX = e.rawX; downY = e.rawY
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (abs(dx) > slop || abs(dy) > slop) moved = true
                        if (moved) {
                            params.x = (startX + dx).toInt()
                            params.y = (startY + dy).toInt()
                            wm.updateViewLayout(container, params)
                        }
                    }
                    MotionEvent.ACTION_UP -> if (moved) snapToEdge() else onTap()
                }
                return true
            }
        })
    }

    fun show() {
        if (shown || !Settings.canDrawOverlays(context)) return
        try {
            wm.addView(container, params)
            shown = true
        } catch (_: Exception) { }
    }

    fun hide() {
        if (!shown) return
        pulse?.cancel()
        try { wm.removeView(container) } catch (_: Exception) { }
        shown = false
    }

    /** À appeler depuis le thread principal. */
    fun setPhase(phase: Phase) {
        pulse?.cancel()
        pulse = null
        dot.scaleX = 1f
        dot.scaleY = 1f
        when (phase) {
            Phase.IDLE -> {
                circle.setColor(Color.parseColor("#7C4DFF"))
                dot.alpha = 0.8f
            }
            Phase.LISTENING -> {
                circle.setColor(Color.parseColor("#B388FF"))
                dot.alpha = 1f
                pulse = pulseAnimator(600)
            }
            Phase.WORKING -> {
                circle.setColor(Color.parseColor("#651FFF"))
                dot.alpha = 1f
                pulse = pulseAnimator(300)
            }
        }
    }

    private fun pulseAnimator(durationMs: Long) = ObjectAnimator.ofPropertyValuesHolder(
        dot,
        PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.3f),
        PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.3f)
    ).apply {
        duration = durationMs
        repeatCount = ObjectAnimator.INFINITE
        repeatMode = ObjectAnimator.REVERSE
        start()
    }

    private fun snapToEdge() {
        val screenW = context.resources.displayMetrics.widthPixels
        params.x = if (params.x + windowSize / 2 < screenW / 2) 0 else screenW - windowSize
        wm.updateViewLayout(container, params)
    }
}
