package com.nasmanagerapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrueNasApplicationTest {

    @Test
    fun `restores a logged-in https session`() {
        assertTrue(shouldRestoreSession(isLoggedIn = true, serverUrl = "https://192.168.1.10"))
    }

    @Test
    fun `never restores a logged-in http session`() {
        // The password would otherwise be resent automatically, in the clear, on whatever
        // network the device joins next — see CONNECTIVITY_TODO.md.
        assertFalse(shouldRestoreSession(isLoggedIn = true, serverUrl = "http://192.168.1.10"))
    }

    @Test
    fun `does not restore when not logged in, regardless of scheme`() {
        assertFalse(shouldRestoreSession(isLoggedIn = false, serverUrl = "https://192.168.1.10"))
        assertFalse(shouldRestoreSession(isLoggedIn = false, serverUrl = "http://192.168.1.10"))
    }
}
