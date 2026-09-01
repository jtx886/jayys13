package com.jay.video.ui.player

import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Language
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
import com.jay.video.data.source.Prefs
import com.jay.video.data.source.SpiderLoader
import com.jay.video.data.source.VodItem
import com.jay.video.ui.theme.Bg
import com.jay.video.ui.theme.Bg2
import com.jay.video.ui.theme.Primary
import com.jay.video.ui.theme.Text1
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
        val headers: Map<String, String> = emptyMap(),
        val webOnly: Boolean = false,          // 需解析（影视仓 needParse）
        val webMode: Boolean = false,          // 当前为网页播放模式
        val pageUrl: String = "",              // 待解析的原始地址（网页播放用）
        val parseUrl: String = "",             // 解析前缀（spider playUrl，空则用用户解析器）
        val sniffing: Boolean = false,         // 影视仓 ParseJob 嗅探中
        val playErr: String = "",
        val playerErr: String = "",
        val switching: Boolean = false,        // 正在自动换源
        val attempt: Int = 0,                  // 重新装载计数（强制刷新播放器）
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

    /** 网页播放地址（影视仓 ParseJob：解析器 + 视频页地址 raw 拼接，不编码） */
    fun webPlayUrl(): String {
        val s = _state.value
        val prefix = s.parseUrl.ifEmpty { Prefs.parseUrl() }
        val target = s.pageUrl.ifEmpty { s.currentUrl }
        return prefix + target
    }

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

    /** 应用解析结果到状态 */
    private fun applyResult(r: com.jay.video.data.PlayResult) {
        if (r.ok) {
            _state.value = _state.value.copy(
                episodes = r.episodes,
                currentUrl = if (r.webOnly) "" else r.url,  // 需解析：嗅探成功后才设置真实地址
                pageUrl = r.url,
                parseUrl = r.parseUrl,
                currentLabel = r.label,
                sourceName = r.sourceName,
                siteKey = r.siteKey,
                headers = r.headers,
                webOnly = r.webOnly,
                webMode = false,
                playerErr = "",
                playErr = "",
                switching = false,
                attempt = _state.value.attempt + 1,
            )
        } else {
            _state.value = _state.value.copy(switching = false, playErr = r.err)
        }
    }

    /**
     * 影视仓 ParseJob 流程：隐藏 WebView 嗅探真实播放地址
     * 成功 → 系统播放器播放；失败 → 网页播放兜底（ffzyplay）
     * 由 UI 层触发（需要 Activity 上下文挂载 WebView）
     */
    fun startSniff(context: android.content.Context) {
        val s = _state.value
        if (!s.webOnly || s.pageUrl.isEmpty() || s.sniffing || s.webMode) return
        _state.value = s.copy(sniffing = true, playerErr = "")

        // 解析前缀：spider playUrl 优先（影视仓 ParseJob.setParse），否则用户解析器（ffzyplay）
        val prefix = s.parseUrl.ifEmpty { Prefs.parseUrl() }

        // spider 站点用其自定义视频判定（影视仓 CustomWebView.isVideoFormat）
        val site = App.source.siteOf(s.siteKey)
        val videoCheck: ((String) -> Boolean)? =
            if (App.source.isSpiderSite(site) && site != null) {
                { url -> SpiderLoader.isVideoFormat(site.key, site.jarUrl, site.api, site.ext, url) }
            } else {
                null
            }

        com.jay.video.player.ParseSniffer.sniff(context, prefix, s.pageUrl, s.headers, videoCheck) { result ->
            if (result.ok) onSniffOk(result.url, result.headers) else onSniffFail()
        }
    }

    /** 嗅探成功：系统播放器播放真实地址 */
    private fun onSniffOk(url: String, headers: Map<String, String>) {
        val s = _state.value
        if (!s.sniffing) return
        _state.value = s.copy(
            sniffing = false,
            currentUrl = url,
            headers = if (headers.isEmpty()) s.headers else headers,
            attempt = s.attempt + 1,
        )
    }

    /** 嗅探失败：网页播放兜底（ffzyplay 可视化播放） */
    private fun onSniffFail() {
        val s = _state.value
        if (!s.sniffing) return
        _state.value = s.copy(sniffing = false, webMode = true)
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
        viewModelScope.launch {
            val r = App.source.directResult(item, data.episode, directLineIdx)
            applyResult(r)
        }
    }

    /** 开始重新解析：清空旧播放状态（避免嗅探不触发/播放旧地址） */
    private fun beginResolve(s: UiState, ep: Int) {
        _state.value = s.copy(
            episode = ep,
            currentUrl = "",
            pageUrl = "",
            parseUrl = "",
            webOnly = false,
            webMode = false,
            sniffing = false,
            playerErr = "",
            playErr = "",
            switching = true,
        )
    }

    /** 解析指定季+集的播放地址（自动换源时排除已失败站点） */
    private fun resolve(season: Int, episode: Int) {
        val s = _state.value
        val title = s.title
        if (title.isEmpty()) return
        _state.value = _state.value.copy(season = season)
        beginResolve(_state.value, episode)
        viewModelScope.launch {
            val r = App.source.resolveAny(title, episode, season, excludeKeys = failedSites)
            applyResult(r)
        }
    }

    fun selectEpisode(ep: Int) {
        val s = _state.value
        val eps = s.episodes

        // 直连模式：重新解析（spider 站点需调 playerContent）
        if (type == "direct") {
            val item = directItem
            if (item != null) {
                beginResolve(s, ep)
                viewModelScope.launch {
                    val r = App.source.directResult(item, ep, directLineIdx)
                    applyResult(r)
                }
                return
            }
        }

        if (eps.isEmpty()) { resolve(s.season, ep); return }
        val idx = ep - 1
        if (idx < 0 || idx >= eps.size) return

        // spider 站点的剧集 url 是 playerContent id，需重新解析
        val site = App.source.siteOf(s.siteKey)
        if (App.source.isSpiderSite(site)) {
            beginResolve(s, ep)
            viewModelScope.launch {
                val r = App.source.resolveAny(
                    s.title, ep, s.season,
                    preferredKey = s.siteKey, excludeKeys = emptySet(),
                )
                applyResult(r)
            }
            return
        }

        _state.value = s.copy(
            episode = ep,
            currentUrl = eps[idx].url,
            pageUrl = eps[idx].url,
            parseUrl = "",
            webOnly = false,
            currentLabel = eps[idx].name.ifEmpty { "第${ep}集" },
            playerErr = "",
            webMode = false,
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

    /** 切换 系统播放 / 网页播放 */
    fun toggleWebMode() {
        val s = _state.value
        if (s.currentUrl.isEmpty()) return
        _state.value = s.copy(webMode = !s.webMode, playerErr = "")
    }

    /** 播放器出错回调：自动换源（最多3次） */
    fun onPlayerFailed() {
        val s = _state.value
        if (s.currentUrl.isEmpty() || s.webMode) return
        failCount++

        if (type == "direct") {
            // 直连模式：自动切换线路
            val item = directItem
            val lines = if (item != null) App.source.playLines(item) else emptyList()
            if (failCount <= 2 && lines.size > 1) {
                switchDirectLine()
            } else {
                _state.value = s.copy(playerErr = "播放失败，可尝试网页播放或切换线路")
            }
            return
        }

        if (failCount > 3) {
            _state.value = s.copy(playerErr = "多个播放源均失败，可尝试网页播放")
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
        beginResolve(_state.value, _state.value.episode)
        viewModelScope.launch {
            val r = App.source.directResult(item, _state.value.episode, directLineIdx)
            applyResult(r)
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
                beginResolve(_state.value, _state.value.episode)
                viewModelScope.launch {
                    val r = App.source.directResult(item, _state.value.episode, directLineIdx)
                    applyResult(r)
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

    // ExoPlayer 数据源工厂（headers 可动态更新）
    val dsFactory = remember {
        DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14) JayVideo/1.0")
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(15000)
            .setAllowCrossProtocolRedirects(true)
    }

    // ExoPlayer 实例
    val player = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dsFactory))
            .build()
    }

    var resumed by remember { mutableStateOf(false) }

    // headers 变化 → 更新数据源默认请求头
    LaunchedEffect(state.headers) {
        dsFactory.setDefaultRequestProperties(state.headers)
    }

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

    // 定时保存进度（网页播放模式暂停系统播放器）
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            if (player.isPlaying && !state.webMode) {
                vm.saveProgress(player.currentPosition, player.duration)
            }
        }
    }

    // 网页模式切换：暂停/恢复系统播放器
    LaunchedEffect(state.webMode) {
        if (state.webMode) player.pause() else if (state.currentUrl.isNotEmpty()) player.play()
    }

    // 影视仓 ParseJob：需解析的地址触发嗅探（成功→系统播放器，失败→网页播放）
    LaunchedEffect(state.webOnly, state.pageUrl, state.attempt) {
        if (state.webOnly && state.pageUrl.isNotEmpty() && !state.webMode && state.currentUrl.isEmpty()) {
            vm.startSniff(context)
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
        // 播放器区域（系统 / 网页 切换）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black),
        ) {
            if (state.webMode) {
                // 网页播放（ffzyplay 解析器）
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.cacheMode = WebSettings.LOAD_DEFAULT
                            settings.userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
                            webViewClient = WebViewClient()
                            webChromeClient = WebChromeClient()
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        }
                    },
                    update = { wv ->
                        val target = vm.webPlayUrl()
                        if (wv.url != target) wv.loadUrl(target)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
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
            }
            // 返回按钮悬浮
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart).padding(2.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
            }
            // 影视仓嗅探解析中提示
            if (state.sniffing) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(28.dp),
                    )
                    Text(
                        "正在解析播放地址…",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            // 网页播放切换按钮（悬浮右上）
            if (state.currentUrl.isNotEmpty() || (state.webOnly && state.pageUrl.isNotEmpty())) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable { vm.toggleWebMode() }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Language, null, tint = Color.White, modifier = Modifier.size(13.dp))
                    Text(
                        if (state.webMode) " 系统播放" else " 网页播放",
                        color = Color.White,
                        fontSize = 11.sp,
                    )
                }
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
                    color = Text1,
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
                    "  正在解析播放地址…",
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
                    modifier = Modifier.weight(1f),
                )
                if (state.currentUrl.isNotEmpty() && !state.webMode) {
                    Text(
                        "网页播放",
                        color = Primary,
                        fontSize = 12.5.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Primary.copy(alpha = 0.12f))
                            .clickable { vm.toggleWebMode() }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
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
