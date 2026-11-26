package com.example.floatingbrowser

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
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
import kotlin.math.abs

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
            loadUrl("https://www.google.com")
        }
        web = page
        view.addView(page, FrameLayout.LayoutParams(-1, -1))

        // The grip is only 4dp painted, but retains a 24dp hit target for dragging.
        val grip = View(this).apply { setBackgroundColor(Color.argb(120, 60, 60, 60)) }
        view.addView(grip, FrameLayout.LayoutParams(-1, d(4), Gravity.TOP))
        val dragArea = View(this)
        view.addView(dragArea, FrameLayout.LayoutParams(-1, d(24), Gravity.TOP))

        val left = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val minus = button("−") { page.zoomOut() }
        val plus = button("+") { page.zoomIn() }
        val menu = button("☰") { toggleMenu(view, page) }
        left.addView(minus); left.addView(plus); left.addView(menu)
        view.addView(left, FrameLayout.LayoutParams(d(96), d(28), Gravity.TOP or Gravity.START))

        val right = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        right.addView(button("—") { minimize() }); right.addView(button("×") { stopSelf() })
        view.addView(right, FrameLayout.LayoutParams(d(64), d(28), Gravity.TOP or Gravity.END))

        val handle = TextView(this).apply { text = "◢"; textSize = 18f; setTextColor(Color.DKGRAY); gravity = Gravity.CENTER }
        view.addView(handle, FrameLayout.LayoutParams(d(28), d(28), Gravity.BOTTOM or Gravity.END))
        drag(dragArea, false); drag(handle, true)
        if (::params.isInitialized) {
            params.width = d(360); params.height = d(560)
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        } else params = layoutParams(d(360), d(560))
        root = view
        wm.addView(view, params)
    }

    private fun enableWebInput(view: View) {
        if (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            root?.let { wm.updateViewLayout(it, params) }
            view.requestFocusFromTouch()
        }
    }

    private fun button(label: String, action: () -> Unit) = TextView(this).apply {
        text = label; textSize = 18f; gravity = Gravity.CENTER; setTextColor(Color.DKGRAY)
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

    private fun drag(v: View, resize: Boolean) = v.setOnTouchListener(object : View.OnTouchListener {
        var x = 0; var y = 0; var w = 0; var h = 0; var downX = 0f; var downY = 0f
        override fun onTouch(view: View, e: MotionEvent): Boolean = when (e.action) {
            MotionEvent.ACTION_DOWN -> { x = params.x; y = params.y; w = params.width; h = params.height; downX = e.rawX; downY = e.rawY; true }
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
