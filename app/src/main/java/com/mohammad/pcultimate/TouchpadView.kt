package com.mohammad.pcultimate

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
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
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF151A22.toInt() }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF232A35.toInt() }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6D7789.toInt()
        textSize = 12.5f * d
        textAlign = Paint.Align.CENTER
    }
    private val touchFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val touchStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d }
    private val rect = RectF()

    var sensitivity = Prefs.sens(ctx)
    var naturalScroll = true
    var haptics = true
    var cornerRadius = 0f
    var hint = "Tap to click, two fingers to scroll"

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
    private var fingerX = -1f
    private var fingerY = -1f
    private val slop = 10f * d

    fun setColors(background: Int, dots: Int, hintColor: Int, accent: Int) {
        bgPaint.color = background
        dotPaint.color = dots
        hintPaint.color = hintColor
        touchFill.color = (accent and 0x00FFFFFF) or 0x2E000000
        touchStroke.color = (accent and 0x00FFFFFF) or 0x73000000
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
        val step = 18f * d
        var y = step / 2
        while (y < height) {
            var x = step / 2
            while (x < width) {
                canvas.drawCircle(x, y, 1f * d, dotPaint)
                x += step
            }
            y += step
        }
        if (fingerX >= 0) {
            canvas.drawCircle(fingerX, fingerY, 28f * d, touchFill)
            canvas.drawCircle(fingerX, fingerY, 28f * d, touchStroke)
        } else {
            canvas.drawText(hint, width / 2f, height / 2f, hintPaint)
        }
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
                fingerX = e.x; fingerY = e.y
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
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
                fingerX = x; fingerY = y
                invalidate()

                if (e.pointerCount >= 2) {
                    scrollAcc += if (naturalScroll) dy else -dy
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
                    val speed = hypot(dxDp, dyDp) / dt
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
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (e.actionMasked == MotionEvent.ACTION_UP && travel < slop && e.eventTime - downTime < 300) {
                    PcClient.click(if (maxPointers >= 2) "right" else "left")
                    if (haptics) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
                fingerX = -1f; fingerY = -1f
                invalidate()
            }
        }
        return true
    }
}

/** Small helpers for the floating touchpad, which is built with plain views. */
object Ui {
    fun dp(c: Context, v: Float): Int = (v * c.resources.displayMetrics.density).toInt()

    fun round(c: Context, color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * c.resources.displayMetrics.density
        }

    private fun ripple(c: Context, radius: Float) =
        RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), null, round(c, 0xFFFFFFFF.toInt(), radius))

    fun button(c: Context, label: String, bg: Int, fg: Int, onClick: () -> Unit): TextView =
        TextView(c).apply {
            text = label
            setTextColor(fg)
            textSize = 13f
            gravity = Gravity.CENTER
            maxLines = 1
            background = round(c, bg, 14f)
            foreground = ripple(c, 14f)
            isClickable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
        }

    fun iconButton(c: Context, icon: Int, description: String, bg: Int, fg: Int, radius: Float = 14f, onClick: () -> Unit): ImageView =
        ImageView(c).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(fg)
            contentDescription = description
            scaleType = ImageView.ScaleType.CENTER
            background = round(c, bg, radius)
            foreground = ripple(c, radius)
            isClickable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
        }
}
