package com.aqi.ccb.gateway

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.aqi.ccb.data.McpBridge

/**
 * MCP 前台服务 —— 移植 STRX 的 McpService 范式。
 *
 * 为什么必须前台服务（STRX 的关键设计）：
 *  普通线程随 Activity 失焦/被回收而终止，MCP 一死客户端就"连不上"。
 *  改成 Service + startForeground + WakeLock 后：
 *   - 系统不因内存压力回收
 *   - 通知栏常驻，用户可见
 *   - WakeLock 防 CPU 休眠断连
 *
 * 状态回调（照抄 STRX 的 StateListener）：
 *  UI 订阅 onStateChanged(running, host, port)，显示的是**实际监听值**，
 *  不再依赖草稿字符串，避免"UI 显示 38081 但实际跑在 3866"的错位。
 */
class McpService : Service() {

    /**
     * 状态监听（照 STRX 的 StateListener）。
     * 放在类体而非 companion：外部写作 McpService.StateListener 才能正确解析。
     */
    interface StateListener {
        fun onStateChanged(running: Boolean, host: String, port: Int)
    }

    companion object {
        private const val CHANNEL_ID = "mcp_service"
        private const val NOTIF_ID = 1001
        const val ACTION_START = "com.aqi.ccb.mcp.START"
        const val ACTION_STOP = "com.aqi.ccb.mcp.STOP"

        /** 实际运行中的 server（全局单例，供 Activity/diag 读取） */
        @Volatile
        var current: McpServer? = null
            private set

        @Volatile
        var running: Boolean = false
            private set

        private val listeners = java.util.concurrent.CopyOnWriteArrayList<StateListener>()

        private var lastHost = ""
        private var lastPort = 0

        fun addListener(l: StateListener) {
            if (!listeners.contains(l)) {
                listeners.add(l)
                // 注册即回调当前状态，避免界面初始不同步
                l.onStateChanged(running, lastHost, lastPort)
            }
        }

        fun removeListener(l: StateListener) {
            listeners.remove(l)
        }

        private fun notifyState(r: Boolean, host: String, port: Int) {
            running = r
            lastHost = if (r) host else ""
            lastPort = if (r) port else 0
            listeners.forEach {
                try {
                    it.onStateChanged(r, lastHost, lastPort)
                } catch (e: Exception) {
                    GateLog.error("mcp", "listener error", e)
                }
            }
        }

        /** 启动（照 STRX：先彻底清理旧实例，避免 bind 冲突） */
        fun start(ctx: Context, host: String, port: Int) {
            GateLog.info("mcp", "service start requested host=$host port=$port")
            if (current != null) {
                GateLog.info("mcp", "cleanup old instance before start")
                try { current?.stop() } catch (_: Exception) {}
                current = null
            }
            val i = Intent(ctx, McpService::class.java).apply {
                action = ACTION_START
                putExtra("host", host)
                putExtra("port", port)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, McpService::class.java).apply { action = ACTION_STOP }
            ctx.startService(i)
        }
    }

    // 物理锁已移到 WakeLockHolder 共享，这里不再持有（避免双倍耗电）

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            GateLog.info("mcp", "service stop requested")
            current?.stop()
            current = null
            notifyState(false, "", 0)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!com.aqi.ccb.data.AppSettings.mcpEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        // The control plane cannot opt into LAN binding through an Intent extra.
        val host = "127.0.0.1"
        val configuredPort = com.aqi.ccb.data.AppSettings.mcpPort(this)
        val port = intent?.getIntExtra("port", configuredPort) ?: configuredPort
        if (port !in 1024..65535) { stopSelf(); return START_NOT_STICKY }

        createChannel()
        startForeground(NOTIF_ID, buildNotif("MCP @ $host:$port"))
        acquireWakeLock()

        // 反复触发也先停旧再建，避免端口占用
        current?.stop()
        current = null

        val srv = McpServer(port, com.aqi.ccb.data.AppSettings.gatewayPort(this),
            LocalAccessTokens.mcp(this)) { McpBridge(applicationContext) }
        val ok = try {
            srv.start()
        } catch (e: Exception) {
            GateLog.error("mcp", "service bind failed port=$port", e)
            false
        }
        if (ok) {
            current = srv
            notifyState(true, host, port)
            GateLog.info("mcp", "service running on $host:$port (foreground)")
        } else {
            notifyState(false, "", 0)
            GateLog.error("mcp", "service failed to bind $port")
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // 悬浮球自愈：进程被杀后系统以 START_STICKY 重启本服务，
        // 但 Activity 不会自动重建，球若只在 Activity 里挂就永远回不来。
        // 这里由服务接管挂载（ensureAttached 幂等，已挂则不重复操作）。
        runCatching {
            // 绑定真值提供者：进程重启后可能没有 Activity，
            // 必须由服务自己绑定，否则球会退回缓存的旧状态（显示过期 MA/MD）。
            FloatingBallState.bind()
            if (FloatingBall.ensureAttached(this)) {
                FloatingBall.refreshFromProviders()
            }
            // 看门狗：周期核对窗口是否真的在屏幕上，并刷新状态文字
            FloatingBall.startWatchdog(this, 30_000L, owner = "mcp")
        }.onFailure { GateLog.warn("float", "service ensureAttached failed: ${it.message}") }

        return START_STICKY
    }

    override fun onDestroy() {
        GateLog.info("mcp", "service onDestroy")
        current?.stop()
        current = null
        notifyState(false, "", 0)
        releaseWakeLock()
        // 释放看门狗持有权：只移除自己那份，
        // 若 GatewayService 还在跑，看门狗继续为其工作。
        runCatching { FloatingBall.stopWatchdog("mcp") }
        super.onDestroy()
    }

    // ---------------- 通知 ----------------

    // 渠道与通知构建已抽到 NotificationHelper（原与 GatewayService 逐行重复，
    // 仅渠道名/标题/图标三个参数不同）
    private fun createChannel() {
        NotificationHelper.ensureChannel(
            this, CHANNEL_ID, "MCP Service", "本地 MCP 服务常驻通知"
        )
    }

    private fun buildNotif(text: String): Notification =
        NotificationHelper.build(
            this, CHANNEL_ID, "GatewayRules MCP", text,
            android.R.drawable.stat_sys_download_done,
        )

    // ---------------- WakeLock ----------------

    // 改用共享引用计数锁：与 GatewayService 共用一把物理锁，
    // 避免两个前台服务各持一把 24 小时 PARTIAL_WAKE_LOCK 造成双倍耗电。
    private fun acquireWakeLock() {
        WakeLockHolder.acquire(this, "mcp")
    }

    private fun releaseWakeLock() {
        WakeLockHolder.release("mcp")
    }
}
