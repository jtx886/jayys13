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
 * - 用户配置 URL → 解析 sites → 与内置源合并
 * - type 1: 苹果CMS json 接口
 * - type 0: 苹果CMS xml 接口
 * - type 3: 爬虫源（需jar），降级尝试 json 接口
 * - 聚合搜索 / 按站点解析 / 多季匹配（移植自 PHP 版）
 */
class SiteRepo(
    private val http: OkHttpClient,
    private val gson: Gson = Gson(),
) {
    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 14) JayVideo/1.0"
        private const val TTL = 30 * 60 * 1000L

        /** 内置源（无配置时兜底） */
        fun builtinSites(): List<Site> = listOf(
            Site("builtin_lz", "量子资源", 1, "https://cj.lziapi.com/api.php/provide/vod", builtin = true),
            Site("builtin_ff", "非凡影视", 1, "https://api.yyzy-tv.vip/inc/apijson.php", builtin = true),
        )

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

    private val _sites = MutableStateFlow(builtinSites())
    val sites: StateFlow<List<Site>> = _sites

    private val _configs = MutableStateFlow<List<String>>(emptyList())
    val configs: StateFlow<List<String>> = _configs

    private var lastLoadOk: Boolean = false
    val loadMessage = MutableStateFlow("")

    /** 全部站点（内置 + 配置） */
    fun allSites(): List<Site> = _sites.value

    /** 已启用站点（未被用户停用且可搜索） */
    fun enabledSites(): List<Site> =
        allSites().filter { it.key !in Prefs.disabledKeys() && it.searchable }

    /** 重新加载用户配置（拉取所有配置URL并合并站点） */
    suspend fun refreshConfigs() {
        val urls = Prefs.configUrls()
        _configs.value = urls

        if (urls.isEmpty()) {
            _sites.value = builtinSites()
            Prefs.saveSitesCache(emptyList())
            lastLoadOk = true
            loadMessage.value = "未添加配置，使用内置源"
            return
        }

        val configs = coroutineScope {
            urls.map { url -> async { TvBoxConfigParser.load(http, url) } }.awaitAll()
        }

        val merged = LinkedHashMap<String, Site>()
        // 配置站点优先（同 key 覆盖）
        for ((i, cfg) in configs.withIndex()) {
            for (s in cfg.sites) {
                merged[s.key] = s
            }
            if (cfg.sites.isEmpty()) {
                loadMessage.value = "配置 ${i + 1} 未解析到站点"
            }
        }
        // 内置源补充（key 不冲突时）
        for (b in builtinSites()) {
            if (merged.none { it.value.api == b.api }) merged[b.key] = b
        }

        val list = merged.values.toList()
        lastLoadOk = list.isNotEmpty()
        if (list.isNotEmpty()) {
            loadMessage.value = "已加载 ${urls.size} 个配置 / ${list.count { !it.builtin }} 个站点"
        }
        _sites.value = list
        Prefs.saveSitesCache(list)
    }

    /** 启动时恢复（缓存优先，后台刷新） */
    fun restoreFromCache() {
        if (_configs.value.isEmpty()) {
            val cached = Prefs.sitesCache()
            if (cached.isNotEmpty()) _sites.value = cached
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
            else -> searchJson(site, kw)   // 1 与 3(降级) 都走 json 尝试
        }
        if (out.isNotEmpty()) cacheSet(cacheKey, out)
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
            val list = parseList(raw) ?: continue
            val out = mutableListOf<VodItem>()
            for (item in list) {
                val n = normItem(item, site) ?: continue
                if (n.name.isNotEmpty()) out += n
            }
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

    /** 选择最佳分组（优先 m3u8 直链数量多者） */
    fun pickBestGroup(groups: List<List<Episode>>): List<Episode> {
        if (groups.isEmpty()) return emptyList()
        if (groups.size == 1) return groups[0]
        var best: List<Episode> = emptyList()
        var bestScore = -1
        for (g in groups) {
            val m3u8 = g.count { isDirectUrl(it.url) }
            val score = m3u8 * 100 + g.size
            if (score > bestScore) {
                bestScore = score
                best = g
            }
        }
        return best
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
            for (suffix in listOf("第${sn}季", "第${sn}部")) {
                list = search(site, "$t $suffix")
                if (list.isNotEmpty()) break
            }
        }
        if (list.isEmpty()) return fail("「${site.name}」未收录《$t》")

        // 标题相关度分级
        val pureTitle = Regex("""\s*(国语|普通话|粤语|高清|完整|版)*${'$'}""").replace(t, "")
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
            outer@ for (allowYue in listOf(false, true)) {
                for (it in pool) {
                    if (!allowYue && it.name.contains("粤语")) continue
                    if (nameSeasonNum(it.name, t) == sn) {
                        item = it
                        break@outer
                    }
                }
            }
            if (item == null) {
                for (suffix in listOf("第${sn}季", "第${sn}部")) {
                    val sub = search(site, "$t $suffix")
                    for (it in sub) {
                        if (nameSeasonNum(it.name, t) == sn) {
                            item = it
                            break
                        }
                    }
                    if (item != null) break
                }
            }
        }
        if (item == null) {
            val unmarked = mutableListOf<VodItem>()
            for (it in candidates) {
                val s = nameSeasonNum(it.name, t)
                if (s == sn) {
                    item = it
                    break
                }
                if (s == 0) unmarked += it
            }
            if (item == null && sn == 1 && unmarked.isNotEmpty()) item = unmarked[0]
        }
        if (item == null) item = candidates[0]

        if (item.play.isEmpty() && item.id.isNotEmpty()) {
            item = fetchDetail(site, item.id) ?: item
        }
        if (item.play.isEmpty()) return fail("「${site.name}」未返回播放地址")

        val eps = pickBestGroup(parsePlayGroups(item.play))
        if (eps.isEmpty()) return fail("播放地址解析失败")

        val chosen = pickEpisode(eps, episode) ?: return fail("播放集数不存在")
        if (!validMediaUrl(chosen.url, site.api)) return fail("「${site.name}」播放直链不可用")

        return PlayResult(
            ok = true,
            url = chosen.url,
            label = chosen.name.ifEmpty { "第${maxOf(episode, 1)}集" },
            episodes = eps,
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
            // 等全部完成，取第一个成功
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

    /** 直接从 VodItem 构建播放结果（资源站搜索直连播放用） */
    fun directResult(item: VodItem, episode: Int, lineIndex: Int = -1): PlayResult {
        val lines = playLines(item)
        if (lines.isEmpty()) return PlayResult(ok = false, err = "播放地址解析失败", sourceName = item.siteName, siteKey = item.siteKey)
        val line = if (lineIndex in lines.indices) lines[lineIndex] else lines.maxByOrNull { it.episodes.count { e -> isDirectUrl(e.url) } } ?: lines[0]
        val chosen = pickEpisode(line.episodes, episode) ?: return PlayResult(ok = false, err = "播放集数不存在", sourceName = item.siteName, siteKey = item.siteKey)
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
}
