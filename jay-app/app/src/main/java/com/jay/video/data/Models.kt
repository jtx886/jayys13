package com.jay.video.data

/** 影视条目（卡片展示用） */
data class Media(
    val id: Int,
    val type: String,       // movie / tv
    val title: String,
    val orig: String,
    val poster: String,
    val backdrop: String,
    val score: Double,
    val year: String,
)

data class Season(
    val number: Int,
    val name: String,
    val episodeCount: Int,
    val poster: String = "",   // 该季海报
)

data class Cast(
    val id: Int,
    val name: String,
    val character: String,
    val profile: String,
)

data class MediaDetail(
    val id: Int,
    val type: String,
    val title: String,
    val orig: String,
    val poster: String,
    val backdrop: String,
    val score: Double,
    val year: String,
    val overview: String,
    val runtime: Int,
    val genres: List<String>,
    val seasons: List<Season>,
    val cast: List<Cast>,
)

data class Episode(
    val name: String,
    val url: String,
)

data class PlayResult(
    val ok: Boolean,
    val url: String = "",
    val label: String = "",
    val episodes: List<Episode> = emptyList(),
    val name: String = "",
    val err: String = "",
    val sourceName: String = "",
    val siteKey: String = "",
    val headers: Map<String, String> = emptyMap(),  // 播放请求头（spider playerContent 返回）
    val webOnly: Boolean = false,                   // 需网页解析播放（影视仓 needParse）
    val parseUrl: String = "",                      // 影视仓 playUrl（spider 指定解析前缀，空则用用户解析器）
)

/** 直连播放数据（资源站搜索 → 直接播放，跳过TMDB） */
data class DirectPlayData(
    val title: String,
    val pic: String,
    val note: String,
    val itemJson: String,      // VodItem 序列化（含播放串）
    val episode: Int = 1,
    val lineIndex: Int = -1,
)

/** 直连播放临时传递（避免超长URL进路由） */
object DirectPlay {
    @Volatile
    var data: DirectPlayData? = null
}

/** 搜索结果卡片（支持分季展开：season>0 表示具体某一季） */
data class SearchCard(
    val media: Media,
    val season: Int = 0,             // 0=整部 / >0=指定季
    val seasonName: String = "",     // "第 2 季"
    val posterOverride: String = "", // 季海报（空则用主海报）
    val yearOverride: String = "",   // 季年份
) {
    val displayTitle: String get() = if (season > 0 && seasonName.isNotEmpty()) "${media.title} $seasonName" else media.title
    val displayPoster: String get() = posterOverride.ifEmpty { media.poster }
    val displayYear: String get() = yearOverride.ifEmpty { media.year }
}
