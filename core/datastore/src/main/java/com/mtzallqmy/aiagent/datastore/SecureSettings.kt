package com.mtzallqmy.aiagent.datastore

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "aegis_settings")

/**
 * Settings store. Secret values are NEVER stored here; settings only hold
 * references to secrets in the CredentialVault (Keystore-encrypted).
 */
class SecureSettings(private val context: Context) {

    // ---- Non-secret preferences ----

    val selectedProviderId: Flow<String?> = context.settingsDataStore.data
        .map { it[stringPreferencesKey("selected_provider_id")] }

    val selectedModelId: Flow<String?> = context.settingsDataStore.data
        .map { it[stringPreferencesKey("selected_model_id")] }

    val arabicLocale: Flow<Boolean> = context.settingsDataStore.data
        .map { it[booleanPreferencesKey("arabic_locale")] ?: false }

    val smartRouting: Flow<Boolean> = context.settingsDataStore.data
        .map { it[booleanPreferencesKey("smart_routing")] ?: false }

    val failoverEnabled: Flow<Boolean> = context.settingsDataStore.data
        .map { it[booleanPreferencesKey("failover_enabled")] ?: false }

    val remoteControlEnabled: Flow<Boolean> = context.settingsDataStore.data
        .map { it[booleanPreferencesKey("remote_control_enabled")] ?: false }

    suspend fun setString(key: String, value: String?) {
        context.settingsDataStore.edit {
            if (value == null) it.remove(stringPreferencesKey(key))
            else it[stringPreferencesKey(key)] = value
        }
    }

    suspend fun setBoolean(key: String, value: Boolean) {
        context.settingsDataStore.edit { it[booleanPreferencesKey(key)] = value }
    }

    suspend fun getString(key: String): String? =
        context.settingsDataStore.data.map { it[stringPreferencesKey(key)] }.first()

    suspend fun getBoolean(key: String): Boolean =
        context.settingsDataStore.data.map { it[booleanPreferencesKey(key)] ?: false }.first()
}
