package org.voltr.antisocial.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.voltr.antisocial.core.Streams
import org.voltr.antisocial.core.Track
import org.voltr.antisocial.core.VideoDetails
import org.voltr.antisocial.core.dashManifest

/** The manifest core builds has to be one Media3 reads the way it's meant. */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManifestTest {
    @Test
    fun `media3 reads every track, its ranges and the duration`() {
        fun video(codecs: String, height: Int) =
            Track("https://x/$height?a=1&b=2", "video/mp4", codecs, height * 1000, 0L..740L, 741L..2000L, height * 16 / 9, height, 30)
        val streams = Streams(
            VideoDetails("abcdefghijk", "T", "C", null, 125),
            listOf(video("avc1.4D401F", 480), video("avc1.64002A", 1080)),
            Track("https://x/a", "audio/mp4", "mp4a.40.2", 130_000, 0L..631L, 632L..999L, sampleRate = 44_100),
        )
        val manifest = DashManifestParser().parse(Uri.parse("https://www.youtube.com/"), dashManifest(streams).byteInputStream())
        assertEquals(125_000L, manifest.durationMs)
        val sets = manifest.getPeriod(0).adaptationSets
        assertEquals(listOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO), sets.map { it.type })
        val video = sets[0].representations
        assertEquals(listOf(480, 1080), video.map { it.format.height })
        assertEquals("https://x/480?a=1&b=2", video[0].baseUrls[0].url)
        val index = video[0].indexUri!!
        assertEquals(741L to 1260L, index.start to index.length)
        assertEquals(0L to 741L, video[0].initializationUri!!.let { it.start to it.length })
        assertEquals(44_100, sets[1].representations[0].format.sampleRate)
    }
}
