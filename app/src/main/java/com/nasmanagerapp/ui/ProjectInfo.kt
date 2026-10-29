package com.nasmanagerapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler

private const val SOURCE_URL = "https://github.com/Juju-archy/Nas-Manager-App"

/**
 * License + source repository link, shown at the bottom of the login screen and of the navigation
 * drawer. The license is GPL-3.0-or-later (see `README.md` / `LICENSE`).
 */
@Composable
fun ProjectInfo(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Free software — GPL-3.0-or-later",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = {
                // AndroidUriHandler throws IllegalArgumentException when no app can open the URL
                // (e.g. no browser installed); nothing useful to do then, so just ignore the tap.
                runCatching { uriHandler.openUri(SOURCE_URL) }
            },
        ) {
            Text(
                text = SOURCE_URL.removePrefix("https://"),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
