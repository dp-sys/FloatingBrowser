package com.example.floatingbrowser

import android.app.*
import android.animation.ValueAnimator
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
import android.service.quicksettings.TileService
import android.service.quicksettings.Tile
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.CookieManager
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
    private var browserX = 0
    private var browserY = 0
    private var browserFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
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
    private var bubbleAtTop = true
    private var lastOrientation = Configuration.ORIENTATION_UNDEFINED
    private var reloadOnRotate = true
    private var transitioning = false
    private val dp get() = resources.displayMetrics.density
    private fun d(n: Int) = (n * dp).toInt()
    private val barHeight get() = d(24)
    private val bubbleSize get() = d(36)
    private val bubbleHalfSize get() = d(18)

    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        setTileActive(true)
        lastOrientation = resources.configuration.orientation
        reloadOnRotate = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("reload_on_rotate", true)
        createChannel()
        startForeground(7, Notification.Builder(this, "floating_browser")
            .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("Floating Browser")
            .setContentText("Browser overlay is running").build())
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showBrowser()
    }

    private fun layoutParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; x = d(16); y = d(80) }

    private fun showBrowser() {
        minimized = false
        val view = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        view.setOnApplyWindowInsetsListener { _, insets ->
            if (!minimized) view.post { fitToDisplay() }
            insets
        }
        val address = TextView(this).apply {
            text = "Bookmarks"; textSize = 10f; gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.WHITE); isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
            tag = "address"
        }
        val cookies = CookieManager.getInstance().apply { setAcceptCookie(true) }
        val page = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            cookies.setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    address.text = "${view.title ?: ""}  ·  $url"
                    cookies.flush()
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
        bar.addView(address, FrameLayout.LayoutParams(-1, barHeight).apply { leftMargin = d(96); rightMargin = d(79) })
        val left = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        left.addView(topButton("☰", Color.rgb(255, 152, 0)) { toggleMenu(view, page) })
        left.addView(topButton("‹") { if (page.canGoBack()) page.goBack() })
        left.addView(topButton("›") { if (page.canGoForward()) page.goForward() })
        left.addView(topButton("−") { page.zoomOut() })
        left.addView(topButton("+") { page.zoomIn() })
        bar.addView(left, FrameLayout.LayoutParams(d(90), barHeight, Gravity.START))

        val right = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        right.addView(topButton("□", Color.rgb(255, 193, 7)) { toggleMaximize() })
        right.addView(View(this), LinearLayout.LayoutParams(d(4), barHeight))
        right.addView(topButton("—", Color.rgb(76, 175, 80), 28) { minimize() })
        right.addView(View(this), LinearLayout.LayoutParams(d(5), barHeight))
        right.addView(topButton("×", Color.rgb(244, 67, 54)) { confirmClose(view) })
        bar.addView(right, FrameLayout.LayoutParams(d(73), barHeight, Gravity.END))
        view.addView(bar, FrameLayout.LayoutParams(-1, barHeight, Gravity.TOP))

        val handle = TextView(this).apply { text = "◢"; textSize = 18f; setTextColor(Color.DKGRAY); gravity = Gravity.CENTER }
        view.addView(handle, FrameLayout.LayoutParams(d(28), d(28), Gravity.BOTTOM or Gravity.END))
        drag(handle, true)
        if (::params.isInitialized) {
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            params = layoutParams(1, 1)
            maximized = false
            loadGeometry()
        }
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

    private fun statusBarVisible(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || wm.currentWindowMetrics.windowInsets.isVisible(WindowInsets.Type.statusBars())

    @Suppress("DEPRECATION")
    private fun safeDisplayBounds(): Rect {
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            val windowInsets = metrics.windowInsets
            val cutout = windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
            val status = if (windowInsets.isVisible(WindowInsets.Type.statusBars()))
                windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()) else android.graphics.Insets.NONE
            val navigation = if (windowInsets.isVisible(WindowInsets.Type.navigationBars()))
                windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()) else android.graphics.Insets.NONE
            Rect(
                metrics.bounds.left + maxOf(cutout.left, navigation.left),
                metrics.bounds.top + status.top,
                metrics.bounds.right - maxOf(cutout.right, navigation.right),
                metrics.bounds.bottom - navigation.bottom
            )
        } else {
            val size = android.graphics.Point()
            wm.defaultDisplay.getSize(size)
            Rect(0, 0, size.x, size.y)
        }
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // Use the left edge fully; permanently reserve the right edge for rounded corners/navigation.
            bounds.left = 0
            bounds.right -= d(40)
        }
        return bounds
    }

    private fun fitToDisplay(update: Boolean = true) {
        val safe = safeDisplayBounds()
        if (maximized) {
            params.width = safe.width(); params.height = safe.height(); params.x = safe.left; params.y = safe.top
        } else {
            params.width = min(params.width, safe.width())
            params.height = min(params.height, safe.height())
            params.x = params.x.coerceIn(safe.left, maxOf(safe.left, safe.right - params.width))
            params.y = params.y.coerceIn(safe.top, maxOf(safe.top, safe.bottom - params.height))
            if (!minimized && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && !statusBarVisible()) {
                params.y = safe.top
            }
        }
        if (update) current()?.let { wm.updateViewLayout(it, params) }
    }

    private fun geometryKey(orientation: Int = resources.configuration.orientation) =
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"

    private fun loadGeometry() {
        val safe = safeDisplayBounds()
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val prefs = getSharedPreferences("geometry", MODE_PRIVATE)
        val key = geometryKey()
        val defaultWidth = (safe.width() * if (landscape) 0.45f else 0.80f).toInt()
        val defaultHeight = (safe.height() * if (landscape) 0.80f else 0.60f).toInt()
        params.width = prefs.getInt("${key}_width", defaultWidth)
        params.height = prefs.getInt("${key}_height", defaultHeight)
        params.x = prefs.getInt("${key}_x", safe.left)
        params.y = prefs.getInt("${key}_y", safe.top)
        fitToDisplay(false)
    }

    private fun saveGeometry(orientation: Int = resources.configuration.orientation) {
        if (maximized || minimized) return
        val key = geometryKey(orientation)
        getSharedPreferences("geometry", MODE_PRIVATE).edit()
            .putInt("${key}_width", params.width).putInt("${key}_height", params.height)
            .putInt("${key}_x", params.x).putInt("${key}_y", params.y).apply()
    }

    private fun loadBubblePosition() {
        val safe = safeDisplayBounds()
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val prefs = getSharedPreferences("bubble", MODE_PRIVATE)
        val key = geometryKey()
        params.x = prefs.getInt("${key}_x", safe.left)
        params.y = prefs.getInt("${key}_y", if (landscape) safe.top else (safe.top + safe.bottom - params.height) / 2)
        if (landscape) {
            bubbleAtTop = params.y + params.height / 2 < (safe.top + safe.bottom) / 2
            params.y = if (bubbleAtTop) safe.top else safe.bottom - params.height
            params.x = params.x.coerceIn(safe.left, maxOf(safe.left, safe.right - params.width))
        } else {
            bubbleOnLeft = params.x + params.width / 2 < (safe.left + safe.right) / 2
            params.x = if (bubbleOnLeft) safe.left else safe.right - params.width
            params.y = params.y.coerceIn(safe.top, maxOf(safe.top, safe.bottom - params.height))
        }
    }

    private fun saveBubblePosition(orientation: Int = resources.configuration.orientation) {
        if (!minimized) return
        val key = geometryKey(orientation)
        getSharedPreferences("bubble", MODE_PRIVATE).edit()
            .putInt("${key}_x", params.x).putInt("${key}_y", params.y).apply()
    }

    private fun animateRootBounds(fromX: Int, fromY: Int, fromWidth: Int, fromHeight: Int) {
        val browser = root ?: return
        val toX = params.x; val toY = params.y; val toWidth = params.width; val toHeight = params.height
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            addUpdateListener {
                val progress = it.animatedValue as Float
                params.x = (fromX + (toX - fromX) * progress).toInt()
                params.y = (fromY + (toY - fromY) * progress).toInt()
                params.width = (fromWidth + (toWidth - fromWidth) * progress).toInt()
                params.height = (fromHeight + (toHeight - fromHeight) * progress).toInt()
                wm.updateViewLayout(browser, params)
            }
            start()
        }
    }

    private fun toggleMaximize() {
        if (minimized || root == null) return
        val fromX = params.x; val fromY = params.y; val fromWidth = params.width; val fromHeight = params.height
        if (!maximized) {
            restoreWidth = params.width; restoreHeight = params.height
            restoreX = params.x; restoreY = params.y
            maximized = true
        } else {
            params.width = restoreWidth; params.height = restoreHeight
            params.x = restoreX; params.y = restoreY
            maximized = false
        }
        fitToDisplay(false)
        animateRootBounds(fromX, fromY, fromWidth, fromHeight)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val orientationChanged = lastOrientation != Configuration.ORIENTATION_UNDEFINED && lastOrientation != newConfig.orientation
        val oldOrientation = lastOrientation
        lastOrientation = newConfig.orientation
        if (orientationChanged) {
            if (minimized) saveBubblePosition(oldOrientation)
            else if (!maximized) saveGeometry(oldOrientation)

            val wasMaximized = maximized
            maximized = false
            loadGeometry()
            if (minimized) {
                browserWidth = params.width; browserHeight = params.height
                browserX = params.x; browserY = params.y
            }
            if (wasMaximized) {
                restoreWidth = params.width; restoreHeight = params.height
                restoreX = params.x; restoreY = params.y
                maximized = true
            }

            if (minimized) {
                params.width = bubbleSize; params.height = bubbleSize
                loadBubblePosition()
                if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    if (bubbleCollapsed) collapseBubble() else expandBubble()
                } else if (bubbleCollapsed) {
                    collapseBubble()
                } else {
                    bubble?.let { wm.updateViewLayout(it, params) }
                }
            } else {
                fitToDisplay()
            }
        } else {
            fitToDisplay()
        }
        if (orientationChanged && reloadOnRotate && !minimized) web?.reload()
    }

    private fun button(label: String, color: Int = Color.WHITE, action: () -> Unit) = TextView(this).apply {
        text = label; textSize = 18f; gravity = Gravity.CENTER; setTextColor(color)
        setOnClickListener { action() }; layoutParams = LinearLayout.LayoutParams(d(32), d(28))
    }

    private fun topButton(label: String, color: Int = Color.WHITE, width: Int = 18, action: () -> Unit) =
        button(label, color, action).apply { textSize = 12f; layoutParams = LinearLayout.LayoutParams(d(width), barHeight) }

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
                if (armed) saveGeometry() else if (event.action == MotionEvent.ACTION_UP) urlEditor(parent, page)
                true
            }
            else -> true
        }
    }

    private fun toggleMenu(parent: FrameLayout, page: WebView) {
        val old = parent.findViewWithTag<View>("menu")
        if (old != null) {
            parent.removeView(old)
            parent.findViewWithTag<View>("menu_dismiss")?.let { parent.removeView(it) }
            return
        }
        val dismiss = View(this).apply { tag = "menu_dismiss" }
        val menu = LinearLayout(this).apply {
            tag = "menu"; orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(238, 238, 238))
            elevation = d(8).toFloat()
        }
        fun closeMenu() {
            parent.removeView(menu)
            parent.findViewWithTag<View>("menu_dismiss")?.let { parent.removeView(it) }
        }
        dismiss.setOnClickListener { closeMenu() }
        fun item(name: String, f: () -> Unit) = TextView(this).apply {
            text = name; textSize = 14f; setTextColor(Color.DKGRAY); gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { f(); closeMenu() }
            menu.addView(this, LinearLayout.LayoutParams(d(180), d(34)))
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
        menu.addView(CheckBox(this).apply {
            text = "Reload on rotate"; textSize = 14f; setTextColor(Color.DKGRAY); isChecked = reloadOnRotate
            setPadding(d(4), 0, 0, 0)
            setOnCheckedChangeListener { _, checked ->
                reloadOnRotate = checked
                getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("reload_on_rotate", checked).apply()
            }
        }, LinearLayout.LayoutParams(d(180), d(34)))
        parent.addView(dismiss, FrameLayout.LayoutParams(-1, -1))
        parent.addView(menu, FrameLayout.LayoutParams(d(180), -2, Gravity.TOP or Gravity.START).apply { leftMargin = d(5); topMargin = barHeight })
    }

    private fun confirmClose(parent: FrameLayout) {
        if (parent.findViewWithTag<View>("close_confirm") != null) return
        val dim = View(this).apply { tag = "close_dismiss"; setBackgroundColor(Color.argb(80, 0, 0, 0)) }
        val panel = LinearLayout(this).apply {
            tag = "close_confirm"; orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE)
            elevation = d(8).toFloat()
        }
        fun dismiss() {
            parent.removeView(panel)
            parent.findViewWithTag<View>("close_dismiss")?.let { parent.removeView(it) }
        }
        dim.setOnClickListener { dismiss() }
        panel.addView(TextView(this).apply {
            text = "Close browser?"; textSize = 14f; setTextColor(Color.DKGRAY); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, d(34)))
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER }
        actions.addView(button("No", Color.DKGRAY) { dismiss() }, LinearLayout.LayoutParams(d(48), d(30)))
        actions.addView(button("Close", Color.rgb(220, 50, 47)) { stopSelf() }, LinearLayout.LayoutParams(d(58), d(30)))
        panel.addView(actions, LinearLayout.LayoutParams(-1, d(30)))
        parent.addView(dim, FrameLayout.LayoutParams(-1, -1))
        parent.addView(panel, FrameLayout.LayoutParams(d(170), d(64), Gravity.CENTER))
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
            val typed = input.text.toString().trim()
            val url = when {
                typed.startsWith("http://") || typed.startsWith("https://") -> typed
                typed.contains('.') && !typed.any { it.isWhitespace() } -> "https://$typed"
                else -> Uri.Builder().scheme("https").authority("www.google.com")
                    .appendPath("search").appendQueryParameter("q", typed).build().toString()
            }
            page.loadUrl(url)
            close()
        }
        input.setOnEditorActionListener { _, _, _ -> submit(); true }
        input.setOnFocusChangeListener { _, focused -> if (!focused) close() }
        address.visibility = View.GONE
        bar.addView(input, FrameLayout.LayoutParams(-1, barHeight).apply { leftMargin = d(96); rightMargin = d(79) })
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
        return listOf(
            "Google" to "https://www.google.com",
            "Genshin map" to "https://act.hoyolab.com/ys/app/interactive-map/index.html?bbs_presentation_style=no_header&bbs_theme=dark&bbs_theme_device=1&lang=en-us#/map/2?shown_types=&center=81.92,49.26&zoom=-1.50"
        ) + custom
    }

    private fun loadHome(page: WebView) {
        fun safe(value: String) = android.text.TextUtils.htmlEncode(value)
        val links = bookmarks().joinToString("") { (name, url) ->
            "<a class=\"bookmark\" href=\"${safe(url)}\">${safe(name)}</a>"
        }.ifEmpty { "<div class=\"empty\">No bookmarks yet.</div>" }
        val html = """
            <html><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              * { box-sizing:border-box; }
              body { margin:0; padding:16px; background:#f4f4f4; font-family:sans-serif; }
              .bookmark { display:block; margin:0 0 6px; padding:10px 12px; background:#ffffff; color:#303030;
                text-decoration:none; font-size:14px; border-radius:8px; border-left:4px solid #f59e0b;
                box-shadow:0 1px 3px rgba(0,0,0,.12); }
              .empty { color:#777777; text-align:center; padding:24px; }
            </style></head><body>$links</body></html>
        """.trimIndent()
        page.loadDataWithBaseURL("https://bookmarks.local/", html, "text/html", "UTF-8", null)
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
            MotionEvent.ACTION_MOVE -> {
                if (resize) {
                    params.width = maxOf(d(180), w + (e.rawX - downX).toInt())
                    params.height = maxOf(d(180), h + (e.rawY - downY).toInt())
                } else {
                    params.x = x + (e.rawX - downX).toInt()
                    if (minimized && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                        val safe = safeDisplayBounds()
                        bubbleAtTop = e.rawY < (safe.top + safe.bottom) / 2f
                        params.y = if (bubbleAtTop) safe.top else safe.bottom - params.height
                    } else params.y = y + (e.rawY - downY).toInt()
                }
                current()?.let { wm.updateViewLayout(it, params) }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!resize && minimized) {
                    if (!expandedFromHalf && abs(e.rawX - downX) < d(4).toFloat() && abs(e.rawY - downY) < d(4).toFloat()) {
                        bubbleHandler.removeCallbacksAndMessages(null)
                        openFromBubble()
                    } else {
                        saveBubblePosition()
                        scheduleBubbleCollapse()
                    }
                } else if (!minimized) saveGeometry()
                true
            }
            else -> true
        }
    })
    private fun current(): View? = if (minimized) bubble else root
    private fun minimize() {
        val browser = root ?: return
        if (transitioning) return
        transitioning = true
        browserWidth = params.width; browserHeight = params.height; browserFlags = params.flags
        browserX = params.x; browserY = params.y
        saveGeometry()
        browserScrollX = web?.scrollX ?: 0; browserScrollY = web?.scrollY ?: 0
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(browser.windowToken, 0)
        browser.animate().cancel()
        browser.animate().scaleX(0.2f).scaleY(0.2f).alpha(0f).setDuration(180).withEndAction {
            // Detach while still transparent; resetting it before removal can flash one full-size frame.
            wm.removeViewImmediate(browser)
            showMinimizedBubble()
            transitioning = false
        }.start()
    }

    private fun showMinimizedBubble() {
        minimized = true
        bubbleCollapsed = false
        bubble = BubbleView(this).apply { elevation = d(4).toFloat() }
        params.width = bubbleSize; params.height = bubbleSize
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        loadBubblePosition()
        drag(bubble!!, false)
        bubble!!.alpha = 0f; bubble!!.scaleX = 0.6f; bubble!!.scaleY = 0.6f
        wm.addView(bubble, params)
        bubble!!.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
        scheduleBubbleCollapse()
    }

    private fun openFromBubble() {
        val icon = bubble
        if (icon == null) { restoreBrowser(); return }
        icon.animate().cancel()
        icon.animate().scaleX(0.2f).scaleY(0.2f).alpha(0f).setDuration(140).withEndAction {
            wm.removeView(icon)
            bubble = null
            restoreBrowser()
        }.start()
    }

    private fun scheduleBubbleCollapse() {
        bubbleHandler.removeCallbacksAndMessages(null)
        bubbleHandler.postDelayed({ collapseBubble() }, 2_000)
    }

    private fun collapseBubble() {
        val icon = bubble ?: return
        if (!minimized) return
        val safe = safeDisplayBounds()
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape) {
            bubbleAtTop = params.y + params.height / 2 < (safe.top + safe.bottom) / 2
            bubbleCollapsed = true
            icon.half = true; icon.horizontal = true; icon.onTop = bubbleAtTop
            params.width = bubbleSize; params.height = bubbleHalfSize
            params.y = if (bubbleAtTop) safe.top else safe.bottom - params.height
            params.x = params.x.coerceIn(safe.left, maxOf(safe.left, safe.right - params.width))
            wm.updateViewLayout(icon, params)
            saveBubblePosition()
            return
        }
        bubbleOnLeft = params.x + params.width / 2 < (safe.left + safe.right) / 2
        bubbleCollapsed = true
        icon.half = true; icon.horizontal = false; icon.onLeft = bubbleOnLeft
        params.width = bubbleHalfSize; params.height = bubbleSize
        params.x = if (bubbleOnLeft) safe.left else safe.right - params.width
        params.y = params.y.coerceIn(safe.top, maxOf(safe.top, safe.bottom - params.height))
        wm.updateViewLayout(icon, params)
        saveBubblePosition()
    }

    private fun expandBubble() {
        val icon = bubble ?: return
        val safe = safeDisplayBounds()
        bubbleCollapsed = false
        icon.half = false
        params.width = bubbleSize
        params.height = bubbleSize
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            params.x = params.x.coerceIn(safe.left, maxOf(safe.left, safe.right - params.width))
            params.y = if (bubbleAtTop) safe.top else safe.bottom - params.height
        } else {
            params.x = if (bubbleOnLeft) safe.left else safe.right - params.width
            params.y = params.y.coerceIn(safe.top, maxOf(safe.top, safe.bottom - params.height))
        }
        wm.updateViewLayout(icon, params)
    }
    private fun restoreBrowser() {
        val browser = root ?: return
        minimized = false; bubbleCollapsed = false
        params.width = browserWidth; params.height = browserHeight
        params.x = browserX; params.y = browserY
        params.flags = browserFlags
        browser.alpha = 0f; browser.scaleX = 0.2f; browser.scaleY = 0.2f
        wm.addView(browser, params)
        fitToDisplay()
        browser.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
        web?.let { page -> page.post {
            page.requestLayout()
            page.scrollTo(browserScrollX, browserScrollY)
            page.invalidate()
        }
        }
    }
    override fun onDestroy() {
        bubbleHandler.removeCallbacksAndMessages(null)
        titleHandler.removeCallbacksAndMessages(null)
        CookieManager.getInstance().flush()
        saveGeometry()
        if (minimized) bubble?.let { wm.removeView(it) } else root?.let { wm.removeView(it) }
        web?.destroy()
        setTileActive(false)
        super.onDestroy()
    }
    private fun setTileActive(active: Boolean) {
        getSharedPreferences("tile", MODE_PRIVATE).edit().putBoolean("active", active).apply()
        TileService.requestListeningState(this, ComponentName(this, FloatingBrowserTileService::class.java))
    }
    private fun createChannel() { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel("floating_browser", "Floating browser", NotificationManager.IMPORTANCE_LOW)) }
}

private class BubbleView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var half = false
    var onLeft = true
    var horizontal = false
    var onTop = true

    override fun onDraw(canvas: Canvas) {
        val radius = if (horizontal) width / 2f else height / 2f
        val centerX = if (half && !horizontal) if (onLeft) 0f else width.toFloat() else width / 2f
        val centerY = if (half && horizontal) if (onTop) 0f else height.toFloat() else height / 2f
        paint.color = Color.rgb(255, 152, 0)
        canvas.drawCircle(centerX, centerY, radius, paint)
        if (!half) {
            paint.color = Color.WHITE
            canvas.drawCircle(centerX, centerY, radius / 5f, paint)
        }
    }
}

class FloatingBrowserTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refreshState()
    }

    override fun onClick() {
        super.onClick()
        val launch = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Settings.canDrawOverlays(this)) {
            startForegroundService(Intent(this, FloatingBrowserService::class.java))
            qsTile.state = Tile.STATE_ACTIVE
            qsTile.updateTile()
        } else {
            startActivityAndCollapse(launch)
        }
    }

    private fun refreshState() {
        qsTile.state = if (getSharedPreferences("tile", MODE_PRIVATE).getBoolean("active", false))
            Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile.updateTile()
    }
}
