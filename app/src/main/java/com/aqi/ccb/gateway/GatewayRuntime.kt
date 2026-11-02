package com.aqi.ccb.gateway

import android.content.Context

/** One ordered publication path shared by UI, MCP, service startup and explicit reload. */
object GatewayRuntime {
    fun isRunning(): Boolean = GatewayService.current?.isRunning() == true
    fun listeningPort(): Int = GatewayService.current?.port ?: 0

    fun configurePublisher() {
        ConfigStore.publisher = { state ->
            val ps=ConfigStore.toProviders(state.providers)
            val rs=ConfigStore.toRules(state.rules)
            val vs=state.virtualModels.map{VirtualModel.fromJson(it)}
            val gw=GatewayService.current
            if(gw==null || !gw.isRunning()) false else {
                gw.update(ps,rs,vs,state.revision,state.keys)
                SecretStore.replace(state.keys)
                GateLog.info("config","runtime revision applied="+state.revision)
                true
            }
        }
    }
    fun reload(context: Context): ConfigStore.Commit {
        configurePublisher()
        return ConfigStore.applyCurrent(context)
    }
    /** Legacy caller compatibility. Prefer reload(context), which reads a single committed generation. */
    fun applyConfig(providers:List<Provider>,rules:List<RuleSpec>,virtualModels:List<VirtualModel>?=null):Boolean {
        val gw=GatewayService.current ?: return false
        if(!gw.isRunning())return false
        gw.update(providers,rules,virtualModels)
        return true
    }
}
