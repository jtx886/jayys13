package com.jay.video.ui.search

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
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
import com.jay.video.ui.theme.Bg
import com.jay.video.ui.theme.Panel
import com.jay.video.ui.theme.Text1
import com.jay.video.ui.theme.Text2
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SearchViewModel : ViewModel() {
    data class UiState(
        val keyword: String = "",
        val searching: Boolean = false,
        val searched: Boolean = false,
        val results: List<Media> = emptyList(),
        val total: Int = 0,
        val page: Int = 1,
        val totalPages: Int = 1,
        val loadingMore: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    fun search(wd: String) {
        val kw = wd.trim()
        if (kw.isEmpty()) return
        _state.value = UiState(keyword = kw, searching = true)
        viewModelScope.launch {
            try {
                val (list, totalPages) = App.tmdb.search(kw, 1)
                _state.value = UiState(
                    keyword = kw,
                    searched = true,
                    results = list,
                    total = list.size,
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
                val (list, totalPages) = App.tmdb.search(s.keyword, s.page + 1)
                _state.value = _state.value.copy(
                    loadingMore = false,
                    results = _state.value.results + list,
                    page = s.page + 1,
                    totalPages = totalPages,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loadingMore = false)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenDetail: (String, Int) -> Unit,
    vm: SearchViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }

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
                    placeholder = { Text("搜索片名，电影 / 剧集 / 动漫", fontSize = 14.sp, color = Text2) },
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
                    keyboardActions = KeyboardActions(onSearch = { vm.search(input) }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Panel,
                        unfocusedContainerColor = Panel,
                        focusedTextColor = Text1,
                        unfocusedTextColor = Text1,
                        cursorColor = Color(0xFFE50914),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
        )

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
                    items(state.results, key = { it.type + it.id }) { m ->
                        MediaCard(m, { onOpenDetail(m.type, m.id) })
                    }
                    if (state.page < state.totalPages) {
                        item("more") {
                            Text(
                                if (state.loadingMore) "加载中…" else "点击加载更多",
                                color = com.jay.video.ui.theme.Primary,
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.loadMore() }
                                    .padding(vertical = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
