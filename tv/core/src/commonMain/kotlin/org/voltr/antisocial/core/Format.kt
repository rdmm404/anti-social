package org.voltr.antisocial.core

import kotlin.math.ceil

/** 65 -> 1:05, 3700 -> 1:01:40 */
fun durationText(seconds: Long): String {
    val total = seconds.coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = (total % 60).toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$secs" else "$minutes:$secs"
}

/** 90 -> 2m, 3660 -> 1h 1m. Rounds up, so the last few seconds still show as a minute. */
fun minutesText(seconds: Long): String {
    val minutes = ceil(seconds / 60.0).toLong()
    if (minutes < 60) return "${minutes}m"
    val rest = minutes % 60
    return if (rest > 0) "${minutes / 60}h ${rest}m" else "${minutes / 60}h"
}

fun videosText(count: Int): String = "$count ${if (count == 1) "video" else "videos"}"

/** 950 -> 950, 1_234 -> 1.2K, 3_400_000 -> 3.4M. One decimal below ten, none above. */
fun countText(count: Long): String {
    if (count < 1000) return count.toString()
    val (value, unit) = when {
        count >= 1_000_000_000 -> count / 1_000_000_000.0 to "B"
        count >= 1_000_000 -> count / 1_000_000.0 to "M"
        else -> count / 1000.0 to "K"
    }
    val text = if (value < 10) {
        val tenths = (value * 10).toLong()
        if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
    } else {
        value.toLong().toString()
    }
    return text + unit
}

/** The parts of a video's line that exist: "1.2M views · 3 days ago". */
fun videoMeta(views: Long?, uploaded: String?, likes: Long? = null): String =
    listOfNotNull(
        views?.let { "${countText(it)} ${if (it == 1L) "view" else "views"}" },
        uploaded?.takeIf { it.isNotBlank() },
        likes?.let { "${countText(it)} ${if (it == 1L) "like" else "likes"}" },
    ).joinToString(" · ")
