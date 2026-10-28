package com.aqi.ccb.gateway

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 权限状态检查与申请入口。
 *
 * 为什么需要这个类：
 * 之前只有 Manifest 声明，没有任何运行时检查与引导——
 * 用户看不到"哪项权限没开"，也不知道该去哪开，
 * 表现为"悬浮球设了却不显示"、"后台老是被杀"。
 *
 * 这里把每项权限的：是否已授予 + 如何跳转设置，集中一处。
 */
object PermissionHelper {

    /** 单项权限状态 */
    data class PermState(
        val key: String,
        val title: String,
        val desc: String,
        val granted: Boolean,
        /** 该项为什么重要（缺了会怎样） */
        val impact: String,
    )

    /**
     * 检查全部关键权限。
     * 调用方应在主线程或自行 post 到主线程使用返回值。
     */
    fun checkAll(ctx: Context): List<PermState> {
        val list = mutableListOf<PermState>()

        // 1) 悬浮窗（特殊权限，走 AppOps，最容易被忽略）
        val overlay = canDrawOverlays(ctx)
        list.add(
            PermState(
                key = KEY_OVERLAY,
                title = "悬浮窗",
                desc = "显示网关状态球与 MCP 状态球",
                granted = overlay,
                impact = if (overlay) "" else "悬浮球不会显示（当前被系统拒绝）",
            )
        )

        // 2) 通知（Android 13+ 需运行时授权）
        val notif = canPostNotification(ctx)
        list.add(
            PermState(
                key = KEY_NOTIFICATION,
                title = "通知",
                desc = "MCP 前台服务的常驻通知",
                granted = notif,
                impact = if (notif) "" else "前台服务通知不可见，系统可能更快回收进程",
            )
        )

        // 3) 电池优化白名单（对保活影响最大）
        val battery = isIgnoringBatteryOptimizations(ctx)
        list.add(
            PermState(
                key = KEY_BATTERY,
                title = "忽略电池优化",
                desc = "防止系统在息屏后杀掉网关与 MCP",
                granted = battery,
                impact = if (battery) "" else "息屏后网关可能被回收，表现为客户端突然连不上",
            )
        )

        return list
    }

    // ---------------- 单项检查 ----------------

    fun canDrawOverlays(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // 注意：Settings.canDrawOverlays 与 appops 一致，
            // 但某些 ROM 会返回 true 而 appops 为 ignore，故失败时仍以 addView 结果为准。
            Settings.canDrawOverlays(ctx)
        } else true

    fun canPostNotification(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true

    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    // ---------------- 跳转设置页 ----------------

    /**
     * 跳到对应权限的设置页。
     * @return true 表示成功拉起设置界面；false 表示该 ROM 不支持此 Intent，需降级到应用详情页
     */
    fun openSettings(ctx: Context, key: String): Boolean {
        return try {
            val intent = when (key) {
                KEY_OVERLAY -> Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + ctx.packageName)
                )
                KEY_NOTIFICATION -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    }
                } else {
                    appDetailIntent(ctx)
                }
                KEY_BATTERY -> Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + ctx.packageName)
                )
                else -> appDetailIntent(ctx)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            GateLog.warn("perm", "openSettings($key) failed: ${e.message}")
            // 降级：打开应用详情页，用户可自行找到权限
            return try {
                ctx.startActivity(appDetailIntent(ctx))
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    private fun appDetailIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 一次性打开所有需要用户处理的权限（逐个跳，不并发） */
    fun missingKeys(ctx: Context): List<String> =
        checkAll(ctx).filter { !it.granted }.map { it.key }

    const val KEY_OVERLAY = "overlay"
    const val KEY_NOTIFICATION = "notification"
    const val KEY_BATTERY = "battery"
}
