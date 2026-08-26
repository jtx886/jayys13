package com.jay.video.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.jay.video.App
import com.jay.video.data.Episode
import com.jay.video.data.MediaDetail
import com.jay.video.data.PlayResult
import com.jay.video.data.Season
import com.jay.video.data.local.Favorite
import com.jay.video.data.source.Site
import com.jay.video.data.tmdb.TmdbRepo
import com.jay.video.ui.components.LoadingBox
import com.jay.video.ui.components.SectionTitle
import com.jay.video.ui.theme.Bg
import com.jay.video.ui.theme.Bg2
import com.jay.video.ui.theme.BorderC
import com.jay.video.ui.theme.Panel
import com.jay.video.ui.theme.Primary
import com.jay.video.ui.theme.Primary2
import com.jay.video.ui.theme.Text1
import com.jay.video.ui.theme.Text2
import com.jay.video.ui.theme.Text3
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DetailViewModel : ViewModel() {
    data class UiState(
        val loading: Boolean = true,
        val error: String = "",
        val detail: MediaDetail? = null,
        val season: Int = 1,
        val resolving: Boolean = false,
        val play: PlayResult? = null,
        val playErr: String = "",
        val favored: Boolean = false,
        val resumeEpisode: Int = 1,
        val resumeLabel: String = "",
        val currentSiteKey: String = "",
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** 可选站点列表（换源用） */
    val sites: List<Site> get() = App.source.enabledSites()

    private var type = "movie"
    private var id = 0

    fun load(t: String, i: Int, initialSeason: Int = 0) {
        if (type == t && id == i && _state.value.detail != null) return
        type = t
        id = i
        _state.value = UiState(loading = true)
        viewModelScope.launch {
            try {
                val resp = App.tmdb.detail(t, i)
                if (resp == null || resp.id == 0L) {
                    _state.value = UiState(loading = false, error = "加载失败，请重试")
                    return@launch
                }
                val seasons = (resp.seasons ?: emptyList())
                    .filter { it.seasonNumber > 0 && it.episodeCount > 0 }
                    .map {
                        Season(
                            number = it.seasonNumber,
                            name = it.name ?: "第 ${it.seasonNumber} 季",
                            episodeCount = it.episodeCount,
                            poster = TmdbRepo.img(it.posterPath, "w300"),
                        )
                    }
                val runtime = resp.runtime ?: resp.episodeRunTime?.firstOrNull() ?: 0
                val detail = MediaDetail(
                    id = resp.id.toInt(),
                    type = t,
                    title = resp.title ?: resp.name ?: "",
                    orig = resp.originalTitle ?: resp.originalName ?: "",
                    poster = TmdbRepo.img(resp.posterPath, "w500"),
                    backdrop = TmdbRepo.img(resp.backdropPath, "w1280"),
                    score = kotlin.math.round(resp.voteAverage * 10) / 10,
                    year = (resp.releaseDate ?: resp.firstAirDate ?: "").take(4),
                    overview = resp.overview ?: "暂无简介",
                    runtime = runtime,
                    genres = resp.genres?.mapNotNull { it.name } ?: emptyList(),
                    seasons = seasons,
                    cast = resp.credits?.cast?.take(15)?.map {
                        com.jay.video.data.Cast(
                            it.id.toInt(),
                            it.name ?: "",
                            it.character ?: "",
                            TmdbRepo.img(it.profilePath, "w185"),
                        )
                    } ?: emptyList(),
                )
                val favored = App.db.favoriteDao().count(id, type) > 0
                val history = App.db.historyDao().get(id, type)
                val initSeason = initialSeason
                    .takeIf { s -> s > 0 && seasons.any { it.number == s } }
                    ?: history?.season?.takeIf { s -> seasons.any { it.number == s } }
                    ?: 1
                _state.value = UiState(
                    loading = false,
                    detail = detail,
                    season = initSeason,
                    favored = favored,
                    resumeEpisode = history?.episode ?: 1,
                    resumeLabel = history?.episodeLabel ?: "",
                )
                resolve(initSeason, history?.episode ?: 1)
            } catch (e: Exception) {
                _state.value = UiState(loading = false, error = e.message ?: "加载失败")
            }
        }
    }

    /** 解析当前季的播放资源（优先上次成功站点） */
    fun resolve(season: Int, episode: Int = 1, siteKey: String? = null) {
        val d = _state.value.detail ?: return
        val preferred = siteKey ?: _state.value.currentSiteKey.ifEmpty { null }
        _state.value = _state.value.copy(season = season, resolving = true, play = null, playErr = "")
        viewModelScope.launch {
            val r = App.source.resolveAny(d.title, episode, season, preferred)
            _state.value = if (r.ok) {
                _state.value.copy(resolving = false, play = r, currentSiteKey = r.siteKey)
            } else {
                _state.value.copy(resolving = false, playErr = r.err)
            }
        }
    }

    /** 换源：用指定站点重新解析 */
    fun changeSite(site: Site) {
        resolve(_state.value.season, _state.value.resumeEpisode, site.key)
    }

    fun toggleFavorite() {
        val d = _state.value.detail ?: return
        viewModelScope.launch {
            val dao = App.db.favoriteDao()
            if (_state.value.favored) {
                dao.delete(d.id, d.type)
                _state.value = _state.value.copy(favored = false)
            } else {
                dao.insert(
                    Favorite(
                        mediaId = d.id,
                        mediaType = d.type,
                        title = d.title,
                        poster = d.poster,
                        score = d.score,
                        year = d.year,
                    ),
                )
                _state.value = _state.value.copy(favored = true)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    type: String,
    id: Int,
    initialSeason: Int = 0,
    onBack: () -> Unit,
    onPlay: (String, Int, Int, Int) -> Unit,
    vm: DetailViewModel = viewModel(),
) {
    LaunchedEffect(type, id, initialSeason) { vm.load(type, id, initialSeason) }
    val state by vm.state.collectAsStateWithLifecycle()
    var showSiteSheet by remember { mutableStateOf(false) }

    if (state.loading) {
        LoadingBox(Modifier.fillMaxSize())
        return
    }
    val d = state.detail
    if (d == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(state.error.ifEmpty { "加载失败" }, color = Text2)
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 30.dp)) {
            // 背景图 + 操作按钮
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f),
            ) {
                AsyncImage(
                    model = d.backdrop.ifEmpty { d.poster },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color(0x880A0E15),
                                1f to Bg,
                            ),
                        ),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { vm.toggleFavorite() }) {
                        Icon(
                            if (state.favored) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            "收藏",
                            tint = if (state.favored) Primary else Color.White,
                        )
                    }
                }
            }

            // 海报 + 基本信息
            Row(modifier = Modifier.padding(horizontal = 16.dp)) {
                AsyncImage(
                    model = d.poster,
                    contentDescription = d.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(104.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Panel),
                )
                Column(modifier = Modifier.padding(start = 14.dp, top = 4.dp)) {
                    Text(
                        d.title.ifEmpty { d.orig },
                        color = Text1,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        if (d.score > 0) {
                            Icon(Icons.Filled.Star, null, tint = Color(0xFFFFC53D), modifier = Modifier.size(14.dp))
                            Text(
                                d.score.toString(),
                                color = Color(0xFFFFC53D),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 3.dp),
                            )
                        }
                        Text(
                            buildString {
                                if (d.year.isNotEmpty()) append(" · ${d.year}")
                                if (d.type == "tv") {
                                    if (d.seasons.size == 1 && d.seasons.firstOrNull()?.episodeCount ?: 0 > 0) {
                                        append(" · ${d.seasons.first().episodeCount}集")
                                    } else if (d.seasons.isNotEmpty()) {
                                        append(" · ${d.seasons.size}季")
                                    }
                                } else if (d.runtime > 0) {
                                    append(" · ${d.runtime}分钟")
                                }
                            },
                            color = Text2,
                            fontSize = 12.5.sp,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                    if (d.genres.isNotEmpty()) {
                        Text(
                            d.genres.joinToString(" / "),
                            color = Text3,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }

            // 播放按钮 + 换源按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Brush.horizontalGradient(listOf(Primary, Primary2)))
                        .clickable {
                            if (!state.resolving) onPlay(d.type, d.id, state.season, state.resumeEpisode)
                        }
                        .padding(vertical = 13.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.resolving) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Text("  正在匹配播放源…", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    } else {
                        Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        val playLabel = if (state.resumeLabel.isNotEmpty()) "继续播放 · ${state.resumeLabel}" else "立即播放"
                        Text("  $playLabel", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
                // 换源按钮
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Bg2)
                        .border(1.dp, BorderC, RoundedCornerShape(10.dp))
                        .clickable { showSiteSheet = true }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Filled.SwapHoriz, "换源", tint = Text2, modifier = Modifier.size(18.dp))
                    Text(
                        state.play?.sourceName?.take(4)?.ifEmpty { "换源" } ?: "换源",
                        color = Text2,
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }
            }

            // 季切换（海报卡片）
            if (d.type == "tv" && d.seasons.size > 1) {
                SectionTitle("季切换", Modifier.padding(horizontal = 16.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(d.seasons, key = { it.number }) { s ->
                        val active = s.number == state.season
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .width(72.dp)
                                .clickable { vm.resolve(s.number, 1) },
                        ) {
                            AsyncImage(
                                model = s.poster.ifEmpty { d.poster },
                                contentDescription = s.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(2f / 3f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Panel)
                                    .border(
                                        width = if (active) 2.dp else 1.dp,
                                        color = if (active) Primary else BorderC,
                                        shape = RoundedCornerShape(8.dp),
                                    ),
                            )
                            Text(
                                s.name,
                                color = if (active) Primary else Text2,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 5.dp),
                            )
                        }
                    }
                }
            }

            // 剧集列表
            val eps = state.play?.episodes ?: emptyList()
            if (d.type == "tv" || eps.size > 1) {
                SectionTitle(
                    if (d.type == "tv") "剧集（${eps.size}）" else "播放列表",
                    Modifier.padding(horizontal = 16.dp),
                )
                if (state.resolving) {
                    Text(
                        "正在匹配资源…",
                        color = Text3,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                } else if (eps.isEmpty()) {
                    Text(
                        state.playErr.ifEmpty { "暂无可用资源" },
                        color = Text3,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                } else {
                    val rows = (eps.size + 3) / 4
                    val gridHeight = (rows * 44).dp
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .height(gridHeight),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        userScrollEnabled = false,
                    ) {
                        items(eps.size) { i ->
                            val ep = eps[i]
                            val active = i + 1 == state.resumeEpisode
                            Text(
                                ep.name.ifEmpty { "第${i + 1}集" },
                                color = if (active) Color.White else Text2,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (active) Primary else Bg2)
                                    .clickable { onPlay(d.type, d.id, state.season, i + 1) }
                                    .padding(horizontal = 6.dp, vertical = 10.dp)
                                    .fillMaxWidth(),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }

            // 简介
            SectionTitle("剧情简介", Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
            Text(
                d.overview,
                color = Text2,
                fontSize = 13.5.sp,
                lineHeight = 21.sp,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            // 演职员
            if (d.cast.isNotEmpty()) {
                SectionTitle("演职员", Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(d.cast, key = { it.id }) { c ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AsyncImage(
                                model = c.profile,
                                contentDescription = c.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(Panel),
                            )
                            Text(
                                c.name,
                                color = Text1,
                                fontSize = 11.5.sp,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            if (c.character.isNotEmpty()) {
                                Text(c.character, color = Text3, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    // 换源面板
    if (showSiteSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSiteSheet = false },
            containerColor = Bg2,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(
                    "选择播放源（${vm.sites.size}个可用）",
                    color = Text1,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                vm.sites.forEach { site ->
                    val active = site.key == state.currentSiteKey
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) Primary.copy(alpha = 0.12f) else Color.Transparent)
                            .clickable {
                                showSiteSheet = false
                                vm.changeSite(site)
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            site.name,
                            color = if (active) Primary else Text1,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (site.builtin) {
                            Text("内置", color = Text3, fontSize = 10.sp)
                        }
                        if (active) {
                            Text("当前", color = Primary, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
