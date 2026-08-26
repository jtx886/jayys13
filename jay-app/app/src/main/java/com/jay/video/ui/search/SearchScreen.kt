package com.jay.video.ui.search

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.jay.video.App
import com.jay.video.data.DirectPlay
import com.jay.video.data.DirectPlayData
import com.jay.video.data.SearchCard
import com.jay.video.data.source.PlayLine
import com.jay.video.data.source.VodItem
import com.jay.video.ui.components.EmptyBox
import com.jay.video.ui.components.LoadingBox
import com.jay.video.ui.theme.Bg
import com.jay.video.ui.theme.Bg2
import com.jay.video.ui.theme.Panel
import com.jay.video.ui.theme.Primary
import com.jay.video.ui.theme.Text1
import com.jay.video.ui.theme.Text2
import com.jay.video.ui.theme.Text3
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SearchViewModel : ViewModel() {
    data class UiState(
        // TMDB 影视库
        val keyword: String = "",
        val searching: Boolean = false,
        val searched: Boolean = false,
        val results: List<SearchCard> = emptyList(),
        val total: Int = 0,
        val page: Int = 1,
        val totalPages: Int = 1,
        val loadingMore: Boolean = false,
        // 资源站聚合
        val srcKeyword: String = "",
        val srcSearching: Boolean = false,
        val srcSearched: Boolean = false,
        val srcResults: List<Pair<String, List<VodItem>>> = emptyList(), // siteName to items
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    fun tmdbSearch(wd: String) {
        val kw = wd.trim()
        if (kw.isEmpty()) return
        _state.value = UiState(keyword = kw, searching = true)
        viewModelScope.launch {
            try {
                val (cards, totalPages) = App.tmdb.searchCards(kw, 1)
                _state.value = UiState(
                    keyword = kw,
                    searched = true,
                    results = cards,
                    total = cards.size,
                    page = 1,
                    totalPages = totalPages,
                )
            } catch (e: Exception) {
                _state.value = UiState(keyword = kw, searched = true)
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.searching || s.loadingMore || s.page >= s.totalPages || s.keyword.isEmpty()) return
        _state.value = s.copy(loadingMore = true)
        viewModelScope.launch {
            try {
                val (cards, totalPages) = App.tmdb.searchCards(s.keyword, s.page + 1)
                _state.value = _state.value.copy(
                    loadingMore = false,
                    results = _state.value.results + cards,
                    page = s.page + 1,
                    totalPages = totalPages,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loadingMore = false)
            }
        }
    }

    /** 资源站聚合搜索（结果增量更新） */
    fun sourceSearch(wd: String) {
        val kw = wd.trim()
        if (kw.isEmpty()) return
        _state.value = _state.value.copy(
            srcKeyword = kw,
            srcSearching = true,
            srcSearched = true,
            srcResults = emptyList(),
        )
        viewModelScope.launch {
            App.source.searchAll(kw) { site, results ->
                val cur = _state.value
                _state.value = cur.copy(srcResults = cur.srcResults + (site.name to results))
            }
            _state.value = _state.value.copy(srcSearching = false)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenDetail: (String, Int, Int) -> Unit,
    onDirectPlay: () -> Unit,
    vm: SearchViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var sheetItem by remember { mutableStateOf<VodItem?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Text1)
                }
            },
            title = {
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("搜索片名 / 明星 / 资源", fontSize = 14.sp, color = Text2) },
                    leadingIcon = { Icon(Icons.Filled.Search, null, tint = Text2) },
                    trailingIcon = {
                        if (input.isNotEmpty()) {
                            IconButton(onClick = { input = "" }) {
                                Icon(Icons.Filled.Close, "清空", tint = Text2)
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            if (tab == 0) vm.tmdbSearch(input) else vm.sourceSearch(input)
                        },
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Panel,
                        unfocusedContainerColor = Panel,
                        focusedTextColor = Text1,
                        unfocusedTextColor = Text1,
                        cursorColor = Primary,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
        )

        // Tab
        TabRow(
            selectedTabIndex = tab,
            containerColor = Bg,
            contentColor = Primary,
            indicator = { positions ->
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(positions[tab]),
                    color = Primary,
                )
            },
            divider = {},
        ) {
            listOf("影视库", "资源站").forEachIndexed { i, label ->
                Tab(
                    selected = tab == i,
                    onClick = {
                        tab = i
                        if (input.isNotBlank()) {
                            if (i == 0 && !state.searched) vm.tmdbSearch(input)
                            if (i == 1 && !state.srcSearched) vm.sourceSearch(input)
                        }
                    },
                ) {
                    Text(
                        label,
                        color = if (tab == i) Primary else Text2,
                        fontSize = 14.sp,
                        fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
        }

        // 内容
        when (tab) {
            0 -> TmdbTab(state, onOpenDetail) { vm.loadMore() }
            1 -> SourceTab(state) { sheetItem = it }
        }
    }

    // 资源详情弹层
    sheetItem?.let { item ->
        SourceDetailSheet(
            item = item,
            onDismiss = { sheetItem = null },
            onPlay = { vod, episode, lineIndex ->
                DirectPlay.data = DirectPlayData(
                    title = vod.name,
                    pic = vod.pic,
                    note = vod.note,
                    itemJson = App.gson.toJson(vod),
                    episode = episode,
                    lineIndex = lineIndex,
                )
                sheetItem = null
                onDirectPlay()
            },
        )
    }
}

/* ---------- Tab 1：TMDB 影视库（分季卡片） ---------- */

@Composable
private fun TmdbTab(
    state: SearchViewModel.UiState,
    onOpenDetail: (String, Int, Int) -> Unit,
    onLoadMore: () -> Unit,
) {
    when {
        state.searching -> LoadingBox(Modifier.fillMaxSize())
        !state.searched -> EmptyBox("输入片名开始探索吧")
        state.results.isEmpty() -> EmptyBox("未找到相关影视内容")
        else -> {
            Text(
                "关键词「${state.keyword}」找到 ${state.total} 个结果",
                color = Text2,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(state.results, key = { it.media.type + it.media.id + "-s" + it.season }) { c ->
                    SearchCardItem(c) { onOpenDetail(c.media.type, c.media.id, c.season) }
                }
                if (state.page < state.totalPages) {
                    item("more") {
                        Text(
                            if (state.loadingMore) "加载中…" else "点击加载更多",
                            color = Primary,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onLoadMore() }
                                .padding(vertical = 16.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/** 搜索卡片（含分季标识） */
@Composable
private fun SearchCardItem(c: SearchCard, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable { onClick() }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(Panel),
        ) {
            AsyncImage(
                model = c.displayPoster,
                contentDescription = c.displayTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // 季标识角标
            if (c.season > 0) {
                Text(
                    c.seasonName,
                    color = Color.White,
                    fontSize = 9.5.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Primary.copy(alpha = 0.9f))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
            // 年份
            if (c.displayYear.isNotEmpty()) {
                Text(
                    c.displayYear,
                    color = Color.White,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0x66000000))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
            // 评分
            if (c.media.score > 0) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xB3000000))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Star, null, tint = Color(0xFFFFC53D), modifier = Modifier.size(10.dp))
                    Text(
                        c.media.score.toString(),
                        color = Color(0xFFFFC53D),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                }
            }
        }
        Text(
            c.displayTitle,
            color = Text1,
            fontSize = 12.5.sp,
            lineHeight = 17.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/* ---------- Tab 2：资源站聚合搜索 ---------- */

@Composable
private fun SourceTab(
    state: SearchViewModel.UiState,
    onItemClick: (VodItem) -> Unit,
) {
    when {
        !state.srcSearched -> EmptyBox("输入片名，聚合搜索全部播放源")
        state.srcSearching && state.srcResults.isEmpty() -> LoadingBox(Modifier.fillMaxSize())
        state.srcResults.all { it.second.isEmpty() } && !state.srcSearching -> EmptyBox("所有源均未找到该片")
        else -> {
            val flat = state.srcResults.flatMap { it.second }
            Column(Modifier.fillMaxSize()) {
                Text(
                    buildString {
                        append("「${state.srcKeyword}」")
                        append(state.srcResults.count { it.second.isNotEmpty() })
                        append("个源 / ")
                        append(flat.size)
                        append("条结果")
                        if (state.srcSearching) append(" · 搜索中…")
                    },
                    color = Text2,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(flat, key = { it.siteKey + it.id + it.name }) { vod ->
                        VodCardItem(vod) { onItemClick(vod) }
                    }
                }
            }
        }
    }
}

/** 资源站结果卡片 */
@Composable
private fun VodCardItem(v: VodItem, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable { onClick() }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(Panel),
        ) {
            if (v.pic.isNotEmpty() && v.pic.startsWith("http")) {
                AsyncImage(
                    model = v.pic,
                    contentDescription = v.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Text3, modifier = Modifier.size(30.dp))
                }
            }
            // 站点角标
            if (v.siteName.isNotEmpty()) {
                Text(
                    v.siteName,
                    color = Color.White,
                    fontSize = 9.5.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xCC1E2941))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
            // 备注角标（集数/清晰度）
            if (v.note.isNotEmpty()) {
                Text(
                    v.note,
                    color = Color.White,
                    fontSize = 9.5.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xB3000000))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            v.name,
            color = Text1,
            fontSize = 12.5.sp,
            lineHeight = 17.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (v.year.isNotEmpty()) {
            Text(v.year, color = Text3, fontSize = 10.5.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/* ---------- 资源详情弹层（线路 + 选集） ---------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceDetailSheet(
    item: VodItem,
    onDismiss: () -> Unit,
    onPlay: (VodItem, Int, Int) -> Unit,
) {
    val lines = remember(item) { App.source.playLines(item) }
    var lineIdx by remember(item) {
        mutableIntStateOf(lines.indexOfFirst { l -> l.episodes.any { App.source.isDirectUrl(it.url) } }.takeIf { it >= 0 } ?: 0)
    }
    val line = lines.getOrNull(lineIdx) ?: PlayLine("线路", emptyList())

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Bg2,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
        ) {
            // 头部
            Row {
                AsyncImage(
                    model = item.pic.takeIf { it.startsWith("http") },
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(84.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Panel),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp, top = 2.dp),
                ) {
                    Text(
                        item.name,
                        color = Text1,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.note.isNotEmpty()) {
                        Text(item.note, color = Primary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                    Text(
                        listOfNotNull(
                            item.siteName.ifEmpty { null },
                            item.year.ifEmpty { null },
                        ).joinToString(" · "),
                        color = Text3,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // 线路选择
            if (lines.size > 1) {
                Text("播放线路", color = Text2, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowItems(lines) { l ->
                        val i = lines.indexOf(l)
                        val active = i == lineIdx
                        Text(
                            l.label,
                            color = if (active) Color.White else Text2,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(15.dp))
                                .background(if (active) Primary else Panel)
                                .clickable { lineIdx = i }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            // 选集
            Text(
                "选集（${line.episodes.size}集）",
                color = Text2,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (line.episodes.isEmpty()) {
                Text("该线路无播放地址", color = Text3, fontSize = 12.sp)
            } else {
                // 网格（每行4个）
                line.episodes.chunked(4).forEachIndexed { rowIdx, rowEps ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowEps.forEachIndexed { colIdx, ep ->
                            val globalIdx = rowIdx * 4 + colIdx
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Panel)
                                    .clickable { onPlay(item, globalIdx + 1, lineIdx) }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    ep.name.ifEmpty { "第${globalIdx + 1}集" },
                                    color = Text1,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        // 补齐空位
                        repeat(4 - rowEps.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}
