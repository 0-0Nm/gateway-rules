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

    /**
     * 权限判定三态。
     *
     * 为什么需要三态：某些 ROM（vivo 尤甚）会对标准 API 假阴性——
     * 系统层面明明已加入白名单，PowerManager.isIgnoringBatteryOptimizations()
     * 仍返回 false。用二态布尔会把"已授予"误报成"未授予"，用户看到就是
     * "我明明开了它说没开"。
     */
    enum class PermStatus { GRANTED, DENIED, UNKNOWN }

    /** 单项权限状态 */
    data class PermState(
        val key: String,
        val title: String,
        val desc: String,
        /** 兼容旧调用：仅 GRANTED 为 true（UNKNOWN 不算已授予，避免误判） */
        val granted: Boolean,
        /** 该项为什么重要（缺了会怎样） */
        val impact: String,
        /** 精确三态（新） */
        val status: PermStatus = if (granted) PermStatus.GRANTED else PermStatus.DENIED,
        /** 判定依据，便于排查"显示与实际不符" */
        val evidence: String = "",
    )

    /**
     * 检查全部关键权限。
     * 调用方应在主线程或自行 post 到主线程使用返回值。
     */
    fun checkAll(ctx: Context): List<PermState> {
        val list = mutableListOf<PermState>()

        // 1) 悬浮窗 —— 双信号：API 判定 + 实际挂载成功记录
        val overlayApi = canDrawOverlays(ctx)
        val overlayAttached = com.aqi.ccb.gateway.FloatingBall.lastAttachError == null &&
            com.aqi.ccb.gateway.FloatingBall.isMounted()
        val overlayGranted = overlayApi || overlayAttached
        list.add(
            PermState(
                key = KEY_OVERLAY,
                title = "悬浮窗",
                desc = "显示网关状态球与 MCP 状态球",
                granted = overlayGranted,
                impact = if (overlayGranted) "" else "悬浮球不会显示（当前被系统拒绝）",
                status = if (overlayGranted) PermStatus.GRANTED else PermStatus.DENIED,
                evidence = "api=" + overlayApi + " mounted=" + overlayAttached,
            )
        )

        // 2) 通知
        val notif = canPostNotification(ctx)
        list.add(
            PermState(
                key = KEY_NOTIFICATION,
                title = "通知",
                desc = "MCP 前台服务的常驻通知",
                granted = notif,
                impact = if (notif) "" else "前台服务通知不可见，系统可能更快回收进程",
                status = if (notif) PermStatus.GRANTED else PermStatus.DENIED,
                evidence = "checkSelfPermission",
            )
        )

        // 3) 电池优化白名单 —— 三信号
        //    a) PowerManager API（标准，但 vivo 假阴性）
        //    b) 是否曾引导用户去过设置页（说明用户已处理，只是 API 读不准）
        val batteryApi = isIgnoringBatteryOptimizations(ctx)
        val batteryVisited = wasPermissionOpened(ctx, KEY_BATTERY)
        val batteryStatus = when {
            batteryApi -> PermStatus.GRANTED
            // API 说否，但用户去过设置页 -> 无法确认，显示"待确认"而不是"未授予"
            batteryVisited -> PermStatus.UNKNOWN
            else -> PermStatus.DENIED
        }
        list.add(
            PermState(
                key = KEY_BATTERY,
                title = "忽略电池优化",
                desc = "防止系统在息屏后杀掉网关与 MCP",
                granted = batteryApi,
                impact = if (batteryApi) "" else
                    if (batteryVisited) "已去过设置页；本机系统对该项判定不可靠，请自行确认"
                    else "息屏后网关可能被回收，表现为客户端突然连不上",
                status = batteryStatus,
                evidence = "pm=" + batteryApi + " visited=" + batteryVisited,
            )
        )

        return list
    }

    // ---------------- 供 UI 读取的电池项三态 ----------------

    /** 电池项状态名：GRANTED / DENIED / UNKNOWN */
    fun batteryStatusName(ctx: Context): String {
        val api = isIgnoringBatteryOptimizations(ctx)
        return when {
            api -> PermStatus.GRANTED.name
            wasPermissionOpened(ctx, KEY_BATTERY) -> PermStatus.UNKNOWN.name
            else -> PermStatus.DENIED.name
        }
    }

    /** 电池项判定依据，用于面板展示与排障 */
    fun batteryEvidence(ctx: Context): String {
        val api = isIgnoringBatteryOptimizations(ctx)
        val visited = wasPermissionOpened(ctx, KEY_BATTERY)
        return "pm=" + api + " visited=" + visited
    }

    // ---------------- 用户是否已经去过该项设置页 ----------------

    private const val PREF = "perm_helper"
    private const val KEY_PREFIX = "opened_"

    fun markPermissionOpened(ctx: Context, key: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PREFIX + key, true).apply()
    }

    fun wasPermissionOpened(ctx: Context, key: String): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREFIX + key, false)

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
            // 记录"用户已前往该项设置"，供下次判定时区分"真的没开"与"API 读不准"
            markPermissionOpened(ctx, key)
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

    // ---------------- 厂商自启动管理（解冻唯一有效路径） ----------------

    /**
     * 跳转到厂商「自启动 / 后台高耗电」管理页。
     *
     * 为什么必须有这个：
     *   vivo 的 cgroup_freeze 会在后台把进程整个挂起（cgroup.freeze=1，
     *   线程 utime 停止增长），此时前台服务仍显示 isForeground=true，
     *   但网关不响应、悬浮球画不出来。电池白名单挡不住这个冻结，
     *   必须进「自启动管理」把应用加入允许列表。
     */
    fun openAutoStart(ctx: Context): Boolean {
        // 各家 ROM 的类名，逐个试
        val candidates = listOf(
            // vivo / iQOO
            "com.vivo.permissionmanager" to
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to
                "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            "com.iqoo.secure" to
                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            // 小米
            "com.miui.securitycenter" to
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            // OPPO / realme
            "com.coloros.safecenter" to
                "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe" to
                "com.oppo.safe.permission.startup.StartupAppListActivity",
            // 华为
            "com.huawei.systemmanager" to
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            // 三星 / 通用
            "com.samsung.android.lool" to
                "com.samsung.android.sm.ui.battery.BatteryActivity",
        )
        for ((pkg, cls) in candidates) {
            try {
                val i = Intent().apply {
                    setClassName(pkg, cls)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(i)
                GateLog.info("perm", "autoStart opened: $pkg/$cls")
                return true
            } catch (_: Exception) {
                // 该 ROM 没有这个页面，继续试下一个
            }
        }
        GateLog.warn("perm", "no autostart activity found, fallback to app detail")
        return try {
            ctx.startActivity(appDetailIntent(ctx))
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 当前厂商是否已知会冻结后台（用于 UI 提示） */
    fun isFreezeProneRom(): Boolean {
        val brand = (android.os.Build.BRAND ?: "").lowercase()
        val manufacturer = (android.os.Build.MANUFACTURER ?: "").lowercase()
        val s = "$brand $manufacturer"
        return s.contains("vivo") || s.contains("iqoo") || s.contains("oppo") ||
            s.contains("realme") || s.contains("xiaomi") || s.contains("redmi") ||
            s.contains("huawei") || s.contains("honor")
    }

    const val KEY_OVERLAY = "overlay"
    const val KEY_NOTIFICATION = "notification"
    const val KEY_BATTERY = "battery"
}
