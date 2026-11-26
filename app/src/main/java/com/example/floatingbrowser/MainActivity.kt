package com.example.floatingbrowser

import android.app.*
import android.content.*
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.webkit.WebSettings
import android.webkit.WebView
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
    private var bubble: TextView? = null
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
    private val dp get() = resources.displayMetrics.density
    private fun d(n: Int) = (n * dp).toInt()

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
        val page = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            webViewClient = WebViewClient()
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
        view.addView(page, FrameLayout.LayoutParams(-1, -1).apply { topMargin = d(28) })

        val bar = FrameLayout(this).apply { setBackgroundColor(Color.rgb(82, 82, 82)) }
        val dragArea = View(this)
        bar.addView(dragArea, FrameLayout.LayoutParams(-1, -1))
        val left = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        left.addView(button("☰", Color.rgb(255, 152, 0)) { toggleMenu(view, page) })
        left.addView(button("‹") { if (page.canGoBack()) page.goBack() })
        left.addView(button("›") { if (page.canGoForward()) page.goForward() })
        left.addView(button("−") { page.zoomOut() })
        left.addView(button("+") { page.zoomIn() })
        bar.addView(left, FrameLayout.LayoutParams(d(160), d(28), Gravity.START))

        val right = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        right.addView(button("□", Color.rgb(255, 193, 7)) { toggleMaximize() })
        right.addView(button("—", Color.rgb(76, 175, 80)) { minimize() })
        right.addView(button("×", Color.rgb(244, 67, 54)) { stopSelf() })
        bar.addView(right, FrameLayout.LayoutParams(d(96), d(28), Gravity.END))
        view.addView(bar, FrameLayout.LayoutParams(-1, d(28), Gravity.TOP))

        val handle = TextView(this).apply { text = "◢"; textSize = 18f; setTextColor(Color.DKGRAY); gravity = Gravity.CENTER }
        view.addView(handle, FrameLayout.LayoutParams(d(28), d(28), Gravity.BOTTOM or Gravity.END))
        drag(dragArea, false); drag(handle, true)
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
    private fun displaySize(): Point = Point().also { wm.defaultDisplay.getSize(it) }

    private fun fitToDisplay() {
        val size = displaySize()
        if (maximized) {
            params.width = size.x; params.height = size.y; params.x = 0; params.y = 0
        } else {
            params.width = min(params.width, size.x)
            params.height = min(params.height, size.y)
            params.x = params.x.coerceIn(0, maxOf(0, size.x - params.width))
            params.y = params.y.coerceIn(0, maxOf(0, size.y - params.height))
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
        fitToDisplay()
    }

    private fun button(label: String, color: Int = Color.WHITE, action: () -> Unit) = TextView(this).apply {
        text = label; textSize = 18f; gravity = Gravity.CENTER; setTextColor(color)
        setOnClickListener { action() }; layoutParams = LinearLayout.LayoutParams(d(32), d(28))
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
        item("Back") { if (page.canGoBack()) page.goBack() }
        item("Forward") { if (page.canGoForward()) page.goForward() }
        item("Reload") { page.reload() }
        item("Enter URL") { urlEditor(parent, page) }
        item("Add bookmark") { bookmarkEditor(parent, page) }
        item("Bookmarks") { loadHome(page) }
        item("Open in default browser") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page.url ?: "https://www.google.com")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        item("Desktop mode: ${if (desktop) "on" else "off"}") {
            desktop = !desktop
            page.settings.userAgentString = if (desktop) WebSettings.getDefaultUserAgent(this).replace("Android", "X11; Linux x86_64") else WebSettings.getDefaultUserAgent(this)
            page.reload()
        }
        parent.addView(menu, FrameLayout.LayoutParams(d(220), -2, Gravity.TOP or Gravity.START).apply { topMargin = d(28) })
    }

    private fun urlEditor(parent: FrameLayout, page: WebView) {
        val input = EditText(this).apply {
            setSingleLine(true); setText(page.url); selectAll(); setBackgroundColor(Color.WHITE)
            setOnEditorActionListener { _, _, _ -> loadTypedUrl(this, page, parent); true }
        }
        parent.addView(input, FrameLayout.LayoutParams(-1, d(48), Gravity.TOP).apply { topMargin = d(28) })
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        wm.updateViewLayout(parent, params)
        input.requestFocus()
        input.post { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
    }
    private fun loadTypedUrl(input: EditText, page: WebView, parent: FrameLayout) {
        var url = input.text.toString().trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        page.loadUrl(url); parent.removeView(input)
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        wm.updateViewLayout(parent, params)
    }

    private fun bookmarks(): List<Pair<String, String>> {
        val saved = getSharedPreferences("bookmarks", MODE_PRIVATE).getString("items", "[]") ?: "[]"
        return try {
            val items = JSONArray(saved)
            List(items.length()) { index ->
                val item = items.getJSONObject(index)
                item.getString("name") to item.getString("url")
            }
        } catch (_: Exception) { emptyList() }
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
        }
        val cancel = button("×", Color.DKGRAY) { close() }
        editor.addView(name, LinearLayout.LayoutParams(0, d(48), 1f))
        editor.addView(save, LinearLayout.LayoutParams(d(52), d(48)))
        editor.addView(cancel, LinearLayout.LayoutParams(d(32), d(48)))
        parent.addView(editor, FrameLayout.LayoutParams(-1, d(48), Gravity.TOP).apply { topMargin = d(28) })
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        wm.updateViewLayout(parent, params)
        name.requestFocus()
        name.post { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(name, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun drag(v: View, resize: Boolean) = v.setOnTouchListener(object : View.OnTouchListener {
        var x = 0; var y = 0; var w = 0; var h = 0; var downX = 0f; var downY = 0f
        override fun onTouch(view: View, e: MotionEvent): Boolean = when (e.action) {
            MotionEvent.ACTION_DOWN -> { if (resize) maximized = false; x = params.x; y = params.y; w = params.width; h = params.height; downX = e.rawX; downY = e.rawY; true }
            MotionEvent.ACTION_MOVE -> { if (resize) { params.width = maxOf(d(180), w + (e.rawX - downX).toInt()); params.height = maxOf(d(180), h + (e.rawY - downY).toInt()) } else { params.x = x + (e.rawX - downX).toInt(); params.y = y + (e.rawY - downY).toInt() }; current()?.let { wm.updateViewLayout(it, params) }; true }
            MotionEvent.ACTION_UP -> {
                if (!resize && minimized && abs(e.rawX - downX) < d(4).toFloat() && abs(e.rawY - downY) < d(4).toFloat()) {
                    bubble?.let { wm.removeView(it) }
                    bubble = null
                    restoreBrowser()
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
        bubble = TextView(this).apply {
            text = "◉"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.DKGRAY) }
            elevation = d(4).toFloat()
        }
        params.width = d(48); params.height = d(48); params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        drag(bubble!!, false); wm.addView(bubble, params)
    }
    private fun restoreBrowser() {
        val browser = root ?: return
        minimized = false
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
        if (minimized) bubble?.let { wm.removeView(it) } else root?.let { wm.removeView(it) }
        web?.destroy()
        super.onDestroy()
    }
    private fun createChannel() { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel("floating_browser", "Floating browser", NotificationManager.IMPORTANCE_LOW)) }
}
