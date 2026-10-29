package com.nasmanagerapp.data.network

import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.Response

/** Attaches the current credentials (if any) as HTTP Basic Auth to every outgoing request. */
class AuthInterceptor(private val credentialsStore: CredentialsStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val username = credentialsStore.username
        val password = credentialsStore.password
        if (username == null || password == null) return chain.proceed(request)
        return chain.proceed(
            request.newBuilder()
                .header("Authorization", Credentials.basic(username, password))
                .build()
        )
    }
}
