package com.mohammad.pcultimate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Shows the PC screen. When [interactive] is on (full screen), the finger acts on the PC directly
 * (see the gesture notes further down).
 */
class StreamView(ctx: Context) : View(ctx) {
    var interactive = false
        set(v) { field = v; if (!v) resetZoom() }
    var client: StreamClient? = null
    var haptics = true

    private var frame: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fit = RectF()          // where the picture sits at zoom 1
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private val draw = Matrix()

    fun setFrame(b: Bitmap) {
        val old = frame
        frame = b
        if (old != null && old !== b && !old.isRecycled && (old.width != b.width || old.height != b.height)) requestLayout()
        invalidate()
    }

    fun clear() { frame = null; invalidate() }

    private fun resetZoom() { zoom = 1f; panX = 0f; panY = 0f; invalidate() }

    private fun computeFit() {
        val b = frame ?: return
        val s = min(width / b.width.toFloat(), height / b.height.toFloat())
        val w = b.width * s
        val h = b.height * s
        fit.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
    }

    private fun clampPan() {
        val maxX = max(0f, fit.width() * (zoom - 1f) / 2f)
        val maxY = max(0f, fit.height() * (zoom - 1f) / 2f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }

    /** The picture's rectangle on screen right now. */
    private fun shown(): RectF {
        val cx = fit.centerX() + panX
        val cy = fit.centerY() + panY
        val w = fit.width() * zoom
        val h = fit.height() * zoom
        return RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    override fun onDraw(c: Canvas) {
        val b = frame ?: return
        computeFit()
        val r = shown()
        draw.reset()
        draw.setRectToRect(RectF(0f, 0f, b.width.toFloat(), b.height.toFloat()), r, Matrix.ScaleToFit.FILL)
        c.drawBitmap(b, draw, paint)
    }

    /** Finger position to 0..1 on the PC screen, or null outside the picture. */
    private fun toPc(x: Float, y: Float): Pair<Float, Float>? {
        if (frame == null) return null
        val r = shown()
        if (!r.contains(x, y)) return null
        return ((x - r.left) / r.width()) to ((y - r.top) / r.height())
    }

    private fun buzz() { if (haptics) performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }

    // ------------------------------------------------------------ gestures (full screen only)
    //
    // Works like touch on a Windows laptop:
    //   tap               left click where you tap (instant, two quick taps = double click)
    //   swipe             scroll what is under your finger, both directions, with momentum
    //   hold              right click (lift without moving)
    //   hold, then move   drag with the left button held (select text, move windows)
    //   two fingers       zoom and move the picture on the phone

    private val dp = resources.displayMetrics.density
    private val slop = 9f * dp
    private val holdMs = 380L
    private val notch = 26f * dp          // finger travel per wheel step

    private enum class Mode { IDLE, PENDING, SCROLL, HOLD, DRAG, MULTI }
    private var mode = Mode.IDLE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var accX = 0f
    private var accY = 0f
    private var lastPoint = 0L
    private var tracker: android.view.VelocityTracker? = null
    private var scaling = false

    private val holdRun = Runnable {
        if (mode == Mode.PENDING) {
            mode = Mode.HOLD
            toPc(downX, downY)?.let { (x, y) -> client?.pointTo(x, y) }
            if (haptics) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    // momentum after a swipe
    private var flingVx = 0f
    private var flingVy = 0f
    private var flingLast = 0L
    private val flingRun = object : Runnable {
        override fun run() {
            val now = android.os.SystemClock.uptimeMillis()
            val dt = (now - flingLast).coerceAtLeast(1) / 1000f
            flingLast = now
            wheel(flingVx * dt, flingVy * dt)
            val k = Math.exp(-dt * 3.2).toFloat()
            flingVx *= k; flingVy *= k
            if (abs(flingVx) + abs(flingVy) > 120f * dp) postOnAnimation(this)
        }
    }

    private fun stopFling() { removeCallbacks(flingRun); flingVx = 0f; flingVy = 0f }

    /** Finger travel to wheel steps. Natural: content follows the finger. */
    private fun wheel(dx: Float, dy: Float) {
        val natural = AppSettings.naturalScroll
        accY += if (natural) dy else -dy
        accX += if (natural) -dx else dx
        while (abs(accY) >= notch) { val d = if (accY > 0) 1 else -1; client?.scroll(d); accY -= d * notch }
        while (abs(accX) >= notch) { val d = if (accX > 0) 1 else -1; client?.hscroll(d); accX -= d * notch }
    }

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var lastFx = 0f
        private var lastFy = 0f
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            lastFx = d.focusX; lastFy = d.focusY
            return true
        }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            scaling = true
            val newZoom = (zoom * d.scaleFactor).coerceIn(1f, 5f)
            val k = newZoom / zoom
            // keep the point under the fingers still while zooming, and follow the fingers when they move
            val cx = fit.centerX() + panX
            val cy = fit.centerY() + panY
            panX += (d.focusX - cx) * (1 - k) + (d.focusX - lastFx)
            panY += (d.focusY - cy) * (1 - k) + (d.focusY - lastFy)
            zoom = newZoom
            lastFx = d.focusX; lastFy = d.focusY
            clampPan()
            invalidate()
            return true
        }
    })

