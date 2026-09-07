package com.javi.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONTokener
import org.json.JSONObject
import kotlin.coroutines.resume

object CoreWebBridge {
    private const val CORE_URL = "https://j-a-v-i-45ursb.v2.appdeploy.ai/"
    private val mutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var appContext: Context? = null
    @Volatile private var webView: WebView? = null
    @Volatile private var pageReady = false

    fun init(context: Context) {
        appContext = context.applicationContext
        mainHandler.post { ensureWebView() }
    }

    suspend fun sendMessage(text: String): String = mutex.withLock {
        val clean = text.trim()
        if (clean.isBlank()) return "No recibí ningún mensaje."

        suspendCancellableCoroutine { cont ->
            mainHandler.post {
                ensureWebView()
                waitUntilReady(
                    startedAt = System.currentTimeMillis(),
                    onReady = {
                        val view = webView
                        if (view == null) {
                            if (cont.isActive) cont.resume("No pude iniciar la conexión con J.A.V.I. Core.")
                            return@waitUntilReady
                        }

                        getAssistantCount(view) { beforeCount ->
                            injectMessage(view, clean) { sent ->
                                if (!sent) {
                                    if (cont.isActive) cont.resume("No pude enviar el mensaje a J.A.V.I. Core.")
                                    return@injectMessage
                                }
                                pollReply(
                                    view = view,
                                    previousCount = beforeCount,
                                    startedAt = System.currentTimeMillis()
                                ) { reply ->
                                    if (cont.isActive) cont.resume(reply)
                                }
                            }
                        }
                    },
                    onTimeout = {
                        if (cont.isActive) cont.resume("J.A.V.I. Core tardó demasiado en iniciar.")
                    }
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView() {
        if (webView != null) return
        val context = appContext ?: return

        webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = false
            settings.mediaPlaybackRequiresUserGesture = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    pageReady = true
                }
            }
            loadUrl(CORE_URL)
        }
    }

    private fun waitUntilReady(startedAt: Long, onReady: () -> Unit, onTimeout: () -> Unit) {
        if (pageReady) {
            onReady()
            return
        }
        if (System.currentTimeMillis() - startedAt > 20_000L) {
            onTimeout()
            return
        }
        mainHandler.postDelayed({ waitUntilReady(startedAt, onReady, onTimeout) }, 300L)
    }

    private fun getAssistantCount(view: WebView, callback: (Int) -> Unit) {
        view.evaluateJavascript(
            "document.querySelectorAll('.row.assistant .bubble').length.toString();"
        ) { raw ->
            callback(decodeJsString(raw).toIntOrNull() ?: 0)
        }
    }

    private fun injectMessage(view: WebView, text: String, callback: (Boolean) -> Unit) {
        val quoted = JSONObject.quote(text)
        val js = """
            (function(){
              const ta=document.querySelector('textarea');
              const btn=[...document.querySelectorAll('button')].find(b=>(b.textContent||'').trim()==='ENVIAR');
              if(!ta||!btn) return 'NO_UI';
              const setter=Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;
              setter.call(ta,$quoted);
              ta.dispatchEvent(new Event('input',{bubbles:true}));
              ta.dispatchEvent(new Event('change',{bubbles:true}));
              btn.click();
              return 'OK';
            })();
        """.trimIndent()

        view.evaluateJavascript(js) { raw ->
            callback(decodeJsString(raw) == "OK")
        }
    }

    private fun pollReply(
        view: WebView,
        previousCount: Int,
        startedAt: Long,
        callback: (String) -> Unit
    ) {
        if (System.currentTimeMillis() - startedAt > 55_000L) {
            callback("J.A.V.I. Core tardó demasiado en responder.")
            return
        }

        val js = """
            (function(){
              const items=document.querySelectorAll('.row.assistant .bubble');
              if(items.length<=$previousCount) return '';
              return (items[items.length-1].textContent||'').trim();
            })();
        """.trimIndent()

        view.evaluateJavascript(js) { raw ->
            val value = decodeJsString(raw)
            if (value.isNotBlank()) {
                callback(value)
            } else {
                mainHandler.postDelayed({ pollReply(view, previousCount, startedAt, callback) }, 450L)
            }
        }
    }

    private fun decodeJsString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return try {
            val value = JSONTokener(raw).nextValue()
            value?.toString().orEmpty()
        } catch (_: Exception) {
            raw.trim().trim('"')
        }
    }
}
