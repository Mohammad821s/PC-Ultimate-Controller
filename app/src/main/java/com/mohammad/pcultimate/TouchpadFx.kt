package com.mohammad.pcultimate

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * Touchpad background (React Bits effects in assets/fx) and its colors. The click spark is fixed.
 * Saved in prefs, observable by Compose, and pushed to every live touchpad (app and floating).
 */
object TouchpadFx {
    val DEFAULT_LIQUID = listOf("#5227FF", "#FF9FFC", "#B497CF")
    const val DEFAULT_DOTS_BASE = "#3A3446"
    const val DEFAULT_DOTS_ACTIVE = "#B497CF"
    const val DEFAULT_RIPPLE = "#B497CF"

    val backgrounds = listOf("liquid" to "Liquid Ether", "dots" to "Dot Grid", "ripple" to "Ripple Grid", "none" to "None")

    var bg by mutableStateOf("liquid")
    var liquid by mutableStateOf(DEFAULT_LIQUID)
    var dotsBase by mutableStateOf(DEFAULT_DOTS_BASE)
    var dotsActive by mutableStateOf(DEFAULT_DOTS_ACTIVE)
    var ripple by mutableStateOf(DEFAULT_RIPPLE)

    private var loaded = false
    private val listeners = mutableSetOf<() -> Unit>()

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        val p = c.getSharedPreferences("pc", Context.MODE_PRIVATE)
        bg = p.getString("fx_bg", "liquid") ?: "liquid"
        liquid = (p.getString("fx_liquid", null)?.split(',')?.filter { it.isNotBlank() }?.takeIf { it.size == 3 }) ?: DEFAULT_LIQUID
        dotsBase = p.getString("fx_dots_base", DEFAULT_DOTS_BASE) ?: DEFAULT_DOTS_BASE
        dotsActive = p.getString("fx_dots_active", DEFAULT_DOTS_ACTIVE) ?: DEFAULT_DOTS_ACTIVE
        ripple = p.getString("fx_ripple", DEFAULT_RIPPLE) ?: DEFAULT_RIPPLE
    }

    fun save(c: Context) {
        c.getSharedPreferences("pc", Context.MODE_PRIVATE).edit()
            .putString("fx_bg", bg).putString("fx_liquid", liquid.joinToString(","))
            .putString("fx_dots_base", dotsBase).putString("fx_dots_active", dotsActive).putString("fx_ripple", ripple)
            .apply()
        listeners.toList().forEach { it() }
    }

    /** Colors of the current background back to the defaults. */
    fun resetColors(c: Context) {
        when (bg) {
            "liquid" -> liquid = DEFAULT_LIQUID
            "dots" -> { dotsBase = DEFAULT_DOTS_BASE; dotsActive = DEFAULT_DOTS_ACTIVE }
            "ripple" -> ripple = DEFAULT_RIPPLE
        }
        save(c)
    }

    fun json(): String = JSONObject()
        .put("bg", bg).put("liquid", JSONArray(liquid))
        .put("dotsBase", dotsBase).put("dotsActive", dotsActive).put("ripple", ripple)
        .toString()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
}

/**
 * The touchpad: the effect page underneath, the gesture view on top. Every touch is handled
 * natively (pointer, clicks, scrolling) and a copy goes to the page so the effect follows the finger.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class TouchpadSurface(ctx: Context, radiusPx: Float) : FrameLayout(ctx) {
    val pad = TouchpadView(ctx)
    private val fx = WebView(ctx)
    private var ready = false
    private var lastJson = ""
    private val onChange = { push() }

    init {
        TouchpadFx.load(ctx)
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
        }
        fx.setBackgroundColor(Color.TRANSPARENT)
        fx.isVerticalScrollBarEnabled = false
        fx.isHorizontalScrollBarEnabled = false
        fx.overScrollMode = View.OVER_SCROLL_NEVER
        fx.isFocusable = false
        fx.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        fx.settings.javaScriptEnabled = true
        fx.settings.allowFileAccess = false
        fx.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) { ready = true; lastJson = ""; push() }
        }
        fx.loadUrl("file:///android_asset/fx/index.html")
        addView(fx, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        pad.fxMode = true
        pad.forward = { e -> fx.dispatchTouchEvent(e) }
        addView(pad, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setBackgroundTint(color: Int) = setBackgroundColor(color)

    /** Sends the current background and colors to the page (only when they changed). */
    fun push() {
        if (!ready) return
        val j = TouchpadFx.json()
        if (j == lastJson) return
        lastJson = j
        fx.evaluateJavascript("window.setFx($j)", null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        TouchpadFx.addListener(onChange)
        fx.onResume()
    }

    override fun onDetachedFromWindow() {
        TouchpadFx.removeListener(onChange)
        fx.onPause()
        super.onDetachedFromWindow()
    }

    fun destroy() {
        TouchpadFx.removeListener(onChange)
        try { removeView(fx); fx.destroy() } catch (_: Exception) {}
    }
}

/** Forwarded copies must not outlive the call. */
internal inline fun MotionEvent.copyFor(block: (MotionEvent) -> Unit) {
    val c = MotionEvent.obtain(this)
    try { block(c) } finally { c.recycle() }
}
