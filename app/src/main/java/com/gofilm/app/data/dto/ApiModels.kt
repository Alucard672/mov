package com.gofilm.app.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ApiResponse<T>(
    val code: Int = -1,
    val data: T? = null,
    val msg: String = ""
)

@Serializable
data class BasicConfig(
    val siteName: String = "GoFilm",
    val domain: String = "",
    val logo: String = "",
    val keyword: String = "",
    val describe: String = "",
    val state: Boolean = true,
    val hint: String = ""
)

@Serializable
data class CategoryItem(
    val id: Long = 0,
    val name: String = "",
    val pid: Long = 0,
    val show: Boolean = true,
    /** 接口可能返回 null，用可空避免解析失败 */
    val children: List<CategoryItem>? = null
)

@Serializable
data class FilmCard(
    val id: Long = 0,
    /** 热播列表 GORM 会序列化成大写 ID，与 id 并存时优先 mid/id */
    @SerialName("ID") val idUpper: Long = 0,
    val mid: Long = 0,
    val cid: Long = 0,
    val pid: Long = 0,
    val name: String = "",
    val subTitle: String = "",
    @SerialName("cName") val cName: String = "",
    val state: String = "",
    val picture: String = "",
    val actor: String = "",
    val director: String = "",
    val blurb: String = "",
    val remarks: String = "",
    val area: String = "",
    @Serializable(with = FlexibleStringSerializer::class)
    val year: String = "",
    val score: Double = 0.0,
    val hits: Long = 0
) {
    val filmId: Long
        get() = when {
            mid != 0L -> mid
            id != 0L -> id
            else -> idUpper
        }
}

@Serializable
data class BannerItem(
    @Serializable(with = FlexibleStringSerializer::class)
    val id: String = "",
    val mid: Long = 0,
    val name: String = "",
    val picture: String = "",
    val poster: String = "",
    val remark: String = "",
    @SerialName("cName") val cName: String = "",
    @Serializable(with = FlexibleStringSerializer::class)
    val year: String = ""
)

@Serializable
data class IndexData(
    val category: CategoryItem? = null,
    /** 接口在无数据时可能为 null */
    val content: List<IndexSection>? = null,
    val banners: List<BannerItem>? = null
)

/**
 * 首页每个分类块。
 * 后端 nav 实际是【单个对象】，不是数组；用 NavSerializer 兼容两种形态。
 */
@Serializable
data class IndexSection(
    val hot: List<FilmCard>? = null,
    val movies: List<FilmCard>? = null,
    @Serializable(with = NavSerializer::class)
    val nav: CategoryItem? = null
)

@Serializable
data class FilmDetailData(
    val detail: FilmDetail? = null,
    val relate: List<FilmCard>? = null
)

/**
 * 后端详情是扁平结构（name/picture/list 直接在 detail 上），
 * 同时兼容部分字段在 descriptor 内的情况。
 */
@Serializable
data class FilmDetail(
    val id: Long = 0,
    val mid: Long = 0,
    val cid: Long = 0,
    val pid: Long = 0,
    val name: String = "",
    val picture: String = "",
    val subTitle: String = "",
    @SerialName("cName") val cName: String = "",
    val actor: String = "",
    val director: String = "",
    val blurb: String = "",
    val remarks: String = "",
    val area: String = "",
    @Serializable(with = FlexibleStringSerializer::class)
    val year: String = "",
    val content: String = "",
    val playFrom: List<String>? = null,
    @SerialName("DownFrom") val downFrom: String = "",
    val playList: List<PlayLink>? = null,
    val downloadList: List<PlayLink>? = null,
    val descriptor: FilmDescriptor? = null,
    val list: List<PlaySource>? = null
)

@Serializable
data class FilmDescriptor(
    val subTitle: String = "",
    @SerialName("cName") val cName: String = "",
    val enName: String = "",
    val initial: String = "",
    val classTag: String = "",
    val actor: String = "",
    val director: String = "",
    val writer: String = "",
    val blurb: String = "",
    val remarks: String = "",
    val releaseDate: String = "",
    val area: String = "",
    val language: String = "",
    @Serializable(with = FlexibleStringSerializer::class)
    val year: String = "",
    val state: String = "",
    val updateTime: String = "",
    val dbScore: String = "0.0",
    val hits: Long = 0,
    val content: String = ""
)

@Serializable
data class PlayLink(
    val episode: String = "",
    val link: String = ""
)

@Serializable
data class PlaySource(
    val id: String = "",
    val name: String = "",
    val linkList: List<PlayLink> = emptyList()
)

@Serializable
data class PlayInfoData(
    val current: PlayLink? = null,
    val currentEpisode: Int = 0,
    val currentPlayFrom: String = "",
    val detail: FilmDetail? = null,
    val relate: List<FilmCard>? = null
)

@Serializable
data class ClassifyData(
    val content: ClassifyContent? = null,
    val title: CategoryItem? = null
)

@Serializable
data class ClassifyContent(
    val news: List<FilmCard> = emptyList(),
    val recent: List<FilmCard> = emptyList(),
    val top: List<FilmCard> = emptyList()
)

@Serializable
data class ClassifySearchData(
    val list: List<FilmCard> = emptyList(),
    val page: PageInfo? = null,
    val params: JsonElement? = null,
    val search: JsonElement? = null,
    val title: CategoryItem? = null
)

@Serializable
data class PageInfo(
    val pageSize: Int = 0,
    val current: Int = 1,
    val pageCount: Int = 0,
    val total: Int = 0
)

/** Search may return list or wrapped object depending on backend version. */
@Serializable
data class SearchData(
    val list: List<FilmCard> = emptyList()
)
