package com.nasmanagerapp.data.network

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** Builds the [OkHttpClient] backing the JSON-RPC WebSocket to the configured TrueNAS server. */
object NetworkModule {

    fun createOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        // WebSocket keepalive: a missed pong fails the socket so the next call reconnects.
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
}
