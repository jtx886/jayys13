package com.jay.video.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jay.video.App
import com.jay.video.data.source.Prefs
import com.jay.video.data.source.Site
import com.jay.video.ui.components.SectionTitle
import com.jay.video.ui.theme.Bg
import com.jay.video.ui.theme.Bg2
import com.jay.video.ui.theme.BorderC
import com.jay.video.ui.theme.Panel
import com.jay.video.ui.theme.Primary
import com.jay.video.ui.theme.Text1
import com.jay.video.ui.theme.Text2
import com.jay.video.ui.theme.Text3
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SettingsViewModel : ViewModel() {
    data class UiState(
        val configs: List<String> = emptyList(),
        val sites: List<Site> = emptyList(),
        val disabled: Set<String> = emptySet(),
        val refreshing: Boolean = false,
        val message: String = "",
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init { reload() }

    fun reload() {
        _state.value = _state.value.copy(
            configs = Prefs.configUrls(),
            sites = App.source.allSites(),
            disabled = Prefs.disabledKeys(),
            message = App.source.loadMessage.value,
        )
    }

    /** 添加配置URL */
    fun addConfig(url: String) {
        val u = url.trim()
        if (u.isEmpty()) return
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            _state.value = _state.value.copy(message = "链接需以 http(s):// 开头")
            return
        }
        val cur = Prefs.configUrls()
        if (cur.contains(u)) {
            _state.value = _state.value.copy(message = "该配置已存在")
            return
        }
        Prefs.saveConfigUrls(cur + u)
        refresh()
    }

    fun removeConfig(url: String) {
        Prefs.saveConfigUrls(Prefs.configUrls() - url)
        refresh()
    }

    /** 重新拉取全部配置 */
    fun refresh() {
        _state.value = _state.value.copy(refreshing = true)
        viewModelScope.launch {
            App.source.refreshConfigs()
            _state.value = _state.value.copy(
                refreshing = false,
                sites = App.source.allSites(),
                configs = Prefs.configUrls(),
                disabled = Prefs.disabledKeys(),
                message = App.source.loadMessage.value,
            )
        }
    }

    /** 站点开关 */
    fun toggleSite(site: Site, enabled: Boolean) {
        Prefs.toggleDisabled(site.key, !enabled)
        _state.value = _state.value.copy(disabled = Prefs.disabledKeys())
    }
}

@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
    ) {
        // ===== 配置管理 =====
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle("订阅配置", Modifier.weight(1f))
                if (state.refreshing) {
                    CircularProgressIndicator(color = Primary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                } else {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Filled.Refresh, "刷新", tint = Text2)
                    }
                }
            }
        }
        item {
            Text(
                "添加影视仓 / TVBox 格式的 JSON 配置链接，加载其中的站点作为播放源",
                color = Text3,
                fontSize = 11.5.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        item {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("https://…/config.json", fontSize = 13.sp, color = Text3) },
                    leadingIcon = { Icon(Icons.Filled.Link, null, tint = Text3) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        vm.addConfig(input)
                        input = ""
                    }),
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
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Primary)
                        .clickable {
                            vm.addConfig(input)
                            input = ""
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Icon(Icons.Filled.Add, "添加", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
        if (state.message.isNotEmpty()) {
            item {
                Text(
                    state.message,
                    color = Text3,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
        }

        // 配置列表
        if (state.configs.isNotEmpty()) {
            items(state.configs, key = { it }) { url ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Bg2)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Link, null, tint = Primary, modifier = Modifier.size(16.dp))
                    Text(
                        url,
                        color = Text2,
                        fontSize = 12.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    )
                    IconButton(onClick = { vm.removeConfig(url) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Delete, "删除", tint = Text3, modifier = Modifier.size(16.dp))
                    }
                }
            }
        } else {
            item {
                Text(
                    "未添加配置，当前使用内置播放源（量子资源 / 非凡影视）",
                    color = Text3,
                    fontSize = 11.5.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }

        // ===== 站点列表 =====
        item {
            SectionTitle(
                "播放站点（${state.sites.count { it.key !in state.disabled }}/${state.sites.size} 启用）",
                Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
        items(state.sites, key = { it.key }) { site ->
            val enabled = site.key !in state.disabled
            val typeLabel = when (site.type) {
                0 -> "XML"
                1 -> "JSON"
                else -> "T${site.type}"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Bg2)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            site.name,
                            color = if (enabled) Text1 else Text3,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (site.builtin) "内置" else typeLabel,
                            color = if (site.builtin) Text3 else Primary.copy(alpha = 0.7f),
                            fontSize = 9.5.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (site.builtin) Panel else Primary.copy(alpha = 0.12f))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                    Text(
                        site.api,
                        color = Text3,
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { vm.toggleSite(site, it) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Primary,
                        uncheckedTrackColor = Panel,
                        checkedThumbColor = Color.White,
                        uncheckedThumbColor = Text3,
                    ),
                    modifier = Modifier.height(28.dp),
                )
            }
        }

        // ===== 关于 =====
        item {
            SectionTitle("关于", Modifier.padding(horizontal = 16.dp, vertical = 16.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Bg2)
                    .padding(14.dp),
            ) {
                Text("Jay影视 v1.1.0", color = Text1, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    "原生 Android 客户端 · TMDB 元数据 + 影视仓多源播放\n支持苹果CMS JSON/XML 接口，聚合搜索与换源播放",
                    color = Text3,
                    fontSize = 11.5.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
