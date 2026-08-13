package com.mtzallqmy.aiagent.memory

import com.mtzallqmy.aiagent.common.SecretSanitizer
import kotlin.math.sqrt

/**
 * RAG concepts studied from AnythingLLM (MIT, clean-room reimplementation):
 * document ingestion with chunking, embeddings abstraction, vector search,
 * and source citations. This is a neutral, offline-first implementation —
 * the on-device embedder uses term-frequency weighting (no network call);
 * remote embedders can be plugged in via EmbeddingsProvider.
 */

/** Embedding input and output contract (provider-neutral). */
interface EmbeddingsProvider {
    val dimension: Int
    suspend fun embed(text: String): List<Double>
    suspend fun embedMany(texts: List<String>): List<List<Double>>
}

/**
 * Offline term-frequency embedder — no network, no external model.
 * Sufficient for keyword-weighted similarity on-device.
 */
class KeywordEmbedder(override val dimension: Int = 256) : EmbeddingsProvider {
    override suspend fun embed(text: String): List<Double> = buildEmbedding(listOf(text))[0]

    override suspend fun embedMany(texts: List<String>): List<List<Double>> = buildEmbedding(texts)

    private fun buildEmbedding(texts: List<String>): List<List<Double>> = texts.map { text ->
        val vector = DoubleArray(dimension)
        for (token in tokenize(text)) {
            vector[(token.hashCode() and 0x7FFFFFFF) % dimension] += 1.0
        }
        val norm = sqrt(vector.sumOf { it * it })
        if (norm == 0.0) vector.toList() else vector.map { it / norm }
    }

    private fun tokenize(text: String): List<String> {
        val sb = StringBuilder()
        val tokens = mutableListOf<String>()
        for (ch in text.lowercase()) {
            if (ch.isLetterOrDigit()) sb.append(ch) else if (sb.isNotEmpty()) {
                tokens.add(sb.toString()); sb.clear()
            }
        }
        if (sb.isNotEmpty()) tokens.add(sb.toString())
        return tokens
    }
}

/** Vector store: in-memory with cosine similarity; pluggable for larger stores. */
interface VectorStore {
    suspend fun upsert(id: String, namespace: String, vector: List<Double>, source: String)
    suspend fun search(namespace: String, query: List<Double>, topK: Int = 5): List<SimilarChunk>
    suspend fun delete(id: String)
    suspend fun clear(namespace: String)
}

data class SimilarChunk(
    val id: String,
    val namespace: String,
    val source: String,
    val score: Double,
)

class InMemoryVectorStore(
    private val dimension: Int = 256,
) : VectorStore {
    private data class Item(
        val namespace: String,
        val source: String,
        val vector: List<Double>,
    )

    private val items = HashMap<String, Item>()

    override suspend fun upsert(id: String, namespace: String, vector: List<Double>, source: String) {
        require(vector.size == dimension) { "vector dimension ${vector.size} != $dimension" }
        items[id] = Item(namespace, source, vector.toList())
    }

    override suspend fun search(namespace: String, query: List<Double>, topK: Int): List<SimilarChunk> {
        require(query.size == dimension)
        val qNorm = sqrt(query.sumOf { it * it })
        if (qNorm == 0.0) return emptyList()
        return items.asSequence()
            .filter { it.value.namespace == namespace }
            .map { (id, item) ->
                val dot = item.vector.zip(query).sumOf { (a, b) -> a * b }
                SimilarChunk(id, namespace, item.source, (dot / qNorm).coerceIn(-1.0, 1.0))
            }
            .sortedByDescending { it.score }
            .take(topK)
            .toList()
    }

    override suspend fun delete(id: String) { items.remove(id) }
    override suspend fun clear(namespace: String) { items.entries.removeAll { it.value.namespace == namespace } }

    val size: Int get() = items.size
}

/**
 * Document ingestion: chunking + secret scan (never index secrets) + indexing.
 */
class DocumentIngestor(
    private val vectorStore: VectorStore,
    private val embedder: EmbeddingsProvider,
    private val chunkMaxChars: Int = 800,
    private val chunkOverlapChars: Int = 100,
) {
    suspend fun ingest(namespace: String, sourceId: String, text: String): Int {
        if (SecretSanitizer.containsSecret(text)) {
            throw IllegalArgumentException("Refusing to index text containing a detected secret")
        }
        val chunks = chunk(text)
        for ((index, chunkText) in chunks.withIndex()) {
            val id = "$sourceId#$index"
            val vector = embedder.embed(chunkText)
            vectorStore.upsert(id, namespace, vector, sourceId)
        }
        return chunks.size
    }

    suspend fun findRelevant(namespace: String, query: String, topK: Int = 5): List<SimilarChunk> =
        vectorStore.search(namespace, embedder.embed(query), topK)

    private fun chunk(text: String): List<String> {
        if (text.length <= chunkMaxChars) return listOf(text.trim()).filter { it.isNotEmpty() }
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + chunkMaxChars, text.length)
            val tail = text.substring(start, end).trim()
            if (tail.isNotEmpty()) chunks.add(tail)
            start = if (end >= text.length) text.length else (start + chunkMaxChars - chunkOverlapChars).coerceAtLeast(start + 1)
        }
        return chunks
    }
}

/** Source citation record attached to answers (AnythingLLM concept). */
data class Citation(val sourceId: String, val snippet: String, val score: Double)
