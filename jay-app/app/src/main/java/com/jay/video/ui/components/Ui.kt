package com.jay.video.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.jay.video.data.Media
import com.jay.video.ui.theme.Panel
import com.jay.video.ui.theme.Primary
import com.jay.video.ui.theme.Primary2
import com.jay.video.ui.theme.Text1
import com.jay.video.ui.theme.Text2

/** 海报卡片：width 为 null 时自适应填充可用宽度（网格内使用） */
@Composable
fun MediaCard(m: Media, onClick: () -> Unit, width: Dp? = null) {
    val base = if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()
    Column(
        modifier = base.clickable { onClick() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(Panel),
        ) {
            AsyncImage(
                model = m.poster,
                contentDescription = m.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (m.score > 0) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xB3000000))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Star, null,
                        tint = Color(0xFFFFC53D),
                        modifier = Modifier.size(10.dp),
                    )
                    Text(
                        m.score.toString(),
                        color = Color(0xFFFFC53D),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                }
            }
            if (m.year.isNotEmpty()) {
                Text(
                    m.year,
                    color = Color.White,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x66000000))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            m.title.ifEmpty { m.orig },
            color = Text1,
            fontSize = 12.5.sp,
            lineHeight = 17.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** 区块标题 */
@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Brush.verticalGradient(listOf(Primary, Primary2))),
        )
        Text(
            title,
            color = Text1,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** 加载中 */
@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = Primary, strokeWidth = 2.5.dp, modifier = Modifier.size(30.dp))
    }
}

/** 错误/空态 */
@Composable
fun EmptyBox(text: String, retry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, color = Text2, fontSize = 14.sp)
        if (retry != null) {
            Button(
                onClick = retry,
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                modifier = Modifier.padding(top = 14.dp),
            ) { Text("重试") }
        }
    }
}

/** 媒体网格 */
@Composable
fun MediaGrid(
    items: List<Media>,
    onClick: (Media) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 3,
    userScrollEnabled: Boolean = true,
    extra: (LazyGridScope.() -> Unit)? = null,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        userScrollEnabled = userScrollEnabled,
    ) {
        items(items, key = { it.type + it.id }) { m ->
            MediaCard(m, { onClick(m) }, width = null)
        }
        extra?.invoke(this)
    }
}

/** 类型小图标 */
@Composable
fun TypeIcon(type: String, tint: Color = Text2, size: Dp = 14.dp) {
    Icon(
        imageVector = if (type == "tv") Icons.Outlined.Tv else Icons.Outlined.Movie,
        contentDescription = if (type == "tv") "剧集" else "电影",
        tint = tint,
        modifier = Modifier.size(size),
    )
}
