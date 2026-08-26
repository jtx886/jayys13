package com.jay.video.ui.category

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jay.video.App
import com.jay.video.data.Media
import com.jay.video.ui.components.EmptyBox
import com.jay.video.ui.components.LoadingBox
import com.jay.video.ui.components.MediaCard
import com.jay.video.ui.theme.Bg2
import com.jay.video.ui.theme.Primary
import com.jay.video.ui.theme.Text2
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class CategoryViewModel : ViewModel() {
    data class UiState(
        val type: String = "movie",
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        val end: Boolean = false,
        val error: String = "",
        val items: List<Media> = emptyList(),
        val page: Int = 1,
        val totalPages: Int = 1,
    )

    private val _state = MutableStateFlow(UiState(loading = true))
    val state: StateFlow<UiState> = _state

    private var loadedType = ""

    init { load("movie") }

    fun load(type: String) {
        if (_state.value.type == type && loadedType == type && _state.value.items.isNotEmpty()) return
        loadedType = type
        _state.value = UiState(type = type, loading = true)
        viewModelScope.launch {
            try {
                val (list, totalPages) = App.tmdb.category(type, 1)
                _state.value = UiState(type = type, items = list, page = 1, totalPages = totalPages)
            } catch (e: Exception) {
                _state.value = UiState(type = type, error = e.message ?: "加载失败")
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.loadingMore || s.end || s.page >= s.totalPages) return
        _state.value = s.copy(loadingMore = true)
        viewModelScope.launch {
            try {
                val (list, totalPages) = App.tmdb.category(s.type, s.page + 1)
                _state.value = _state.value.copy(
                    loadingMore = false,
                    items = _state.value.items + list,
                    page = s.page + 1,
                    totalPages = totalPages,
                    end = s.page + 1 >= totalPages || list.isEmpty(),
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loadingMore = false)
            }
        }
    }
}

private val TABS = listOf(
    "movie" to "电影",
    "tv" to "剧集",
    "variety" to "综艺",
    "anime" to "动漫",
)

@Composable
fun CategoryScreen(onOpenDetail: (String, Int) -> Unit, vm: CategoryViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        // 分类切换
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(TABS.size) { i ->
                val (key, label) = TABS[i]
                FilterChip(
                    selected = state.type == key,
                    onClick = { vm.load(key) },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Bg2,
                        labelColor = Text2,
                        selectedContainerColor = Primary.copy(alpha = 0.18f),
                        selectedLabelColor = Primary,
                    ),
                )
            }
        }

        when {
            state.loading -> LoadingBox(Modifier.fillMaxSize())
            state.error.isNotEmpty() && state.items.isEmpty() -> EmptyBox(state.error) { vm.load(state.type) }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(state.items, key = { it.type + it.id }) { m ->
                        MediaCard(m, { onOpenDetail(m.type, m.id) })
                    }
                    // 加载更多 / 到底
                    item("footer") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 18.dp),
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        ) {
                            when {
                                state.loadingMore -> Text("加载中…", color = Text2, fontSize = 13.sp)
                                state.end -> Text("已经到底啦", color = Text2, fontSize = 13.sp)
                                else -> Text(
                                    "点击加载更多",
                                    color = Primary,
                                    fontSize = 13.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { vm.loadMore() }
                                        .padding(10.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
