package com.jay.video.ui.favorite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jay.video.App
import com.jay.video.data.Media
import com.jay.video.ui.components.EmptyBox
import com.jay.video.ui.components.MediaCard
import com.jay.video.ui.theme.Text2
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class FavoriteViewModel : ViewModel() {
    val favorites: StateFlow<List<Media>> = App.db.favoriteDao().all()
        .map { list ->
            list.map {
                Media(
                    id = it.mediaId,
                    type = it.mediaType,
                    title = it.title,
                    orig = "",
                    poster = it.poster,
                    backdrop = "",
                    score = it.score,
                    year = it.year,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@Composable
fun FavoriteScreen(onOpenDetail: (String, Int) -> Unit, vm: FavoriteViewModel = viewModel()) {
    val list by vm.favorites.collectAsStateWithLifecycle()

    if (list.isEmpty()) {
        EmptyBox("暂无收藏，去发现喜欢的影片吧")
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(list, key = { it.type + it.id }) { m ->
            MediaCard(m, { onOpenDetail(m.type, m.id) })
        }
    }
}
