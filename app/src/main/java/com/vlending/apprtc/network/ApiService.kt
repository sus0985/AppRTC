package com.vlending.apprtc.network

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.POST
import retrofit2.http.Path

interface ApiService {

    @POST("join/{room}")
    suspend fun connectToRoom(@Path("room") room: String): String

    @POST("message/{room}/{client}")
    suspend fun sendOffer(
        @Path("room") room: String,
        @Path("client") client: String,
        @Body sdp: String
    ): String

    @POST("leave/{room}/{client}")
    suspend fun sendLeave(
        @Path("room") room: String,
        @Path("client") client: String
    ): String

    @DELETE("{room}/{client}")
    suspend fun sendDelete(
        @Path("room") room: String,
        @Path("client") client: String
    ): String
}