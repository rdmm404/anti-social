package org.voltr.antisocial.core

import kotlin.test.Test
import kotlin.test.assertEquals

class LinkifyTest {
    @Test
    fun `links and timestamps are picked out`() {
        assertEquals(
            listOf(
                TextPart.Time("0:00", 0),
                TextPart.Plain(" Intro\n"),
                TextPart.Time("1:02:03", 3723),
                TextPart.Plain(" End, see "),
                TextPart.Url("https://example.com/a?b=c"),
                TextPart.Plain(" now"),
            ),
            linkParts("0:00 Intro\n1:02:03 End, see https://example.com/a?b=c now", 4000),
        )
    }

    @Test
    fun `impossible or late timestamps and look-alikes stay words`() {
        assertEquals(listOf(TextPart.Plain("at 1:75 or 9:00")), linkParts("at 1:75 or 9:00", 300).let { merge(it) })
        assertEquals(listOf(TextPart.Plain("v1.2:30 and 10:30:00:00")), merge(linkParts("v1.2:30 and 10:30:00:00", 99999)))
    }

    private fun merge(parts: List<TextPart>) =
        if (parts.all { it is TextPart.Plain }) listOf(TextPart.Plain(parts.joinToString("") { it.text })) else parts
}
