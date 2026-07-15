package com.gofilm.app.data.torrent

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * BT 下载网络辅助：
 * - 绑定到「非 VPN」网络，避免 Clash/Meta 吞掉 P2P
 * - 检测代理 App / 系统 VPN
 */
object TorrentNetwork {
    private const val TAG = "TorrentNetwork"

    private val PROXY_PACKAGES = listOf(
        "com.github.metacubex.clash.meta",
        "com.github.kr.clash",
        "com.github.metacubex.clash",
        "com.github.kr.clash.meta",
        "com.v2ray.ang",
        "com.v2ray.ang.fun",
        "io.nekohasekai.sfa",
        "com.github.shadowsocks",
        "com.xray.app",
        "org.outline.android.client"
    )

    @Volatile
    private var boundNetwork: Network? = null

    @Volatile
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun isBound(): Boolean = boundNetwork != null

    fun isVpnActive(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } catch (_: Throwable) {
            false
        }
    }

    /** 用户可读的代理提示，无则 null */
    fun proxyHint(context: Context): String? {
        val running = detectProxyApps(context)
        val vpn = isVpnActive(context)
        if (running.isEmpty() && !vpn) return null
        val names = running.toMutableList()
        if (vpn && "系统 VPN" !in names) names += "系统 VPN"
        return "检测到 ${names.joinToString("、")}：已找到同伴但可能连不上。" +
            "请彻底关闭 Clash（关掉 VPN 开关），或把本 App 设为「直连/绕过」。"
    }

    fun detectProxyApps(context: Context): List<String> {
        val pm = context.packageManager
        val found = mutableListOf<String>()
        for (pkg in PROXY_PACKAGES) {
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0)
                }
                val label = when {
                    pkg.contains("clash", ignoreCase = true) -> "Clash"
                    pkg.contains("v2ray", ignoreCase = true) -> "v2rayNG"
                    pkg.contains("sfa", ignoreCase = true) -> "sing-box"
                    pkg.contains("shadowsocks", ignoreCase = true) -> "Shadowsocks"
                    pkg.contains("outline", ignoreCase = true) -> "Outline"
                    else -> pkg.substringAfterLast('.')
                }
                if (label !in found) found += label
            } catch (_: PackageManager.NameNotFoundException) {
            } catch (_: Throwable) {
            }
        }
        return found
    }

    /**
     * 请求非 VPN 网络并 bind 进程；最多阻塞 [waitMs] 等待绑定成功。
     * @return 是否成功绑定到非 VPN 网络
     */
    fun bindDirectNetwork(context: Context, waitMs: Long = 2500L): Boolean {
        return try {
            val app = context.applicationContext
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            val existing = findDirectNetwork(cm)
            if (existing != null) {
                cm.bindProcessToNetwork(existing)
                boundNetwork = existing
                Log.i(TAG, "bound to existing non-VPN network $existing")
                return true
            }

            val result = AtomicReference<Network?>(null)
            val latch = CountDownLatch(1)
            // 取消旧回调
            callback?.let {
                try {
                    cm.unregisterNetworkCallback(it)
                } catch (_: Throwable) {
                }
            }
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    try {
                        cm.bindProcessToNetwork(network)
                        boundNetwork = network
                        result.set(network)
                        Log.i(TAG, "bound process to non-VPN $network")
                    } catch (t: Throwable) {
                        Log.w(TAG, "bindProcessToNetwork: ${t.message}")
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onUnavailable() {
                    latch.countDown()
                }

                override fun onLost(network: Network) {
                    if (boundNetwork == network) {
                        try {
                            cm.bindProcessToNetwork(null)
                        } catch (_: Throwable) {
                        }
                        boundNetwork = null
                    }
                }
            }
            callback = cb
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            cm.requestNetwork(req, cb)
            latch.await(waitMs, TimeUnit.MILLISECONDS)
            result.get() != null || boundNetwork != null
        } catch (t: Throwable) {
            Log.w(TAG, "bindDirectNetwork: ${t.message}")
            false
        }
    }

    private fun findDirectNetwork(cm: ConnectivityManager): Network? {
        return try {
            @Suppress("DEPRECATION")
            cm.allNetworks.firstOrNull { net ->
                val caps = cm.getNetworkCapabilities(net) ?: return@firstOrNull false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    (
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                        )
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun unbind(context: Context) {
        try {
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            callback?.let {
                try {
                    cm.unregisterNetworkCallback(it)
                } catch (_: Throwable) {
                }
            }
            callback = null
            cm.bindProcessToNetwork(null)
            boundNetwork = null
        } catch (_: Throwable) {
        }
    }
}