    private var lastTapAt = 0L

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!interactive) return false
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopFling()
                mode = Mode.PENDING
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                accX = 0f; accY = 0f; scaling = false
                tracker?.recycle(); tracker = android.view.VelocityTracker.obtain()
                tracker?.addMovement(e)
                postDelayed(holdRun, holdMs)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(holdRun)
                if (mode == Mode.DRAG) client?.button("left", false)
                mode = Mode.MULTI
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                if (mode == Mode.MULTI || e.pointerCount > 1) return true
                val moved = Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) > slop
                when (mode) {
                    Mode.PENDING -> if (moved) {
                        removeCallbacks(holdRun)
                        mode = Mode.SCROLL
                        // the wheel scrolls whatever is under the pointer, so put it where the finger is
                        toPc(downX, downY)?.let { (x, y) -> client?.pointTo(x, y) }
                        wheel(e.x - lastX, e.y - lastY)
                    }
                    Mode.SCROLL -> wheel(e.x - lastX, e.y - lastY)
                    Mode.HOLD -> if (moved) {
                        mode = Mode.DRAG
                        toPc(downX, downY)?.let { (x, y) -> client?.pointTo(x, y) }
                        client?.button("left", true)
                        if (haptics) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    Mode.DRAG -> {
                        val now = e.eventTime
                        if (now - lastPoint >= 16) {
                            lastPoint = now
                            toPc(e.x, e.y)?.let { (x, y) -> client?.pointTo(x, y) }
                        }
                    }
                    else -> {}
                }
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(holdRun)
                when (mode) {
                    Mode.PENDING -> toPc(e.x, e.y)?.let { (x, y) ->
                        client?.tap(x, y, "left"); buzz()
                        lastTapAt = e.eventTime
                    }
                    Mode.HOLD -> toPc(downX, downY)?.let { (x, y) -> client?.tap(x, y, "right"); buzz() }
                    Mode.DRAG -> {
                        toPc(e.x, e.y)?.let { (x, y) -> client?.pointTo(x, y) }
                        client?.button("left", false)
                    }
                    Mode.SCROLL -> {
                        tracker?.computeCurrentVelocity(1000)
                        val vx = tracker?.xVelocity ?: 0f
                        val vy = tracker?.yVelocity ?: 0f
                        if (Math.hypot(vx.toDouble(), vy.toDouble()) > 900 * dp) {
                            flingVx = vx * 0.9f; flingVy = vy * 0.9f
                            flingLast = android.os.SystemClock.uptimeMillis()
                            postOnAnimation(flingRun)
                        }
                    }
                    else -> {}
                }
                mode = Mode.IDLE
                tracker?.recycle(); tracker = null
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdRun)
                if (mode == Mode.DRAG) client?.button("left", false)
                mode = Mode.IDLE
                tracker?.recycle(); tracker = null
            }
        }
        return true
    }
}
