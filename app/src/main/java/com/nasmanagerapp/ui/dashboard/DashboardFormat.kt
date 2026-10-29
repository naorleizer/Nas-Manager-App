package com.nasmanagerapp.ui.dashboard

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

/** Formats a byte count as a human-readable IEC size, e.g. `15.5 GiB`. */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KiB", "MiB", "GiB", "TiB", "PiB")
    val exponent = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(1, units.size)
    val value = bytes / 1024.0.pow(exponent)
    return String.format(Locale.US, "%.1f %s", value, units[exponent - 1])
}

/** Formats an uptime in seconds as e.g. `32 d 5 h 20 min`. */
internal fun formatUptime(totalSeconds: Double): String {
    val totalMinutes = (totalSeconds / 60).toLong()
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes % (24 * 60)) / 60
    val minutes = totalMinutes % 60
    return buildString {
        if (days > 0) append("$days d ")
        if (days > 0 || hours > 0) append("$hours h ")
        append("$minutes min")
    }
}

internal fun formatPercent(value: Double): String =
    String.format(Locale.US, "%.0f %%", value.coerceIn(0.0, 100.0))

/** Formats a duration in seconds spelled out, e.g. `1 hour 30 minutes 49 seconds`. */
internal fun formatDurationLong(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return buildString {
        if (hours > 0) append(if (hours == 1L) "1 hour " else "$hours hours ")
        if (hours > 0 || minutes > 0) append(if (minutes == 1L) "1 minute " else "$minutes minutes ")
        append(if (seconds == 1L) "1 second" else "$seconds seconds")
    }
}

/** Formats a unix timestamp (seconds) as local date-time, e.g. `2026-08-02 01:30:52`. */
internal fun formatEpochSeconds(epochSeconds: Long): String =
    Instant.ofEpochSecond(epochSeconds)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US))

/**
 * Formats a KiB/s rate (the `disk` reporting graph's unit — curl-verified via `GET
 * /reporting/graphs`'s `vertical_label: "Kibibytes/s"`) as a fixed MiB/s value (not auto-scaled
 * like [formatBytes]), e.g. `12.34 MiB/s` — used for the per-disk I/O graphs, where a single fixed
 * unit across the whole chart reads better than switching units between small and large values.
 */
internal fun formatMebibytesPerSecond(kibibytesPerSecond: Double): String =
    String.format(Locale.US, "%.2f MiB/s", kibibytesPerSecond / 1024.0)

/** Formats a temperature in Celsius, e.g. `38 °C`. */
internal fun formatCelsius(value: Double): String =
    String.format(Locale.US, "%.0f °C", value)
