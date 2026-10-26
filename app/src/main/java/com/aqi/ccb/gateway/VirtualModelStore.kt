package com.aqi.ccb.gateway

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 虚拟模型持久化：virtual_models.json 落 filesDir。
 * 与 providers/rules 平级，构成配置三件套。
 */
object VirtualModelStore {

    private const val F = "virtual_models.json"

    fun load(context: Context): MutableList<VirtualModel> {
        val f = File(context.filesDir, F)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            MutableList(arr.length()) { VirtualModel.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            GateLog.error("config", "loadVirtualModels failed", e)
            mutableListOf()
        }
    }

    fun save(context: Context, list: List<VirtualModel>) {
        try {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            File(context.filesDir, F).writeText(arr.toString(2), Charsets.UTF_8)
            GateLog.info("config", "virtual models saved n=${list.size}")
        } catch (e: Exception) {
            GateLog.error("config", "saveVirtualModels failed", e)
        }
    }

    /** 客户端可见的模型列表 = 普通 provider 的 models + 虚拟模型名 */
    fun allClientModelNames(context: Context, providers: List<Provider>): List<String> {
        val regular = providers.flatMap { it.models.keys }.toSet()
        val virtual = load(context).filter { it.enabled }.map { it.name }
        return (regular + virtual).sorted()
    }
}
