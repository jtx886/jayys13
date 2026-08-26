package com.jay.video.data.source

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** 播放站点（影视仓/TVBox 兼容） */
data class Site(
    val key: String,          // 站点唯一标识
    val name: String,         // 站点名称
    val type: Int,            // 0=xml 1=json 3=spider(需jar，降级尝试)
    val api: String,          // 接口地址
    val ext: String = "",     // 扩展参数（字符串或对象序列化）
    val searchable: Boolean = true,
    val builtin: Boolean = false,   // 内置源（来自APP默认，非配置）
    val fromConfig: String = "",    // 来源配置URL
)

/** TVBox 配置解析结果 */
data class TvBoxConfig(
    val sites: List<Site>,
    val wallpaper: String = "",
    val parseCount: Int = 0,
)

/** SharedPreferences 轻量持久化（配置URL / 站点开关） */
object Prefs {
    private const val FILE = "jay_prefs"
    private const val KEY_CONFIGS = "config_urls"        // 用户配置URL列表(JSON数组)
    private const val KEY_SITES_CACHE = "sites_cache"    // 上次解析成功的站点缓存
    private const val KEY_DISABLED = "disabled_keys"     // 已停用站点key(逗号分隔)

    private lateinit var sp: SharedPreferences
    private val gson = Gson()

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    /** 用户添加的配置 URL 列表 */
    fun configUrls(): List<String> = try {
        val raw = sp.getString(KEY_CONFIGS, "[]") ?: "[]"
        gson.fromJson(raw, object : TypeToken<List<String>>() {}.type) ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    fun saveConfigUrls(urls: List<String>) {
        sp.edit().putString(KEY_CONFIGS, gson.toJson(urls.distinct())).apply()
    }

    /** 站点缓存（离线启动时恢复） */
    fun sitesCache(): List<Site> = try {
        val raw = sp.getString(KEY_SITES_CACHE, "[]") ?: "[]"
        gson.fromJson(raw, object : TypeToken<List<Site>>() {}.type) ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    fun saveSitesCache(sites: List<Site>) {
        sp.edit().putString(KEY_SITES_CACHE, gson.toJson(sites)).apply()
    }

    /** 已停用站点 key 集合 */
    fun disabledKeys(): Set<String> =
        (sp.getString(KEY_DISABLED, "") ?: "")
            .split(',')
            .filter { it.isNotBlank() }
            .toSet()

    fun toggleDisabled(key: String, disabled: Boolean) {
        val cur = disabledKeys().toMutableSet()
        if (disabled) cur += key else cur -= key
        sp.edit().putString(KEY_DISABLED, cur.joinToString(",")).apply()
    }
}
