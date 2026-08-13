package com.mtzallqmy.aiagent.local_llm

import com.mtzallqmy.aiagent.local_llm.internal.LlamaNativeBridge
import com.mtzallqmy.aiagent.local_llm.internal.NativeModelInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LlamaCppLocalModelBackendTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `assessment gates real load and generation reports native usage`() = runBlocking {
        val root = temporaryFolder.newFolder("models")
        val file = root.resolve("model.gguf").also(::createGguf)
        val native = FakeNativeBridge()
        val backend = LlamaCppLocalModelBackend(LocalModelDiscovery(listOf(root)), FakeResources(), native)
        val reference = LocalModelReference(file.canonicalPath)
        val options = LocalModelLoadOptions()

        val assessment = backend.assessLoad(reference, options)
        assertTrue(assessment.canLoad)
        val loaded = backend.load(reference, options, assessment.assessmentId)
        val events = backend.generate("Hello", LocalGenerationOptions(maxTokens = 8)).toList()

        assertEquals("Native Test", loaded.nativeDescription)
        assertEquals(1, native.loadCount)
        assertEquals(LocalGenerationEvent.Text("hello"), events[0])
        assertEquals(LocalGenerationEvent.Completed(LocalTokenUsage(2, 1, 7)), events[1])
        backend.unload()
        assertEquals(1, native.unloadCount)
    }

    @Test
    fun `explicit cancellation reaches active native generation`() = runBlocking {
        val root = temporaryFolder.newFolder("models")
        val file = root.resolve("model.gguf").also(::createGguf)
        val native = FakeNativeBridge(blockNext = true)
        val backend = LlamaCppLocalModelBackend(LocalModelDiscovery(listOf(root)), FakeResources(), native)
        val reference = LocalModelReference(file.canonicalPath)
        val options = LocalModelLoadOptions()
        val assessment = backend.assessLoad(reference, options)
        backend.load(reference, options, assessment.assessmentId)

        val collection = async { backend.generate("Hello").toList() }
        assertTrue(native.generationStarted.await(2, TimeUnit.SECONDS))
        backend.cancelGeneration()
        collection.await()

        assertTrue(native.cancelled)
        assertFalse(native.generationAllocated)
    }
}

private class FakeNativeBridge(
    private val blockNext: Boolean = false,
) : LlamaNativeBridge {
    var loadCount = 0
    var unloadCount = 0
    @Volatile var cancelled = false
    @Volatile var generationAllocated = false
    val generationStarted = CountDownLatch(1)
    private var tokenReturned = false

    override fun loadModel(path: String, useMemoryMap: Boolean): Long {
        loadCount += 1
        return 10
    }

    override fun modelInfo(modelHandle: Long) = NativeModelInfo("Native Test", 100, 200)

    override fun unloadModel(modelHandle: Long) {
        unloadCount += 1
    }

    override fun startGeneration(
        modelHandle: Long,
        prompt: String,
        contextSize: Int,
        threads: Int,
        maxTokens: Int,
        temperature: Float,
        seed: Int,
    ): Long {
        generationAllocated = true
        generationStarted.countDown()
        return 20
    }

    override fun nextToken(generationHandle: Long): ByteArray? {
        if (blockNext) {
            repeat(100) {
                if (cancelled) return null
                Thread.sleep(5)
            }
        }
        if (tokenReturned) return null
        tokenReturned = true
        return "hello".encodeToByteArray()
    }

    override fun cancelGeneration(generationHandle: Long) {
        cancelled = true
    }

    override fun generationUsage(generationHandle: Long) = longArrayOf(2, 1, 7)

    override fun freeGeneration(generationHandle: Long) {
        generationAllocated = false
    }
}
