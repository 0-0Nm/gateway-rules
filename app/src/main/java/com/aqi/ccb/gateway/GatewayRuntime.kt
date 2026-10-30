package com.aqi.ccb.gateway

/**
 * 网关运行时门面。
 *
 * 为什么需要：
 *   GatewayService.current 是全局单例，原本只有两处访问是合理的——
 *   gateway 层内部（FloatingBall 判断状态）和 UI 层（MainActivity 显示面板）。
 *   数据层（data/AppSettings.kt 里的 McpBridge）也要访问它时，
 *   就属于绕过门面直接够具体实现，约定被破坏。
 *
 *   正确做法是让 gateway 层对外暴露一个语义化的门面，
 *   数据层依赖「能力」而不是「某个 Service 的静态字段」。
 *   这样将来网关托管方式变化（比如换成 WorkManager、或多实例），
 *   只需要改这一个文件。
 *
 * 只暴露真正跨层需要的能力，不把 GatewayServer 整个漏出去。
 */
object GatewayRuntime {

    /** 网关当前是否在运行 */
    fun isRunning(): Boolean = GatewayService.current?.isRunning() == true

    /** 当前实际监听端口；未运行返回 0 */
    fun listeningPort(): Int = GatewayService.current?.port ?: 0

    /**
     * 热更新运行中网关的配置。
     *
     * @return true=已应用到运行中的实例；false=网关未运行（配置已落盘，启动时自然生效）
     */
    fun applyConfig(
        providers: List<Provider>,
        rules: List<RuleSpec>,
        virtualModels: List<VirtualModel>? = null,
    ): Boolean {
        val gw = GatewayService.current ?: return false
        if (!gw.isRunning()) return false
        gw.update(providers, rules, virtualModels)
        GateLog.info(
            "config",
            "runtime config applied: providers=" + providers.size +
                " rules=" + rules.size +
                " vmodels=" + (virtualModels?.size ?: -1)
        )
        return true
    }
}
