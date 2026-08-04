package com.noesolution.gtracker.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Builds a [PositionApi] for a given backend URL + API key. The base URL and
 * key can change at runtime (Settings screen), so we rebuild on demand rather
 * than holding a single global instance.
 */
object ApiClient {

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    fun create(
        baseUrl: String,
        apiKey: String,
        groupCode: String = "",
        deviceId: String = "",
    ): PositionApi {
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                    .addHeader("X-API-Key", apiKey)
                if (groupCode.isNotBlank()) {
                    builder.addHeader("X-Group-Code", groupCode)
                }
                if (deviceId.isNotBlank()) {
                    builder.addHeader("X-Device-Id", deviceId)
                }
                chain.proceed(builder.build())
            }
            .addInterceptor(logging)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(normalized)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(PositionApi::class.java)
    }
}
