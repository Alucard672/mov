package com.gofilm.app.data.repo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.gofilm.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.Proxy
import java.util.concurrent.TimeUnit

@Serializable
data class AppUpdateInfo(
    val versionCode: Int = 0,
    val versionName: String = "",
    /** 下载地址（兼容字段） */
    val downloadUrl: String = "",
    /** 下载地址（当前 OTA 使用） */
    val apkUrl: String = "",
    /** 强制更新（兼容字段） */
    val force: Boolean = false,
    /** 强制更新（当前 OTA 使用） */
    val forceUpdate: Boolean = false,
    val changelog: String = ""
) {
    val resolvedDownloadUrl: String
        get() = downloadUrl.ifBlank { apkUrl }.trim()

    val isForce: Boolean
        get() = force || forceUpdate
}

sealed class UpdateCheckResult {
    data object AlreadyLatest : UpdateCheckResult()
    data class Available(val info: AppUpdateInfo) : UpdateCheckResult()
    data class Failed(val message: String) : UpdateCheckResult()
}

object AppUpdateChecker {

    /** 版本清单地址（与 API 同机 Nginx 静态目录） */
    const val VERSION_URL: String = "http://120.27.148.45/app/version.json"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun check(): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(VERSION_URL)
                .header("User-Agent", "AlucardFilm/${BuildConfig.VERSION_NAME}")
                .header("Cache-Control", "no-cache")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext UpdateCheckResult.Failed("检查失败（${resp.code}）")
                }
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) {
                    return@withContext UpdateCheckResult.Failed("版本信息为空")
                }
                val info = json.decodeFromString(AppUpdateInfo.serializer(), body)
                if (info.versionCode <= 0 || info.resolvedDownloadUrl.isBlank()) {
                    return@withContext UpdateCheckResult.Failed("版本信息不完整")
                }
                if (info.versionCode > BuildConfig.VERSION_CODE) {
                    UpdateCheckResult.Available(info)
                } else {
                    UpdateCheckResult.AlreadyLatest
                }
            }
        } catch (e: Exception) {
            UpdateCheckResult.Failed(e.message?.take(80) ?: "网络错误")
        }
    }

    /**
     * 下载 APK 到 app 私有目录。
     * @param onProgress 0~100
     */
    suspend fun downloadApk(
        context: Context,
        info: AppUpdateInfo,
        onProgress: (Int) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val url = info.resolvedDownloadUrl
            if (url.isBlank()) error("下载地址为空")
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "AlucardFilm/${BuildConfig.VERSION_NAME}")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("下载失败（${resp.code}）")
                val body = resp.body ?: error("下载内容为空")
                val total = body.contentLength()
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val out = File(dir, "gofilm-${info.versionCode}.apk")
                if (out.exists()) out.delete()
                body.byteStream().use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(8 * 1024)
                        var readTotal = 0L
                        var n: Int
                        while (input.read(buf).also { n = it } >= 0) {
                            output.write(buf, 0, n)
                            readTotal += n
                            if (total > 0) {
                                onProgress(((readTotal * 100) / total).toInt().coerceIn(0, 100))
                            }
                        }
                        output.flush()
                    }
                }
                if (out.length() < 1000) error("APK 文件异常")
                onProgress(100)
                out
            }
        }
    }

    fun canRequestInstall(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallPermissionSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivity(intent)
        }
    }

    fun installApk(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
