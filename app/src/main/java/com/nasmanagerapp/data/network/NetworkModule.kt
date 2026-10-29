package com.nasmanagerapp.data.network

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** Builds the single [OkHttpClient] shared by all calls to the configured TrueNAS server. */
object NetworkModule {

    fun createOkHttpClient(credentialsStore: CredentialsStore): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(credentialsStore))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
}
