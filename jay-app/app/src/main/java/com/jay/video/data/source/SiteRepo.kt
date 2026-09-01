package com.jay.video.data.source

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jay.video.data.Episode
import com.jay.video.data.PlayResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.Collections
import java.util.concurrent.TimeUnit

/** 资源条目（标准化后） */
data class VodItem(
    val id: String,
    val name: String,
    val pic: String,
    val note: String,
    val year: String,
    val play: String,
    val from: String,
    val siteKey: String = "",
    val siteName: String = "",
)

/** 播放线路（一个站点内的多组播放地址） */
data class PlayLine(
    val label: String,
    val episodes: List<Episode>,
)

/**
 * 多站点资源仓库（影视仓/TVBox 兼容）
 * - 用户配置 URL → 解析 sites（仅用户配置，不混入内置源）
 * - type 3: spider 爬虫源（DexClassLoader 加载 jar，TVBox 协议）
 * - type 1: 苹果CMS json 接口
 * - type 0: 苹果CMS xml 接口
 * - 聚合搜索 / 按站点解析 / 多季匹配
 */
class SiteRepo(
    private val http: OkHttpClient,
    private val gson: Gson = Gson(),
) {
    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 14) JayVideo/1.0"
        private const val TTL = 30 * 60 * 1000L

        private val cache = HashMap<String, Pair<Long, Any>>()

        private fun cacheGet(key: String): Any? {
            val (t, v) = cache[key] ?: return null
            return if (System.currentTimeMillis() - t < TTL) v else null
        }

        private fun cacheSet(key: String, v: Any) {
            cache[key] = System.currentTimeMillis() to v
            if (cache.size > 400) cache.clear()
        }
    }

    private val _sites = MutableStateFlow<List<Site>>(emptyList())
    val sites: StateFlow<List<Site>> = _sites

    private val _configs = MutableStateFlow<List<String>>(emptyList())
    val configs: StateFlow<List<String>> = _configs

    val loadMessage = MutableStateFlow("")

    /** 全部站点（仅来自用户配置） */
    fun allSites(): List<Site> = _sites.value

    /** 已启用站点（未被用户停用且可搜索） */
    fun enabledSites(): List<Site> =
        allSites().filter { it.key !in Prefs.disabledKeys() && it.searchable }

    /** 按 key 查站点 */
    fun siteOf(key: String): Site? = allSites().firstOrNull { it.key == key }

    /** 是否 spider 站点 */
    fun isSpiderSite(site: Site?): Boolean = site != null && site.type == 3 && site.jarUrl.isNotEmpty()

    /** 重新加载用户配置（拉取所有配置URL并合并站点，主用配置优先） */
    suspend fun refreshConfigs() {
        val urls = Prefs.configUrls()
        _configs.value = urls

        if (urls.isEmpty()) {
            _sites.value = emptyList()
            Prefs.saveSitesCache(emptyList())
            loadMessage.value = "未添加配置，请在「我的」页面添加"
            return
        }

        val configs = coroutineScope {
            urls.map { url -> async { TvBoxConfigParser.load(http, url) } }.awaitAll()
        }

        val merged = LinkedHashMap<String, Site>()
        // 主用配置在前（同 key 优先保留主用）
        for (cfg in configs) {
            for (s in cfg.sites) {
                if (merged.containsKey(s.key)) continue
                merged[s.key] = s
            }
        }

        val list = merged.values.toList()

        if (list.isNotEmpty()) {
            loadMessage.value = "已加载 ${urls.size} 个配置 / ${list.size} 个站点"
        } else {
            loadMessage.value = "配置未解析到站点，请检查配置链接"
        }
        _sites.value = list
        Prefs.saveSitesCache(list)

        // 后台预热 spider jar（加速首次搜索）
        val jars = list.map { it.jarUrl }.filter { it.isNotEmpty() }.distinct()
        if (jars.isNotEmpty()) {
            com.jay.video.App.appScope.launch {
                jars.forEach { jar -> runCatching { SpiderLoader.loadJar(jar) } }
            }
        }
    }

    /** 启动时恢复（缓存优先，后台刷新） */
    fun restoreFromCache() {
        if (_configs.value.isEmpty()) {
            val cached = Prefs.sitesCache()
            if (cached.isNotEmpty()) {
                _sites.value = cached
                loadMessage.value = "已恢复 ${cached.size} 个站点"
            }
        }
    }

    /* ---------- HTTP ---------- */

    private suspend fun httpGet(url: String, timeoutSec: Long = 12): String = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/plain, */*")
                .build()
            http.newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .build()
                .newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() ?: "" else ""
                }
        } catch (e: Exception) {
            ""
        }
    }

    /* ---------- 站点搜索 ---------- */

    /** 按站点类型分发搜索 */
    suspend fun search(site: Site, keyword: String): List<VodItem> {
        val kw = keyword.trim()
        if (kw.isEmpty() || site.api.isEmpty()) return emptyList()

        val cacheKey = "src:${site.key}|$kw"
        @Suppress("UNCHECKED_CAST")
        cacheGet(cacheKey)?.let { return it as List<VodItem> }

        val out = when (site.type) {
            0 -> searchXml(site, kw)
            3 -> if (site.jarUrl.isNotEmpty()) searchSpider(site, kw) else emptyList()
            else -> searchJson(site, kw)
        }
        if (out.isNotEmpty()) cacheSet(cacheKey, out)
        return out
    }

    /** type 3: spider 爬虫搜索（searchContent → TVBox JSON） */
    private suspend fun searchSpider(site: Site, kw: String): List<VodItem> {
        val raw = SpiderLoader.search(site.key, site.jarUrl, site.api, site.ext, kw) ?: return emptyList()
        return parseVodList(raw, site)
    }

    /** 解析 TVBox/MacCMS 通用列表 JSON */
    private fun parseVodList(raw: String, site: Site): List<VodItem> {
        val list = parseList(raw) ?: return emptyList()
        val out = mutableListOf<VodItem>()
        for (item in list) {
            val n = normItem(item, site) ?: continue
            if (n.name.isNotEmpty()) out += n
        }
        return out
    }

    /** type 1: json 接口搜索（依次尝试多种 URL 模式） */
    private suspend fun searchJson(site: Site, kw: String): List<VodItem> {
        val enc = java.net.URLEncoder.encode(kw, "UTF-8")
        val sep = if (site.api.contains('?')) "&" else "?"
        val base = site.api
        val candidates = listOf(
            "$base${sep}ac=videolist&wd=$enc",
            "$base${sep}wd=$enc",
            "$base${sep}ac=detail&wd=$enc",
        )

        for (url in candidates) {
            val raw = httpGet(url) ?: continue
            if (raw.isEmpty()) continue
            val out = parseVodList(raw, site)
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    /** type 0: xml 接口搜索（MacCMS xml） */
    private suspend fun searchXml(site: Site, kw: String): List<VodItem> {
        val enc = java.net.URLEncoder.encode(kw, "UTF-8")
        val sep = if (site.api.contains('?')) "&" else "?"
        for (path in listOf("at/xml", "")) {
            val url = if (path.isEmpty()) {
                "${site.api}${sep}wd=$enc"
            } else {
                val base = site.api.trimEnd('/')
                "${base}/${path}${sep}wd=$enc"
            }
            val raw = httpGet(url)
            if (raw.isEmpty() || !raw.contains("<")) continue
            val items = parseXmlList(raw, site)
            if (items.isNotEmpty()) return items
        }
        return emptyList()
    }

    /** 解析响应中的条目数组（list / data / items / vod_list / results） */
    private fun parseList(raw: String): List<JsonObject>? {
        return try {
            val obj = JsonParser.parseString(raw).asJsonObject
            for (key in listOf("list", "data", "items", "vod_list", "results")) {
                val arr = obj.get(key) ?: continue
                if (arr.isJsonArray) {
                    val out = mutableListOf<JsonObject>()
                    for (el in arr.asJsonArray) {
                        if (el.isJsonObject) out += el.asJsonObject
                    }
                    if (out.isNotEmpty()) return out
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /** 标准化 json 条目 */
    private fun normItem(item: JsonObject, site: Site): VodItem? {
        val name = item.str("vod_name", "name", "title") ?: return null
        var play = item.str("vod_play_url", "play_url", "urls", "vod_url", "url") ?: ""

        val playEl = item.get("vod_play_url") ?: item.get("play_url") ?: item.get("urls")
        if (playEl != null && playEl.isJsonArray) {
            val tmp = mutableListOf<String>()
            for (el in playEl.asJsonArray) {
                if (el.isJsonObject) {
                    val o = el.asJsonObject
                    tmp += "${o.str("name") ?: ""}${'$'}${o.str("url", "play_url") ?: ""}"
                } else {
                    tmp += "${'$'}${el.asString}"
                }
            }
            play = tmp.joinToString("#")
        } else if (playEl != null && playEl.isJsonObject) {
            val tmp = mutableListOf<String>()
            for ((k, v) in playEl.asJsonObject.entrySet()) {
                tmp += "$k${'$'}${v.asString}"
            }
            play = tmp.joinToString("#")
        }

        return VodItem(
            id = item.str("vod_id", "id") ?: "",
            name = name,
            pic = item.str("vod_pic", "pic") ?: "",
            note = item.str("vod_remarks", "remarks", "note") ?: "",
            year = item.str("vod_year", "year") ?: "",
            play = play,
            from = item.str("vod_play_from", "from") ?: "",
            siteKey = site.key,
            siteName = site.name,
        )
    }

    /** 解析 MacCMS xml 列表 */
    private fun parseXmlList(raw: String, site: Site): List<VodItem> {
        if (!raw.contains("<video")) return emptyList()
        return try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(raw))

            val items = mutableListOf<VodItem>()
            var fields: HashMap<String, StringBuilder>? = null
            var ddAll: StringBuilder? = null
            var ddBuf: StringBuilder? = null
            var tag = ""
            var event = parser.eventType

            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        tag = parser.name.lowercase()
                        if (tag == "video") {
                            fields = HashMap()
                            ddAll = StringBuilder()
                        } else if (fields != null && tag == "dd") {
                            ddBuf = StringBuilder()
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val t = parser.text ?: ""
                        if (ddBuf != null) ddBuf!!.append(t)
                        else if (fields != null && tag.isNotEmpty()) {
                            fields!!.getOrPut(tag) { StringBuilder() }.append(t)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val name = parser.name.lowercase()
                        if (name == "dd" && ddBuf != null && ddAll != null) {
                            if (ddAll!!.isNotEmpty()) ddAll!!.append("$$$")
                            ddAll!!.append(ddBuf.toString())
                            ddBuf = null
                        } else if (name == "video" && fields != null && ddAll != null) {
                            val f = fields!!
                            val vName = f["name"]?.toString()?.trim() ?: ""
                            if (vName.isNotEmpty()) {
                                items += VodItem(
                                    id = f["id"]?.toString()?.trim() ?: "",
                                    name = vName,
                                    pic = f["pic"]?.toString()?.trim() ?: "",
                                    note = f["note"]?.toString()?.trim() ?: "",
                                    year = f["year"]?.toString()?.trim() ?: "",
                                    play = ddAll.toString(),
                                    from = "",
                                    siteKey = site.key,
                                    siteName = site.name,
                                )
                            }
                            fields = null
                            ddAll = null
                        }
                        tag = ""
                    }
                }
                event = parser.next()
            }
            items
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 按站点+id 拉详情 */
    suspend fun fetchDetail(site: Site, vodId: String): VodItem? {
        if (vodId.isEmpty()) return null
        val cacheKey = "srcd:${site.key}|$vodId"
        cacheGet(cacheKey)?.let { return it as VodItem }

        // spider 站点：detailContent
        if (isSpiderSite(site)) {
            val raw = SpiderLoader.detail(site.key, site.jarUrl, site.api, site.ext, vodId) ?: return null
            val list = parseList(raw) ?: return null
            if (list.isEmpty()) return null
            val item = normItem(list[0], site) ?: return null
            cacheSet(cacheKey, item)
            return item
        }

        val enc = java.net.URLEncoder.encode(vodId, "UTF-8")
        val sep = if (site.api.contains('?')) "&" else "?"

        // json 尝试
        if (site.type != 0) {
            for (q in listOf("ac=videolist&ids=", "ac=detail&ids=")) {
                val raw = httpGet("${site.api}$sep$q$enc")
                if (raw.isEmpty()) continue
                val list = parseList(raw) ?: continue
                if (list.isEmpty()) continue
                val first = normItem(list[0], site) ?: continue
                cacheSet(cacheKey, first)
                return first
            }
        }
        // xml 尝试
        for (q in listOf("ac=videolist&ids=", "ac=detail&ids=")) {
            val base = site.api.trimEnd('/')
            val url = "$base/at/xml${sep}$q$enc"
            val raw = httpGet(url)
            if (raw.isEmpty() || !raw.contains("<video")) continue
            val items = parseXmlList(raw, site)
            if (items.isNotEmpty()) {
                cacheSet(cacheKey, items[0])
                return items[0]
            }
        }
        return null
    }

    /* ---------- 聚合搜索 ---------- */

    /**
     * 聚合搜索所有启用站点（并行），每完成一个站点立即回调
     * @return 全部结果（按结果数量降序）
     */
    suspend fun searchAll(
        keyword: String,
        onSiteDone: (site: Site, results: List<VodItem>) -> Unit = { _, _ -> },
    ): List<Pair<Site, List<VodItem>>> = coroutineScope {
        val kw = keyword.trim()
        if (kw.isEmpty()) return@coroutineScope emptyList()
        val enabled = enabledSites()
        if (enabled.isEmpty()) return@coroutineScope emptyList()

        val collected = Collections.synchronizedList(mutableListOf<Pair<Site, List<VodItem>>>())
        enabled.map { site ->
            async {
                val r = runCatching { search(site, kw) }.getOrDefault(emptyList())
                collected += site to r
                runCatching { onSiteDone(site, r) }
                site to r
            }
        }.awaitAll()
        collected.sortedByDescending { it.second.size }
    }

    /* ---------- 播放串解析 ---------- */

    /** 解析播放串为分组剧集（$$$ 分组 / # 分隔 / name$url） */
    fun parsePlayGroups(str: String): List<List<Episode>> {
        val groups = mutableListOf<List<Episode>>()
        val s = str.replace("\r\n", "#")
        for (part in s.split("$$$")) {
            val eps = mutableListOf<Episode>()
            for (seg in part.split(Regex("#|\\$\\$"))) {
                val t = seg.trim()
                if (t.isEmpty()) continue
                val name: String
                val url: String
                val idx = t.indexOf('$')
                if (idx >= 0) {
                    name = t.substring(0, idx).trim()
                    url = t.substring(idx + 1).trim()
                } else {
                    name = ""
                    url = t
                }
                if (url.isEmpty()) continue
                eps += Episode(name, url)
            }
            if (eps.isNotEmpty()) groups += eps
        }
        return groups
    }

    /** 提取播放线路（带标签，来自 vod_play_from） */
    fun playLines(item: VodItem): List<PlayLine> {
        val groups = parsePlayGroups(item.play)
        val labels = item.from.split("$$$").map { it.trim() }
        return groups.mapIndexed { i, g ->
            val label = labels.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: "线路${i + 1}"
            PlayLine(label, g)
        }
    }

    /** 是否为可直连播放的媒体地址 */
    fun isDirectUrl(url: String): Boolean {
        val u = url.trim()
        if (u.isEmpty()) return false
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(u)) return false
        val extOk = u.contains(".m3u8", true) || u.contains(".mp4", true) ||
            u.contains(".mkv", true) || u.contains(".mov", true) ||
            u.contains(".flv", true) || u.contains(".ts", true)
        val shareOk = u.contains("/share/", true) && u.substringAfterLast('/').length > 20
        return extOk || shareOk
    }

    /** 定位某一集 */
    fun pickEpisode(eps: List<Episode>, ep: Int): Episode? {
        if (eps.isEmpty()) return null
        if (ep <= 0) return eps[0]
        val idx = ep - 1
        if (idx < eps.size) return eps[idx]
        val re = Regex("(?:第)?\\s*0*$ep\\s*(?:集|话|期|集数)?$")
        for (item in eps) {
            if (item.name.isNotEmpty() && re.containsMatchIn(item.name.trim())) return item
        }
        return if (ep <= eps.size) eps[idx] else eps.last()
    }

    /** 校验直链可用（排除与站点同域的网页外壳地址） */
    fun validMediaUrl(url: String, apiUrl: String): Boolean {
        val u = url.trim()
        if (u.isEmpty()) return false
        if (u.startsWith("//")) return true
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(u)) return false
        val host = runCatching { java.net.URI(u).host?.lowercase() }.getOrNull() ?: ""
        val srcHost = runCatching { java.net.URI(apiUrl).host?.lowercase() }.getOrNull() ?: ""
        return !(host.isNotEmpty() && host == srcHost)
    }

    /* ---------- spider 播放解析 ---------- */

    /**
     * spider 站点播放地址解析（影视仓 SiteApi.playerContent + Result.needParse 逻辑）：
     * - episode.url 是 playerContent 的 id，先调 spider.playerContent(flag, id, flags)
     * - needParse = parse==1 || jx==1；或 url 非视频直链 → 网页解析（ffzyplay）
     * - spider 返回 playUrl 时优先作为解析前缀（影视仓 ParseJob.setParse）
     */
    private suspend fun resolveSpiderPlay(
        site: Site, flag: String, id: String, label: String,
        episodes: List<Episode>, itemName: String,
    ): PlayResult {
        val raw = SpiderLoader.player(site.key, site.jarUrl, site.api, site.ext, flag, id)
        if (raw != null) {
            try {
                val obj = JsonParser.parseString(raw).asJsonObject
                val url = obj.str("url") ?: ""
                val playUrl = obj.str("playUrl") ?: ""
                val parse = obj.int("parse")
                val jx = obj.int("jx")
                val headers = mutableMapOf<String, String>()
                obj.get("header")?.takeIf { it.isJsonObject }?.asJsonObject?.let { h ->
                    for ((k, v) in h.entrySet()) {
                        if (v.isJsonPrimitive) headers[k] = v.asString
                    }
                }
                if (url.isNotEmpty()) {
                    // 影视仓 needParse()：parse==1 || jx==1；直链判定用 Sniffer.isVideoFormat
                    val needParse = parse == 1 || jx == 1
                    val direct = !needParse && Sniffer.isVideoFormat(url) && playUrl.isEmpty()
                    return PlayResult(
                        ok = true,
                        url = url,
                        label = label,
                        episodes = episodes,
                        name = itemName,
                        sourceName = site.name,
                        siteKey = site.key,
                        headers = headers,
                        webOnly = !direct,           // 需解析 → 嗅探/网页播放（ffzyplay）
                        parseUrl = playUrl,           // 影视仓 playUrl（spider 指定解析器）
                    )
                }
            } catch (e: Exception) {
                // JSON 解析失败，降级
            }
        }
        // playerContent 失败或无 url：若 id 本身是直链则直接用
        if (isDirectUrl(id)) {
            return PlayResult(
                ok = true, url = id, label = label, episodes = episodes,
                name = itemName, sourceName = site.name, siteKey = site.key,
            )
        }
        return PlayResult(ok = false, err = "「${site.name}」播放地址解析失败", sourceName = site.name, siteKey = site.key)
    }

    /* ---------- 季数识别 ---------- */

    private fun seasonCnNum(cn: String): Int {
        val map = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        if (cn == "十") return 10
        if (cn.contains('十')) {
            val parts = cn.split('十')
            val a = if (parts[0].isEmpty()) 1 else (map[parts[0][0]] ?: 0)
            val b = if (parts.size > 1 && parts[1].isNotEmpty()) (map[parts[1][0]] ?: 0) else 0
            return a * 10 + b
        }
        return if (cn.length == 1) (map[cn[0]] ?: 0) else 0
    }

    /** 阿拉伯数字 → 中文数字（1→一，12→十二） */
    private fun cnSeason(n: Int): String {
        val digits = listOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九")
        if (n <= 0) return ""
        if (n < 10) return digits[n]
        if (n == 10) return "十"
        if (n < 20) return "十" + digits[n % 10]
        val tens = digits[n / 10] + "十"
        return tens + if (n % 10 == 0) "" else digits[n % 10]
    }

    /** 解析播放线路标签中的季数（"第一季"/"第2季"/"S02"），无季标识返回 0 */
    fun labelSeasonNum(label: String): Int {
        val l = label.trim()
        if (l.isEmpty()) return 0
        Regex("""第\s*(\d{1,2})\s*[季部]""").find(l)?.let { return it.groupValues[1].toIntOrNull() ?: 0 }
        Regex("""第\s*([一二三四五六七八九十]{1,3})\s*[季部]""").find(l)?.let { return seasonCnNum(it.groupValues[1]) }
        Regex("""(?:^|[^0-9])S\s*0*(\d{1,2})(?!\d)""", RegexOption.IGNORE_CASE).find(l)?.let {
            return it.groupValues[1].toIntOrNull() ?: 0
        }
        return 0
    }

    /** 条目是否包含指定季的播放分组（vod_play_from = "第一季$$$第二季"） */
    private fun hasSeasonGroup(item: VodItem, sn: Int): Boolean =
        item.from.split("$$$").any { labelSeasonNum(it.trim()) == sn }

    /** 季检索关键词变体（中文/阿拉伯数字 × 带空格/紧凑，覆盖不同站的模糊匹配） */
    private fun seasonKeywords(t: String, sn: Int): List<String> {
        val cn = cnSeason(sn)
        return linkedSetOf(
            "${t}第${cn}季", "$t 第${cn}季",
            "${t}第${sn}季", "$t 第${sn}季",
            "${t}第${cn}部", "$t 第${cn}部",
            "${t}第${sn}部", "$t 第${sn}部",
        ).toList()
    }

    /** 解析条目名称中的季数；无季标识返回 0 */
    fun nameSeasonNum(name: String, title: String): Int {
        var rest = name.trim()
        if (title.isNotEmpty() && rest.startsWith(title)) {
            rest = rest.removePrefix(title).trim()
        }
        if (rest.isEmpty()) return 0

        Regex("""第\s*(\d{1,2})\s*[季部]""").find(rest)?.let { return it.groupValues[1].toIntOrNull() ?: 0 }
        Regex("""第\s*([一二三四五六七八九十]{1,3})\s*[季部]""").find(rest)?.let {
            return seasonCnNum(it.groupValues[1])
        }
        Regex("""(?:S|Season[\s.]*)0*(\d{1,2})(?!\d)""", RegexOption.IGNORE_CASE).find(rest)?.let {
            return it.groupValues[1].toIntOrNull() ?: 0
        }
        val roman = mapOf('Ⅰ' to 1, 'Ⅱ' to 2, 'Ⅲ' to 3, 'Ⅳ' to 4, 'Ⅴ' to 5, 'Ⅵ' to 6, 'Ⅶ' to 7, 'Ⅷ' to 8, 'Ⅸ' to 9, 'Ⅹ' to 10)
        Regex("""[ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ]""").find(rest)?.let { return roman[it.value[0]] ?: 0 }
        Regex("""(?:^|\s)(II|III|IV|V|VI|VII|VIII|IX|X)(?:${'$'}|\s)""").find(rest)?.let {
            val ascii = mapOf("I" to 1, "II" to 2, "III" to 3, "IV" to 4, "V" to 5, "VI" to 6, "VII" to 7, "VIII" to 8, "IX" to 9, "X" to 10)
            return ascii[it.groupValues[1]] ?: 0
        }
        Regex("""^0*(\d{1,2})${'$'}""").find(rest)?.let { return it.groupValues[1].toIntOrNull() ?: 0 }
        return 0
    }

    /* ---------- 解析主入口 ---------- */

    /** 在指定站点解析播放地址（含季匹配） */
    suspend fun resolve(site: Site, title: String, episode: Int, season: Int): PlayResult {
        val fail = { err: String -> PlayResult(ok = false, err = err, sourceName = site.name, siteKey = site.key) }
        val t = title.trim()
        if (t.isEmpty()) return fail("片名不能为空")
        var sn = season
        if (sn < 1) sn = 1

        var list = search(site, t)
        if (list.isEmpty() && sn > 1) {
            for (kw in seasonKeywords(t, sn)) {
                list = search(site, kw)
                if (list.isNotEmpty()) break
            }
        }
        if (list.isEmpty()) return fail("「${site.name}」未收录《$t》")

        // 标题相关度分级
        val pureTitle = Regex("""\s*(国语|普通话|粤语|高清|完整|版)*$""").replace(t, "")
        val exact = mutableListOf<VodItem>()
        val contains = mutableListOf<VodItem>()
        val loose = mutableListOf<VodItem>()
        for (it in list) {
            when {
                it.name == t -> exact += it
                it.name.contains(t) -> contains += it
                pureTitle.isNotEmpty() && it.name.contains(pureTitle) -> loose += it
            }
        }
        val candidates = (exact.ifEmpty { contains.ifEmpty { loose } }).ifEmpty { list }
        val pool = (exact + contains + loose).ifEmpty { list }

        var item: VodItem? = null
        if (sn > 1) {
            // 1) 名称明确标注该季（排除粤语版优先）
            outer@ for (allowYue in listOf(false, true)) {
                for (it in pool) {
                    if (!allowYue && it.name.contains("粤语")) continue
                    if (nameSeasonNum(it.name, t) == sn) {
                        item = it
                        break@outer
                    }
                }
            }
            // 2) 单条目合并多季（vod_play_from 分组带季标签）
            if (item == null) {
                for (it in pool) {
                    if (hasSeasonGroup(it, sn)) {
                        item = it
                        break
                    }
                }
            }
            // 3) 二次检索（中文/阿拉伯数字变体）
            if (item == null) {
                outer2@ for (kw in seasonKeywords(t, sn)) {
                    val sub = search(site, kw)
                    for (it in sub) {
                        if (nameSeasonNum(it.name, t) == sn) {
                            item = it
                            break@outer2
                        }
                    }
                }
            }
            // 未找到对应季：快速失败，交由其它站点（避免错放第一季内容）
            if (item == null) return fail("「${site.name}」未收录《$t》第${sn}季")
        } else {
            // 第一季：优先明确标注，其次无季标注条目
            for (it in candidates) {
                if (nameSeasonNum(it.name, t) == 1) {
                    item = it
                    break
                }
            }
            if (item == null) {
                for (it in candidates) {
                    if (nameSeasonNum(it.name, t) == 0) {
                        item = it
                        break
                    }
                }
            }
            if (item == null) item = candidates[0]
        }

        if (item.play.isEmpty() && item.id.isNotEmpty()) {
            item = fetchDetail(site, item.id) ?: item
        }
        if (item.play.isEmpty()) return fail("「${site.name}」未返回播放地址")

        // 选择线路：多季合并条目优先按季标签选组，否则选直链最多的线路
        val lines = playLines(item)
        if (lines.isEmpty()) return fail("播放地址解析失败")
        val line = lines.firstOrNull { l -> labelSeasonNum(l.label) == sn }
            ?: lines.maxByOrNull { l -> l.episodes.count { isDirectUrl(it.url) } * 100 + l.episodes.size }
            ?: lines[0]

        val chosen = pickEpisode(line.episodes, episode) ?: return fail("播放集数不存在")

        // spider 站点：chosen.url 是 playerContent 的 id
        if (isSpiderSite(site)) {
            return resolveSpiderPlay(site, line.label, chosen.url, chosen.name.ifEmpty { "第${maxOf(episode, 1)}集" }, line.episodes, item.name)
        }

        // CMS 站点（影视仓 SiteApi:184）：非视频直链 → 解析播放
        if (!validMediaUrl(chosen.url, site.api) || !Sniffer.isVideoFormat(chosen.url)) {
            if (chosen.url.startsWith("http")) {
                return PlayResult(
                    ok = true,
                    url = chosen.url,
                    label = chosen.name.ifEmpty { "第${maxOf(episode, 1)}集" },
                    episodes = line.episodes,
                    name = item.name,
                    sourceName = site.name,
                    siteKey = site.key,
                    webOnly = true,
                )
            }
            return fail("「${site.name}」播放直链不可用")
        }

        return PlayResult(
            ok = true,
            url = chosen.url,
            label = chosen.name.ifEmpty { "第${maxOf(episode, 1)}集" },
            episodes = line.episodes,
            name = item.name,
            sourceName = site.name,
            siteKey = site.key,
        )
    }

    /**
     * 多站点解析：优先 preferred（或第一个启用站点），
     * 失败则并行尝试其余站点，取第一个成功结果
     * @param excludeKeys 已播放失败的站点（自动换源时排除）
     */
    suspend fun resolveAny(
        title: String,
        episode: Int,
        season: Int,
        preferredKey: String? = null,
        excludeKeys: Set<String> = emptySet(),
    ): PlayResult {
        val enabled = enabledSites().filter { it.key !in excludeKeys }
        if (enabled.isEmpty()) return PlayResult(ok = false, err = "暂无可用播放源，请在设置中添加配置")

        val first = enabled.firstOrNull { it.key == preferredKey } ?: enabled[0]
        val r = resolve(first, title, episode, season)
        if (r.ok) return r

        val rest = enabled.filter { it != first }
        if (rest.isEmpty()) return r

        // 并行尝试其余站点，取第一个成功
        return coroutineScope {
            val deferreds = rest.map { site ->
                async {
                    runCatching { resolve(site, title, episode, season) }.getOrNull()
                }
            }
            var firstOk: PlayResult? = null
            var lastErr = r
            for (d in deferreds) {
                val res = d.await()
                if (res != null) {
                    if (res.ok && firstOk == null) firstOk = res
                    if (!res.ok) lastErr = res
                }
            }
            firstOk ?: lastErr
        }
    }

    /** 直接从 VodItem 构建播放结果（资源站搜索直连播放用；spider 站点自动调 playerContent） */
    suspend fun directResult(item: VodItem, episode: Int, lineIndex: Int = -1): PlayResult {
        val lines = playLines(item)
        if (lines.isEmpty()) return PlayResult(ok = false, err = "播放地址解析失败", sourceName = item.siteName, siteKey = item.siteKey)
        val line = if (lineIndex in lines.indices) {
            lines[lineIndex]
        } else {
            lines.maxByOrNull { it.episodes.count { e -> isDirectUrl(e.url) } } ?: lines[0]
        }
        val chosen = pickEpisode(line.episodes, episode)
            ?: return PlayResult(ok = false, err = "播放集数不存在", sourceName = item.siteName, siteKey = item.siteKey)

        // spider 站点：episode.url 是 playerContent 的 id
        val site = siteOf(item.siteKey)
        if (isSpiderSite(site)) {
            return resolveSpiderPlay(
                site!!, line.label, chosen.url,
                chosen.name.ifEmpty { "第${maxOf(episode, 1)}集" },
                line.episodes, item.name,
            )
        }

        // CMS 站点：非视频直链 → 解析播放（影视仓逻辑）
        if (!chosen.url.startsWith("http") || (!validMediaUrl(chosen.url, site?.api ?: "") && !Sniffer.isVideoFormat(chosen.url))) {
            if (chosen.url.startsWith("http")) {
                return PlayResult(
                    ok = true,
                    url = chosen.url,
                    label = chosen.name.ifEmpty { "第${maxOf(episode, 1)}集" },
                    episodes = line.episodes,
                    name = item.name,
                    sourceName = item.siteName,
                    siteKey = item.siteKey,
                    webOnly = true,
                )
            }
            return PlayResult(ok = false, err = "播放地址不可用", sourceName = item.siteName, siteKey = item.siteKey)
        }

        return PlayResult(
            ok = true,
            url = chosen.url,
            label = chosen.name.ifEmpty { "第${maxOf(episode, 1)}集" },
            episodes = line.episodes,
            name = item.name,
            sourceName = item.siteName,
            siteKey = item.siteKey,
        )
    }

    /* ---------- 工具 ---------- */

    private fun JsonObject.str(vararg keys: String): String? {
        for (k in keys) {
            val v = get(k) ?: continue
            if (v.isJsonPrimitive) return v.asString
        }
        return null
    }

    private fun JsonObject.int(key: String): Int {
        val v = get(key) ?: return 0
        return runCatching { v.asInt }.getOrDefault(0)
    }
}
