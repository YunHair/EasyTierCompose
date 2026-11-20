package cc.ptoe.easytier.compose.transport.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import cc.ptoe.easytier.compose.MainActivity
import cc.ptoe.easytier.compose.R
import cc.ptoe.easytier.compose.core.EasyTierJni

class EasyTierVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    // 是否成功创建过接口。用于 onDestroy 判断要不要去注销残留的 VPN 配置。
    private var everEstablished = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (intent?.action == ACTION_PURGE) {
            // 兜底清理：上层检测到 VPN 接口在 stopService 之后仍然存在时下发。
            Log.w(TAG, "onStartCommand: purge requested")
            closeTunnel()
            // force=true：新起的 Service 实例没有 everEstablished 记录，不能因为
            // 这个标志为 false 就跳过注销（那正是残留接口没被清掉的原因）。
            releaseVpnConfig(force = true)
            stopSelf(startId)
            return Service.START_NOT_STICKY
        }
        val command = intent ?: return stopAndExit(startId)
        val instanceName = command.getStringExtra(EXTRA_INSTANCE_NAME) ?: return stopAndExit(startId, "missing instance name")
        val ipv4Cidr = command.getStringExtra(EXTRA_IPV4_CIDR) ?: return stopAndExit(startId, "missing ipv4 cidr")
        val routes = command.getStringArrayExtra(EXTRA_ROUTES)?.map { it }.orEmpty().toTypedArray()
        val dns = command.getBooleanExtra(EXTRA_DNS, false)
        val mtu = command.getIntExtra(EXTRA_MTU, 1380)
        val disableIpv6 = command.getBooleanExtra(EXTRA_DISABLE_IPV6, false)
        Log.i(TAG, "onStartCommand: instance=$instanceName cidr=$ipv4Cidr routes=${routes.size} dns=$dns mtu=$mtu disableIpv6=$disableIpv6")
        vpnInterface?.close()
        vpnInterface = try {
            createInterface(ipv4Cidr, routes, dns, mtu, disableIpv6)
        } catch (e: Exception) {
            Log.e(TAG, "createInterface failed: ${e.message}", e)
            return stopAndExit(startId, "createInterface: ${e.message}")
        }
        everEstablished = true
        val result = EasyTierJni.setTunFd(instanceName, requireNotNull(vpnInterface).fd)
        if (result != 0) {
            Log.e(TAG, "setTunFd failed: result=$result")
            vpnInterface?.close()
            vpnInterface = null
            return stopAndExit(startId, "setTunFd=$result")
        }
        Log.i(TAG, "onStartCommand: success, fd=${vpnInterface?.fd}")
        return Service.START_NOT_STICKY
    }

    private fun stopAndExit(startId: Int, reason: String = "unknown"): Int {
        Log.w(TAG, "stopAndExit: $reason")
        stopSelf(startId)
        return Service.START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
    }

    override fun onRevoke() {
        Log.w(TAG, "onRevoke: VPN permission revoked by system/user")
        closeTunnel()
        releaseVpnConfig()
        super.onRevoke()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy: tearing down tunnel (everEstablished=$everEstablished)")
        closeTunnel()
        // 刻意不在这里 retainNetworkInstance(null)：热重载（切换出口节点 =
        // coordinator.stop() + start()）时，旧 Service 的 onDestroy 可能晚于
        // 新会话的 runNetworkInstance 执行，会把刚起来的实例误杀。
        // 停止实例的职责只在 EasyTierRuntimeCoordinator.stopLocked()，
        // 这里只负责把 VPN 配置从系统上注销。
        releaseVpnConfig(force = everEstablished)
        super.onDestroy()
    }

    /**
     * 强制让 system_server 注销本条 VPN 配置（默认路由 + DNS 覆盖）。
     *
     * 为什么需要这一步：`Builder.establish()` 返回的 fd 会通过
     * `EasyTierJni.setTunFd()` 交给原生 EasyTier。原生侧对 fd 另有引用（dup 语义），
     * 因此仅关闭 Java 层的 [ParcelFileDescriptor] 时，内核里的 tun 设备可能仍然存在，
     * VPN 网络配置（0.0.0.0/0 默认路由、DNS 覆盖）就可能继续生效 —— 表现为
     * “断开连接后整机断网、只有杀掉 App 进程才恢复”。
     *
     * 这里重新 establish 一个「无路由、无 DNS」的最小接口：
     * frameworks 的 Vpn.establish() 会先 jniClose 并注销上一条 VPN 配置，然后才挂新接口。
     * 新接口不声明任何 route/dns，随即关闭，系统网络立即回到物理网卡。
     * 整个过程不依赖原生层是否释放了旧 fd。
     */
    private fun releaseVpnConfig(force: Boolean = false) {
        if (!everEstablished && !force) return
        everEstablished = false
        runCatching {
            Builder()
                .setSession(getString(R.string.vpn_session_name))
                .setMtu(1280)
                .addAddress(PURGE_PROBE_ADDRESS, 32)
                .establish()
        }.onSuccess { probe ->
            probe?.close()
            Log.i(TAG, "releaseVpnConfig: stale VPN configuration revoked")
        }.onFailure {
            Log.w(TAG, "releaseVpnConfig failed: ${it.message}")
        }
    }

    private fun createInterface(ipv4Cidr: String, routes: Array<String>, dns: Boolean, mtu: Int, disableIpv6: Boolean): ParcelFileDescriptor {
        val (address, prefix) = ipv4Cidr.parseCidr()
        val builder = Builder()
            .setSession(getString(R.string.vpn_session_name))
            .setBlocking(false)
            .setMtu(mtu)
            .addAddress(address, prefix)
            .addAddress("fd00::1", 128)
        // Add routes defensively: a single malformed/unexpected CIDR (e.g. bare IP
        // that slipped through normalization, or a prefix the device rejects) must
        // not abort the whole VPN establishment — that would leave the user with a
        // connected EasyTier but no TUN interface, manifesting as silent timeouts.
        var added = 0
        for (route in routes) {
            try {
                val (routeAddress, routePrefix) = route.parseCidr()
                builder.addRoute(routeAddress, routePrefix)
                added++
            } catch (e: Exception) {
                Log.w(TAG, "createInterface: skipping invalid route '$route': ${e.message}")
            }
        }
        // 出口节点模式下装的是 0.0.0.0/0 默认路由。此时若不开 Magic DNS，Android 会把底层
        // 网络的 DNS（多为 192.168.x.1 这类内网网关）当作 VPN 的解析器，出口节点侧根本回不去，
        // 表现为“隧道通了但网页打不开”。这里退回到会随隧道一起走的公共 DNS。
        val hasDefaultRoute = routes.any { it.trim() == DEFAULT_ROUTE_V4 }
        if (dns) {
            builder.addDnsServer(MAGIC_DNS)
        } else if (hasDefaultRoute) {
            builder.addDnsServer(FALLBACK_DNS)
        }
        // IPv6 接管策略：
        // 1) 配置里关闭「启用 IPv6」→ 整机禁用：把 ::/0 收进隧道并丢弃，
        //    对应用而言 IPv6 完全不可用（Android 不允许非 root 应用移除蜂窝
        //    网卡的 IPv6 地址，接管+黑洞是等效手段），所有流量强制走 IPv4。
        // 2) 出口节点默认路由（0.0.0.0/0）存在 → 同样接管 ::/0，防止原生
        //    IPv6 绕过隧道泄漏本机地址。EasyTier 内置 NAT 仅支持 IPv4，v6
        //    在隧道内被丢弃后应用回退 IPv4；出口节点配置了内核转发 +
        //    ip6tables MASQUERADE（proxy_forward_by_system）时 v6 才会真正
        //    从出口节点出去。
        val disableIpv6Effective = disableIpv6 || hasDefaultRoute
        if (disableIpv6Effective) {
            builder.addRoute(IPV6_DEFAULT_ROUTE, 0)
            Log.i(TAG, "createInterface: IPv6 captured into tunnel (::/0), disableIpv6=$disableIpv6 exitNodeMode=$hasDefaultRoute")
        }
        builder.addDisallowedApplication(packageName)
        Log.i(TAG, "createInterface: cidr=$ipv4Cidr mtu=$mtu routes=${routes.size} added=$added dns=$dns disableIpv6=$disableIpv6Effective")
        return builder.establish() ?: error(getString(R.string.error_vpn_establish_failed))
    }

    private fun closeTunnel() {
        val hadInterface = vpnInterface != null
        runCatching { vpnInterface?.close() }
            .onFailure { Log.w(TAG, "closeTunnel: close failed: ${it.message}") }
        vpnInterface = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        Log.i(TAG, "closeTunnel: hadInterface=$hadInterface")
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(getString(R.string.vpn_notification_title))
        .setContentText(getString(R.string.vpn_notification_text))
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.vpn_notification_channel), NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun String.parseCidr(): Pair<String, Int> {
        val values = split('/', limit = 2)
        require(values.size == 2) { "Invalid CIDR: $this" }
        return values[0] to values[1].toInt()
    }

    companion object {
        private const val TAG = "EasyTierVpnService"
        const val EXTRA_INSTANCE_NAME = "cc.ptoe.easytier.compose.INSTANCE_NAME"
        const val EXTRA_IPV4_CIDR = "cc.ptoe.easytier.compose.IPV4_CIDR"
        const val EXTRA_ROUTES = "cc.ptoe.easytier.compose.ROUTES"
        const val EXTRA_DNS = "cc.ptoe.easytier.compose.DNS"
        const val EXTRA_MTU = "cc.ptoe.easytier.compose.MTU"
        const val EXTRA_DISABLE_IPV6 = "cc.ptoe.easytier.compose.DISABLE_IPV6"
        private const val CHANNEL_ID = "easytier_vpn"
        private const val NOTIFICATION_ID = 1001
        private const val MAGIC_DNS = "100.100.100.101"
        private const val FALLBACK_DNS = "223.5.5.5"
        private const val DEFAULT_ROUTE_V4 = "0.0.0.0/0"
        private const val IPV6_DEFAULT_ROUTE = "::"
        // 注销 VPN 配置时用的探针地址（不声明任何路由/DNS，不会截走流量）。
        private const val PURGE_PROBE_ADDRESS = "10.255.255.254"
        const val ACTION_PURGE = "cc.ptoe.easytier.compose.PURGE"

        fun intent(context: Context, instanceName: String, ipv4Cidr: String, routes: List<String>, dns: Boolean, mtu: Int, disableIpv6: Boolean) =
            Intent(context, EasyTierVpnService::class.java)
                .putExtra(EXTRA_INSTANCE_NAME, instanceName)
                .putExtra(EXTRA_IPV4_CIDR, ipv4Cidr)
                .putExtra(EXTRA_ROUTES, routes.toTypedArray())
                .putExtra(EXTRA_DNS, dns)
                .putExtra(EXTRA_MTU, mtu)
                .putExtra(EXTRA_DISABLE_IPV6, disableIpv6)
    }
}
