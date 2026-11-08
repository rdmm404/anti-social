package org.voltr.antisocial.core

/**
 * Strip the clickbait: plain titles, and frames instead of chosen thumbnails.
 *
 * YouTube generates hq1, hq2 and hq3 from the video itself, so they can't be a custom
 * thumbnail. hq2 is the midpoint. They're letterboxed to 4:3; screens crop that to 16:9.
 */
fun frameUrl(videoId: String) = "https://i.ytimg.com/vi/$videoId/hq2.jpg"

private val baitTags = Regex(
    """[\[(]\s*(must watch|gone wrong|shocking|insane|not clickbait|emotional|you won'?t believe)[^\])]*[\])]""",
    RegexOption.IGNORE_CASE,
)

private val dropped = setOf(
    CharCategory.OTHER_SYMBOL,
    CharCategory.MODIFIER_SYMBOL,
    CharCategory.SURROGATE, // Emoji outside the basic plane arrive as surrogate pairs.
    CharCategory.FORMAT,
)

/** Drop the shouting and decoration. */
fun cleanTitle(title: String): String {
    var text = title.filter { it.category !in dropped }
    text = baitTags.replace(text, "")
    text = Regex("""(^|\s)#\w+""").replace(text, " ")
    text = Regex("!+").replace(text, "")
    text = Regex("""\?+""").replace(text, "?")
    text = Regex("""\.{4,}""").replace(text, "...")
    val words = text.split(Regex("""\s+""")).filter { it.isNotEmpty() }

    val letters = text.filter { it.isLetter() }
    val mostlyCaps = letters.length > 3 && letters.count { it.isUpperCase() }.toDouble() / letters.length > 0.7

    fun tame(word: String): String {
        val bare = word.filter { it.isLetterOrDigit() || it == '_' }
        val shouted = bare.any { it.isLetter() } && bare.none { it.isLowerCase() }
        return if (shouted && (mostlyCaps || bare.length > 4)) word.lowercase() else word
    }

    val result = words.joinToString(" ") { tame(it) }.trim(' ', '-', '|', ':')
    if (result.isEmpty()) return title.trim()
    // Leave words like "iPhone" alone: only capitalize a first word that's all lowercase.
    val first = result.split(' ').first()
    val lowercaseWord = first.any { it.isLetter() } && first.none { it.isUpperCase() }
    return if (lowercaseWord) result.replaceFirstChar { it.uppercaseChar() } else result
}
