package org.voltr.antisocial.tv

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/** Whether the server refused this load because today's time ran out. */
@OptIn(UnstableApi::class)
fun Throwable.isOutOfTime(): Boolean =
    generateSequence(this) { it.cause }.any {
        it is HttpDataSource.InvalidResponseCodeException && it.responseCode == ApiError.OUT_OF_TIME
    }

/** A 402 won't change by asking again, so stop and let the player report it. */
@OptIn(UnstableApi::class)
class OutOfTimeAwareErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (info.exception.isOutOfTime()) C.TIME_UNSET else super.getRetryDelayMsFor(info)
}

/** Above 1080p the server only offers AV1, which a stick can't decode in software. */
private fun hasHardwareAv1(): Boolean =
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
        !codec.isEncoder &&
            codec.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AV1, ignoreCase = true) } &&
            if (Build.VERSION.SDK_INT >= 29) {
                codec.isHardwareAccelerated
            } else {
                !codec.name.startsWith("OMX.google.") && !codec.name.startsWith("c2.android.")
            }
    }

@OptIn(UnstableApi::class)
fun buildPlayer(context: Context, playlistUrl: String): ExoPlayer {
    val selector = DefaultTrackSelector(context)
    if (!hasHardwareAv1()) {
        selector.setParameters(selector.buildUponParameters().setMaxVideoSize(1920, 1080))
    }
    // Everything buffered is charged, so keep the lead short, as the web player does.
    val buffering = DefaultLoadControl.Builder()
        .setBufferDurationsMs(12_000, 20_000, 1_500, 3_000)
        .setBackBuffer(30_000, false)
        .build()
    val source = HlsMediaSource.Factory(DefaultHttpDataSource.Factory().setUserAgent("anti-social-tv"))
        .setLoadErrorHandlingPolicy(OutOfTimeAwareErrorPolicy())
        .createMediaSource(MediaItem.fromUri(playlistUrl))
    return ExoPlayer.Builder(context)
        .setTrackSelector(selector)
        .setLoadControl(buffering)
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
                .map { index ->
                    group.getTrackFormat(index).height to
                        TrackSelectionOverride(group.mediaTrackGroup, index)
                }
        }
        .sortedByDescending { it.first }
        .map { (height, override) -> Quality("${height}p", override) }
    return listOf(Quality("auto", null)) + choices
}
