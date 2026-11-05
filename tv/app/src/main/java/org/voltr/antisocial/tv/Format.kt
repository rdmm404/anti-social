package org.voltr.antisocial.tv

import java.text.DateFormat
import java.util.Date
import kotlin.math.ceil

/** 65 -> 1:05, 3700 -> 1:01:40 */
fun durationText(seconds: Long): String {
    val total = seconds.coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = "%02d".format(total % 60)
    return if (hours > 0) "$hours:${"%02d".format(minutes)}:$secs" else "$minutes:$secs"
}

/** 90 -> 2m, 3660 -> 1h 1m. Rounds up, like the web clock. */
fun minutesText(seconds: Long): String {
    val minutes = ceil(seconds / 60.0).toLong()
    if (minutes < 60) return "${minutes}m"
    val rest = minutes % 60
    return if (rest > 0) "${minutes / 60}h ${rest}m" else "${minutes / 60}h"
}

fun videosText(count: Int): String = "$count ${if (count == 1) "video" else "videos"}"

fun timeOfDay(timestamp: Double): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date((timestamp * 1000).toLong()))
