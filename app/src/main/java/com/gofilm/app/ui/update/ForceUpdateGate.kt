package com.gofilm.app.ui.update

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.gofilm.app.data.repo.AppUpdateChecker
import com.gofilm.app.data.repo.AppUpdateInfo
import com.gofilm.app.data.repo.UpdateCheckResult
import com.gofilm.app.ui.theme.Accent
import com.gofilm.app.ui.theme.Bg
import com.gofilm.app.ui.theme.BgCard
import com.gofilm.app.ui.theme.TextMuted
import com.gofilm.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * 启动时检查更新：
 * - forceUpdate=true：全屏遮罩，不可关闭，必须更新
 * - 普通更新：可关闭的对话框
 */
@Composable
fun ForceUpdateGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    var pending by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var checking by remember { mutableStateOf(true) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    /** 用户关闭的「可选更新」，强制更新不会进这里 */
    var dismissedOptional by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        checking = true
        when (val r = AppUpdateChecker.check()) {
            is UpdateCheckResult.Available -> pending = r.info
            is UpdateCheckResult.AlreadyLatest -> pending = null
            is UpdateCheckResult.Failed -> {
                // 启动检查失败不挡进 App（强制更新也依赖网络；可在「我的」再检查）
                pending = null
                status = r.message
            }
        }
        checking = false
    }

    fun startDownload(info: AppUpdateInfo) {
        if (downloading) return
        scope.launch {
            downloading = true
            progress = 0
            status = "正在下载 v${info.versionName}…"
            val result = AppUpdateChecker.downloadApk(context, info) { p -> progress = p }
            downloading = false
            result
                .onSuccess { file ->
                    if (!AppUpdateChecker.canRequestInstall(context)) {
                        status = "请先允许「安装未知应用」"
                        activity?.let { AppUpdateChecker.openInstallPermissionSettings(it) }
                        return@onSuccess
                    }
                    status = "下载完成，请安装更新"
                    AppUpdateChecker.installApk(context, file)
                }
                .onFailure {
                    status = "下载失败：${it.message ?: "未知错误"}"
                }
        }
    }

    val forceInfo = pending?.takeIf { it.isForce }
    val optionalInfo = pending?.takeIf { !it.isForce && !dismissedOptional }

    // 强制更新：拦截返回键，禁止关掉遮罩
    if (forceInfo != null) {
        BackHandler(enabled = true) { /* 禁止返回退出 */ }
    }

    Box(Modifier.fillMaxSize()) {
        // 强制更新时仍渲染底层（避免白屏），但全屏挡住交互
        content()

        if (forceInfo != null) {
            ForceUpdateFullscreen(
                info = forceInfo,
                downloading = downloading,
                progress = progress,
                status = status,
                onUpdate = { startDownload(forceInfo) }
            )
        } else if (optionalInfo != null) {
            AlertDialog(
                onDismissRequest = {
                    if (!downloading) dismissedOptional = true
                },
                properties = DialogProperties(
                    dismissOnBackPress = !downloading,
                    dismissOnClickOutside = !downloading
                ),
                title = {
                    Text("发现新版本 v${optionalInfo.versionName}")
                },
                text = {
                    Column {
                        Text(
                            optionalInfo.changelog.ifBlank { "建议更新到最新版以获得更好体验。" },
                            fontSize = 13.sp,
                            color = TextSecondary
                        )
                        if (downloading) {
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = { progress / 100f },
                                modifier = Modifier.fillMaxWidth(),
                                color = Accent
                            )
                            Text(
                                "下载中 $progress%",
                                fontSize = 12.sp,
                                color = TextMuted,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                        status?.let {
                            Text(
                                it,
                                fontSize = 12.sp,
                                color = TextMuted,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { startDownload(optionalInfo) },
                        enabled = !downloading
                    ) {
                        Text(if (downloading) "下载中…" else "立即更新", color = Accent)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { dismissedOptional = true },
                        enabled = !downloading
                    ) {
                        Text("稍后", color = TextMuted)
                    }
                }
            )
        }
    }
}

@Composable
private fun ForceUpdateFullscreen(
    info: AppUpdateInfo,
    downloading: Boolean,
    progress: Int,
    status: String?,
    onUpdate: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.88f))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(BgCard, RoundedCornerShape(18.dp))
                .padding(22.dp)
        ) {
            Text(
                "必须更新",
                color = Accent,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "发现新版本 v${info.versionName}",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
            Spacer(Modifier.height(10.dp))
            Text(
                info.changelog.ifBlank {
                    "当前版本过旧，请更新后继续使用。"
                },
                color = TextSecondary,
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "不更新将无法继续使用本应用。",
                color = Color(0xFFFF8A80),
                fontSize = 12.sp
            )

            if (downloading) {
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = Accent,
                    trackColor = Bg
                )
                Text(
                    "下载中 $progress%",
                    fontSize = 12.sp,
                    color = TextMuted,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            status?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, fontSize = 12.sp, color = TextMuted)
            }

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onUpdate,
                enabled = !downloading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Color(0xFF1A1000)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    if (downloading) "下载中…" else "立即更新并安装",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
