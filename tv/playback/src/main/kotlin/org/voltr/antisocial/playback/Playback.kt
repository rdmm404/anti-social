package org.voltr.antisocial.playback

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import org.voltr.antisocial.core.Streams
import org.voltr.antisocial.core.dashManifest

/** Above 1080p YouTube only offers AV1, which a stick can't decode in software. */
fun hasHardwareAv1(): Boolean =
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
        !codec.isEncoder &&
            codec.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AV1, ignoreCase = true) } &&
            if (Build.VERSION.SDK_INT >= 29) {
                codec.isHardwareAccelerated
            } else {
                !codec.name.startsWith("OMX.google.") && !codec.name.startsWith("c2.android.")
            }
    }

/**
 * A player for extracted streams. It fetches from YouTube directly, in ranges, choosing the
 * quality itself. Nothing it fetches is charged: check-ins report what was watched.
 */
@OptIn(UnstableApi::class)
fun buildPlayer(context: Context, streams: Streams): ExoPlayer {
    val http = DefaultHttpDataSource.Factory()
        .setUserAgent(streams.headers["User-Agent"] ?: "anti-social")
        .setDefaultRequestProperties(streams.headers - "User-Agent")
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)
    val manifestUri = Uri.parse("https://www.youtube.com/watch?v=${streams.details.id}")
    val manifest = DashManifestParser().parse(manifestUri, dashManifest(streams).byteInputStream())
    val source = DashMediaSource.Factory(http).createMediaSource(manifest, MediaItem.fromUri(manifestUri))
    return ExoPlayer.Builder(context)
        .setTrackSelector(DefaultTrackSelector(context))
        .build()
        .apply { setMediaSource(source) }
}

/** One choice on the quality button. A null override means let the player decide. */
data class Quality(val label: String, val override: TrackSelectionOverride?)

@OptIn(UnstableApi::class)
fun qualitiesOf(tracks: Tracks): List<Quality> {
    val choices = tracks.groups
        .filter { it.type == C.TRACK_TYPE_VIDEO }
        .flatMap { group ->
            (0 until group.length)
                .filter { group.isTrackSupported(it) && group.getTrackFormat(it).height > 0 }
                .map { index -> group.getTrackFormat(index).height to TrackSelectionOverride(group.mediaTrackGroup, index) }
        }
        .sortedByDescending { it.first }
        .distinctBy { it.first }
        .map { (height, override) -> Quality("${height}p", override) }
    return listOf(Quality("auto", null)) + choices
}

@OptIn(UnstableApi::class)
fun ExoPlayer.applyQuality(override: TrackSelectionOverride?) {
    val builder = trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
    if (override != null) builder.addOverride(override)
    trackSelectionParameters = builder.build()
}

/** Whether YouTube refused a stream URL, which goes stale after some hours. */
@OptIn(UnstableApi::class)
fun Throwable.isStaleStream(): Boolean =
    generateSequence(this) { it.cause }.any {
        it is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException && it.responseCode in setOf(403, 410)
    }
