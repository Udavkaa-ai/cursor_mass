package ru.buswidget

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportZoom(false)
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView, request: WebResourceRequest, error: WebResourceError
                ) {
                    if (request.isForMainFrame) showError()
                }
            }
        }
        setContentView(webView)

        if (!hasSavedConfig()) showSettingsDialog(firstRun = true) else loadStop()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Настройки").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) showSettingsDialog(firstRun = false)
        return super.onOptionsItemSelected(item)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    // ── Конфигурация ────────────────────────────────────────────────────────

    private fun hasSavedConfig(): Boolean {
        val p = prefs()
        return p.getString("url", "").orEmpty().isNotBlank() &&
               p.getString("stop", "").orEmpty().isNotBlank()
    }

    private fun loadStop() {
        val p = prefs()
        val url  = p.getString("url",    "").orEmpty().trimEnd('/')
        val stop = p.getString("stop",   "").orEmpty().trim()
        val key  = p.getString("key",    "").orEmpty().trim()
        // API-ключ передаём query-параметром чтобы не писать кастомный WebViewClient
        val fullUrl = if (key.isNotBlank())
            "$url/stop/$stop?api_key=$key"
        else
            "$url/stop/$stop"
        webView.loadUrl(fullUrl)
    }

    private fun showSettingsDialog(firstRun: Boolean) {
        val p = prefs()
        val dp = resources.displayMetrics.density

        fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(8), px(24), px(8))
        }

        fun label(text: String) = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(px(4), px(12), 0, px(2))
        }

        fun input(hint: String, saved: String, password: Boolean = false) =
            EditText(this).apply {
                this.hint = hint
                setText(saved)
                textSize = 16f
                setTextColor(0xFFF4F4F6.toInt())
                setHintTextColor(0xFF666680.toInt())
                if (password) inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                setBackgroundColor(0xFF1A1A1D.toInt())
                setPadding(px(12), px(10), px(12), px(10))
            }

        val urlInput   = input("https://…containers.yandexcloud.net", p.getString("url",  "").orEmpty())
        val stopInput  = input("5854295457",                           p.getString("stop", "").orEmpty())
        val routeInput = input("925,907  (пусто = все)",               p.getString("routes","").orEmpty())
        val keyInput   = input("не обязательно",                       p.getString("key",  "").orEmpty(), password = true)

        root.addView(label("URL сервера"));   root.addView(urlInput)
        root.addView(label("ID остановки")); root.addView(stopInput)
        root.addView(label("Маршруты"));     root.addView(routeInput)
        root.addView(label("API-ключ"));     root.addView(keyInput)

        AlertDialog.Builder(this)
            .setTitle("Настройки")
            .setView(root)
            .setCancelable(!firstRun)
            .setPositiveButton("Сохранить") { _, _ ->
                p.edit()
                    .putString("url",    urlInput.text.toString().trim())
                    .putString("stop",   stopInput.text.toString().trim())
                    .putString("routes", routeInput.text.toString().trim())
                    .putString("key",    keyInput.text.toString().trim())
                    .apply()
                loadStop()
            }
            .show()
    }

    private fun showError() {
        webView.loadData(
            """<html><body style="background:#0f0f10;color:#f4f4f6;
               font-family:sans-serif;display:flex;align-items:center;
               justify-content:center;height:100vh;margin:0;text-align:center">
               <div><p style="font-size:18px">Не удалось подключиться к серверу</p>
               <p style="color:#7a7a80;font-size:14px">Проверь URL в настройках</p></div>
               </body></html>""",
            "text/html", "utf-8"
        )
    }

    private fun prefs() = getSharedPreferences("buswidget", Context.MODE_PRIVATE)
}
