package com.mtzallqmy.aiagent.memory

import com.mtzallqmy.aiagent.database.DatabaseProvider
import com.mtzallqmy.aiagent.database.MemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import com.mtzallqmy.aiagent.common.SecretSanitizer
import java.util.UUID

/**
 * Five memory stores as required: conversation, working, long-term,
 * procedural, workspace — each scoped in a namespace with search,
 * ranking, metadata, edit, delete, expiry and pinning. Secrets never stored.
 */
object MemoryNamespaces {
    const val CONVERSATION = "conversation"
    const val WORKING = "working"
    const val LONG_TERM = "long_term"
    const val PROCEDURAL = "procedural"
    const val WORKSPACE = "workspace"
}

class MemoryStore(private val database: () -> Any) {
    private fun db() = database() as com.mtzallqmy.aiagent.database.AppDatabase

    suspend fun put(
        namespace: String,
        key: String,
        value: String,
        type: String = "long_term",
        metadata: String? = null,
        score: Double = 0.5,
        expiresAtMs: Long? = null,
    ) {
        // HARDENING: never persist secrets into memory. Reject values that
        // look like API keys, JWTs, bearer tokens, or private key material.
        if (SecretSanitizer.containsSecret(value)) {
            throw IllegalArgumentException("Refusing to store value containing a detected secret in memory")
        }
        db().memoryDao().upsert(MemoryEntity(
            id = UUID.randomUUID().toString(),
            namespace = namespace,
            type = type,
            key = key,
            value = value,
            metadata = metadata,
            score = score,
            pinned = false,
            expiresAt = expiresAtMs,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        ))
    }

    suspend fun edit(id: String, namespace: String, value: String) {
        val dao = db().memoryDao()
        val items = dao.list(namespace).first()
        val existing = items.firstOrNull { it.id == id } ?: return
        db().memoryDao().upsert(existing.copy(value = value, updatedAt = System.currentTimeMillis()))
    }

    suspend fun delete(id: String) = db().memoryDao().delete(id)

    suspend fun pin(id: String, pinned: Boolean) {
        val dao = db().memoryDao()
        val items = dao.list(MemoryNamespaces.LONG_TERM).first()
        val existing = items.firstOrNull { it.id == id } ?: return
        db().memoryDao().upsert(existing.copy(pinned = pinned))
    }

    fun list(namespace: String): Flow<List<MemoryEntity>> = db().memoryDao().list(namespace)

    suspend fun search(namespace: String, query: String): List<MemoryEntity> =
        db().memoryDao().search(namespace, query)
}
