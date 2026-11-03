package com.aqi.ccb.data

import android.content.Context
import com.aqi.ccb.gateway.VirtualModel
import com.aqi.ccb.gateway.VirtualModelStore
import com.aqi.ccb.gateway.GateLog

/**
 * 虚拟模型仓库：UI 与 运行时对象 之间的通道。
 */
object VirtualModelRepo {

    fun list(context:Context):List<VirtualModelDraft> {
        val state=com.aqi.ccb.gateway.ConfigStore.read(context)
        return state.virtualModels.map{VirtualModelDraft.fromJson(it).copy(configRevision=state.revision)}
    }

    fun save(context: Context, draft: VirtualModelDraft): Boolean {
        if (!draft.isComplete) return false
        saveCommitted(context,draft)
        return true
    }

    fun saveCommitted(context:Context,draft:VirtualModelDraft):com.aqi.ccb.gateway.ConfigStore.Commit {
        require(draft.isComplete) { "Incomplete virtual model" }
        return com.aqi.ccb.gateway.ConfigCommands.saveVirtualModel(context,draft.toJson(),draft.configRevision)
    }

    fun delete(context: Context, name: String) {
        com.aqi.ccb.gateway.ConfigCommands.deleteVirtualModel(context, name)
    }

    /** providerId -> (providerName, modelIds)，供勾选页展示 */
    fun providerModels(context: Context): Map<String, Pair<String, List<String>>> {
        val provs = ProviderRepo.list(context)
        return provs.associate { d ->
            d.idOr() to (d.name.ifBlank { d.idOr() } to
                d.modelId.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() })
        }
    }
}
