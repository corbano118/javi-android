package com.javi.assistant

import android.annotation.SuppressLint
import android.app.Activity
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object BrowserPostBridge {
    private const val ORIGIN = "https://j-a-v-i-45ursb.v2.appdeploy.ai/"
    private var webView: WebView? = null
    private var ready = CompletableDeferred<Unit>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()

    @SuppressLint("SetJavaScriptEnabled")
    fun init(activity: Activity) {
        if (webView != null) return
        activity.runOnUiThread {
            if (webView != null) return@runOnUiThread
            ready = CompletableDeferred()
            webView = WebView(activity).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                addJavascriptInterface(Callback, "JaviNative")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (!ready.isCompleted && url?.startsWith(ORIGIN) == true) ready.complete(Unit)
                    }
                }
                loadUrl(ORIGIN)
            }
        }
    }

    suspend fun postJson(path: String, payload: JSONObject, timeoutMs: Long = 120_000): String {
        withTimeout(30_000) { ready.await() }
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        pending[id] = deferred
        val safePath = JSONObject.quote(path)
        val safePayload = payload.toString()
        val safeId = JSONObject.quote(id)
        val script = """
            (async function(){
              try {
                const response=await fetch($safePath,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify($safePayload)});
                const body=await response.text();
                JaviNative.onResult($safeId,String(response.status),body);
              } catch(e) {
                JaviNative.onResult($safeId,'0',String(e));
              }
            })();
        """.trimIndent()
        withContext(Dispatchers.Main) {
            webView?.evaluateJavascript(script, null) ?: throw IllegalStateException("El puente multimedia no está inicializado")
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    fun destroy() {
        val view = webView ?: return
        webView = null
        view.post {
            runCatching { view.removeJavascriptInterface("JaviNative") }
            runCatching { view.destroy() }
        }
        pending.values.forEach { if (!it.isCompleted) it.completeExceptionally(IllegalStateException("J.A.V.I. se cerró")) }
        pending.clear()
    }

    private object Callback {
        @JavascriptInterface
        fun onResult(id: String, statusText: String, body: String) {
            val deferred = pending.remove(id) ?: return
            val status = statusText.toIntOrNull() ?: 0
            if (status in 200..299) {
                deferred.complete(body)
            } else {
                val detail = runCatching { JSONObject(body).optString("error") }.getOrDefault("").ifBlank { body.take(240) }
                deferred.completeExceptionally(IllegalStateException(if (status == 0) detail else "J.A.V.I. Core respondió $status: $detail"))
            }
        }
    }
}
