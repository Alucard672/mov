package com.gofilm.app.nav

sealed class Routes(val route: String) {
    data object Home : Routes("home")
    data object Classify : Routes("classify")
    data object Search : Routes("search")
    data object Mine : Routes("mine")
    data object Detail : Routes("detail/{filmId}") {
        fun create(filmId: Long) = "detail/$filmId"
    }
    data object Play : Routes("play/{filmId}?playFrom={playFrom}&episode={episode}&resume={resume}") {
        fun create(
            filmId: Long,
            playFrom: String = "",
            episode: Int = 0,
            resumeMs: Long = 0L
        ): String {
            val from = java.net.URLEncoder.encode(playFrom, Charsets.UTF_8.name())
            return "play/$filmId?playFrom=$from&episode=$episode&resume=$resumeMs"
        }
    }
    data object History : Routes("history")
    data object Favorites : Routes("favorites")
    data object Settings : Routes("settings")
    data object Torrent : Routes("torrent")
    data object Downloads : Routes("downloads")
    data object LinkDownload : Routes("link_download")
    data object AdCoop : Routes("ad_coop")
}
