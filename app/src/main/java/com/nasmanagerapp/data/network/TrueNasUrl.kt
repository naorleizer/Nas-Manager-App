package com.nasmanagerapp.data.network

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
}
