package com.aqi.ccb.data

import android.content.Context
import com.aqi.ccb.gateway.VirtualModel
import com.aqi.ccb.gateway.VirtualModelStore
import com.aqi.ccb.gateway.GateLog

/**
 * 虚拟模型仓库：UI 与 运行时对象 之间的通道。
 */
object VirtualModelRepo {

    fun list(context: Context): List<VirtualModelDraft> =
        VirtualModelStore.load(context).map { vm ->
            VirtualModelDraft(
                name = vm.name,
                description = vm.description,
                ruleId = vm.ruleId,
                enabled = vm.enabled,
                members = vm.members.map { "${it.providerId}|${it.modelId}" },
            )
        }

    fun save(context: Context, draft: VirtualModelDraft): Boolean {
        if (!draft.isComplete) return false
        val all = VirtualModelStore.load(context).toMutableList()
        val vm = VirtualModel.fromJson(draft.toJson())
        val idx = all.indexOfFirst { it.name == draft.name }
        if (idx >= 0) all[idx] = vm else all.add(vm)
        VirtualModelStore.save(context, all)
        GateLog.info("vmodel", "saved name=${draft.name} members=${draft.members.size}")
        return true
    }

    fun delete(context: Context, name: String) {
        val all = VirtualModelStore.load(context).toMutableList()
        all.removeAll { it.name == name }
        VirtualModelStore.save(context, all)
        GateLog.info("vmodel", "deleted name=$name")
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
