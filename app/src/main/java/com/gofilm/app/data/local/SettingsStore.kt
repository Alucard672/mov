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
        normalizeBaseUrl(prefs[keyBaseUrl] ?: BuildConfig.DEFAULT_BASE_URL)
    }

    suspend fun setBaseUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[keyBaseUrl] = normalizeBaseUrl(url)
        }
    }

    companion object {
        fun normalizeBaseUrl(raw: String): String {
            var u = raw.trim()
            if (u.isEmpty()) u = BuildConfig.DEFAULT_BASE_URL
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                u = "http://$u"
            }
            if (!u.endsWith("/")) u = "$u/"
            return u
        }
    }
}
