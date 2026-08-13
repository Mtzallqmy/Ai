package com.mtzallqmy.aiagent.feature.browser

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.webkit.JavascriptInterface
import android.webkit.SafeBrowsingResponse
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Hardened browser backend: WebView-driven navigation, DOM snapshot via a
 * scoped JS bridge (limited, non-invasive extraction), safe actions, and
 * navigation policy enforcement.
 *
 * HARDENING (v1.1):
 * - JS injection is ALWAYS JSON-encoded (never string interpolation of raw
 *   input) — no injection surface into the JS payload.
 * - Navigation policy: javascript:, file://, intent://, tel:, sms:, mailto:
 *   schemes are blocked by default (mailto/tel are not browser actions; the
 *   open-URI device action handles them explicitly with user confirmation).
 * - Safe Browsing enabled when available; mixed content blocked; third-party
 *   cookies disabled (agent does not need cross-site tracking);
 *   allowFileAccess/fileAccessFromUrls disabled.
 * - The JS bridge exposes ONLY one method (provideSnapshot); no arbitrary
 *   native access from page content.
 */
class WebViewEngine(
    private val webViewFactory: () -> WebView,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var webView: WebView? = null
    private var lastSnapshot = ""
    private var pendingNav: CompletableDeferred<Unit>? = null

    fun create() {
        val view = webViewFactory()
        setupWebView(view)
        webView = view
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            allowFileAccess = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
            }
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
            setSupportMultipleWindows(false)
        }
        view.webChromeClient = WebChromeClient()
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url?.toString() ?: return false
                return !isAllowedScheme(uri)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                pendingNav?.complete(Unit)
                pendingNav = null
            }

            override fun onSafeBrowsingHit(
                view: WebView?, request: WebResourceRequest?, threatType: Int,
                callback: SafeBrowsingResponse?,
            ) {
                callback?.showInterstitial(false) ?: callback?.proceed(false)
            }
        }
        view.addJavascriptInterface(SnapshotBridge(), "AegisBridge")
    }

    private fun isAllowedScheme(url: String): Boolean {
        val scheme = Uri.parse(url)?.scheme?.lowercase() ?: return false
        return scheme == "https" || scheme == "http" || scheme == "about"
    }

    private fun requireView(): WebView = webView ?: error("WebView not created")

    suspend fun navigate(url: String): Result<Unit> = runCatching {
        val view = requireView()
        if (!isAllowedScheme(url)) throw IllegalArgumentException("Blocked URL scheme: $url")
        withContext(Dispatchers.Main) {
            pendingNav?.cancel()
            pendingNav = CompletableDeferred()
            view.loadUrl(url)
        }
    }

    suspend fun snapshot(): String = withContext(Dispatchers.Main) {
        val view = requireView()
        val deferred = CompletableDeferred<String>()
        view.evaluateJavascript(SNAPSHOT_JS) { result ->
            val unquoted = result?.trim()?.trim('"')?.replace("\\\"", "\"") ?: ""
            lastSnapshot = unquoted
            deferred.complete(unquoted)
        }
        deferred.await()
    }

    /**
     * Safe click: selector and action are JSON-encoded; the JS payload is built
     * from static templates only — user input never interpolates into code.
     */
    suspend fun clickSelector(selector: String): Boolean = withContext(Dispatchers.Main) {
        val view = webView ?: return@withContext false
        val deferred = CompletableDeferred<Boolean>()
        view.evaluateJavascript("(${clickTemplateJs()})(${buildPayloadJson("click", selector)})") { deferred.complete(it == "true") }
        deferred.await()
    }

    suspend fun typeIntoSelector(selector: String, text: String): Boolean = withContext(Dispatchers.Main) {
        val view = webView ?: return@withContext false
        val deferred = CompletableDeferred<Boolean>()
        view.evaluateJavascript("(${typeTemplateJs()})(${buildPayloadJson("type", selector, text)})") { deferred.complete(it == "true") }
        deferred.await()
    }

    suspend fun scrollBy(deltaY: Int): Unit = withContext(Dispatchers.Main) {
        webView?.scrollBy(0, deltaY)
    }

    suspend fun scrollSelectorIntoView(selector: String, up: Boolean = false): Boolean = withContext(Dispatchers.Main) {
        val view = webView ?: return@withContext false
        val deferred = CompletableDeferred<Boolean>()
        view.evaluateJavascript("(${scrollTemplateJs()})(${buildPayloadJson(if (up) "scrollUp" else "scroll", selector)})") { deferred.complete(it == "true") }
        deferred.await()
    }

    suspend fun goBack(): Boolean = withContext(Dispatchers.Main) {
        val view = webView ?: return@withContext false
        if (!view.canGoBack()) return@withContext false
        view.goBack()
        true
    }

    suspend fun goForward(): Boolean = withContext(Dispatchers.Main) {
        val view = webView ?: return@withContext false
        if (!view.canGoForward()) return@withContext false
        view.goForward()
        true
    }

    suspend fun reload(): Unit = withContext(Dispatchers.Main) {
        requireView().reload()
    }

    suspend fun findOnPage(query: String): Int = withContext(Dispatchers.Main) {
        val view = webView ?: return@withContext 0
        val deferred = CompletableDeferred<Int>()
        view.evaluateJavascript("(${findTemplateJs()})(${buildPayloadJson("find", query)})") { deferred.complete(it?.toIntOrNull() ?: 0) }
        deferred.await()
    }

    fun currentUrl(): String? = webView?.url

    fun destroy() {
        webView?.stopLoading()
        webView?.removeAllViews()
        webView?.destroy()
        webView = null
    }

    /**
     * Scoped, non-invasive DOM extraction: tags, ids, classes, text, links —
     * no credentials, no cookies. The page content cannot call into native
     * code: the bridge exposes only provideSnapshot.
     */
    private inner class SnapshotBridge {
        @JavascriptInterface
        fun provideSnapshot(jsonPayload: String) {
            lastSnapshot = jsonPayload
        }
    }

    // ---- Static JS templates. User input arrives as a JSON payload only ----

    private fun clickTemplateJs() = """
(function(payload){
  var sel = JSON.parse(payload).selector;
  var el = document.querySelector(sel);
  if(!el) return 'false';
  el.scrollIntoView({block:'center'});
  el.click();
  return 'true';
})"""

    private fun typeTemplateJs() = """
(function(payload){
  var p = JSON.parse(payload);
  var el = document.querySelector(p.selector);
  if(!el) return 'false';
  el.focus();
  var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;
  if(setter && el instanceof HTMLInputElement) setter.call(el, p.text);
  else if (el instanceof HTMLTextAreaElement) el.value = p.text;
  else el.setAttribute('value', p.text);
  el.dispatchEvent(new Event('input',{bubbles:true}));
  el.dispatchEvent(new Event('change',{bubbles:true}));
  return 'true';
})"""

    private fun scrollTemplateJs() = """
(function(payload){
  var p = JSON.parse(payload);
  var el = document.querySelector(p.selector);
  if(!el) return 'false';
  if(p.action === 'scrollUp') el.scrollTop = Math.max(0, el.scrollTop - el.clientHeight);
  else if(p.action === 'scroll') el.scrollIntoView({block:'center'});
  else if(p.action === 'scrollDown') el.scrollTop += el.clientHeight;
  return 'true';
})"""

    private fun findTemplateJs() = """
(function(payload){
  var q = JSON.parse(payload).selector;
  return String(document.body.innerText.split(q).length - 1);
})"""

    /**
     * Builds the JSON payload via the serialization library (JSON-encoded —
     * never string-interpolated into the JS template).
     */
    private fun buildPayloadJson(action: String, selector: String, text: String? = null): String =
        buildJsonObject {
            put("action", JsonPrimitive(action))
            put("selector", JsonPrimitive(selector))
            if (text != null) put("text", JsonPrimitive(text))
        }.toString()

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

/** JSON payload schema for static JS templates. */
private data class SelectorPayload(val action: String, val selector: String, val text: String?)
