package com.mtzallqmy.aiagent.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.VisibleForTesting
import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.random.Random

/**
 * Secure credential storage backed by Android Keystore (AES-256-GCM).
 * Keys never leave the TEE/StrongBox. Plaintext secrets are never stored
 * in DataStore, logs, messages, memory, or analytics.
 */
class CredentialVault(
    context: Context,
    @VisibleForTesting internal val keystore: KeystoreGateway = AndroidKeystoreGateway(),
) {
    private val appContext = context.applicationContext
    private val encryptor = AuthenticatedAesGcm(keystore, KEY_ALIAS)

    fun save(scope: CredentialScope, name: String, secret: String) {
        val ciphertext = encryptor.encrypt(secret.toByteArray(Charsets.UTF_8))
        val prefs = appContext.getSharedPreferences(
            "credentials:${scope.id}", Context.MODE_PRIVATE,
        )
        prefs.edit()
            .putString(name, Base64.getEncoder().encodeToString(ciphertext))
            .apply()
    }

    fun load(scope: CredentialScope, name: String): String? {
        val raw = appContext.getSharedPreferences(
            "credentials:${scope.id}", Context.MODE_PRIVATE,
        ).getString(name, null) ?: return null
        val ciphertext = Base64.getDecoder().decode(raw)
        return try {
            encryptor.decrypt(ciphertext).toString(Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            // Key destroyed (device lock reset) -> invalidate stored ref.
            delete(scope, name)
            null
        }
    }

    fun delete(scope: CredentialScope, name: String) {
        appContext.getSharedPreferences("credentials:${scope.id}", Context.MODE_PRIVATE)
            .edit().remove(name).apply()
    }

    fun has(scope: CredentialScope, name: String): Boolean = load(scope, name) != null

    fun allNames(scope: CredentialScope): List<String> {
        return appContext.getSharedPreferences("credentials:${scope.id}", Context.MODE_PRIVATE)
            .all.keys.toList()
    }

    fun clear(scope: CredentialScope) {
        appContext.getSharedPreferences("credentials:${scope.id}", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    fun rotate(scope: CredentialScope, name: String) {
        delete(scope, name)
        keystore.deleteKey(KEY_ALIAS)
        encryptor.resetCipherState()
    }

    companion object {
        private const val KEY_ALIAS = "aegis_credential_key"
        const val CIPHER_KEY_SIZE = 256
        const val GCM_TAG_LENGTH = 128
        const val GCM_IV_LENGTH = 12
    }
}

data class CredentialScope(val id: String) {
    companion object {
        val PROVIDER = CredentialScope("provider")
        val SSH = CredentialScope("ssh")
        val MCP = CredentialScope("mcp")
        val API_KEY_POOL = CredentialScope("keypool")
    }
}

/** Keystore abstraction for testability. */
interface KeystoreGateway {
    fun loadOrCreateKey(alias: String): SecretKey
    fun deleteKey(alias: String)
}

internal class AndroidKeystoreGateway : KeystoreGateway {
    override fun loadOrCreateKey(alias: String): SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(alias)) {
            return ks.getKey(alias, null) as SecretKey
        }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore",
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(CredentialVault.CIPHER_KEY_SIZE)
                .build(),
        )
        return generator.generateKey()
    }

    override fun deleteKey(alias: String) {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }
}

/** AES-256-GCM authenticated encryption with random IV per operation. */
internal class AuthenticatedAesGcm(
    private val gateway: KeystoreGateway,
    private val alias: String,
) {
    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, gateway.loadOrCreateKey(alias))
        val ciphertext = cipher.doFinal(plaintext)
        val iv = cipher.iv
        // layout: iv (12) || ciphertext (with tag)
        return iv + ciphertext
    }

    fun decrypt(blob: ByteArray): ByteArray {
        val iv = blob.take(GCM_IV_LENGTH).toByteArray()
        val ciphertext = blob.drop(GCM_IV_LENGTH).toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, gateway.loadOrCreateKey(alias), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(ciphertext)
    }

    fun resetCipherState() {
        // Ciphers are per-operation; nothing persistent to reset.
    }

    companion object {
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
    }
}

/** API key pool strategies for provider fallback (per requirements). */
enum class KeyPoolStrategy { PRIMARY, FAILOVER, ROUND_ROBIN, WEIGHTED }

class ApiKeyPool(
    private val keys: List<ProviderKeyEntry>,
    private val strategy: KeyPoolStrategy = KeyPoolStrategy.PRIMARY,
) {
    private var roundRobinIndex = 0

    fun current(): ProviderKeyEntry? = when (strategy) {
        KeyPoolStrategy.PRIMARY -> keys.firstOrNull()
        KeyPoolStrategy.FAILOVER -> keys.firstOrNull()
        KeyPoolStrategy.ROUND_ROBIN -> keys.getOrNull(roundRobinIndex % maxOf(keys.size, 1)).also { roundRobinIndex++ }
        KeyPoolStrategy.WEIGHTED -> weightedPick()
    }

    /** Failover: pick next working key; do NOT rotate on 400/model-not-found. */
    fun failover(exclude: String, statusCode: Int?): ProviderKeyEntry? {
        if (statusCode != null && (statusCode in 400..499 && statusCode != 429)) return null
        return keys.firstOrNull { it.secretRef != exclude }
    }

    private fun weightedPick(): ProviderKeyEntry? {
        if (keys.isEmpty()) return null
        val total = keys.sumOf { it.weight }
        if (total <= 0) return keys.first()
        val roll = Random.nextInt(total)
        var acc = 0
        for (entry in keys) {
            acc += entry.weight
            if (roll < acc) return entry
        }
        return keys.last()
    }
}

data class ProviderKeyEntry(val secretRef: String, val weight: Int = 1)
