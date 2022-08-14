package com.vlending.apprtc.di

import android.app.Application
import com.google.gson.GsonBuilder
import com.vlending.apprtc.network.ApiService
import com.vlending.apprtc.network.IceApiService
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.util.concurrent.TimeUnit

object Injector {

    lateinit var app: Application

    private var retrofit: Retrofit? = null
    private const val BASE_URL = "http://192.168.35.248:8080/"

    fun getService(): ApiService = createRetrofit().create(ApiService::class.java)

    fun getIceService(url: String): IceApiService = createRetrofit(url).create(IceApiService::class.java)

    private fun createRetrofit(): Retrofit {
        retrofit?.let { return it } ?: run {
            val okHttpClient = OkHttpClient
                .Builder()
                .connectTimeout(5L, TimeUnit.SECONDS)
                .addInterceptor(HttpLoggingInterceptor().apply { setLevel(HttpLoggingInterceptor.Level.BODY) })
                .build()

            val gson = GsonBuilder().setLenient().create()
            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(okHttpClient)
                .addConverterFactory(ScalarsConverterFactory.create())
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build().apply {
                    retrofit = this
                }
        }
    }

    private fun createRetrofit(baseUrl: String): Retrofit {

        val okHttpClient = OkHttpClient
            .Builder()
            .connectTimeout(5L, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply { setLevel(HttpLoggingInterceptor.Level.BODY) })
            .build()

        val gson = GsonBuilder().setLenient().create()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }
}