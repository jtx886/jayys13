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
)

data class PlaySource(
    val id: Int,
    val name: String,
    val apiUrl: String,
    val isDefault: Boolean = false,
)

object PlaySources {
    val ALL = listOf(
        PlaySource(1, "非凡影视", "https://api.yyzy-tv.vip/inc/apijson.php", isDefault = true),
        PlaySource(2, "量子影视", "https://cj.lziapi.com/api.php/provide/vod"),
    )
    val DEFAULT get() = ALL.first { it.isDefault }
}
