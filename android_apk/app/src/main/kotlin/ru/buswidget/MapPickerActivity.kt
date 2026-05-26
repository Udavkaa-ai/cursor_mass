package ru.buswidget

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

class MapPickerActivity : AppCompatActivity() {

    companion object {
        const val RESULT_STOP_ID   = "stop_id"
        const val RESULT_STOP_NAME = "stop_name"
        const val RESULT_LAT       = "lat"
        const val RESULT_LON       = "lon"
        private val STOP_RE = Regex("""stops/(?:stop__)?(\d+)""")
        private val TITLE_SUFFIX = Regex("""\s*[—–-]\s*Яндекс.*$""")
        private val LL_RE = Regex("""[?&]ll=(-?\d+\.?\d*)[,%2C]+(-?\d+\.?\d*)""")
    }

    private lateinit var webView:    WebView
    private lateinit var tvHint:     TextView
    private lateinit var btnConfirm: Button
    private lateinit var progress:   ProgressBar

    private var selectedId:   String? = null
    private var selectedName: String? = null
    private var selectedLat = 0.0
    private var selectedLon = 0.0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_picker)

        webView    = findViewById(R.id.webView)
        tvHint     = findViewById(R.id.tvHint)
        btnConfirm = findViewById(R.id.btnConfirm)
        progress   = findViewById(R.id.progressBar)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // modern Chrome UA so Yandex serves the standard mobile site
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: ""
                // Yandex Maps redirects to intent:// to open the native app — block it
                return scheme != "https" && scheme != "http"
            }

            // fires on every history push/replace (SPA navigation)
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                onUrlChanged(url, null)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }
            override fun onReceivedTitle(view: WebView, title: String) {
                onUrlChanged(view.url ?: "", title)
            }
        }

        webView.loadUrl("https://yandex.ru/maps/")

        btnConfirm.isEnabled = false
        btnConfirm.alpha = 0.4f
        btnConfirm.setOnClickListener {
            val id = selectedId ?: return@setOnClickListener
            setResult(Activity.RESULT_OK, Intent().apply {
                putExtra(RESULT_STOP_ID,   id)
                putExtra(RESULT_STOP_NAME, selectedName ?: id)
                putExtra(RESULT_LAT, selectedLat)
                putExtra(RESULT_LON, selectedLon)
            })
            finish()
        }

        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    private fun onUrlChanged(url: String, title: String?) {
        val match = STOP_RE.find(url)
        if (match != null) {
            selectedId = match.groupValues[1]
            if (title != null && title.isNotBlank()) {
                selectedName = title.replace(TITLE_SUFFIX, "").trim()
            }
            LL_RE.find(url)?.let {
                selectedLon = it.groupValues[1].toDoubleOrNull() ?: 0.0
                selectedLat = it.groupValues[2].toDoubleOrNull() ?: 0.0
            }
            tvHint.text = "Выбрано: ${selectedName ?: selectedId}"
            tvHint.setTextColor(0xFFF4F4F6.toInt())
            btnConfirm.isEnabled = true
            btnConfirm.alpha = 1f
        } else if (!url.contains("stops/")) {
            selectedId   = null
            selectedName = null
            btnConfirm.isEnabled = false
            btnConfirm.alpha = 0.4f
            tvHint.text = "Нажмите на значок остановки на карте"
            tvHint.setTextColor(0xFF888899.toInt())
        }
    }
}
