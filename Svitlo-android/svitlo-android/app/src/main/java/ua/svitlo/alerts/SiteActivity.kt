package ua.svitlo.alerts

import android.annotation.SuppressLint
import android.app.Activity
import android.net.http.SslError
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Сайт обленерго всередині додатка. Запасний варіант, якщо автоматика не впоралась:
 * людина сама вводить рахунок, тисне «Переглянути», а потім «Взяти графік».
 */
class SiteActivity : Activity() {
    private lateinit var web: WebView
    private var slot = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        slot = intent.getIntExtra("slot", 0).coerceIn(0, Prefs.SLOTS - 1)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
            fitsSystemWindows = true
        }
        val hint = TextView(this).apply {
            text = "Введіть особовий рахунок ${Prefs.account(this@SiteActivity, slot)}, натисніть «Переглянути», " +
                "дочекайтеся годин відключень і натисніть кнопку внизу."
            setTextColor(getColor(R.color.muted))
            textSize = 13f
            setPadding(32, 20, 32, 12)
        }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    if (SiteFetcher.trusted(this@SiteActivity, error)) handler.proceed() else handler.cancel()
                }
            }
            loadUrl(SiteFetcher.URL)
        }
        val take = Button(this).apply {
            text = "Взяти графік із цієї сторінки"
            isAllCaps = false
            setOnClickListener { grab() }
        }
        root.addView(hint, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(take, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(root)
    }

    private fun grab() {
        web.evaluateJavascript("document.body.innerText") { r ->
            val text = SiteFetcher.decode(r)
            if (text.isNullOrBlank()) {
                Toast.makeText(this, "Сторінка ще не завантажилась", Toast.LENGTH_SHORT).show()
                return@evaluateJavascript
            }
            val out = Updater.apply(this, slot, text)
            sendBroadcast(android.content.Intent(PowerService.BROADCAST_UPDATE).setPackage(packageName))
            if (Prefs.enabled(this)) PowerService.start(this, PowerService.ACTION_REFRESH)
            Toast.makeText(this, out.message, Toast.LENGTH_LONG).show()
            if (out.ok) finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
