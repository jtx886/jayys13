package com.jay.video.data.source

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** 播放站点（影视仓/TVBox 兼容） */
data class Site(
    val key: String,          // 站点唯一标识
    val name: String,         // 站点名称
    val type: Int,            // 0=xml 1=json 3=spider(需jar)
    val api: String,          // 接口地址 / csp_爬虫类名
    val ext: String = "",     // 扩展参数（字符串或对象序列化）
    val searchable: Boolean = true,
    val builtin: Boolean = false,   // 内置源（来自APP默认，非配置）
    val fromConfig: String = "",    // 来源配置URL
    val jarUrl: String = "",        // type:3 依赖的 spider jar 地址
)

/** TVBox 配置解析结果 */
data class TvBoxConfig(
    val sites: List<Site>,
    val spider: String = "",      // 配置级 spider jar
    val wallpaper: String = "",
    val parseCount: Int = 0,
)

/** 默认配置（用户订阅） */
object Defaults {
    /** 主用配置 */
    const val MAIN_CONFIG = "https://18322.kstore.space/杰同学/杰同学.json"

    /** 备用配置 */
    const val BACKUP_CONFIG = "https://18322.kstore.space/杰同学/杰同学/杰同学.json"

    /** 解析播放器（网页播放兜底） */
    const val PARSE_URL = "https://svip.ffzyplay.com/?url="

    fun all(): List<String> = listOf(MAIN_CONFIG, BACKUP_CONFIG)
}

/** SharedPreferences 轻量持久化（配置URL / 站点开关 / 解析器） */
object Prefs {
    private const val FILE = "jay_prefs"
    private const val KEY_CONFIGS = "config_urls"        // 用户配置URL列表(JSON数组)
    private const val KEY_SITES_CACHE = "sites_cache"    // 上次解析成功的站点缓存
    private const val KEY_DISABLED = "disabled_keys"     // 已停用站点key(逗号分隔)
    private const val KEY_PARSE = "parse_url"            // 解析播放器地址
    private const val KEY_INITIALIZED = "defaults_initialized"

    private lateinit var sp: SharedPreferences
    private val gson = Gson()

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        // 首次启动：预置默认配置（主用+备用）
        if (!sp.getBoolean(KEY_INITIALIZED, false)) {
            if (configUrls().isEmpty()) {
                saveConfigUrls(Defaults.all())
            }
            sp.edit().putString(KEY_PARSE, Defaults.PARSE_URL)
                .putBoolean(KEY_INITIALIZED, true).apply()
        }
    }

    /** 用户添加的配置 URL 列表（主用在前） */
    fun configUrls(): List<String> = try {
        val raw = sp.getString(KEY_CONFIGS, "[]") ?: "[]"
        gson.fromJson(raw, object : TypeToken<List<String>>() {}.type) ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    fun saveConfigUrls(urls: List<String>) {
        sp.edit().putString(KEY_CONFIGS, gson.toJson(urls.distinct())).apply()
    }

    /** 解析播放器地址 */
    fun parseUrl(): String = sp.getString(KEY_PARSE, Defaults.PARSE_URL) ?: Defaults.PARSE_URL

    fun saveParseUrl(url: String) {
        sp.edit().putString(KEY_PARSE, url.trim()).apply()
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
