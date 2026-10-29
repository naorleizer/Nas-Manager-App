package com.nasmanagerapp.ui.dashboard

import com.nasmanagerapp.data.dashboard.AppState
import org.junit.Assert.assertEquals
import org.junit.Test

class AppRunToggleTest {

    @Test
    fun `offers Stop for a running or deploying app and Start for a stopped or crashed one`() {
        val expected = mapOf(
            AppState.RUNNING to (false to true),
            AppState.DEPLOYING to (false to true),
            AppState.STOPPED to (true to false),
            AppState.CRASHED to (true to false),
            AppState.STOPPING to (false to false),
            AppState.UNKNOWN to (false to false),
        )
        AppState.entries.forEach { state ->
            assertEquals("$state", expected.getValue(state), canStartApp(state) to canStopApp(state))
        }
    }
}
