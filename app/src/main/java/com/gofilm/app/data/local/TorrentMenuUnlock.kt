package com.gofilm.app.data.local

import android.content.Context
import java.io.File

/**
 * 种子菜单解锁状态：一次解锁，永久记住（直到清数据/卸载）。
 * SharedPreferences + filesDir 标记双写，避免仅 apply 丢失。
 */
object TorrentMenuUnlock {
    private const val PREF = "secret_features"
    private const val KEY = "torrent_menu_unlocked"
    private const val MARKER = ".torrent_menu_unlocked"

    @Volatile
    private var cached: Boolean? = null

    fun isUnlocked(context: Context): Boolean {
        cached?.let { return it }
        val app = context.applicationContext
        val fromPref = try {
            app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getBoolean(KEY, false)
        } catch (_: Throwable) {
            false
        }
        val fromFile = try {
            File(app.filesDir, MARKER).exists()
        } catch (_: Throwable) {
            false
        }
        val unlocked = fromPref || fromFile
        // 一侧有一侧无则补齐
        if (unlocked) {
            persist(app, true)
        }
        cached = unlocked
        return unlocked
    }

    fun unlock(context: Context) {
        persist(context.applicationContext, true)
        cached = true
    }

    fun lock(context: Context) {
        persist(context.applicationContext, false)
        cached = false
    }

    private fun persist(app: Context, unlocked: Boolean) {
        try {
            app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY, unlocked)
                .commit()
        } catch (_: Throwable) {
        }
        try {
            val marker = File(app.filesDir, MARKER)
            if (unlocked) {
                if (!marker.exists()) {
                    marker.writeText("1")
                }
            } else {
                marker.delete()
            }
        } catch (_: Throwable) {
        }
    }
}
