package com.nasmanagerapp.ui.dashboard

import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIconTest {

    @Test
    fun `detects svg from content type`() {
        assertTrue(isSvgIcon("image/svg+xml", "https://media.sys.truenas.net/apps/jellyfin/icons/icon.svg"))
        assertTrue(isSvgIcon("image/svg+xml; charset=utf-8", "https://example.com/icon"))
        assertFalse(isSvgIcon("image/png", "https://media.sys.truenas.net/apps/plex/icons/icon.png"))
    }

    @Test
    fun `content type wins over url extension`() {
        assertFalse(isSvgIcon("image/png", "https://example.com/icon.svg"))
    }

    @Test
    fun `falls back to url extension without content type`() {
        assertTrue(isSvgIcon(null, "https://example.com/icon.SVG?v=2"))
        assertFalse(isSvgIcon(null, "https://example.com/icon.jpeg"))
    }

    @Test
    fun `reads a body up to the limit`() {
        val body = ByteArray(100) { it.toByte() }
        assertArrayEquals(body, readAtMost(Buffer().write(body), maxBytes = 100))
    }

    @Test
    fun `rejects a body over the limit`() {
        assertNull(readAtMost(Buffer().write(ByteArray(101)), maxBytes = 100))
    }

    @Test
    fun `subsamples real catalog icon sizes down to about the displayed size`() {
        val target = 110 // 40dp at xxhdpi
        assertEquals(8, iconSampleSize(1024, 1024, target)) // qbittorrent → 128 px
        assertEquals(8, iconSampleSize(900, 900, target)) // plex → 112 px
        assertEquals(1, iconSampleSize(120, 178, target)) // firefly-iii, already small
        assertEquals(1, iconSampleSize(64, 64, target)) // vaultwarden, never upscaled
    }

    @Test
    fun `bounds memory for an extreme aspect ratio`() {
        val sampleSize = iconSampleSize(1_000_000, 100, 110)
        assertEquals(true, 1_000_000 / sampleSize < 220)
    }
}
