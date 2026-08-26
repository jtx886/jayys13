package com.jay.video.data.source

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jay.video.data.Episode
import com.jay.video.data.PlayResult
import com.jay.video.data.PlaySource
import com.jay.video.data.PlaySources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
)

/**
 * 播放源仓库：完整移植自网站 PHP（includes/player.php）
 * - 资源接口搜索（多候选 URL 模式）
 * - 播放串解析（$$$分组 / #分隔 / name$url）
 * - 季数识别与多季匹配（第N季/第N部/S02/Season N/Ⅱ/II/纯数字后缀）
 */
class SourceRepo(
    private val http: OkHttpClient,
    private val gson: Gson = Gson(),
) {
    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 14) JayVideo/1.0"

        /** 简单内存缓存（30 分钟） */
        private val cache = HashMap<String, Pair<Long, Any>>()
        private const val TTL = 30 * 60 * 1000L

        private fun cacheGet(key: String): Any? {
            val (t, v) = cache[key] ?: return null
            return if (System.currentTimeMillis() - t < TTL) v else null
        }

        private fun cacheSet(key: String, v: Any) {
            cache[key] = System.currentTimeMillis() to v
            if (cache.size > 300) cache.clear()
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

    /* ---------- 资源接口 ---------- */

    /** 搜索影片（对应 PHP source_search：依次尝试多种 URL 模式） */
    suspend fun search(source: PlaySource, keyword: String): List<VodItem> {
        val kw = keyword.trim()
        if (kw.isEmpty() || source.apiUrl.isEmpty()) return emptyList()

        val cacheKey = "src:${source.id}|$kw"
        @Suppress("UNCHECKED_CAST")
        cacheGet(cacheKey)?.let { return it as List<VodItem> }

        val enc = java.net.URLEncoder.encode(kw, "UTF-8")
        val sep = if (source.apiUrl.contains('?')) "&" else "?"
        val base = source.apiUrl
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
                val n = normItem(item) ?: continue
                if (n.name.isNotEmpty()) out += n
            }
            if (out.isNotEmpty()) {
                cacheSet(cacheKey, out)
                return out
            }
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

    /** 标准化条目（对应 PHP source_norm_item） */
    private fun normItem(item: JsonObject): VodItem? {
        val name = item.str("vod_name", "name", "title") ?: return null
        var play = item.str("vod_play_url", "play_url", "urls", "vod_url", "url") ?: ""

        // play 为数组的情况：[{name,url}] 或 {名称: url}
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
        )
    }

    /** 按 id 拉详情（对应 PHP source_fetch_detail） */
    suspend fun fetchDetail(source: PlaySource, vodId: String): VodItem? {
        if (vodId.isEmpty()) return null
        val cacheKey = "srcd:${source.id}|$vodId"
        cacheGet(cacheKey)?.let { return it as VodItem }

        val enc = java.net.URLEncoder.encode(vodId, "UTF-8")
        val sep = if (source.apiUrl.contains('?')) "&" else "?"
        for (q in listOf("ac=videolist&ids=", "ac=detail&ids=")) {
            val raw = httpGet("${source.apiUrl}$sep$q$enc") ?: continue
            if (raw.isEmpty()) continue
            val list = parseList(raw) ?: continue
            if (list.isEmpty()) continue
            val first = normItem(list[0]) ?: continue
            cacheSet(cacheKey, first)
            return first
        }
        return null
    }

    /* ---------- 播放串解析 ---------- */

    /** 解析播放串为分组剧集（对应 PHP parse_play_groups） */
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

    /** 选择最佳分组（优先 m3u8 数量多者，对应 PHP pick_best_group） */
    fun pickBestGroup(groups: List<List<Episode>>): List<Episode> {
        if (groups.isEmpty()) return emptyList()
        if (groups.size == 1) return groups[0]
        var best: List<Episode> = emptyList()
        var bestScore = -1
        for (g in groups) {
            val m3u8 = g.count { it.url.contains(".m3u8", ignoreCase = true) }
            val score = m3u8 * 100 + g.size
            if (score > bestScore) {
                bestScore = score
                best = g
            }
        }
        return best
    }

    /** 定位某一集（对应 PHP pick_episode） */
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

    /** 校验直链可用（对应 PHP valid_media_url） */
    fun validMediaUrl(url: String, apiUrl: String): Boolean {
        val u = url.trim()
        if (u.isEmpty()) return false
        if (u.startsWith("//")) return true
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(u)) return false
        val host = runCatching { java.net.URI(u).host?.lowercase() }.getOrNull() ?: ""
        val srcHost = runCatching { java.net.URI(apiUrl).host?.lowercase() }.getOrNull() ?: ""
        return !(host.isNotEmpty() && host == srcHost)
    }

    /* ---------- 季数识别（对应 PHP season_cn_num / name_season_num） ---------- */

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

    /* ---------- 主入口 ---------- */

    /**
     * 解析播放地址（对应 PHP resolve_play，含季匹配）
     * @param season 剧集季数（电影传 1）
     */
    suspend fun resolve(source: PlaySource, title: String, episode: Int, season: Int): PlayResult {
        val fail = { err: String -> PlayResult(ok = false, err = err, sourceName = source.name) }
        val t = title.trim()
        if (t.isEmpty()) return fail("片名不能为空")
        var sn = season
        if (sn < 1) sn = 1

        var list = search(source, t)
        if (list.isEmpty() && sn > 1) {
            for (suffix in listOf("第${sn}季", "第${sn}部")) {
                list = search(source, "$t $suffix")
                if (list.isNotEmpty()) break
            }
        }
        if (list.isEmpty()) return fail("播放源未收录《$t》")

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
            // 多季：全部相关条目中找季标识一致的（优先非粤语）
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
                    val sub = search(source, "$t $suffix")
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
            // 常规：优先季标识一致，其次（第 1 季）无季标识条目
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

        // 无播放串则拉详情
        if (item.play.isEmpty() && item.id.isNotEmpty()) {
            item = fetchDetail(source, item.id) ?: item
        }
        if (item.play.isEmpty()) return fail("播放源未返回播放地址")

        val eps = pickBestGroup(parsePlayGroups(item.play))
        if (eps.isEmpty()) return fail("播放地址解析失败")

        val chosen = pickEpisode(eps, episode) ?: return fail("播放集数不存在")
        if (!validMediaUrl(chosen.url, source.apiUrl)) return fail("播放直链不可用")

        return PlayResult(
            ok = true,
            url = chosen.url,
            label = chosen.name.ifEmpty { "第${maxOf(episode, 1)}集" },
            episodes = eps,
            name = item.name,
            sourceName = source.name,
        )
    }

    /** 依次尝试多个源，返回第一个成功结果 */
    suspend fun resolveAny(title: String, episode: Int, season: Int): PlayResult {
        var last: PlayResult = PlayResult(ok = false, err = "暂无可用播放源")
        for (source in PlaySources.ALL) {
            last = resolve(source, title, episode, season)
            if (last.ok) return last
        }
        return last
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
