package com.screenreader.app.llm

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object LlmPreferences {

    private const val PREFS_NAME = "llm_config_prefs"
    private const val FALLBACK_PREFS_NAME = "llm_config_prefs_fallback"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_DISCLOSURE_ACCEPTED = "disclosure_accepted"

    @Volatile
    private var encryptedPrefs: SharedPreferences? = null
    @Volatile
    private var initializationAttempted = false
    @Volatile
    private var storageError: String? = null

    fun isEnabled(context: Context): Boolean {
        return prefs(context)?.getBoolean(KEY_ENABLED, false) ?: false
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context)?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
    }

    fun hasAcceptedDisclosure(context: Context): Boolean {
        return prefs(context)?.getBoolean(KEY_DISCLOSURE_ACCEPTED, false) ?: false
    }

    fun setDisclosureAccepted(context: Context, accepted: Boolean) {
        prefs(context)?.edit()?.putBoolean(KEY_DISCLOSURE_ACCEPTED, accepted)?.apply()
    }

    fun getConfig(context: Context): LlmConfig {
        val prefs = prefs(context) ?: return LlmConfig()
        val provider = prefs.getString(KEY_PROVIDER, Provider.DEEPSEEK.name)
            ?.let { value -> Provider.values().firstOrNull { it.name == value } }
            ?: Provider.DEEPSEEK
        return LlmConfig(
            provider = provider,
            apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
            model = prefs.getString(KEY_MODEL, DEFAULT_DEEPSEEK_MODEL).orEmpty().ifBlank { DEFAULT_DEEPSEEK_MODEL },
            baseUrl = prefs.getString(KEY_BASE_URL, DEFAULT_DEEPSEEK_BASE_URL).orEmpty().ifBlank { DEFAULT_DEEPSEEK_BASE_URL }
        )
    }

    fun setProvider(context: Context, provider: Provider) {
        prefs(context)?.edit()?.putString(KEY_PROVIDER, provider.name)?.apply()
    }

    fun setApiKey(context: Context, apiKey: String): Boolean {
        val prefs = prefs(context) ?: return false
        return prefs.edit().putString(KEY_API_KEY, apiKey.trim()).commit()
    }

    fun hasApiKey(context: Context): Boolean {
        return prefs(context)?.getString(KEY_API_KEY, "").orEmpty().isNotBlank()
    }

    fun setModel(context: Context, model: String) {
        prefs(context)?.edit()
            ?.putString(KEY_MODEL, model.trim().ifBlank { DEFAULT_DEEPSEEK_MODEL })
            ?.apply()
    }

    fun setBaseUrl(context: Context, baseUrl: String) {
        prefs(context)?.edit()
            ?.putString(KEY_BASE_URL, baseUrl.trim().ifBlank { DEFAULT_DEEPSEEK_BASE_URL })
            ?.apply()
    }

    fun isSecureStorageAvailable(context: Context): Boolean {
        return prefs(context) != null
    }

    fun getSecureStorageError(context: Context): String? {
        prefs(context)
        return storageError
    }

    @Synchronized
    private fun prefs(context: Context): SharedPreferences? {
        encryptedPrefs?.let { return it }
        if (initializationAttempted) return null
        initializationAttempted = true

        val appContext = context.applicationContext
        // Remove data that may have been written by the old plaintext fallback.
        appContext.deleteSharedPreferences(FALLBACK_PREFS_NAME)

        return runCatching {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.onSuccess {
            encryptedPrefs = it
            storageError = null
        }.onFailure { error ->
            storageError = "${error.javaClass.simpleName}: ${error.message ?: "unknown error"}"
        }.getOrNull()
    }

    fun secureStorageStatus(context: Context): String {
        return if (isSecureStorageAvailable(context)) {
            "available"
        } else {
            getSecureStorageError(context) ?: "unavailable"
        }
    }
}
