package com.jay.video.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.jay.video.App
import com.jay.video.data.Media
import com.jay.video.ui.components.EmptyBox
import com.jay.video.ui.components.LoadingBox
import com.jay.video.ui.components.MediaCard
import com.jay.video.ui.components.SectionTitle
import com.jay.video.ui.theme.Bg
import com.jay.video.ui.theme.Panel
import com.jay.video.ui.theme.Primary
import com.jay.video.ui.theme.Primary2
import com.jay.video.ui.theme.Text1
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {
    data class UiState(
        val loading: Boolean = true,
        val error: String = "",
        val hero: List<Media> = emptyList(),
        val trending: List<Media> = emptyList(),
        val movies: List<Media> = emptyList(),
        val tvs: List<Media> = emptyList(),
        val animes: List<Media> = emptyList(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init { load() }

    fun load() {
        _state.value = UiState(loading = true)
        viewModelScope.launch {
            try {
                val hero = App.tmdb.heroList()
                val trending = App.tmdb.trendingRow()
                val movies = App.tmdb.movieRow()
                val tvs = App.tmdb.tvRow()
                val animes = App.tmdb.animeRow()
                _state.value = UiState(
                    loading = false,
                    hero = hero,
                    trending = trending,
                    movies = movies,
                    tvs = tvs,
                    animes = animes,
                )
            } catch (e: Exception) {
                _state.value = UiState(loading = false, error = e.message ?: "加载失败，请检查网络")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(onOpenDetail: (String, Int) -> Unit, vm: HomeViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    if (state.loading) {
        LoadingBox(Modifier.fillMaxSize())
        return
    }
    if (state.error.isNotEmpty() && state.hero.isEmpty()) {
        EmptyBox(state.error) { vm.load() }
        return
    }

    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 20.dp),
    ) {
        // 轮播
        if (state.hero.isNotEmpty()) {
            item("hero") {
                val pager = rememberPagerState(pageCount = { state.hero.size })
                Column {
                    HorizontalPager(
                        state = pager,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f),
                    ) { page ->
                        val m = state.hero[page]
                        HeroItem(m) { onOpenDetail(m.type, m.id) }
                    }
                    // 指示器
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        repeat(state.hero.size) { i ->
                            val active = pager.currentPage == i
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 3.dp)
                                    .size(width = if (active) 16.dp else 6.dp, height = 6.dp)
                                    .clip(CircleShape)
                                    .background(if (active) Primary else Color(0xFF2A3550)),
                            )
                        }
                    }
                }
            }
        }

        // 区块
        rowSection("本周趋势", state.trending, onOpenDetail)
        rowSection("热门电影", state.movies, onOpenDetail)
        rowSection("热门剧集", state.tvs, onOpenDetail)
        rowSection("热血动漫", state.animes, onOpenDetail)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.rowSection(
    title: String,
    list: List<Media>,
    onOpenDetail: (String, Int) -> Unit,
) {
    if (list.isEmpty()) return
    item(title) {
        SectionTitle(title, Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp))
    }
    item(title + "-row") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(list, key = { title + it.type + it.id }) { m ->
                MediaCard(m, { onOpenDetail(m.type, m.id) }, width = 110.dp)
            }
        }
    }
}

@Composable
private fun HeroItem(m: Media, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clickable { onClick() },
    ) {
        AsyncImage(
            model = m.backdrop.ifEmpty { m.poster },
            contentDescription = m.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // 底部渐变
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.55f to Color.Transparent,
                        1f to Color(0xE60A0E15),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
        ) {
            Text(
                m.title,
                color = Text1,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 6.dp),
            ) {
                if (m.score > 0) {
                    Icon(Icons.Filled.Star, null, tint = Color(0xFFFFC53D), modifier = Modifier.size(13.dp))
                    Text(
                        m.score.toString(),
                        color = Color(0xFFFFC53D),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 3.dp),
                    )
                }
                if (m.year.isNotEmpty()) {
                    Text(
                        " · ${m.year} · " + if (m.type == "tv") "剧集" else "电影",
                        color = Color(0xFFB8C2D4),
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(15.dp))
                        .background(Brush.horizontalGradient(listOf(Primary, Primary2)))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.PlayArrow, "播放", tint = Color.White, modifier = Modifier.size(14.dp))
                    Text("播放", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
