package com.gofilm.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.playabilityStore by preferencesDataStore(name = "gofilm_playability")

/**
 * 本地缓存「可播 / 失效」影片 id，避免列表反复探测。
 * 仅记录明确结论：dead / ok。
 */
class PlayabilityCache(private val context: Context) {

    private val keyDead = stringSetPreferencesKey("dead_mids")
    private val keyOk = stringSetPreferencesKey("ok_mids")

    val deadMidsFlow: Flow<Set<Long>> = context.playabilityStore.data.map { prefs ->
        prefs[keyDead].orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
    }

    val okMidsFlow: Flow<Set<Long>> = context.playabilityStore.data.map { prefs ->
        prefs[keyOk].orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
    }

    suspend fun markDead(mid: Long) {
        if (mid <= 0L) return
        context.playabilityStore.edit { prefs ->
            val dead = prefs[keyDead].orEmpty().toMutableSet()
            val ok = prefs[keyOk].orEmpty().toMutableSet()
            dead.add(mid.toString())
            ok.remove(mid.toString())
            prefs[keyDead] = dead
            prefs[keyOk] = ok
        }
    }

    suspend fun markOk(mid: Long) {
        if (mid <= 0L) return
        context.playabilityStore.edit { prefs ->
            val dead = prefs[keyDead].orEmpty().toMutableSet()
            val ok = prefs[keyOk].orEmpty().toMutableSet()
            ok.add(mid.toString())
            dead.remove(mid.toString())
            prefs[keyDead] = dead
            prefs[keyOk] = ok
        }
    }

    suspend fun clearAll() {
        context.playabilityStore.edit { prefs ->
            prefs[keyDead] = emptySet()
            prefs[keyOk] = emptySet()
        }
    }
}
