package com.nasmanagerapp.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrueNasUrlTest {

    @Test
    fun `http address is http`() {
        assertTrue(TrueNasUrl.isHttp("http://192.168.1.10"))
    }

    @Test
    fun `https address is not http`() {
        assertFalse(TrueNasUrl.isHttp("https://192.168.1.10"))
    }

    @Test
    fun `address without a scheme defaults to https`() {
        assertFalse(TrueNasUrl.isHttp("192.168.1.10"))
    }

    @Test
    fun `uppercase scheme is not recognized, matching normalize's case sensitivity`() {
        // normalize() only matches a lowercase "http://" prefix; an uppercase scheme falls
        // through to the "no scheme" branch and gets "https://" prepended in front of it, so
        // this is *not* treated as http. Documented here so a future change to normalize()'s
        // case-sensitivity doesn't silently flip this.
        assertFalse(TrueNasUrl.isHttp("HTTP://192.168.1.10"))
    }

    @Test
    fun `deriveWebSocketUri keeps an explicit port`() {
        assertEquals("wss://nas.local:8443/api/current", TrueNasUrl.deriveWebSocketUri("https://nas.local:8443"))
    }

    @Test
    fun `deriveWebSocketUri defaults to 443 for https and a bare address`() {
        assertEquals("wss://192.168.1.10:443/api/current", TrueNasUrl.deriveWebSocketUri("https://192.168.1.10"))
        assertEquals("wss://192.168.1.10:443/api/current", TrueNasUrl.deriveWebSocketUri("192.168.1.10/"))
    }

    @Test
    fun `deriveWebSocketUri maps http to ws with port 80 by default`() {
        assertEquals("ws://nas.local:80/api/current", TrueNasUrl.deriveWebSocketUri("http://nas.local"))
        assertEquals("ws://nas.local:8080/api/current", TrueNasUrl.deriveWebSocketUri("http://nas.local:8080"))
    }

    @Test
    fun `deriveWebSocketUri brackets an IPv6 host`() {
        assertEquals("wss://[fd00::1]:443/api/current", TrueNasUrl.deriveWebSocketUri("https://[fd00::1]"))
    }

    @Test
    fun `deriveWebSocketUri returns null for garbage`() {
        assertNull(TrueNasUrl.deriveWebSocketUri("not a url!"))
        assertNull(TrueNasUrl.deriveWebSocketUri(""))
        assertNull(TrueNasUrl.deriveWebSocketUri("https://"))
    }
}
