package com.gofilm.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gofilm.app.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "gofilm_settings")

class SettingsStore(private val context: Context) {

    private val keyBaseUrl = stringPreferencesKey("base_url")
    /** 跳过片头秒数，默认 120（2 分钟） */
    private val keySkipHeadSec = intPreferencesKey("skip_head_sec")
    /** 跳过片尾秒数，默认 120（2 分钟） */
    private val keySkipTailSec = intPreferencesKey("skip_tail_sec")
    /** 默认倍速，默认 1.0 */
    private val keyPlaybackSpeed = floatPreferencesKey("playback_speed")

    val baseUrlFlow: Flow<String> = context.dataStore.data.map { prefs ->
        val raw = prefs[keyBaseUrl] ?: BuildConfig.DEFAULT_BASE_URL
        normalizeBaseUrl(migrateLegacyBaseUrl(raw))
    }

    val skipHeadSecFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[keySkipHeadSec] ?: DEFAULT_SKIP_SEC).coerceIn(0, MAX_SKIP_SEC)
    }

    val skipTailSecFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[keySkipTailSec] ?: DEFAULT_SKIP_SEC).coerceIn(0, MAX_SKIP_SEC)
    }

    val playbackSpeedFlow: Flow<Float> = context.dataStore.data.map { prefs ->
        (prefs[keyPlaybackSpeed] ?: 1f).coerceIn(0.5f, 3f)
    }

    suspend fun setBaseUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[keyBaseUrl] = normalizeBaseUrl(url)
        }
    }

    suspend fun setSkipHeadSec(sec: Int) {
        context.dataStore.edit { prefs ->
            prefs[keySkipHeadSec] = sec.coerceIn(0, MAX_SKIP_SEC)
        }
    }

    suspend fun setSkipTailSec(sec: Int) {
        context.dataStore.edit { prefs ->
            prefs[keySkipTailSec] = sec.coerceIn(0, MAX_SKIP_SEC)
        }
    }

    suspend fun setPlaybackSpeed(speed: Float) {
        context.dataStore.edit { prefs ->
            prefs[keyPlaybackSpeed] = speed.coerceIn(0.5f, 3f)
        }
    }

    companion object {
        const val DEFAULT_SKIP_SEC = 120
        const val MAX_SKIP_SEC = 600

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
