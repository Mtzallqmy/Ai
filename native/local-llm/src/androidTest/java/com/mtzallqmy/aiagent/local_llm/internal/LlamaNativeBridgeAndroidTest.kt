package com.mtzallqmy.aiagent.local_llm.internal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LlamaNativeBridgeAndroidTest {
    @Test
    fun missingGgufIsRejectedByNativeLoader() {
        val error = runCatching {
            LlamaCppJniBridge().loadModel(
                "/data/local/tmp/aegis-model-that-does-not-exist.gguf",
                false,
            )
        }.exceptionOrNull()

        assertTrue(error is IOException)
    }

    @Test
    fun corruptGgufIsRejectedByNativeValidation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = context.cacheDir.resolve("corrupt-model.gguf")
        model.writeText("not a gguf model")

        val error = runCatching {
            LlamaCppJniBridge().loadModel(model.absolutePath, false)
        }.exceptionOrNull()

        assertTrue(error is IOException)
    }
}
