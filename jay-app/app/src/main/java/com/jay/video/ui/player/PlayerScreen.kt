package com.jay.video.ui.player

import android.view.ViewGroup
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.jay.video.App
import com.jay.video.data.DirectPlay
import com.jay.video.data.Episode
import com.jay.video.data.Season
import com.jay.video.data.source.VodItem
import com.jay.video.ui.theme.Bg
import com.jay.video.ui.theme.Bg2
import com.jay.video.ui.theme.Primary
import com.jay.video.ui.theme.Text2
import com.jay.video.ui.theme.Text3
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PlayerViewModel : ViewModel() {
    data class UiState(
        val loading: Boolean = true,
        val title: String = "",
        val poster: String = "",
        val seasons: List<Season> = emptyList(),
        val season: Int = 1,
        val episode: Int = 1,
        val episodes: List<Episode> = emptyList(),
        val currentUrl: String = "",
        val currentLabel: String = "",
        val sourceName: String = "",
        val siteKey: String = "",
        val playErr: String = "",
        val playerErr: String = "",
        val switching: Boolean = false,   // 正在自动换源
        val attempt: Int = 0,             // 重新装载计数（强制刷新播放器）
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var type = "movie"
    private var id = 0

    // 直连播放（资源站搜索 → 直接播放）
    private var directItem: VodItem? = null
    private var directLineIdx = -1

    // 自动换源
    private val failedSites = mutableSetOf<String>()
    private var failCount = 0

    /** 保存观看历史（供播放页调用；直连模式不入库） */
    fun saveProgress(positionMs: Long, durationMs: Long) {
        if (type == "direct") return
        val s = _state.value
        if (s.title.isEmpty() || s.currentUrl.isEmpty()) return
        if (positionMs <= 0) return
        viewModelScope.launch {
            try {
                App.db.historyDao().upsert(
                    com.jay.video.data.local.History(
                        mediaId = id,
                        mediaType = type,
                        title = s.title,
                        poster = s.poster,
                        season = s.season,
                        episode = s.episode,
                        episodeLabel = s.currentLabel,
                        positionMs = positionMs,
                        durationMs = if (durationMs < 0) 0 else durationMs,
                    ),
                )
            } catch (e: Exception) { /* 保存失败不阻塞播放 */ }
        }
    }

    fun load(t: String, i: Int, season: Int, episode: Int) {
        if (t == "direct") {
            type = "direct"
            id = 0
            loadDirect()
            return
        }
        if (type == t && id == i && !_state.value.loading && _state.value.episodes.isNotEmpty()) {
            // 已加载：直接切集
            if (_state.value.season != season) changeSeason(season, episode) else selectEpisode(episode)
            return
        }
        type = t
        id = i
        _state.value = UiState(loading = true, season = season, episode = episode)
        viewModelScope.launch {
            try {
                val resp = App.tmdb.detail(t, i)
                if (resp == null || resp.id == 0L) {
                    _state.value = _state.value.copy(loading = false, playErr = "详情加载失败")
                    return@launch
                }
                val seasons = (resp.seasons ?: emptyList())
                    .filter { it.seasonNumber > 0 && it.episodeCount > 0 }
                    .map { Season(it.seasonNumber, it.name ?: "第 ${it.seasonNumber} 季", it.episodeCount) }
                val title = resp.title ?: resp.name ?: ""
                val poster = com.jay.video.data.tmdb.TmdbRepo.img(resp.posterPath, "w300")
                _state.value = _state.value.copy(
                    loading = false,
                    title = title,
                    poster = poster,
                    seasons = seasons,
                )
                resolve(season, episode)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, playErr = e.message ?: "加载失败")
            }
        }
    }

    /** 直连播放模式：来自资源站搜索结果 */
    private fun loadDirect() {
        val data = DirectPlay.data
        if (data == null) {
            _state.value = UiState(loading = false, playErr = "播放数据已过期，请重新搜索")
            return
        }
        DirectPlay.data = null // 消费一次性数据
        val item = runCatching { App.gson.fromJson(data.itemJson, VodItem::class.java) }.getOrNull()
        if (item == null || item.play.isEmpty()) {
            _state.value = UiState(loading = false, title = data.title, poster = data.pic, playErr = "播放地址解析失败")
            return
        }
        directItem = item
        val lines = App.source.playLines(item)
        directLineIdx = if (data.lineIndex in lines.indices) {
            data.lineIndex
        } else {
            lines.indexOfFirst { l -> l.episodes.any { App.source.isDirectUrl(it.url) } }.takeIf { it >= 0 } ?: 0
        }
        _state.value = UiState(
            loading = false,
            title = data.title,
            poster = data.pic,
            episode = data.episode,
        )
        val r = App.source.directResult(item, data.episode, directLineIdx)
        if (r.ok) {
            _state.value = _state.value.copy(
                episodes = r.episodes,
                currentUrl = r.url,
                currentLabel = r.label,
                sourceName = r.sourceName,
                siteKey = r.siteKey,
                attempt = 1,
            )
        } else {
            _state.value = _state.value.copy(playErr = r.err)
        }
    }

    /** 解析指定季+集的播放地址（自动换源时排除已失败站点） */
    private fun resolve(season: Int, episode: Int) {
        val s = _state.value
        val title = s.title
        if (title.isEmpty()) return
        _state.value = s.copy(
            season = season, episode = episode,
            playErr = "", playerErr = "", currentUrl = "", switching = true,
        )
        viewModelScope.launch {
            val r = App.source.resolveAny(title, episode, season, excludeKeys = failedSites)
            if (r.ok) {
                _state.value = _state.value.copy(
                    switching = false,
                    episodes = r.episodes,
                    currentUrl = r.url,
                    currentLabel = r.label,
                    sourceName = r.sourceName,
                    siteKey = r.siteKey,
                    attempt = _state.value.attempt + 1,
                )
            } else {
                _state.value = _state.value.copy(switching = false, playErr = r.err)
            }
        }
    }

    fun selectEpisode(ep: Int) {
        val s = _state.value
        val eps = s.episodes
        if (eps.isEmpty()) { resolve(s.season, ep); return }
        val idx = ep - 1
        if (idx < 0 || idx >= eps.size) return
        _state.value = s.copy(
            episode = ep,
            currentUrl = eps[idx].url,
            currentLabel = eps[idx].name.ifEmpty { "第${ep}集" },
            playerErr = "",
            attempt = s.attempt + 1,
        )
    }

    fun changeSeason(season: Int, episode: Int = 1) {
        resolve(season, episode)
    }

    fun next() {
        val s = _state.value
        if (s.episode < s.episodes.size) selectEpisode(s.episode + 1)
    }

    fun prev() {
        val s = _state.value
        if (s.episode > 1) selectEpisode(s.episode - 1)
    }

    /** 播放器出错回调：自动换源（最多3次） */
    fun onPlayerFailed() {
        val s = _state.value
        if (s.currentUrl.isEmpty()) return
        failCount++

        if (type == "direct") {
            // 直连模式：自动切换线路
            val item = directItem
            val lines = if (item != null) App.source.playLines(item) else emptyList()
            if (failCount <= 2 && lines.size > 1) {
                switchDirectLine()
            } else {
                _state.value = s.copy(playerErr = "播放失败，点击切换线路重试")
            }
            return
        }

        if (failCount > 3) {
            _state.value = s.copy(playerErr = "多个播放源均失败，点击重试")
            return
        }
        // 记录失败站点，自动换源
        if (s.siteKey.isNotEmpty()) failedSites += s.siteKey
        resolve(s.season, s.episode)
    }

    /** 直连模式切换到下一条线路 */
    private fun switchDirectLine() {
        val item = directItem ?: return
        val lines = App.source.playLines(item)
        if (lines.isEmpty()) return
        directLineIdx = (directLineIdx + 1).mod(lines.size)
        val r = App.source.directResult(item, _state.value.episode, directLineIdx)
        if (r.ok) {
            _state.value = _state.value.copy(
                episodes = r.episodes,
                currentUrl = r.url,
                currentLabel = r.label,
                sourceName = r.sourceName,
                siteKey = r.siteKey,
                playerErr = "",
                playErr = "",
                switching = false,
                attempt = _state.value.attempt + 1,
            )
        } else {
            _state.value = _state.value.copy(playerErr = "播放失败，点击切换线路重试")
        }
    }

    /** 手动重试（用户点击） */
    fun retry() {
        failCount = 0
        if (type == "direct") {
            val item = directItem
            if (item != null) {
                val lines = App.source.playLines(item)
                if (lines.size > 1) {
                    switchDirectLine()
                    return
                }
                val r = App.source.directResult(item, _state.value.episode, directLineIdx)
                if (r.ok) {
                    _state.value = _state.value.copy(
                        currentUrl = r.url,
                        currentLabel = r.label,
                        playerErr = "",
                        playErr = "",
                        attempt = _state.value.attempt + 1,
                    )
                } else {
                    _state.value = _state.value.copy(playErr = r.err)
                }
                return
            }
            _state.value = _state.value.copy(playerErr = "播放数据已过期，请重新搜索")
            return
        }
        resolve(_state.value.season, _state.value.episode)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    type: String,
    id: Int,
    season: Int,
    episode: Int,
    onBack: () -> Unit,
    vm: PlayerViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    // ExoPlayer 实例
    val player = remember {
        val dsFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14) JayVideo/1.0")
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(15000)
            .setAllowCrossProtocolRedirects(true)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dsFactory))
            .build()
    }

    var resumed by remember { mutableStateOf(false) }

    // 播放地址变化 → 切换媒体源（attempt 变化时强制重载）
    LaunchedEffect(state.currentUrl, state.attempt) {
        val url = state.currentUrl
        if (url.isEmpty()) return@LaunchedEffect
        val item = MediaItem.Builder()
            .setUri(url)
            .apply {
                if (url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
            }
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    // 断点续播（仅首次，直连模式跳过）
    LaunchedEffect(state.currentUrl, state.episode) {
        if (type == "direct") return@LaunchedEffect
        if (state.currentUrl.isEmpty() || resumed) return@LaunchedEffect
        val h = App.db.historyDao().get(id, type)
        if (h != null && h.season == state.season && h.episode == state.episode && h.positionMs > 3000) {
            // 等待播放器就绪后 seek
            kotlinx.coroutines.withTimeoutOrNull(8000) {
                while (player.playbackState != Player.STATE_READY) delay(200)
            }
            if (h.durationMs <= 0 || h.positionMs < h.durationMs - 10000) {
                player.seekTo(h.positionMs)
            }
        }
        resumed = true
    }

    // 定时保存进度
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            if (player.isPlaying) vm.saveProgress(player.currentPosition, player.duration)
        }
    }

    // 播放状态监听（结束自动下一集 / 失败自动换源）
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) vm.next()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                vm.onPlayerFailed()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // 退出时保存 + 释放
    DisposableEffect(Unit) {
        onDispose {
            vm.saveProgress(player.currentPosition, player.duration)
            player.release()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        // 播放器区域
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black),
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            // 返回按钮悬浮
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart).padding(2.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
            }
        }

        // 标题栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.title.ifEmpty { "加载中…" },
                    color = com.jay.video.ui.theme.Text1,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        state.currentLabel.ifEmpty { null },
                        "第 ${state.season} 季".takeIf { state.seasons.size > 1 },
                        state.sourceName.ifEmpty { null },
                    ).joinToString(" · "),
                    color = Text3,
                    fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (state.episodes.size > 1) {
                IconButton(onClick = { vm.prev() }, enabled = state.episode > 1) {
                    Icon(Icons.Filled.KeyboardArrowLeft, "上一集", tint = if (state.episode > 1) Text2 else Text3)
                }
                IconButton(onClick = { vm.next() }, enabled = state.episode < state.episodes.size) {
                    Icon(Icons.Filled.KeyboardArrowRight, "下一集", tint = if (state.episode < state.episodes.size) Text2 else Text3)
                }
            }
        }

        // 自动换源中提示
        if (state.switching) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Bg2)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = Primary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp),
                )
                Text(
                    "  播放失败，正在自动换源…",
                    color = Text2,
                    fontSize = 12.5.sp,
                )
            }
        }

        // 错误提示
        if (state.playErr.isNotEmpty() || state.playerErr.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Bg2)
                    .clickable { vm.retry() }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Refresh, null, tint = Primary, modifier = Modifier.size(16.dp))
                Text(
                    "  ${(state.playerErr.ifEmpty { state.playErr })}，点击重试",
                    color = Text2,
                    fontSize = 12.5.sp,
                )
            }
        }

        // 季切换
        if (state.seasons.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowItems(state.seasons, key = { it.number }) { s ->
                    val active = s.number == state.season
                    Text(
                        s.name,
                        color = if (active) Color.White else Text2,
                        fontSize = 12.5.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(15.dp))
                            .background(if (active) Primary else Bg2)
                            .clickable { vm.changeSeason(s.number) }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
        }

        // 剧集网格
        Box(modifier = Modifier.weight(1f)) {
            when {
                state.episodes.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (state.playErr.isNotEmpty()) state.playErr else if (state.loading) "加载中…" else "暂无剧集",
                            color = Text3,
                            fontSize = 13.sp,
                        )
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.episodes.size) { i ->
                            val ep = state.episodes[i]
                            val active = i + 1 == state.episode
                            Text(
                                ep.name.ifEmpty { "第${i + 1}集" },
                                color = if (active) Color.White else Text2,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (active) Primary else Bg2)
                                    .clickable { vm.selectEpisode(i + 1) }
                                    .padding(horizontal = 4.dp, vertical = 10.dp)
                                    .fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}
