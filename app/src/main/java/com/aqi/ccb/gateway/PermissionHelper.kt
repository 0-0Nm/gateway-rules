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
    enum class PermStatus {
        GRANTED,
        DENIED,
        UNKNOWN,
        /**
         * 系统报未授予，但实测功能正常（如 vivo 自有白名单放行悬浮窗）。
         * 不能谎报 GRANTED —— 系统确实没开；也不能报 DENIED —— 实际能用。
         * UI 必须把这对矛盾同时呈现给用户。
         */
        CONFLICT
    }

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

        // 1) 悬浮窗 —— 以「能否真的挂上窗口」为权威证据
        //
        // 实测：vivo 上 settings.canDrawOverlays() 返回 false，
        // 但 WindowManager.addView 完全成功、悬浮球正常显示。
        // 说明系统 API 会假阴性，而 addView 成功是硬证据。
        // 因此判定顺序：真挂着 > API 说可以 > 曾挂过(无法确认) > 全否
        val overlayApi = canDrawOverlays(ctx)
        val overlayMounted = com.aqi.ccb.gateway.FloatingBall.isMounted()
        val overlayAttachedOnce = com.aqi.ccb.gateway.FloatingBall.everAttached(ctx)
        // 关键：不能因为「球能挂上」就打勾说已授予。
        // 系统里 appops 确实可能还是 default（未授予），
        // 只是 vivo 自有白名单让 addView 成功了。
        // 这种情况必须呈现为 CONFLICT，让用户自己决定去不去系统里开。
        val overlayStatus = when {
            overlayApi -> PermStatus.GRANTED            // 系统确认已授予
            overlayMounted -> PermStatus.CONFLICT       // 系统说没有，实际能用
            overlayAttachedOnce -> PermStatus.UNKNOWN   // 曾能用，现未挂载
            else -> PermStatus.DENIED
        }
        val overlayGranted = overlayStatus == PermStatus.GRANTED
        list.add(
            PermState(
                key = KEY_OVERLAY,
                title = "悬浮窗",
                desc = "显示网关状态球与 MCP 状态球",
                granted = overlayGranted,
                impact = when (overlayStatus) {
                    PermStatus.GRANTED -> ""
                    PermStatus.CONFLICT -> "系统显示未授予，但悬浮球正在正常工作" +
                        "（可能由厂商白名单放行）。建议仍去系统里正式开启，" +
                        "否则系统更新或重启后可能失效"
                    PermStatus.UNKNOWN -> "曾成功挂载过但系统现在报未授予，可能是权限被撤销"
                    PermStatus.DENIED -> "悬浮球不会显示（当前被系统拒绝）"
                },
                status = overlayStatus,
                evidence = "mounted=" + overlayMounted + " api=" + overlayApi +
                    " ever=" + overlayAttachedOnce,
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

    // ---------------- 自启动状态（无法真检测，只能推断） ----------------

    /**
     * 自启动状态名。
     *
     * 为什么只能推断：Android 没有任何标准 API 能查询
     * "本应用是否被加入厂商自启动白名单"。各 ROM 的实现都是私有的，
     * 连读取都需要系统签名。
     *
     * 所以采用「访问记录 + 实测冻结情况」推断：
     *   - 用户没去过设置页          -> DENIED  （明确提示必须去）
     *   - 用户去过设置页            -> UNKNOWN （提示"已前往，若仍被冻结请再确认"）
     *
     * 不谎报 GRANTED —— 因为我们确实无法确认。
     */
    fun autostartStatusName(ctx: Context): String =
        if (wasPermissionOpened(ctx, KEY_AUTOSTART)) PermStatus.UNKNOWN.name
        else PermStatus.DENIED.name

    /** 用户是否曾前往自启动设置页 */
    fun autostartVisited(ctx: Context): Boolean =
        wasPermissionOpened(ctx, KEY_AUTOSTART)

    // ---------------- 供 UI 读取的电池项三态 ----------------

    /** 悬浮窗状态名：GRANTED / DENIED / UNKNOWN */
    fun overlayStatusName(ctx: Context): String = when {
        canDrawOverlays(ctx) -> PermStatus.GRANTED.name
        com.aqi.ccb.gateway.FloatingBall.isMounted() -> PermStatus.CONFLICT.name
        com.aqi.ccb.gateway.FloatingBall.everAttached(ctx) -> PermStatus.UNKNOWN.name
        else -> PermStatus.DENIED.name
    }

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

    /**
     * 上一次 openSettings 是否只是降级到了「应用详情页」。
     * 详情页里没有悬浮窗/电池开关，对用户等于「点了没反应」——
     * 调用方应根据此标记给出明确指引，而不是假装成功。
     */
    @Volatile
    var lastOpenDegraded: Boolean = false
        private set

    /** 上一次 openSettings 实际打开的页面描述（供 Toast 提示） */
    @Volatile
    var lastOpenTarget: String = ""
        private set

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
                KEY_BATTERY -> {
                    // 实测 vivo：带 package 的 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    // 会被系统静默拦截（直接回桌面，无任何报错），
                    // 而列表页 IGNORE_BATTERY_OPTIMIZATION_SETTINGS 能正常打开
                    // com.iqoo.powersaving.BackgroundHighUsageActivity。
                    // 所以顺序改为：列表页 -> 厂商直连页 -> 带包名页 -> 应用详情页

                    // 1) 电池优化列表页（各 ROM 通用，最可靠）
                    try {
                        ctx.startActivity(
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        markPermissionOpened(ctx, key)
                        lastOpenDegraded = false
                        lastOpenTarget = "电池优化列表页（请在本应用上选择「不限制」）"
                        return true
                    } catch (_: Exception) {
                    }

                    // 2) 厂商电池管理直连页
                    if (openVendorBatteryPage(ctx)) {
                        markPermissionOpened(ctx, key)
                        lastOpenDegraded = false
                        lastOpenTarget = "厂商电池管理页"
                        return true
                    }

                    // 3) 带包名的标准页（部分 ROM 支持）
                    try {
                        ctx.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + ctx.packageName)
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        markPermissionOpened(ctx, key)
                        lastOpenDegraded = false
                        lastOpenTarget = "系统电池优化页"
                        return true
                    } catch (_: Exception) {
                    }

                    // 4) 兜底：应用详情页（没有电池开关，必须明确告知）
                    lastOpenDegraded = true
                    lastOpenTarget = "应用详情页（无电池开关）"
                    appDetailIntent(ctx)
                }
                else -> appDetailIntent(ctx)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            GateLog.info("perm", "openSettings(" + key + ") -> " + intent.action + " " + intent.data)
            // 记录"用户已前往该项设置"，供下次判定时区分"真的没开"与"API 读不准"
            markPermissionOpened(ctx, key)
            true
        } catch (e: Exception) {
            GateLog.warn("perm", "openSettings($key) failed: ${e.message}")
            // 降级：打开应用详情页，用户可自行找到权限
            lastOpenDegraded = true
            lastOpenTarget = "应用详情页"
            return try {
                ctx.startActivity(appDetailIntent(ctx))
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    /**
     * 厂商电池管理页（vivo 的「后台高耗电」在这里）。
     * 标准 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 被拦截时用这个兜底。
     */
    private fun openVendorBatteryPage(ctx: Context): Boolean {
        val candidates = listOf(
            // vivo / iQOO：实测 com.vivo.abe 的 ExcessivePowerManagerActivity
            // 需要 com.vivo.abe.permission.action.openhpactivity（厂商签名），
            // 第三方必被 SecurityException 拒绝，故移除。
            // com.iqoo.powersaving 的 BackgroundHighUsageActivity 才可达
            // （能由 IGNORE_BATTERY_OPTIMIZATION_SETTINGS 拉起，说明无签名限制）。
            "com.iqoo.powersaving" to "com.iqoo.powersaving.BackgroundHighUsageActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            // 小米
            "com.miui.powerkeeper" to "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
            // OPPO
            "com.coloros.oppoguardelf" to
                "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity",
            // 华为
            "com.huawei.systemmanager" to
                "com.huawei.systemmanager.power.ui.HwPowerManagerActivity",
        )
        for ((pkg, cls) in candidates) {
            try {
                ctx.startActivity(
                    Intent().setClassName(pkg, cls)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                GateLog.info("perm", "vendor battery page: " + pkg + "/" + cls)
                return true
            } catch (_: Exception) {
            }
        }
        return false
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
                markPermissionOpened(ctx, KEY_AUTOSTART)
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
    /** 自启动 / 后台运行管理。Android 无标准 API 可查询真实状态，只能用访问记录推断 */
    const val KEY_AUTOSTART = "autostart"
    const val KEY_NOTIFICATION = "notification"
    const val KEY_BATTERY = "battery"
}
