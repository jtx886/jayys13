package com.jay.video.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.jay.video.data.local.History
import com.jay.video.ui.components.EmptyBox
import com.jay.video.ui.theme.Bg2
import com.jay.video.ui.theme.Panel
import com.jay.video.ui.theme.Primary
import com.jay.video.ui.theme.Text1
import com.jay.video.ui.theme.Text2
import com.jay.video.ui.theme.Text3
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel : ViewModel() {
    val history: StateFlow<List<History>> = App.db.historyDao().all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun delete(h: History) {
        viewModelScope.launch { App.db.historyDao().delete(h.mediaId, h.mediaType) }
    }

    fun clear() {
        viewModelScope.launch { App.db.historyDao().clear() }
    }
}

@Composable
fun HistoryScreen(onOpenDetail: (String, Int) -> Unit, vm: HistoryViewModel = viewModel()) {
    val list by vm.history.collectAsStateWithLifecycle()

    if (list.isEmpty()) {
        EmptyBox("暂无观看记录")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
    ) {
        items(list, key = { it.mediaType + it.mediaId }) { h ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Bg2)
                    .clickable { onOpenDetail(h.mediaType, h.mediaId) },
            ) {
                Box {
                    AsyncImage(
                        model = h.poster,
                        contentDescription = h.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(92.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp)),
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(30.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(Primary.copy(alpha = 0.9f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.PlayArrow, "播放", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        h.title,
                        color = Text1,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            append("看到 ${h.episodeLabel.ifEmpty { "第${h.episode}集" }}")
                            if (h.season > 1) append(" · 第${h.season}季")
                        },
                        color = Text2,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    // 进度条
                    if (h.durationMs > 0 && h.positionMs > 0) {
                        val pct = (h.positionMs.toFloat() / h.durationMs).coerceIn(0f, 1f)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Panel),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(pct)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Primary),
                            )
                        }
                        Text(
                            "${formatTime(h.positionMs)} / ${formatTime(h.durationMs)}",
                            color = Text3,
                            fontSize = 10.5.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                IconButton(onClick = { vm.delete(h) }, modifier = Modifier.align(Alignment.CenterVertically)) {
                    Icon(Icons.Filled.Delete, "删除", tint = Text3, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
