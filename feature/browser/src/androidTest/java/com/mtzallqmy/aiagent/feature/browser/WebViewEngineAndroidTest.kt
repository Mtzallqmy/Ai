package com.mtzallqmy.aiagent.feature.browser

import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebViewEngineAndroidTest {
    @Test
    fun hardenedSettingsAndNavigationPolicyWorkOnRealWebView() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        lateinit var view: WebView
        val engine = WebViewEngine(
            webViewFactory = {
                WebView(context).also { view = it }
            },
        )

        engine.create()
        try {
            val settings = view.settings
            assertFalse(settings.allowFileAccess)
            assertFalse(settings.allowContentAccess)
            assertFalse(settings.allowFileAccessFromFileURLs)
            assertFalse(settings.allowUniversalAccessFromFileURLs)
            assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, settings.mixedContentMode)
            assertTrue(settings.safeBrowsingEnabled)
            assertFalse(CookieManager.getInstance().acceptThirdPartyCookies(view))

            assertTrue(engine.navigate("about:blank").isSuccess)
            assertTrue(engine.navigate("file:///data/data/private").isFailure)
            assertTrue(engine.navigate("http://example.com").isFailure)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { view.destroy() }
        }
    }
}
