package com.mohammad.pcultimate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Shows the PC screen. When [interactive] is on (full screen), the finger acts on the PC directly:
 * tap = left click there, long press = right click there, one finger drag = move the pointer,
 * two finger drag = scroll, pinch = zoom the picture on the phone.
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

    private var twoFinger = false
    private var scaling = false
    private var lastMidY = 0f
    private var scrollAcc = 0f
    private var lastMove = 0L

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var lastFx = 0f
        private var lastFy = 0f
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            lastFx = d.focusX; lastFy = d.focusY
            return true
        }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val f = d.scaleFactor
            if (!scaling && abs(f - 1f) < 0.02f) return false
            scaling = true
            val newZoom = (zoom * f).coerceIn(1f, 5f)
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

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            toPc(e.x, e.y)?.let { (x, y) -> client?.tap(x, y, "left"); buzz() }
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            toPc(e.x, e.y)?.let { (x, y) ->
                client?.tap(x, y, "left"); client?.tap(x, y, "left"); buzz()
            }
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            if (twoFinger) return
            toPc(e.x, e.y)?.let { (x, y) -> client?.tap(x, y, "right"); buzz() }
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (twoFinger || e2.pointerCount > 1) return false
            val now = System.currentTimeMillis()
            if (now - lastMove < 25) return true
            lastMove = now
            toPc(e2.x, e2.y)?.let { (x, y) -> client?.pointTo(x, y) }
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!interactive) return false
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { twoFinger = false; scaling = false; scrollAcc = 0f }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                twoFinger = true
                lastMidY = (e.getY(0) + e.getY(1)) / 2f
            }
            MotionEvent.ACTION_MOVE -> if (twoFinger && e.pointerCount >= 2 && !scaling) {
                val mid = (e.getY(0) + e.getY(1)) / 2f
                scrollAcc += mid - lastMidY
                lastMidY = mid
                val step = 40f * resources.displayMetrics.density / 2f
                while (abs(scrollAcc) >= step) {
                    val dir = if (scrollAcc > 0) 1 else -1
                    val n = if (AppSettings.naturalScroll) dir else -dir
                    client?.scroll(n)
                    scrollAcc -= dir * step
                }
            }
        }
        if (!twoFinger) gestures.onTouchEvent(e)
        return true
    }
}
