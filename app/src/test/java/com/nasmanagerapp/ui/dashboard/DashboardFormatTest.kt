package com.nasmanagerapp.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardFormatTest {

    @Test
    fun `formats bytes using IEC units`() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KiB", formatBytes(1024))
        assertEquals("15.5 GiB", formatBytes(16_664_072_192))
    }

    @Test
    fun `formats uptime in days hours minutes`() {
        assertEquals("32 d 5 h 20 min", formatUptime(2_784_052.0))
        assertEquals("5 h 20 min", formatUptime(19_252.0))
        assertEquals("20 min", formatUptime(1_252.0))
    }

    @Test
    fun `formats and clamps percentages`() {
        assertEquals("42 %", formatPercent(42.4))
        assertEquals("100 %", formatPercent(123.0))
        assertEquals("0 %", formatPercent(-5.0))
    }
}
