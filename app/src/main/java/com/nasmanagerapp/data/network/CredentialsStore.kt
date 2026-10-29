package com.nasmanagerapp.data.network

/**
 * Holds the credentials currently used to authenticate requests, if any. TrueNAS Scale's REST
 * API has no session/login endpoint — every request authenticates itself, here via HTTP Basic
 * Auth — so this is the single source of truth [AuthInterceptor] reads from.
 */
class CredentialsStore {
    @Volatile
    var username: String? = null

    @Volatile
    var password: String? = null
}
