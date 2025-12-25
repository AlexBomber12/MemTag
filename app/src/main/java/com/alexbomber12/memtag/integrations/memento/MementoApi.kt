package com.alexbomber12.memtag.integrations.memento

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

interface MementoApi {
    @GET("libraries/{libraryId}")
    suspend fun getLibrary(
        @Path("libraryId") libraryId: String,
        @Query("token") token: String,
    ): ResponseBody

    @GET("libraries/{libraryId}/entries")
    suspend fun getEntries(
        @Path("libraryId") libraryId: String,
        @Query("token") token: String,
        @Query("fields") fields: String,
        @Query("pageSize") pageSize: Int,
        @Query("pageToken") pageToken: String?,
        @Query("page") pageIndex: Int?,
    ): ResponseBody

    @GET
    suspend fun getEntriesByUrl(
        @Url url: String,
    ): ResponseBody
}
