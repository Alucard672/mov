package com.gofilm.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gofilm.app.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "gofilm_settings")

class SettingsStore(private val context: Context) {

    private val keyBaseUrl = stringPreferencesKey("base_url")

    val baseUrlFlow: Flow<String> = context.dataStore.data.map { prefs ->
        val raw = prefs[keyBaseUrl] ?: BuildConfig.DEFAULT_BASE_URL
        normalizeBaseUrl(migrateLegacyBaseUrl(raw))
    }

    suspend fun setBaseUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[keyBaseUrl] = normalizeBaseUrl(url)
        }
    }

    companion object {
        /** 旧 IP / 旧带 /api/ 的默认地址 → 新默认根路径 */
        fun migrateLegacyBaseUrl(raw: String): String {
            val key = raw.trim().trimEnd('/')
            return when (key) {
                "http://120.27.148.45/api",
                "http://120.27.148.45/api/",
                "https://120.27.148.45/api",
                "https://120.27.148.45/api/",
                "http://120.27.148.45",
                "https://api.alucard.top/api",
                "https://api.alucard.top/api/",
                "http://api.alucard.top/api",
                "http://api.alucard.top/api/" -> BuildConfig.DEFAULT_BASE_URL
                else -> raw
            }
        }

        fun normalizeBaseUrl(raw: String): String {
            var u = raw.trim()
            if (u.isEmpty()) u = BuildConfig.DEFAULT_BASE_URL
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                u = "https://$u"
            }
            if (!u.endsWith("/")) u = "$u/"
            return u
        }
    }
}
