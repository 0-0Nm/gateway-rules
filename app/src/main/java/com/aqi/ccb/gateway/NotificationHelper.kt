package com.aqi.ccb.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 前台服务通知的统一构建器。
 *
 * 为什么抽出来：
 *   McpService 和 GatewayService 各有一份 createChannel() 和 buildNotif()，
 *   逐行 diff 的结果是只差 2-3 个参数（渠道名、通知标题、小图标），
 *   其余 20 多行完全相同。这是**逻辑重复**，不是结构相似。
 *
 * 为什么用工具类而不是抽基类：
 *   两个 Service 职责不同（一个跑 MCP server，一个跑 Gateway server），
 *   只是"都要起前台通知"这一个横切关注点相同。
 *   为横切关注点引入继承会让两者耦合，反而更难维护。
 *   组合优于继承。
 */
object NotificationHelper {

    /** 幂等创建通知渠道（已存在则跳过） */
    fun ensureChannel(
        ctx: Context,
        channelId: String,
        name: String,
        description: String,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(channelId) != null) return
        nm.createNotificationChannel(
            NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_LOW)
                .apply { this.description = description }
        )
    }

    /**
     * 构建前台常驻通知。
     * 点击通知会拉起 MainActivity。
     */
    fun build(
        ctx: Context,
        channelId: String,
        title: String,
        text: String,
        smallIconRes: Int,
        contentIntentClass: Class<*>? = null,
    ): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(ctx, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(ctx)
        }

        val cls = contentIntentClass ?: runCatching {
            Class.forName("com.aqi.ccb.MainActivity")
        }.getOrNull()

        if (cls != null) {
            val pi = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, cls),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.setContentIntent(pi)
        }

        return builder
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(smallIconRes)
            .setOngoing(true)
            .build()
    }
}
