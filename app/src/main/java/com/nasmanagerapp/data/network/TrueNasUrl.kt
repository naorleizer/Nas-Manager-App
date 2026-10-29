package com.nasmanagerapp.data.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Normalizes a user-typed server address into a full base URL (`scheme://host[:port]`). */
object TrueNasUrl {

    fun normalize(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    /** True if [input] normalizes to a cleartext `http://` address (not `https://`). */
    internal fun isHttp(input: String): Boolean = normalize(input).startsWith("http://")

    /**
     * The JSON-RPC WebSocket endpoint for [input] (`wss://host:port/api/current`, or `ws://` for an
     * `http://` address). The port is the explicit one typed by the user, else the scheme default
     * (443/80). Returns null if [input] isn't a usable address.
     */
    fun deriveWebSocketUri(input: String): String? {
        val url = normalize(input).toHttpUrlOrNull() ?: return null
        // okhttp's lenient parsing can turn scheme-like garbage into a literal host named
        // "https"/"http" (e.g. "https://" survives normalize's trimEnd as "https:") — that is
        // never a usable address; a real NAS host can't collide with it either.
        if (url.host.isBlank() || url.host == "https" || url.host == "http") return null
        val scheme = if (url.scheme == "https") "wss" else "ws"
        val host = if (':' in url.host) "[${url.host}]" else url.host
        return "$scheme://$host:${url.port}/api/current"
    }
}
