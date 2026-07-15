package com.gofilm.app.data.api

import com.gofilm.app.data.dto.ApiResponse
import com.gofilm.app.data.dto.BasicConfig
import com.gofilm.app.data.dto.CategoryItem
import com.gofilm.app.data.dto.ClassifyData
import com.gofilm.app.data.dto.ClassifySearchData
import com.gofilm.app.data.dto.FilmDetailData
import com.gofilm.app.data.dto.IndexData
import com.gofilm.app.data.dto.PlayInfoData
import com.gofilm.app.data.dto.SearchData
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Query

interface FilmApi {

    @GET("index")
    suspend fun index(): ApiResponse<IndexData>

    /** 原始 JSON，便于兼容后端字段差异 */
    @GET("index")
    suspend fun indexRaw(): ResponseBody

    @GET("config/basic")
    suspend fun basicConfig(): ApiResponse<BasicConfig>

    @GET("navCategory")
    suspend fun navCategory(): ApiResponse<List<CategoryItem>>

    @GET("filmDetail")
    suspend fun filmDetail(@Query("id") id: Long): ApiResponse<FilmDetailData>

    @GET("filmDetail")
    suspend fun filmDetailRaw(@Query("id") id: Long): ResponseBody

    @GET("filmPlayInfo")
    suspend fun filmPlayInfo(
        @Query("id") id: Long,
        @Query("playFrom") playFrom: String,
        @Query("episode") episode: Int
    ): ApiResponse<PlayInfoData>

    @GET("filmPlayInfo")
    suspend fun filmPlayInfoRaw(
        @Query("id") id: Long,
        @Query("playFrom") playFrom: String,
        @Query("episode") episode: Int
    ): ResponseBody

    @GET("searchFilm")
    suspend fun searchFilm(@Query("keyword") keyword: String): ApiResponse<SearchData>

    @GET("searchFilm")
    suspend fun searchFilmRaw(@Query("keyword") keyword: String): ResponseBody

    @GET("filmClassify")
    suspend fun filmClassify(@Query("Pid") pid: Long): ApiResponse<ClassifyData>

    @GET("filmClassifySearch")
    suspend fun filmClassifySearch(
        @Query("Pid") pid: Long,
        @Query("Category") category: String = "",
        @Query("Plot") plot: String = "",
        @Query("Area") area: String = "",
        @Query("Language") language: String = "",
        @Query("Year") year: String = "",
        @Query("Sort") sort: String = "",
        @Query("current") current: Int = 1
    ): ApiResponse<ClassifySearchData>
}
