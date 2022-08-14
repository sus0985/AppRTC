package com.vlending.apprtc.network

import retrofit2.http.GET

interface IceApiService {

    @GET("iceconfig")
    suspend fun getIceServer(): String
}