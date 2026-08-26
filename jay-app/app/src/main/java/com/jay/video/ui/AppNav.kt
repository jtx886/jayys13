package com.jay.video.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jay.video.ui.category.CategoryScreen
import com.jay.video.ui.detail.DetailScreen
import com.jay.video.ui.favorite.FavoriteScreen
import com.jay.video.ui.history.HistoryScreen
import com.jay.video.ui.home.HomeScreen
import com.jay.video.ui.player.PlayerScreen
import com.jay.video.ui.search.SearchScreen
import com.jay.video.ui.settings.SettingsScreen
import com.jay.video.ui.theme.Bg
import com.jay.video.ui.theme.Primary
import com.jay.video.ui.theme.Text1
import com.jay.video.ui.theme.Text2

object Routes {
    const val HOME = "home"
    const val CATEGORY = "category"
    const val FAVORITE = "favorite"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val SEARCH = "search"
    const val DETAIL = "detail/{type}/{id}?season={season}"
    const val PLAYER = "player/{type}/{id}/{season}/{episode}"

    fun detail(type: String, id: Int, season: Int = 0) =
        if (season > 0) "detail/$type/$id?season=$season" else "detail/$type/$id?season=0"
    fun player(type: String, id: Int, season: Int = 1, episode: Int = 1) = "player/$type/$id/$season/$episode"
}

private data class TabItem(val route: String, val label: String, val icon: ImageVector, val activeIcon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route

    val tabs = listOf(
        TabItem(Routes.HOME, "首页", Icons.Outlined.Home, Icons.Filled.Home),
        TabItem(Routes.CATEGORY, "分类", Icons.Outlined.VideoLibrary, Icons.Filled.VideoLibrary),
        TabItem(Routes.FAVORITE, "收藏", Icons.Outlined.FavoriteBorder, Icons.Filled.Favorite),
        TabItem(Routes.HISTORY, "历史", Icons.Outlined.History, Icons.Filled.History),
        TabItem(Routes.SETTINGS, "我的", Icons.Outlined.Person, Icons.Filled.Person),
    )
    val isTopLevel = current in tabs.map { it.route }

    Scaffold(
        containerColor = Bg,
        topBar = {
            if (isTopLevel) {
                TopAppBar(
                    title = { Text("Jay影视", color = Text1, fontWeight = FontWeight.ExtraBold) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
                    actions = {
                        IconButton(onClick = { nav.navigate(Routes.SEARCH) }) {
                            Icon(Icons.Filled.Search, "搜索", tint = Text2)
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (isTopLevel) {
                NavigationBar(containerColor = Bg) {
                    tabs.forEach { tab ->
                        val selected = current == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    if (selected) tab.activeIcon else tab.icon,
                                    contentDescription = tab.label,
                                    tint = if (selected) Primary else Text2,
                                )
                            },
                            label = { Text(tab.label, color = if (selected) Primary else Text2) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = Primary.copy(alpha = 0.15f),
                            ),
                        )
                    }
                }
            }
        },
    ) { pad ->
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(pad),
        ) {
            composable(Routes.HOME) {
                HomeScreen(onOpenDetail = { t, i -> nav.navigate(Routes.detail(t, i)) })
            }
            composable(Routes.CATEGORY) {
                CategoryScreen(onOpenDetail = { t, i -> nav.navigate(Routes.detail(t, i)) })
            }
            composable(Routes.FAVORITE) {
                FavoriteScreen(onOpenDetail = { t, i -> nav.navigate(Routes.detail(t, i)) })
            }
            composable(Routes.HISTORY) {
                HistoryScreen(onOpenDetail = { t, i -> nav.navigate(Routes.detail(t, i)) })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen()
            }
            composable(Routes.SEARCH) {
                SearchScreen(
                    onBack = { nav.popBackStack() },
                    onDirectPlay = { nav.navigate(Routes.player("direct", 0, 0, 1)) },
                )
            }
            composable(
                Routes.DETAIL,
                arguments = listOf(
                    navArgument("type") { type = NavType.StringType },
                    navArgument("id") { type = NavType.IntType },
                    navArgument("season") {
                        type = NavType.IntType
                        defaultValue = 0
                    },
                ),
            ) { entry ->
                val type = entry.arguments?.getString("type") ?: "movie"
                val id = entry.arguments?.getInt("id") ?: 0
                val season = entry.arguments?.getInt("season") ?: 0
                DetailScreen(
                    type = type,
                    id = id,
                    initialSeason = season,
                    onBack = { nav.popBackStack() },
                    onPlay = { t, i, s, e -> nav.navigate(Routes.player(t, i, s, e)) },
                )
            }
            composable(
                Routes.PLAYER,
                arguments = listOf(
                    navArgument("type") { type = NavType.StringType },
                    navArgument("id") { type = NavType.IntType },
                    navArgument("season") { type = NavType.IntType },
                    navArgument("episode") { type = NavType.IntType },
                ),
            ) { entry ->
                val type = entry.arguments?.getString("type") ?: "movie"
                val id = entry.arguments?.getInt("id") ?: 0
                val season = entry.arguments?.getInt("season") ?: 1
                val episode = entry.arguments?.getInt("episode") ?: 1
                PlayerScreen(
                    type = type,
                    id = id,
                    season = season,
                    episode = episode,
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}
