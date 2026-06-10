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

    fun isEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getConfig(context: Context): LlmConfig {
        val prefs = prefs(context)
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
        prefs(context).edit().putString(KEY_PROVIDER, provider.name).apply()
    }

    fun setApiKey(context: Context, apiKey: String) {
        prefs(context).edit().putString(KEY_API_KEY, apiKey.trim()).apply()
    }

    fun hasApiKey(context: Context): Boolean {
        return prefs(context).getString(KEY_API_KEY, "").orEmpty().isNotBlank()
    }

    fun setModel(context: Context, model: String) {
        prefs(context).edit().putString(KEY_MODEL, model.trim().ifBlank { DEFAULT_DEEPSEEK_MODEL }).apply()
    }

    fun setBaseUrl(context: Context, baseUrl: String) {
        prefs(context).edit().putString(KEY_BASE_URL, baseUrl.trim().ifBlank { DEFAULT_DEEPSEEK_BASE_URL }).apply()
    }

    private fun prefs(context: Context): SharedPreferences {
        val appContext = context.applicationContext
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
        }.getOrElse {
            appContext.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        }
    }
}
