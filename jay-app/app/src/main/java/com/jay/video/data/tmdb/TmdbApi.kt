package com.jay.video.data.tmdb

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/** TMDB REST API（api_key / language 由 OkHttp 拦截器统一附加） */
interface TmdbApi {

    @GET("trending/all/week")
    suspend fun trendingWeek(@Query("page") page: Int): MediaResponse

    @GET("movie/popular")
    suspend fun moviePopular(@Query("page") page: Int): MediaResponse

    @GET("tv/popular")
    suspend fun tvPopular(@Query("page") page: Int): MediaResponse

    @GET("discover/movie")
    suspend fun discoverMovie(
        @Query("sort_by") sortBy: String,
        @Query("page") page: Int,
    ): MediaResponse

    @GET("discover/tv")
    suspend fun discoverTv(
        @Query("with_genres") genres: String,
        @Query("sort_by") sortBy: String,
        @Query("page") page: Int,
    ): MediaResponse

    @GET("search/multi")
    suspend fun search(
        @Query("query") query: String,
        @Query("page") page: Int,
    ): MediaResponse

    @GET("movie/{id}")
    suspend fun movieDetail(
        @Path("id") id: Int,
        @Query("language") language: String,
    ): DetailResponse

    @GET("tv/{id}")
    suspend fun tvDetail(
        @Path("id") id: Int,
        @Query("language") language: String,
    ): DetailResponse

    @GET("tv/{id}/season/{n}")
    suspend fun season(
        @Path("id") id: Int,
        @Path("n") season: Int,
        @Query("language") language: String,
    ): SeasonResponse
}

/* ---------- 响应模型 ---------- */

data class MediaResponse(
    val page: Int = 0,
    @SerializedName("total_pages") val totalPages: Int = 0,
    @SerializedName("total_results") val totalResults: Int = 0,
    val results: List<RawItem> = emptyList(),
)

data class RawItem(
    val id: Long = 0,
    @SerializedName("media_type") val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    @SerializedName("original_title") val originalTitle: String? = null,
    @SerializedName("original_name") val originalName: String? = null,
    @SerializedName("poster_path") val posterPath: String? = null,
    @SerializedName("backdrop_path") val backdropPath: String? = null,
    @SerializedName("vote_average") val voteAverage: Double = 0.0,
    @SerializedName("release_date") val releaseDate: String? = null,
    @SerializedName("first_air_date") val firstAirDate: String? = null,
)

data class DetailResponse(
    val id: Long = 0,
    val title: String? = null,
    val name: String? = null,
    @SerializedName("original_title") val originalTitle: String? = null,
    @SerializedName("original_name") val originalName: String? = null,
    @SerializedName("poster_path") val posterPath: String? = null,
    @SerializedName("backdrop_path") val backdropPath: String? = null,
    @SerializedName("vote_average") val voteAverage: Double = 0.0,
    @SerializedName("release_date") val releaseDate: String? = null,
    @SerializedName("first_air_date") val firstAirDate: String? = null,
    val overview: String? = null,
    val runtime: Int? = null,
    @SerializedName("episode_run_time") val episodeRunTime: List<Int>? = null,
    @SerializedName("number_of_seasons") val numberOfSeasons: Int = 0,
    @SerializedName("number_of_episodes") val numberOfEpisodes: Int = 0,
    val genres: List<Genre>? = null,
    val seasons: List<RawSeason>? = null,
    val credits: Credits? = null,
)

data class Genre(val id: Long = 0, val name: String? = null)

data class RawSeason(
    @SerializedName("season_number") val seasonNumber: Int = 0,
    val name: String? = null,
    @SerializedName("episode_count") val episodeCount: Int = 0,
    @SerializedName("poster_path") val posterPath: String? = null,
    val overview: String? = null,
)

data class Credits(val cast: List<RawCast>? = null)

data class RawCast(
    val id: Long = 0,
    val name: String? = null,
    val character: String? = null,
    @SerializedName("profile_path") val profilePath: String? = null,
)

data class SeasonResponse(
    @SerializedName("season_number") val seasonNumber: Int = 0,
    val name: String? = null,
    val overview: String? = null,
    @SerializedName("poster_path") val posterPath: String? = null,
    val episodes: List<RawEpisode>? = null,
)

data class RawEpisode(
    @SerializedName("episode_number") val episodeNumber: Int = 0,
    val name: String? = null,
    val overview: String? = null,
    @SerializedName("still_path") val stillPath: String? = null,
)
