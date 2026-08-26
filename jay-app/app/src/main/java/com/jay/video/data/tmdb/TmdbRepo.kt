package com.jay.video.data.tmdb

import com.jay.video.data.Media
import com.jay.video.data.SearchCard
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** TMDB 仓库：标准化数据 + 中文缺失时英文兜底 */
class TmdbRepo(private val api: TmdbApi) {

    companion object {
        private const val IMG_BASE = "https://images.tmdb.org/t/p"

        /** TMDB 图片完整地址 */
        fun img(path: String?, size: String = "w500"): String =
            if (path.isNullOrEmpty()) "" else "$IMG_BASE/$size$path"
    }

    /** 标准化条目（对应 PHP tmdb_norm） */
    private fun norm(item: RawItem): Media? {
        val hasName = item.name != null && item.title == null && item.firstAirDate != null
        val isTv = item.mediaType == "tv" || hasName
        var type = item.mediaType ?: if (isTv) "tv" else "movie"
        if (type != "tv" && type != "movie") type = if (item.title != null) "movie" else "tv"

        val title = item.title ?: item.name ?: ""
        val poster = img(item.posterPath, "w500")
        if (item.id == 0L || poster.isEmpty() || title.isEmpty()) return null

        return Media(
            id = item.id.toInt(),
            type = type,
            title = title,
            orig = item.originalTitle ?: item.originalName ?: "",
            poster = poster,
            backdrop = img(item.backdropPath, "w1280"),
            score = kotlin.math.round(item.voteAverage * 10) / 10,
            year = (item.releaseDate ?: item.firstAirDate ?: "").take(4),
        )
    }

    private fun normList(resp: MediaResponse, needBackdrop: Boolean = false, limit: Int = 12): List<Media> {
        val out = mutableListOf<Media>()
        for (raw in resp.results) {
            if (raw.mediaType == "person") continue
            val m = norm(raw) ?: continue
            if (needBackdrop && m.backdrop.isEmpty()) continue
            out += m
            if (out.size >= limit) break
        }
        return out
    }

    /** 首页轮播（周趋势，需背景图） */
    suspend fun heroList(): List<Media> = normList(api.trendingWeek(1), needBackdrop = true, limit = 5)

    suspend fun trendingRow(): List<Media> = normList(api.trendingWeek(1))
    suspend fun movieRow(): List<Media> = normList(api.moviePopular(1))
    suspend fun tvRow(): List<Media> = normList(api.tvPopular(1))
    suspend fun animeRow(): List<Media> = normList(api.discoverTv("16", "popularity.desc", 1))

    /** 分类页：type = movie / tv / variety / anime */
    suspend fun category(type: String, page: Int): Pair<List<Media>, Int> {
        val resp = when (type) {
            "tv" -> api.discoverTv("", "popularity.desc", page)
            "variety" -> api.discoverTv("10764,10767", "popularity.desc", page)
            "anime" -> api.discoverTv("16", "popularity.desc", page)
            else -> api.discoverMovie("popularity.desc", page)
        }
        return normList(resp, limit = 24) to minOf(resp.totalPages, 500)
    }

    /** 搜索 */
    suspend fun search(wd: String, page: Int): Pair<List<Media>, Int> {
        val resp = api.search(wd, page)
        return normList(resp, limit = 24) to minOf(resp.totalPages, 500)
    }

    /** 详情原始响应（供分季展开等复用） */
    suspend fun tvRaw(id: Int): DetailResponse? = try {
        api.tvDetail(id, "zh-CN")
    } catch (e: Exception) {
        null
    }

    /**
     * 搜索并展开分季卡片：
     * TV 多季 → 每季一张卡（使用各季独立海报+年份）
     * 电影 / 单季剧 → 一张主卡
     */
    suspend fun searchCards(wd: String, page: Int): Pair<List<SearchCard>, Int> {
        val (list, totalPages) = search(wd, page)
        val cards = mutableListOf<SearchCard>()
        coroutineScope {
            // 分批并发（每批6个，避免触发TMDB限流）
            list.chunked(6).forEach { batch ->
                val results = batch.map { m ->
                    async {
                        if (m.type != "tv") return@async listOf(SearchCard(m))
                        val resp = runCatching { api.tvDetail(m.id, "zh-CN") }.getOrNull()
                        val seasons = resp?.seasons
                            ?.filter { it.seasonNumber > 0 && it.episodeCount > 0 }
                            ?: emptyList()
                        if (seasons.size > 1) {
                            seasons.map { s ->
                                SearchCard(
                                    media = m,
                                    season = s.seasonNumber,
                                    seasonName = s.name ?: "第 ${s.seasonNumber} 季",
                                    posterOverride = img(s.posterPath, "w500").ifEmpty { m.poster },
                                    yearOverride = (s.airDate ?: "").take(4),
                                )
                            }
                        } else {
                            listOf(SearchCard(m))
                        }
                    }
                }.awaitAll()
                results.forEach { cards += it }
            }
        }
        return cards to totalPages
    }

    /** 详情（中文简介缺失时英文兜底；TV 返回季列表） */
    suspend fun detail(type: String, id: Int): DetailResponse? = try {
        val zh = if (type == "tv") api.tvDetail(id, "zh-CN") else api.movieDetail(id, "zh-CN")
        if (zh.overview.isNullOrBlank()) {
            val en = if (type == "tv") api.tvDetail(id, "en-US") else api.movieDetail(id, "en-US")
            if (!en.overview.isNullOrBlank()) zh.copy(overview = en.overview) else zh
        } else zh
    } catch (e: Exception) {
        null
    }

    /** 季详情（中文简介缺失时英文兜底） */
    suspend fun season(tvId: Int, season: Int): SeasonResponse? = try {
        val zh = api.season(tvId, season, "zh-CN")
        if (zh.overview.isNullOrBlank()) {
            val en = api.season(tvId, season, "en-US")
            if (!en.overview.isNullOrBlank()) zh.copy(overview = en.overview) else zh
        } else zh
    } catch (e: Exception) {
        null
    }
}
