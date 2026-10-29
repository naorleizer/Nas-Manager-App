package com.nasmanagerapp.data.network

import org.junit.Assert.assertFalse
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
}
