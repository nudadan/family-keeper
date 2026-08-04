package com.noesolution.gtracker.data

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

interface PositionApi {

    @POST("api/positions")
    suspend fun upload(@Body body: PositionUpload)

    @GET("api/positions/latest")
    suspend fun latestAll(): List<Position>

    @GET("api/positions")
    suspend fun history(
        @Query("deviceId") deviceId: String,
        @Query("limit") limit: Int = 100,
    ): List<Position>

    @GET("api/positions/tracks")
    suspend fun tracks(@Query("hours") hours: Double = 12.0): TracksResponse

    // --- Emergency audio ---

    @GET("api/audio/pending")
    suspend fun pendingAudio(): List<AudioRequest>

    @POST("api/audio/request")
    suspend fun requestAudio(@Body body: AudioRequestBody): AudioRequestResponse

    @POST("api/audio/clip/{id}")
    suspend fun uploadClip(
        @Path("id") id: String,
        @Body body: RequestBody,
        @Header("X-Duration-Ms") durationMs: Long,
    )

    @POST("api/audio/reject/{id}")
    suspend fun rejectAudio(@Path("id") id: String)

    @Streaming
    @GET("api/audio/clip/{id}")
    suspend fun downloadClip(@Path("id") id: String): ResponseBody

    @GET("api/audio/log")
    suspend fun audioLog(): List<AudioLogEntry>
}
