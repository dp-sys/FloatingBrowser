package com.example.floatingbrowser

import android.app.*
import android.content.*
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.min

class MainActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (Settings.canDrawOverlays(this)) start() else
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }
    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) start()
    }
    private fun start() {
        startForegroundService(Intent(this, FloatingBrowserService::class.java))
        finish()
    }
}

class FloatingBrowserService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var root: FrameLayout? = null
    private var bubble: BubbleView? = null
    private var web: WebView? = null
    private var minimized = false
    private var desktop = false
    private var browserWidth = 0
    private var browserHeight = 0
    private var browserFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    private var browserScrollX = 0
    private var browserScrollY = 0
    private var maximized = false
    private var restoreWidth = 0
    private var restoreHeight = 0
    private var restoreX = 0
    private var restoreY = 0
    private val bubbleHandler = Handler(Looper.getMainLooper())
    private val titleHandler = Handler(Looper.getMainLooper())
    private var bubbleCollapsed = false
    private var bubbleOnLeft = true
    private val dp get() = resources.displayMetrics.density
    private fun d(n: Int) = (n * dp).toInt()
    private val barHeight get() = d(24)

    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int = START_NOT_STICKY

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(7, Notification.Builder(this, "floating_browser")
            .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("Floating Browser")
            .setContentText("Browser overlay is running").build())
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showBrowser()
    }

    private fun layoutParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; x = d(16); y = d(80) }

    private fun showBrowser() {
        minimized = false
        val view = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        val address = TextView(this).apply {
            text = "Bookmarks"; textSize = 10f; gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.WHITE); isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
            tag = "address"
        }
        val page = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    address.text = "${view.title ?: ""}  ·  $url"
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView, title: String?) {
                    address.text = "${title ?: ""}  ·  ${view.url ?: ""}"
                }
            }
            isFocusableInTouchMode = true
            // A non-focusable overlay can receive taps, but WebView cannot ask the
            // input method for a keyboard. Become focusable before WebView handles
            // the touch so HTML search, login, and other form fields work normally.
            setOnTouchListener { touched, event ->
                if (event.action == MotionEvent.ACTION_DOWN) enableWebInput(touched)
                false
            }
            loadHome(this)
        }
        web = page
        view.addView(page, FrameLayout.LayoutParams(-1, -1).apply { topMargin = barHeight })

        val bar = FrameLayout(this).apply { setBackgroundColor(Color.rgb(82, 82, 82)) }
        address.setOnTouchListener(titleBarTouch(address, bar, view, page))
        bar.addView(address, FrameLayout.LayoutParams(-1, barHeight).apply { leftMargin = d(90); rightMargin = d(54) })
        val left = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        left.addView(topButton("☰", Color.rgb(255, 152, 0)) { toggleMenu(view, page) })
        left.addView(topButton("‹") { if (page.canGoBack()) page.goBack() })
        left.addView(topButton("›") { if (page.canGoForward()) page.goForward() })
        left.addView(topButton("−") { page.zoomOut() })
        left.addView(topButton("+") { page.zoomIn() })
        bar.addView(left, FrameLayout.LayoutParams(d(90), barHeight, Gravity.START))

        val right = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        right.addView(topButton("□", Color.rgb(255, 193, 7)) { toggleMaximize() })
        right.addView(topButton("—", Color.rgb(76, 175, 80)) { minimize() })
        right.addView(topButton("×", Color.rgb(244, 67, 54)) { stopSelf() })
        bar.addView(right, FrameLayout.LayoutParams(d(54), barHeight, Gravity.END))
        view.addView(bar, FrameLayout.LayoutParams(-1, barHeight, Gravity.TOP))

        val handle = TextView(this).apply { text = "◢"; textSize = 18f; setTextColor(Color.DKGRAY); gravity = Gravity.CENTER }
        view.addView(handle, FrameLayout.LayoutParams(d(28), d(28), Gravity.BOTTOM or Gravity.END))
        drag(handle, true)
        if (::params.isInitialized) {
            params.width = d(360); params.height = d(560)
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        } else params = layoutParams(d(360), d(560))
        root = view
        wm.addView(view, params)
        fitToDisplay()
    }

    private fun enableWebInput(view: View) {
        if (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            root?.let { wm.updateViewLayout(it, params) }
            view.requestFocusFromTouch()
        }
    }

    @Suppress("DEPRECATION")
    private fun safeDisplayBounds(): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            return Rect(
                metrics.bounds.left + insets.left,
                metrics.bounds.top + insets.top,
                metrics.bounds.right - insets.right,
                metrics.bounds.bottom - insets.bottom
            )
        }
        val size = android.graphics.Point()
        wm.defaultDisplay.getSize(size)
        return Rect(0, 0, size.x, size.y)
    }

    private fun fitToDisplay() {
        val safe = safeDisplayBounds()
        val landscapeMargin = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) d(12) else 0
        safe.left += landscapeMargin; safe.right -= landscapeMargin
        if (maximized) {
            params.width = safe.width(); params.height = safe.height(); params.x = safe.left; params.y = safe.top
        } else {
            params.width = min(params.width, safe.width())
            params.height = min(params.height, safe.height())
            params.x = params.x.coerceIn(safe.left, maxOf(safe.left, safe.right - params.width))
            params.y = params.y.coerceIn(safe.top, maxOf(safe.top, safe.bottom - params.height))
        }
        current()?.let { wm.updateViewLayout(it, params) }
    }

    private fun toggleMaximize() {
        if (minimized || root == null) return
        if (!maximized) {
            restoreWidth = params.width; restoreHeight = params.height
            restoreX = params.x; restoreY = params.y
            maximized = true
        } else {
            params.width = restoreWidth; params.height = restoreHeight
            params.x = restoreX; params.y = restoreY
            maximized = false
        }
        fitToDisplay()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (minimized && bubbleCollapsed) collapseBubble() else fitToDisplay()
    }

    private fun button(label: String, color: Int = Color.WHITE, action: () -> Unit) = TextView(this).apply {
        text = label; textSize = 18f; gravity = Gravity.CENTER; setTextColor(color)
        setOnClickListener { action() }; layoutParams = LinearLayout.LayoutParams(d(32), d(28))
    }

    private fun topButton(label: String, color: Int = Color.WHITE, action: () -> Unit) =
        button(label, color, action).apply { textSize = 12f; layoutParams = LinearLayout.LayoutParams(d(18), barHeight) }

    private fun titleBarTouch(title: View, bar: FrameLayout, parent: FrameLayout, page: WebView) = object : View.OnTouchListener {
        var startX = 0; var startY = 0; var downX = 0f; var downY = 0f; var armed = false
        val armMove = Runnable {
            armed = true
            bar.setBackgroundColor(Color.rgb(255, 152, 0))
        }
        override fun onTouch(view: View, event: MotionEvent): Boolean = when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = params.x; startY = params.y; downX = event.rawX; downY = event.rawY; armed = false
                titleHandler.postDelayed(armMove, 1_000)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (armed) {
                    params.x = startX + (event.rawX - downX).toInt()
                    params.y = startY + (event.rawY - downY).toInt()
                    root?.let { wm.updateViewLayout(it, params) }
                } else if (abs(event.rawX - downX) > d(6) || abs(event.rawY - downY) > d(6)) {
                    titleHandler.removeCallbacks(armMove)
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                titleHandler.removeCallbacks(armMove)
                bar.setBackgroundColor(Color.rgb(82, 82, 82))
                if (!armed && event.action == MotionEvent.ACTION_UP) urlEditor(parent, page)
                true
            }
            else -> true
        }
    }

    private fun toggleMenu(parent: FrameLayout, page: WebView) {
        val old = parent.findViewWithTag<View>("menu")
        if (old != null) { parent.removeView(old); return }
        val menu = LinearLayout(this).apply {
            tag = "menu"; orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE)
            elevation = d(8).toFloat()
        }
        fun item(name: String, f: () -> Unit) = TextView(this).apply {
            text = name; textSize = 16f; setTextColor(Color.DKGRAY); gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { f(); parent.removeView(menu) }
            menu.addView(this, LinearLayout.LayoutParams(d(220), d(44)))
        }
        item("Home") { loadHome(page) }
        item("Back") { if (page.canGoBack()) page.goBack() }
        item("Forward") { if (page.canGoForward()) page.goForward() }
        item("Reload") { page.reload() }
        item("Enter URL") { urlEditor(parent, page) }
        item("Add bookmark") { bookmarkEditor(parent, page) }
        item("Open in default browser") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page.url ?: "https://www.google.com")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        item("Desktop mode: ${if (desktop) "on" else "off"}") {
            desktop = !desktop
            page.settings.userAgentString = if (desktop) WebSettings.getDefaultUserAgent(this).replace("Android", "X11; Linux x86_64") else WebSettings.getDefaultUserAgent(this)
            page.reload()
        }
        parent.addView(menu, FrameLayout.LayoutParams(d(220), -2, Gravity.TOP or Gravity.START).apply { topMargin = barHeight })
    }

    private fun urlEditor(parent: FrameLayout, page: WebView) {
        val address = parent.findViewWithTag<TextView>("address") ?: return
        val bar = address.parent as? FrameLayout ?: return
        val old = bar.findViewWithTag<View>("url_editor")
        if (old != null) return
        val priorFlags = params.flags
        val input = EditText(this).apply {
            tag = "url_editor"; setSingleLine(true); setText(page.url); selectAll(); textSize = 12f; gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false; setPadding(0, 0, 0, 0)
            setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY); setBackgroundColor(Color.rgb(70, 70, 70))
        }
        fun close() {
            bar.removeView(input)
            address.visibility = View.VISIBLE
            params.flags = priorFlags
            wm.updateViewLayout(parent, params)
        }
        fun submit() {
            var url = input.text.toString().trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
            page.loadUrl(url)
            close()
        }
        input.setOnEditorActionListener { _, _, _ -> submit(); true }
        input.setOnFocusChangeListener { _, focused -> if (!focused) close() }
        address.visibility = View.GONE
        bar.addView(input, FrameLayout.LayoutParams(-1, barHeight).apply { leftMargin = d(90); rightMargin = d(54) })
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        wm.updateViewLayout(parent, params)
        input.requestFocus()
        input.post { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun bookmarks(): List<Pair<String, String>> {
        val saved = getSharedPreferences("bookmarks", MODE_PRIVATE).getString("items", "[]") ?: "[]"
        val custom = try {
            val items = JSONArray(saved)
            List(items.length()) { index ->
                val item = items.getJSONObject(index)
                item.getString("name") to item.getString("url")
            }
        } catch (_: Exception) { emptyList() }
        return listOf("Google" to "https://www.google.com") + custom
    }

    private fun loadHome(page: WebView) {
        fun safe(value: String) = android.text.TextUtils.htmlEncode(value)
        val links = bookmarks().joinToString("") { (name, url) ->
            "<p><a href=\"${safe(url)}\">${safe(name)}</a></p>"
        }.ifEmpty { "<p>No bookmarks yet.</p>" }
        page.loadDataWithBaseURL("https://bookmarks.local/", "<html><body>$links</body></html>", "text/html", "UTF-8", null)
    }

    private fun bookmarkEditor(parent: FrameLayout, page: WebView) {
        val old = parent.findViewWithTag<View>("bookmark_editor")
        if (old != null) parent.removeView(old)
        val priorFlags = params.flags
        val editor = LinearLayout(this).apply {
            tag = "bookmark_editor"; orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.WHITE)
        }
        val name = EditText(this).apply {
            setSingleLine(true); hint = "Bookmark name"; setText(page.title?.takeIf { it.isNotBlank() } ?: "Bookmark")
            selectAll()
        }
        fun close() {
            parent.removeView(editor)
            params.flags = priorFlags
            wm.updateViewLayout(parent, params)
        }
        val save = button("Save", Color.DKGRAY) {
            val label = name.text.toString().trim().ifEmpty { "Bookmark" }
            val url = page.url ?: return@button
            val items = JSONArray(getSharedPreferences("bookmarks", MODE_PRIVATE).getString("items", "[]") ?: "[]")
            items.put(JSONObject().put("name", label).put("url", url))
            getSharedPreferences("bookmarks", MODE_PRIVATE).edit().putString("items", items.toString()).apply()
            close()
            loadHome(page)
        }
        val cancel = button("×", Color.DKGRAY) { close() }
        editor.addView(name, LinearLayout.LayoutParams(0, d(48), 1f))
        editor.addView(save, LinearLayout.LayoutParams(d(52), d(48)))
        editor.addView(cancel, LinearLayout.LayoutParams(d(32), d(48)))
        parent.addView(editor, FrameLayout.LayoutParams(-1, d(48), Gravity.TOP).apply { topMargin = barHeight })
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        wm.updateViewLayout(parent, params)
        name.requestFocus()
        name.post { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(name, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun drag(v: View, resize: Boolean) = v.setOnTouchListener(object : View.OnTouchListener {
        var x = 0; var y = 0; var w = 0; var h = 0; var downX = 0f; var downY = 0f; var expandedFromHalf = false
        override fun onTouch(view: View, e: MotionEvent): Boolean = when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                if (resize) maximized = false
                expandedFromHalf = !resize && minimized && bubbleCollapsed
                if (expandedFromHalf) expandBubble()
                x = params.x; y = params.y; w = params.width; h = params.height; downX = e.rawX; downY = e.rawY
                true
            }
            MotionEvent.ACTION_MOVE -> { if (resize) { params.width = maxOf(d(180), w + (e.rawX - downX).toInt()); params.height = maxOf(d(180), h + (e.rawY - downY).toInt()) } else { params.x = x + (e.rawX - downX).toInt(); params.y = y + (e.rawY - downY).toInt() }; current()?.let { wm.updateViewLayout(it, params) }; true }
            MotionEvent.ACTION_UP -> {
                if (!resize && minimized) {
                    if (!expandedFromHalf && abs(e.rawX - downX) < d(4).toFloat() && abs(e.rawY - downY) < d(4).toFloat()) {
                        bubbleHandler.removeCallbacksAndMessages(null)
                        bubble?.let { wm.removeView(it) }
                        bubble = null
                        restoreBrowser()
                    } else scheduleBubbleCollapse()
                }
                true
            }
            else -> true
        }
    })
    private fun current(): View? = if (minimized) bubble else root
    private fun minimize() {
        val browser = root ?: return
        browserWidth = params.width; browserHeight = params.height; browserFlags = params.flags
        browserScrollX = web?.scrollX ?: 0; browserScrollY = web?.scrollY ?: 0
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(browser.windowToken, 0)
        web?.onPause()
        wm.removeView(browser)
        minimized = true
        bubbleCollapsed = false
        bubble = BubbleView(this).apply { elevation = d(4).toFloat() }
        params.width = d(48); params.height = d(48); params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        drag(bubble!!, false); wm.addView(bubble, params)
        scheduleBubbleCollapse()
    }

    private fun scheduleBubbleCollapse() {
        bubbleHandler.removeCallbacksAndMessages(null)
        bubbleHandler.postDelayed({ collapseBubble() }, 2_000)
    }

    private fun collapseBubble() {
        val icon = bubble ?: return
        if (!minimized) return
        val safe = safeDisplayBounds()
        bubbleOnLeft = params.x + params.width / 2 < (safe.left + safe.right) / 2
        bubbleCollapsed = true
        icon.half = true; icon.onLeft = bubbleOnLeft
        params.width = d(24)
        params.x = if (bubbleOnLeft) safe.left else safe.right - params.width
        params.y = params.y.coerceIn(safe.top, maxOf(safe.top, safe.bottom - params.height))
        wm.updateViewLayout(icon, params)
    }

    private fun expandBubble() {
        val icon = bubble ?: return
        val safe = safeDisplayBounds()
        bubbleCollapsed = false
        icon.half = false
        params.width = d(48)
        params.x = if (bubbleOnLeft) safe.left else safe.right - params.width
        params.y = params.y.coerceIn(safe.top, maxOf(safe.top, safe.bottom - params.height))
        wm.updateViewLayout(icon, params)
    }
    private fun restoreBrowser() {
        val browser = root ?: return
        minimized = false; bubbleCollapsed = false
        params.width = browserWidth; params.height = browserHeight
        params.flags = browserFlags
        wm.addView(browser, params)
        fitToDisplay()
        web?.let { page -> page.post {
            page.onResume()
            page.requestLayout()
            page.scrollTo(browserScrollX, browserScrollY)
            page.invalidate()
        }
        }
    }
    override fun onDestroy() {
        bubbleHandler.removeCallbacksAndMessages(null)
        titleHandler.removeCallbacksAndMessages(null)
        if (minimized) bubble?.let { wm.removeView(it) } else root?.let { wm.removeView(it) }
        web?.destroy()
        super.onDestroy()
    }
    private fun createChannel() { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel("floating_browser", "Floating browser", NotificationManager.IMPORTANCE_LOW)) }
}

private class BubbleView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var half = false
    var onLeft = true

    override fun onDraw(canvas: Canvas) {
        val radius = height / 2f
        val centerX = if (half) if (onLeft) 0f else width.toFloat() else width / 2f
        paint.color = Color.rgb(255, 152, 0)
        canvas.drawCircle(centerX, radius, radius, paint)
        if (!half) {
            paint.color = Color.WHITE
            canvas.drawCircle(centerX, radius, radius / 5f, paint)
        }
    }
}
