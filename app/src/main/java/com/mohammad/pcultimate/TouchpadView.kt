package com.mohammad.pcultimate

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * One finger moves the pointer, a quick tap is a left click,
 * a quick two-finger tap is a right click, two fingers scroll.
 */
class TouchpadView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1B222D.toInt() }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6B7686.toInt()
        textSize = 13f * d
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    var sensitivity = Prefs.sens(ctx)
    var hint = "حرّك إصبعك هنا • إصبعين للسكرول"

    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L
    private var accX = 0f
    private var accY = 0f
    private var scrollAcc = 0f
    private var downTime = 0L
    private var travel = 0f
    private var maxPointers = 1
    private var speedAvg = 0f
    private val slop = 10f * d

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, 20f * d, 20f * d, bgPaint)
        canvas.drawText(hint, width / 2f, height / 2f, hintPaint)
    }

    private fun centerX(e: MotionEvent, skip: Int = -1): Float {
        var sum = 0f; var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { sum += e.getX(i); n++ }
        return if (n == 0) lastX else sum / n
    }

    private fun centerY(e: MotionEvent, skip: Int = -1): Float {
        var sum = 0f; var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { sum += e.getY(i); n++ }
        return if (n == 0) lastY else sum / n
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y; lastT = e.eventTime
                downTime = e.eventTime
                travel = 0f; maxPointers = 1
                accX = 0f; accY = 0f; scrollAcc = 0f; speedAvg = 0f
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = max(maxPointers, e.pointerCount)
                lastX = centerX(e); lastY = centerY(e)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                lastX = centerX(e, e.actionIndex); lastY = centerY(e, e.actionIndex)
            }
            MotionEvent.ACTION_MOVE -> {
                val x = centerX(e)
                val y = centerY(e)
                val dx = x - lastX
                val dy = y - lastY
                val dt = max(1L, e.eventTime - lastT)
                lastX = x; lastY = y; lastT = e.eventTime
                travel += abs(dx) + abs(dy)

                if (e.pointerCount >= 2) {
                    scrollAcc += dy
                    val step = 14f * d
                    while (abs(scrollAcc) >= step) {
                        val dir = if (scrollAcc > 0) 1 else -1
                        PcClient.scroll(dir)
                        scrollAcc -= dir * step
                    }
                } else if (maxPointers == 1) {
                    val dxDp = dx / d
                    val dyDp = dy / d
                    // Smoothed speed so uneven touch timing does not make the pointer jump.
                    val speed = hypot(dxDp, dyDp) / dt // dp per ms
                    speedAvg += (min(speed, 4f) - speedAvg) * 0.3f
                    val gain = sensitivity * (1f + 0.8f * min(speedAvg, 2.5f))
                    accX += dxDp * gain
                    accY += dyDp * gain
                    val ix = accX.toInt()
                    val iy = accY.toInt()
                    if (ix != 0 || iy != 0) {
                        PcClient.move(ix, iy)
                        accX -= ix
                        accY -= iy
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (travel < slop && e.eventTime - downTime < 300) {
                    PcClient.click(if (maxPointers >= 2) "right" else "left")
                }
            }
        }
        return true
    }
}

/** Small helpers for building the screens in code. */
object Ui {
    const val BG = 0xFF0D1117.toInt()
    const val CARD = 0xFF161C25.toInt()
    const val BTN = 0xFF222B38.toInt()
    const val ACCENT = 0xFF2563EB.toInt()
    const val TEXT = 0xFFE6EDF3.toInt()
    const val MUTED = 0xFF8B97A6.toInt()

    fun dp(c: Context, v: Float): Int = (v * c.resources.displayMetrics.density).toInt()

    fun round(c: Context, color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * c.resources.displayMetrics.density
        }

    fun button(c: Context, label: String, color: Int = BTN, onClick: () -> Unit): TextView =
        TextView(c).apply {
            text = label
            setTextColor(TEXT)
            textSize = 14f
            gravity = Gravity.CENTER
            maxLines = 1
            background = round(c, color, 14f)
            val p = dp(c, 8f)
            setPadding(p, p, p, p)
            isClickable = true
            setOnClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
        }
}
