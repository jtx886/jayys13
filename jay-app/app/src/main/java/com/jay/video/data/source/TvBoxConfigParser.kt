package com.jay.video.data.source

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 影视仓/TVBox 配置解析器
 * 支持标准格式：
 * {
 *   "sites": [ {"key":"..","name":"..","type":1,"api":"..","ext":"..","searchable":1} ],
 *   "lives": [...], "parses": [...], "wallpaper": ".."
 * }
 * 兼容 base64 编码的配置内容。
 */
object TvBoxConfigParser {

    private val gson = Gson()

    /** 拉取并解析一个配置 URL */
    suspend fun load(http: OkHttpClient, url: String): TvBoxConfig = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) JayVideo/1.0")
                .build()
            val raw = http.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
                .newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() ?: "" else ""
                }
            if (raw.isBlank()) TvBoxConfig(emptyList())
            else parse(raw, url)
        } catch (e: Exception) {
            TvBoxConfig(emptyList())
        }
    }

    /** 解析配置文本（自动尝试 base64 解码） */
    fun parse(raw: String, fromUrl: String = ""): TvBoxConfig {
        val text = raw.trim()
        var root: JsonObject? = null

        // 直接尝试 JSON
        root = runCatching { JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()

        // 失败则尝试 base64 解码（部分影视仓配置为base64）
        if (root == null) {
            val decoded = runCatching {
                val cleaned = text.replace("\n", "").replace("\r", "")
                String(android.util.Base64.decode(cleaned, android.util.Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull()
            if (decoded != null) {
                root = runCatching { JsonParser.parseString(decoded).takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()
            }
        }

        root ?: return TvBoxConfig(emptyList())

        val wallpaper = root.str("wallpaper") ?: ""
        val parses = root.getAsJsonArray("parses")?.size() ?: 0

        // sites / site 两种字段名兼容
        val sitesArr = root.getAsJsonArray("sites") ?: root.getAsJsonArray("site")
        val sites = mutableListOf<Site>()
        if (sitesArr != null) {
            for (el in sitesArr) {
                if (!el.isJsonObject) continue
                val o = el.asJsonObject
                val key = o.str("key") ?: continue
                val name = o.str("name") ?: key
                val type = o.str("type")?.toIntOrNull() ?: 0
                var api = o.str("api") ?: o.str("apiUrl") ?: ""

                // ext 可能是字符串或对象
                val extEl = o.get("ext")
                var ext = ""
                if (extEl != null) {
                    ext = when {
                        extEl.isJsonPrimitive -> extEl.asString
                        extEl.isJsonObject -> extEl.toString()
                        else -> ""
                    }
                }
                // 部分配置 ext 为对象内含 url
                if (ext.startsWith("{")) {
                    runCatching {
                        val eo = JsonParser.parseString(ext).asJsonObject
                        eo.str("url")?.let { if (api.isBlank()) api = it }
                    }
                }

                if (api.isBlank()) continue
                val searchable = o.str("searchable")?.let { it != "0" } ?: true

                sites += Site(
                    key = key,
                    name = name,
                    type = type,
                    api = api,
                    ext = ext,
                    searchable = searchable,
                    fromConfig = fromUrl,
                )
            }
        }
        return TvBoxConfig(sites = sites, wallpaper = wallpaper, parseCount = parses)
    }

    private fun JsonObject.str(vararg keys: String): String? {
        for (k in keys) {
            val v = get(k) ?: continue
            if (v.isJsonPrimitive) return v.asString
        }
        return null
    }
}
