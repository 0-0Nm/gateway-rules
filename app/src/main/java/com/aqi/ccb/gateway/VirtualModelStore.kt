package com.aqi.ccb.gateway
import android.content.Context
object VirtualModelStore {
    fun load(context: Context): MutableList<VirtualModel> =
        ConfigStore.read(context).virtualModels.map { VirtualModel.fromJson(it) }.toMutableList()
    fun save(context: Context,list: List<VirtualModel>) {
        ConfigStore.transact(context) { state -> state.virtualModels.clear();state.virtualModels.addAll(list.map{it.toJson()}) }
    }
    fun allClientModelNames(context: Context,providers: List<Provider>): List<String> {
        val real=providers.filter{it.enabled}.flatMap{it.models.values.filter{m->m.enabled}.map{m->m.modelId}}
        return (real+load(context).filter{it.enabled}.map{it.name}).distinct().sorted()
    }
}
