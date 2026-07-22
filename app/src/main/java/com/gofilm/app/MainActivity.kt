package com.gofilm.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gofilm.app.nav.Routes
import com.gofilm.app.ui.classify.ClassifyScreen
import com.gofilm.app.ui.detail.DetailScreen
import com.gofilm.app.ui.favorites.FavoritesScreen
import com.gofilm.app.ui.history.HistoryScreen
import com.gofilm.app.ui.home.HomeScreen
import com.gofilm.app.ui.mine.AdCoopScreen
import com.gofilm.app.ui.mine.MineScreen
import com.gofilm.app.ui.play.PlayScreen
import com.gofilm.app.ui.search.SearchScreen
import com.gofilm.app.ui.downloads.MyDownloadsScreen
import com.gofilm.app.ui.link.LinkDownloadScreen
import com.gofilm.app.ui.torrent.TorrentPlayScreen
import com.gofilm.app.ui.theme.Accent
import com.gofilm.app.ui.theme.Bg
import com.gofilm.app.ui.theme.GoFilmTheme
import com.gofilm.app.ui.theme.TextMuted
import com.gofilm.app.ui.update.ForceUpdateGate
import java.net.URLDecoder

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 应用在前台时保持屏幕常亮，避免自动息屏
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            GoFilmTheme {
                ForceUpdateGate {
                    GoFilmRoot()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 每日首次回到前台记一次 active
        com.gofilm.app.data.stats.UserStatsReporter.reportDailyActive(this)
    }
}

private data class TabItem(val route: String, val label: String, val icon: ImageVector)

@Composable
private fun GoFilmRoot() {
    val navController = rememberNavController()
    val tabs = listOf(
        TabItem(Routes.Home.route, "首页", Icons.Default.Home),
        TabItem(Routes.Classify.route, "分类", Icons.Default.Category),
        TabItem(Routes.Search.route, "搜索", Icons.Default.Search),
        TabItem(Routes.Mine.route, "我的", Icons.Default.Person)
    )
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = currentRoute in tabs.map { it.route }

    Scaffold(
        containerColor = Bg,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = Bg) {
                    tabs.forEach { tab ->
                        val selected = currentRoute == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label, fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Accent,
                                selectedTextColor = Accent,
                                unselectedIconColor = TextMuted,
                                unselectedTextColor = TextMuted,
                                indicatorColor = Accent.copy(alpha = 0.12f)
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.Home.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.Home.route) {
                HomeScreen(
                    onOpenDetail = { navController.navigate(Routes.Detail.create(it)) },
                    onOpenSearch = {
                        navController.navigate(Routes.Search.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onContinuePlay = { h ->
                        navController.navigate(
                            Routes.Play.create(h.filmId, h.playFrom, h.episode, h.positionMs)
                        )
                    },
                    onOpenHistory = { navController.navigate(Routes.History.route) }
                )
            }
            composable(Routes.Classify.route) {
                ClassifyScreen(onOpenDetail = { navController.navigate(Routes.Detail.create(it)) })
            }
            composable(Routes.Search.route) {
                SearchScreen(
                    onBack = {
                        if (!navController.popBackStack()) {
                            navController.navigate(Routes.Home.route)
                        }
                    },
                    onOpenDetail = { navController.navigate(Routes.Detail.create(it)) }
                )
            }
            composable(Routes.Mine.route) {
                MineScreen(
                    onOpenHistory = { navController.navigate(Routes.History.route) },
                    onOpenFavorites = { navController.navigate(Routes.Favorites.route) },
                    onOpenTorrent = { navController.navigate(Routes.Torrent.route) },
                    onOpenDownloads = { navController.navigate(Routes.Downloads.route) },
                    onOpenLinkDownload = { navController.navigate(Routes.LinkDownload.route) },
                    onOpenAdCoop = { navController.navigate(Routes.AdCoop.route) }
                )
            }
            composable(Routes.AdCoop.route) {
                AdCoopScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.Torrent.route) {
                TorrentPlayScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.Downloads.route) {
                MyDownloadsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.LinkDownload.route) {
                LinkDownloadScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.Detail.route,
                arguments = listOf(navArgument("filmId") { type = NavType.LongType })
            ) { entry ->
                val filmId = entry.arguments?.getLong("filmId") ?: 0L
                DetailScreen(
                    filmId = filmId,
                    onBack = { navController.popBackStack() },
                    onPlay = { id, from, ep ->
                        navController.navigate(Routes.Play.create(id, from, ep))
                    },
                    onOpenDetail = { navController.navigate(Routes.Detail.create(it)) }
                )
            }
            composable(
                route = "play/{filmId}?playFrom={playFrom}&episode={episode}&resume={resume}",
                arguments = listOf(
                    navArgument("filmId") { type = NavType.LongType },
                    navArgument("playFrom") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("episode") {
                        type = NavType.IntType
                        defaultValue = 0
                    },
                    navArgument("resume") {
                        type = NavType.LongType
                        defaultValue = 0L
                    }
                )
            ) { entry ->
                val filmId = entry.arguments?.getLong("filmId") ?: 0L
                val playFrom = URLDecoder.decode(
                    entry.arguments?.getString("playFrom").orEmpty(),
                    Charsets.UTF_8.name()
                )
                val episode = entry.arguments?.getInt("episode") ?: 0
                val resume = entry.arguments?.getLong("resume") ?: 0L
                PlayScreen(
                    filmId = filmId,
                    playFrom = playFrom,
                    episode = episode,
                    resumePositionMs = resume,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Routes.History.route) {
                HistoryScreen(
                    onBack = { navController.popBackStack() },
                    onContinue = { h ->
                        navController.navigate(
                            Routes.Play.create(h.filmId, h.playFrom, h.episode, h.positionMs)
                        )
                    }
                )
            }
            composable(Routes.Favorites.route) {
                FavoritesScreen(
                    onBack = { navController.popBackStack() },
                    onOpenDetail = { navController.navigate(Routes.Detail.create(it)) }
                )
            }

        }
    }
}
