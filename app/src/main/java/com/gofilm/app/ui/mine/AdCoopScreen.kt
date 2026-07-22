package com.gofilm.app.ui.mine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gofilm.app.ui.theme.Accent
import com.gofilm.app.ui.theme.Bg
import com.gofilm.app.ui.theme.BgCard
import com.gofilm.app.ui.theme.TextMuted
import com.gofilm.app.ui.theme.TextSecondary

/** 下载站 / 对外合作入口（BuildConfig 统一域名） */
private val DOWNLOAD_URL = com.gofilm.app.BuildConfig.DOWNLOAD_URL
private val ADS_PAGE_URL = com.gofilm.app.BuildConfig.ADS_URL
private const val CONTACT_WECHAT_PHONE = "15989049527"

@Composable
fun AdCoopScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Accent
                )
            }
            Text(
                "广告合作说明",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Accent
            )
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            SectionCard(title = "关于媒体") {
                Body(
                    "Alucard影视是 Android 客户端 + 独立下载站，支持影视浏览、链接下载等功能。" +
                        "支持自建广告位（开屏 / 首页 Banner / 下载页），素材由广告主提供，我方配置上线。"
                )
            }
            Spacer(Modifier.height(12.dp))

            SectionCard(title = "可售广告位") {
                Body(
                    "1. App 开屏（建议 2～3 秒，可跳过）\n" +
                        "2. App 首页 Banner\n" +
                        "3. 下载站页面 Banner（$DOWNLOAD_URL）\n" +
                        "4. 打包：开屏 + 首页 + 下载页（更易出量）"
                )
            }
            Spacer(Modifier.height(12.dp))

            SectionCard(title = "合作方式") {
                Body(
                    "· 计费：以包月 CPT 为主，也可按天 / 按点击协商\n" +
                        "· 素材：静图或链接，尺寸与规范上线前沟通\n" +
                        "· 数据：可提供展示、点击等基础统计\n" +
                        "· 流程：确认位置与周期 → 提供素材 → 上线 → 结算"
                )
            }
            Spacer(Modifier.height(12.dp))

            SectionCard(title = "不接受的类目") {
                Body(
                    "赌博、色情、诈骗、仿冒、违规贷款、以及明显违法违规内容，一律不合作。"
                )
            }
            Spacer(Modifier.height(12.dp))

            SectionCard(title = "联系与入口") {
                Body(
                    "微信 / 手机：$CONTACT_WECHAT_PHONE\n\n" +
                        "下载 / 分发站：\n$DOWNLOAD_URL\n\n" +
                        "合作说明页：\n$ADS_PAGE_URL\n\n" +
                        "商务合作请直接加微信或来电，也可复制本页信息发给对方。"
                )
            }
            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    copyText(
                        context,
                        "广告合作\n微信/手机：$CONTACT_WECHAT_PHONE\n下载站：$DOWNLOAD_URL\n说明页：$ADS_PAGE_URL"
                    )
                    Toast.makeText(context, "合作信息已复制", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("复制合作信息", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    copyText(context, CONTACT_WECHAT_PHONE)
                    Toast.makeText(context, "已复制微信/手机号", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("复制微信/手机号", color = Accent)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { openUrl(context, DOWNLOAD_URL) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("打开下载站", color = Accent)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { openUrl(context, ADS_PAGE_URL) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("打开合作说明网页", color = Accent)
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "说明：当前为自建广告合作说明，非第三方广告联盟。",
                color = TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(BgCard)
            .padding(16.dp)
    ) {
        Text(title, color = Accent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun Body(text: String) {
    Text(text, color = TextSecondary, fontSize = 13.sp, lineHeight = 20.sp)
}

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("ad_coop", text))
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Throwable) {
        Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
    }
}
