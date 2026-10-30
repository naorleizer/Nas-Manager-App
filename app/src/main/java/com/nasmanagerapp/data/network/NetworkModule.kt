package com.nasmanagerapp.data.network

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** Builds the [OkHttpClient] backing the JSON-RPC WebSocket to the configured TrueNAS server. */
object NetworkModule {

    const val TIMEOUT_SECONDS = 15L

    fun createOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // WebSocket keepalive: a missed pong fails the socket so the next call reconnects.
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
}
