package ru.ivan.jarvis.parts

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import ru.ivan.jarvis.parts.core.Page
import ru.ivan.jarvis.parts.core.PageFetcher
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * Загрузка открытых страниц как обычным телефонным браузером: GET, обычный User-Agent,
 * без логинов и обхода капч. Таймаут загрузки 10 секунд.
 * js = true — страница открывается невидимым WebView (выдача многих сайтов рисуется скриптом),
 * после загрузки ждём ещё до 4 секунд, пока скрипт дорисует таблицу.
 */
class AndroidFetcher(context: Context) : PageFetcher {
    private val ctx = context.applicationContext

    override suspend fun get(url: String, js: Boolean): Page = if (js) web(url) else http(url)

    private suspend fun http(url: String): Page = withContext(Dispatchers.IO) {
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9")
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            val code = c.responseCode
            val body = (if (code in 200..399) c.inputStream else c.errorStream)?.bufferedReader()?.use { r ->
                val sb = StringBuilder(); val buf = CharArray(8192)
                while (sb.length < MAX) { val n = r.read(buf); if (n < 0) break; sb.append(buf, 0, n) }
                sb.toString()
            }.orEmpty()
            c.disconnect()
            Page(code, body)
        } catch (e: Exception) {
            Page(0, "", "нет связи: ${e.javaClass.simpleName}")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun web(url: String): Page = withTimeoutOrNull(15_000) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val main = Handler(Looper.getMainLooper())
                val wv = WebView(ctx)
                var code = 200
                var done = false
                var loaded = false
                fun finish(p: Page) {
                    if (done) return
                    done = true
                    main.post { runCatching { wv.stopLoading(); wv.destroy() } }
                    if (cont.isActive) cont.resume(p)
                }
                fun grab(attempt: Int) {
                    if (done) return
                    wv.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                        val html = runCatching { JSONArray("[$raw]").getString(0) }.getOrDefault("")
                        // Ждём, пока скрипт дорисует цены (до 4 попыток по секунде)
                        if (attempt < 4 && !PRICE.containsMatchIn(html)) main.postDelayed({ grab(attempt + 1) }, 1000)
                        else finish(Page(code, html))
                    }
                }
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.loadsImagesAutomatically = false
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, u: String) {
                        if (loaded) return
                        loaded = true
                        main.postDelayed({ grab(0) }, 1000)
                    }
                    override fun onReceivedHttpError(view: WebView, req: WebResourceRequest, resp: WebResourceResponse) {
                        if (req.isForMainFrame) code = resp.statusCode
                    }
                    override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                        if (req.isForMainFrame) finish(Page(0, "", "сайт не открылся: ${err.description}"))
                    }
                }
                // Загрузка дольше 10 секунд — забираем, что есть
                main.postDelayed({ if (!loaded) { loaded = true; grab(3) } }, 10_000)
                cont.invokeOnCancellation { main.post { runCatching { wv.destroy() } } }
                wv.loadUrl(url)
            }
        }
    } ?: Page(0, "", "не ответил за 10 секунд")

    companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36"
        private const val MAX = 1_500_000
        private val PRICE = Regex("\\d\\s*(₽|руб|р\\.)")
    }
}
