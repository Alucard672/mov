package com.gofilm.app.data.stats

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.gofilm.app.BuildConfig
import com.gofilm.app.data.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.Proxy
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 轻量用户统计：启动/前台上报一次 open。
 * 服务端短路径：POST /e（Nginx 反代 stats）
 */
object UserStatsReporter {
    private const val TAG = "UserStats"
    private const val PREFS = "user_stats"
    private const val KEY_DEVICE = "device_id"
    private const val KEY_LAST_OPEN_DAY = "last_open_day"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reportedThisProcess = AtomicBoolean(false)

    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun deviceId(context: Context): String {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cached = sp.getString(KEY_DEVICE, null)
        if (!cached.isNullOrBlank()) return cached
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Throwable) {
            null
        }.orEmpty()
        val raw = (androidId.ifBlank { "rand-${System.nanoTime()}" }) + "|gofilm"
        val id = sha1(raw).take(32)
        sp.edit().putString(KEY_DEVICE, id).apply()
        return id
    }

    /** App 冷启动上报（每个进程最多一次） */
    fun reportOpen(context: Context) {
        if (!reportedThisProcess.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch {
            try {
                postEvent(app, event = "open")
            } catch (t: Throwable) {
                Log.w(TAG, "reportOpen: ${t.message}")
            }
        }
    }

    /** 每天首次回到前台再补一次 active（可选） */
    fun reportDailyActive(context: Context) {
        val app = context.applicationContext
        scope.launch {
            try {
                val sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(java.util.Date())
                if (sp.getString(KEY_LAST_OPEN_DAY, null) == today) return@launch
                postEvent(app, event = "active")
                sp.edit().putString(KEY_LAST_OPEN_DAY, today).apply()
            } catch (t: Throwable) {
                Log.w(TAG, "reportDailyActive: ${t.message}")
            }
        }
    }

    private fun postEvent(context: Context, event: String) {
        val body = JSONObject()
            .put("deviceId", deviceId(context))
            .put("event", event)
            .put("versionName", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("channel", "android")
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(64))
            .put("sdk", Build.VERSION.SDK_INT.toString())
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val req = Request.Builder()
            .url(ServerConfig.statsEventUrl())
            .post(body)
            .header("User-Agent", "AlucardFilm/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "event http ${resp.code}")
            } else {
                Log.i(TAG, "event ok $event")
            }
        }
    }

    private fun sha1(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
