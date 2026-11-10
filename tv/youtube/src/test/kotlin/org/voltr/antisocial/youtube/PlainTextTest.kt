package org.voltr.antisocial.youtube

import org.schabi.newpipe.extractor.stream.Description
import kotlin.test.Test
import kotlin.test.assertEquals

class PlainTextTest {
    @Test
    fun `links get their address back, timestamps and tags keep their words`() {
        val html = "Intro<br>" +
            "<a href=\"https://www.youtube.com/watch?v=abcdefghijk&amp;t=90s\">1:30</a> The middle<br>" +
            "Code: <a href=\"https://github.com/someone/project/tree/main\">https://github.com/someone/pr...</a><br>" +
            "<a href=\"https://www.youtube.com/hashtag/cats\">#cats</a> &amp; <a href=\"/@someone\">@someone</a> &quot;hi&quot; &#39;x&#39; &#x1F600;"
        assertEquals(
            "Intro\n1:30 The middle\nCode: https://github.com/someone/project/tree/main\n#cats & @someone \"hi\" 'x' 😀",
            plainText(Description(html, Description.Type.HTML)),
        )
    }

    @Test
    fun `plain text stays as it is`() {
        assertEquals("a <b> & c", plainText(Description(" a <b> & c ", Description.Type.PLAIN_TEXT)))
        assertEquals("", plainText(null))
    }

    @Test
    fun `video ids come from watch and shorts urls`() {
        assertEquals("abcdefghijk", videoIdOf("https://www.youtube.com/watch?v=abcdefghijk"))
        assertEquals("abcdefghijk", videoIdOf("https://www.youtube.com/shorts/abcdefghijk"))
        assertEquals(null, videoIdOf("https://www.youtube.com/channel/UCx"))
    }
}
