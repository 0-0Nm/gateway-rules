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

        /** 状态监听（照 STRX 的 StateListener） */
        interface StateListener {
            fun onStateChanged(running: Boolean, host: String, port: Int)
        }

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

    private var wakeLock: PowerManager.WakeLock? = null

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

        val host = intent?.getStringExtra("host") ?: "127.0.0.1"
        val port = intent?.getIntExtra("port", 38081) ?: 38081

        createChannel()
        startForeground(NOTIF_ID, buildNotif("MCP @ $host:$port"))
        acquireWakeLock()

        // 反复触发也先停旧再建，避免端口占用
        current?.stop()
        current = null

        val srv = McpServer(port, 38080) { McpBridge(this) }
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
        }
        return START_STICKY
    }

    override fun onDestroy() {
        GateLog.info("mcp", "service onDestroy")
        current?.stop()
        current = null
        notifyState(false, "", 0)
        releaseWakeLock()
        super.onDestroy()
    }

    // ---------------- 通知 ----------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "MCP Service",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { description = "本地 MCP 服务常驻通知" }
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
            .setContentTitle("GatewayRules MCP")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
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
                PowerManager.PARTIAL_WAKE_LOCK, "GatewayRules::McpWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(24L * 60 * 60 * 1000)
            }
            GateLog.info("mcp", "wakelock acquired")
        } catch (e: Exception) {
            GateLog.warn("mcp", "wakelock failed: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wakeLock = null
    }
}
