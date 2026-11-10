package org.voltr.antisocial.core

/** A piece of a description or comment: words, a link, or a timestamp to seek to. */
sealed interface TextPart {
    val text: String

    data class Plain(override val text: String) : TextPart
    data class Url(override val text: String) : TextPart
    data class Time(override val text: String, val seconds: Int) : TextPart
}

// The web app's rule (linkified in app.js): a URL, or m:ss / h:mm:ss standing on its own.
private val PARTS = Regex("""https?://\S+|(?<![\w:.])(?:\d{1,2}:)?\d{1,2}:\d{2}(?![\w:])""")

/**
 * Splits text into words, links and timestamps. A timestamp past the end of the video, or with
 * minutes or seconds of 60 and up, stays plain words.
 */
fun linkParts(text: String, durationSeconds: Long): List<TextPart> {
    val parts = mutableListOf<TextPart>()
    var at = 0
    for (match in PARTS.findAll(text)) {
        if (match.range.first > at) parts += TextPart.Plain(text.substring(at, match.range.first))
        val value = match.value
        parts += if (value.startsWith("http")) {
            TextPart.Url(value)
        } else {
            timestampSeconds(value)?.takeIf { it <= durationSeconds }?.let { TextPart.Time(value, it) } ?: TextPart.Plain(value)
        }
        at = match.range.last + 1
    }
    if (at < text.length) parts += TextPart.Plain(text.substring(at))
    return parts
}

private fun timestampSeconds(text: String): Int? {
    val numbers = text.split(':').map { it.toInt() }
    if (numbers.drop(1).any { it >= 60 }) return null
    return numbers.fold(0) { total, n -> total * 60 + n }
}
