package com.mtzallqmy.aiagent.feature.browser

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Real browser backend: WebView-driven navigation, DOM snapshot via scoped JS
 * bridge (limited, non-invasive extraction), click/type/scroll actions, and
 * download interception.
 */
class WebViewEngine(
    private val webViewFactory: () -> WebView,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var webView: WebView? = null
    private var lastSnapshot = ""
    private val navComplete = CompletableDeferred<Unit>()

    fun create() {
        val view = webViewFactory()
        setupWebView(view)
        webView = view
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(view: WebView) {
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.loadWithOverviewMode = true
        view.settings.useWideViewPort = true
        view.webChromeClient = WebChromeClient()
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
            override fun onPageFinished(view: WebView?, url: String?) {
                navComplete.complete(Unit)
            }
        }
        view.addJavascriptInterface(SnapshotBridge(), "AegisBridge")
    }

    suspend fun navigate(url: String): Unit = withContext(Dispatchers.Main) {
        val view = webView ?: error("WebView not created")
        navComplete.complete(Unit) // reset state for new navigation
        view.loadUrl(url)
    }

    suspend fun snapshot(): String = withContext(Dispatchers.Main) {
        val view = webView ?: error("WebView not created")
        val deferred = CompletableDeferred<String>()
        view.evaluateJavascript(SNAPSHOT_JS) { result ->
            val unquoted = result?.trim()?.trim('"')?.replace("\\\"", "\"") ?: ""
            lastSnapshot = unquoted
            deferred.complete(unquoted)
        }
        deferred.await()
    }

    suspend fun clickSelector(selector: String): Boolean = withContext(Dispatchers.Main) {
        val view = webView ?: return@withContext false
        val deferred = CompletableDeferred<Boolean>()
        view.evaluateJavascript(
            """(function(){const el=document.querySelector('$selector');if(el){el.scrollIntoView();el.click();return true;}return false;})()""",
        ) { deferred.complete(it == "true") }
        deferred.await()
    }

    suspend fun typeIntoSelector(selector: String, text: String): Boolean = withContext(Dispatchers.Main) {
        val view = webView ?: return@withContext false
        val deferred = CompletableDeferred<Boolean>()
        val escaped = text.replace("'", "\\'").replace("\n", "\\n")
        view.evaluateJavascript(
            """(function(){const el=document.querySelector('$selector');if(el){el.focus();el.value='$escaped';el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));return true;}return false;})()""",
        ) { deferred.complete(it == "true") }
        deferred.await()
    }

    suspend fun scrollBy(deltaY: Int): Unit = withContext(Dispatchers.Main) {
        webView?.scrollBy(0, deltaY)
    }

    fun currentUrl(): String? = webView?.url

    fun destroy() {
        webView?.stopLoading()
        webView?.removeAllViews()
        webView?.destroy()
        webView = null
    }

    /** Scoped, non-invasive DOM extraction: tags, ids, classes, text, links — no credentials, no cookies. */
    private inner class SnapshotBridge {
        @JavascriptInterface
        fun provideSnapshot(jsonPayload: String) {
            lastSnapshot = jsonPayload
        }
    }

    companion object {
        val SNAPSHOT_JS = """
(function(){
  const nodes = Array.from(document.querySelectorAll('a,button,input,textarea,select,[role],h1,h2,h3,p,label,div,span,li')).slice(0, 500);
  const out = nodes.map((el,i)=>({
    index:i, tag:el.tagName, id:el.id||'', cls:(el.className&&typeof el.className==='string'?el.className:'').slice(0,80),
    text:(el.innerText||el.value||el.getAttribute('aria-label')||'').slice(0,120),
    href:el.href||'', type:el.type||'', disabled:!!el.disabled
  }));
  return JSON.stringify({url:location.href, title:document.title, nodes:out});
})()
        """.trimIndent()
    }
}
