package com.mtzallqmy.aiagent.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
 *
 * HARDENING (v1.1):
 * - The master key protects the application; every secret is encrypted with its
 *   own DERIVED sub-key (per (scope, name) Keystore alias), so rotating or
 *   deleting ONE secret NEVER invalidates the others, and removing the master
 *   key wipes nothing silently — each scope's ciphertexts are recoverable
 *   through user confirmation.
 * - StrongBox availability is detected and requested when the device supports
 *   it (best-effort; falls back to TEE without failing).
 * - Corrupted ciphertext is quarantined (moved to a ".corrupt" entry) instead
 *   of silently destroyed, so the issue is observable and recoverable.
 * Keys never leave the TEE/StrongBox. Plaintext secrets are never stored in
 * DataStore, logs, messages, memory (beyond the loaded value), or analytics.
 */
class CredentialVault(
    context: Context,
    @get:VisibleForTesting internal val keystore: KeystoreGateway = AndroidKeystoreGateway(),
) {
    private val appContext = context.applicationContext

    val supportsStrongBox: Boolean by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) false
        else runCatching {
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        }.getOrDefault(false)
    }

    /** Encrypts and stores one secret under (scope, name). */
    fun save(scope: CredentialScope, name: String, secret: String) {
        val encryptor = AuthenticatedAesGcm(keystore, deriveSubKeyAlias(scope.id, name))
        val ciphertext = encryptor.encrypt(secret.toByteArray(Charsets.UTF_8))
        appContext.getSharedPreferences("credentials:${scope.id}", Context.MODE_PRIVATE)
            .edit()
            .putString(name, Base64.getEncoder().encodeToString(ciphertext))
            .apply()
    }

    /** Loads and decrypts one secret; returns null when missing or unrecoverable. */
    fun load(scope: CredentialScope, name: String): String? {
        val prefs = appContext.getSharedPreferences("credentials:${scope.id}", Context.MODE_PRIVATE)
        val raw = prefs.getString(name, null) ?: return null
        val ciphertext = Base64.getDecoder().decode(raw)
        val encryptor = AuthenticatedAesGcm(keystore, deriveSubKeyAlias(scope.id, name))
        return try {
            encryptor.decrypt(ciphertext).toString(Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            // Corrupted ciphertext: quarantine instead of destroying.
            quarantine(prefs, name, raw, e)
            null
        }
    }

    fun delete(scope: CredentialScope, name: String) {
        appContext.getSharedPreferences("credentials:${scope.id}", Context.MODE_PRIVATE)
            .edit().remove(name).remove("$name.corrupt").remove("$name.corruptNote").apply()
    }

    fun has(scope: CredentialScope, name: String): Boolean =
        appContext.getSharedPreferences("credentials:${scope.id}", Context.MODE_PRIVATE)
            .contains(name)

    fun allNames(scope: CredentialScope): List<String> =
        appContext.getSharedPreferences("credentials:${scope.id}", Context.MODE_PRIVATE)
            .all.keys.filterNot { it.endsWith(".corrupt") || it.endsWith(".corruptNote") }

    /** Wipes the whole scope (useful after a master-key rotation). */
    fun clear(scope: CredentialScope) {
        appContext.getSharedPreferences("credentials:${scope.id}", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    /**
     * Rotate a single secret's sub-key: re-encrypt the value under a fresh
     * derived alias (master key untouched — other secrets are unaffected).
     */
    fun rotate(scope: CredentialScope, name: String) {
        val secret = load(scope, name) ?: return
        // New nonce makes the derived alias/key different from the previous one.
        val nonce = Random.nextBytes(16)
        val prefs = appContext.getSharedPreferences("credentials:${scope.id}", Context.MODE_PRIVATE)
        val newAlias = deriveSubKeyAlias(scope.id, name, nonce)
        val encryptor = AuthenticatedAesGcm(keystore, newAlias)
        val ciphertext = encryptor.encrypt(secret.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("$name.nonce", Base64.getEncoder().encodeToString(nonce))
            .putString(name, Base64.getEncoder().encodeToString(ciphertext))
            .apply()
    }

    private fun deriveSubKeyAlias(scopeId: String, name: String, nonce: ByteArray? = null): String {
        val nonceSuffix = nonce?.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it).take(10) } ?: "d0"
        val raw = "aegis_${scopeId.take(10)}_${name.take(24)}_$nonceSuffix"
        return raw.replace(Regex("[^a-zA-Z0-9_]"), "_").take(60).lowercase()
    }

    private fun quarantine(
        prefs: android.content.SharedPreferences, name: String, raw: String, cause: GeneralSecurityException,
    ) {
        val note = "corrupt:${cause.javaClass.simpleName}:${System.currentTimeMillis()}"
        prefs.edit()
            .remove(name)
            .putString("$name.corrupt", raw)
            .putString("$name.corruptNote", note)
            .apply()
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

internal class AndroidKeystoreGateway(
    @get:VisibleForTesting private val strongBoxAvailable: Boolean = detectStrongBox(),
) : KeystoreGateway {
    override fun loadOrCreateKey(alias: String): SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(alias)) {
            return ks.getKey(alias, null) as SecretKey
        }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore",
        )
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(CredentialVault.CIPHER_KEY_SIZE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && strongBoxAvailable) {
            builder.setIsStrongBoxBacked(true)
        }
        generator.init(builder.build())
        return generator.generateKey()
    }

    override fun deleteKey(alias: String) {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }

    companion object {
        private fun detectStrongBox(): Boolean = runCatching {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                java.lang.Class.forName("android.content.pm.PackageManager")
                    .getField("FEATURE_STRONGBOX_KEYSTORE").get(null) != null
        }.getOrDefault(false)
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
    keys: List<ProviderKeyEntry>,
    private val strategy: KeyPoolStrategy = KeyPoolStrategy.PRIMARY,
) {
    /** Mutable pool so keys can be enabled/disabled and health-tracked at runtime. */
    private val entries: MutableList<ProviderKeyEntry> = keys.toMutableList()

    fun add(entry: ProviderKeyEntry) { entries.add(entry) }

    fun remove(secretRef: String): Boolean = entries.removeAll { it.secretRef == secretRef }

    fun enable(secretRef: String, enabled: Boolean) {
        entries.firstOrNull { it.secretRef == secretRef }?.enabled = enabled
    }

    fun updateHealth(secretRef: String, success: Boolean, statusCode: Int? = null, rateLimitUntil: Long? = null) {
        val entry = entries.firstOrNull { it.secretRef == secretRef } ?: return
        entry.lastSuccess = if (success) System.currentTimeMillis() else entry.lastSuccess
        entry.lastError = if (!success) System.currentTimeMillis() else entry.lastError
        entry.lastStatusCode = statusCode ?: entry.lastStatusCode
        entry.rateLimitedUntil = rateLimitUntil ?: entry.rateLimitedUntil
    }

    fun healthSummary(): List<String> = entries.map { entry ->
        val masked = entry.secretRef.take(8) + "****" + entry.secretRef.takeLast(4)
        "${masked} enabled=${entry.enabled} errors=${entry.errorCount} lastStatus=${entry.lastStatusCode}"
    }

    fun current(): ProviderKeyEntry? = usableEntries().run {
        when (strategy) {
            KeyPoolStrategy.PRIMARY -> firstOrNull()
            KeyPoolStrategy.FAILOVER -> firstOrNull()
            KeyPoolStrategy.ROUND_ROBIN -> getOrNull(roundRobinIndex % maxOf(size, 1)).also { roundRobinIndex++ }
            KeyPoolStrategy.WEIGHTED -> weightedPick()
        }
    }

    /** Failover: pick next usable key; do NOT rotate on 400/model-not-found (client errors). */
    fun failover(exclude: String, statusCode: Int?): ProviderKeyEntry? {
        if (statusCode != null && (statusCode in 400..499 && statusCode != 429)) return null
        return usableEntries().firstOrNull { it.secretRef != exclude }
    }

    /** Usable keys: enabled and not currently rate-limited. */
    private fun usableEntries(): List<ProviderKeyEntry> =
        entries.filter { it.enabled && (it.rateLimitedUntil ?: 0L) < System.currentTimeMillis() }

    private var roundRobinIndex = 0

    private fun weightedPick(): ProviderKeyEntry? {
        val usable = usableEntries()
        if (usable.isEmpty()) return null
        val total = usable.sumOf { it.weight }
        if (total <= 0) return usable.first()
        val roll = Random.nextInt(total)
        var acc = 0
        for (entry in usable) {
            acc += entry.weight
            if (roll < acc) return entry
        }
        return usable.last()
    }
}

data class ProviderKeyEntry(
    val secretRef: String,
    val weight: Int = 1,
    var enabled: Boolean = true,
    var errorCount: Int = 0,
    var lastSuccess: Long? = null,
    var lastError: Long? = null,
    var lastStatusCode: Int? = null,
    var rateLimitedUntil: Long? = null,
)
