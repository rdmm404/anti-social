package org.voltr.antisocial.core

/**
 * A DASH manifest for extracted tracks, so the player can switch quality on its own and seek
 * by fetching only the ranges it needs, the same way YouTube's own players do.
 */
fun dashManifest(streams: Streams): String {
    val seconds = streams.details.durationSeconds.coerceAtLeast(1)
    return buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" """)
        append("""type="static" minBufferTime="PT1.5S" mediaPresentationDuration="PT${seconds}S">""").append('\n')
        append("""<Period>""").append('\n')

        // One adaptation set per codec family, since players only switch within a set.
        streams.video.groupBy { it.codecs.substringBefore('.') }.values.forEachIndexed { set, tracks ->
            append("""<AdaptationSet id="$set" contentType="video" mimeType="video/mp4" subsegmentAlignment="true">""").append('\n')
            tracks.forEach { t -> representation(t, """width="${t.width}" height="${t.height}"""" + if (t.fps > 0) """ frameRate="${t.fps}"""" else "") }
            append("</AdaptationSet>\n")
        }
        val audio = streams.audio
        // Ids must be numbers: Media3 parses them as such.
        append("""<AdaptationSet id="$AUDIO_SET" contentType="audio" mimeType="audio/mp4" subsegmentAlignment="true">""").append('\n')
        representation(audio, if (audio.sampleRate > 0) """audioSamplingRate="${audio.sampleRate}"""" else "") {
            append("""<AudioChannelConfiguration schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" value="${audio.channels}"/>""")
        }
        append("</AdaptationSet>\n")
        append("</Period>\n</MPD>\n")
    }
}

private const val AUDIO_SET = 100

private fun StringBuilder.representation(t: Track, attributes: String, extra: StringBuilder.() -> Unit = {}) {
    val id = (t.codecs + "-" + t.height + "-" + t.bitrate).replace(Regex("[^A-Za-z0-9.-]"), "")
    append("""<Representation id="$id" codecs="${xml(t.codecs)}" bandwidth="${t.bitrate}" $attributes>""")
    extra()
    append("<BaseURL>").append(xml(t.url)).append("</BaseURL>")
    append("""<SegmentBase indexRange="${t.index.first}-${t.index.last}">""")
    append("""<Initialization range="${t.init.first}-${t.init.last}"/>""")
    append("</SegmentBase></Representation>\n")
}

private fun xml(text: String) = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
