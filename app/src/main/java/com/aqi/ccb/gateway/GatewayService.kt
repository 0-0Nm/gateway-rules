package com.aqi.ccb.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * 网关前台服务 —— 移植 McpService 范式。
 *
 * 为什么必须有这个（有实测依据）：
 * 之前网关线程绑在 MainActivity 上，Activity 一 onDestroy 网关就停。
 * 实测证据（P250）：
 *   进程 pid=25751 存活、MCP 38081 LISTEN 正常、
 *   但网关 38080 已消失（health=000）、悬浮球窗口也没了。
 * 结论：悬浮窗保的是「进程优先级」，不是「网关存活」。
 * 同一进程里前台服务（MCP）活着，Activity 附属物全死。
 *
 * 本服务托管 GatewayServer：
 *  - startForeground + 通知栏常驻，系统承诺不回收
 *  - WakeLock 防息屏断连
 *  - StateListener 把「实际监听端口」回调给 UI，避免显示草稿值
 */
class GatewayService : Service() {

    /**
     * 网关状态回调。
     * 注意：必须声明在类层级而非 companion 内——
     * 放在 companion 里时外部写成 GatewayService.StateListener 会解析失败，
     * 编译器要求 GatewayService.Companion.StateListener，调用方很别扭。
     */
    interface StateListener {
        fun onGatewayState(running: Boolean, port: Int, error: String?)
    }

    companion object {
        private const val CHANNEL_ID = "gateway_service"
        private const val NOTIF_ID = 1002
        const val ACTION_START = "com.aqi.ccb.gw.START"
        const val ACTION_STOP = "com.aqi.ccb.gw.STOP"

        /** 当前运行中的网关实例（全局单例） */
        @Volatile
        var current: GatewayServer? = null
            private set

        @Volatile
        var running: Boolean = false
            private set

        /**
         * 日志目录的「预设值」。
         * Activity 在 onCreate 时写入，服务启动时读取填充 diag.logDir。
         * 加这个是因为 onStartCommand 可能先于 Activity 完成初始化，
         * 直接调 current?.setLogDir() 时 current 还是 null。
         */
        @Volatile
        var pendingLogDir: String = ""
            private set

        fun setPendingLogDir(path: String) {
            pendingLogDir = path
            current?.setLogDir(path)
        }

        private val listeners = java.util.concurrent.CopyOnWriteArrayList<StateListener>()
        private var lastPort = 0
        private var lastError: String? = null

        fun addListener(l: StateListener) {
            if (!listeners.contains(l)) {
                listeners.add(l)
                l.onGatewayState(running, lastPort, lastError)
            }
        }

        fun removeListener(l: StateListener) {
            listeners.remove(l)
        }

        private fun notifyState(r: Boolean, port: Int, err: String?) {
            running = r
            lastPort = port
            lastError = err
            listeners.forEach {
                try { it.onGatewayState(r, port, err) }
                catch (e: Exception) { GateLog.error("gw", "listener error", e) }
            }
        }

        /**
         * 启动网关服务。
         * 配置从 ConfigStore / ProviderRepo 重新读取，保证与 UI 一致。
         */
        fun start(ctx: Context, port: Int) {
            GateLog.info("gw", "service start requested port=" + port)
            if (current != null) {
                try { current?.stop() } catch (_: Exception) {}
                current = null
            }
            val i = Intent(ctx, GatewayService::class.java).apply {
                action = ACTION_START
                putExtra("port", port)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, GatewayService::class.java).apply { action = ACTION_STOP }
            ctx.startService(i)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var lastLogDir: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            GateLog.info("gw", "service stop requested")
            current?.stop()
            current = null
            notifyState(false, 0, null)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val port = intent?.getIntExtra("port", 38080) ?: 38080

        createChannel()
        startForeground(NOTIF_ID, buildNotif("网关 @ 127.0.0.1:$port"))
        acquireWakeLock()

        // 反复触发也先停旧再建，避免端口占用
        current?.stop()
        current = null

        val providers = com.aqi.ccb.data.ProviderRepo.runtimeProviders(this)
        val rules = com.aqi.ccb.data.ProviderRepo.runtimeRules(this)
        val vmodels = VirtualModelStore.load(this)

        // 限流策略按 provider 各自配置组装
        val policies = HashMap<String, RatePolicy>()
        providers.forEach { policies[it.id] = it.ratePolicy }
        val limiter = RateLimiter(policies, RatePolicy())

        val srv = GatewayServer(port, providers, rules, limiter, "", vmodels)
        // 优先用 Activity 预设值，其次用本服务缓存
        srv.logDirPath = if (pendingLogDir.isNotEmpty()) pendingLogDir else lastLogDir
        srv.configDirPath = filesDir.absolutePath

        val ok = try {
            srv.start()
        } catch (e: Exception) {
            GateLog.error("gw", "service bind failed port=$port", e)
            false
        }

        if (ok) {
            current = srv
            notifyState(true, srv.port, null)
            GateLog.info("gw", "service running on 0.0.0.0:" + srv.port + " (foreground)")
        } else {
            notifyState(false, 0, srv.lastError)
            GateLog.error("gw", "service failed to bind " + port)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        GateLog.info("gw", "service onDestroy")
        current?.stop()
        current = null
        notifyState(false, 0, lastError)
        releaseWakeLock()
        super.onDestroy()
    }

    /** 由 Activity 在初始化时注入日志目录，供 diag 暴露 */
    fun setLogDir(path: String) {
        lastLogDir = path
        current?.logDirPath = path
    }

    // ---------------- 通知 ----------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "Gateway Service",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { description = "本地网关常驻通知" }
                )
            }
        }
    }

    private fun buildNotif(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, Class.forName("com.aqi.ccb.MainActivity")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return builder
            .setContentTitle("GatewayRules 网关")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    // ---------------- WakeLock ----------------

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "GatewayRules::GwWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(24L * 60 * 60 * 1000)
            }
            GateLog.info("gw", "wakelock acquired")
        } catch (e: Exception) {
            GateLog.warn("gw", "wakelock failed: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wakeLock = null
    }
}
