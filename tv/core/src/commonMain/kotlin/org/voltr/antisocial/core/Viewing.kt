package org.voltr.antisocial.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The few things the check-in loop needs from a player. Calls arrive on the player's thread. */
interface PlayerControl {
    val positionSeconds: Double
    val isPlaying: Boolean
    fun play()
    fun pause()
}

/**
 * One video's check-in loop, apart from any screen (ADR 0001): it plays only while check-ins
 * allow it. Whoever owns the player calls [tick] every half second, and routes the person's
 * play and pause through [play] and [pause], so nothing can start playback without a check-in.
 */
class Viewing(
    private val watch: Watch,
    private val player: PlayerControl,
    private val budget: Budget,
) {
    private val _held = MutableStateFlow<Verdict?>(Verdict.Unknown)

    /** Why playback is held, when it is. Null while check-ins allow playing. */
    val held: StateFlow<Verdict?> = _held.asStateFlow()

    /** Whether the person wants it playing, whatever the check-ins say right now. */
    var wanted = true
        private set

    /** Set once the video has finished or failed. Nothing plays again until it's cleared. */
    var ended = false

    /** Check in, and play or hold by the answer. */
    suspend fun checkIn() {
        when (val verdict = watch.checkIn()) {
            is Verdict.Allowed -> {
                budget.update(verdict.status)
                _held.value = null
                if (wanted && !ended) player.play()
            }
            is Verdict.Refused -> {
                budget.update(verdict.status)
                _held.value = verdict
                player.pause()
            }
            Verdict.SignedOut -> {
                _held.value = verdict
                player.pause()
                budget.refresh()
            }
            // Playing goes on until the last check-in's expiry; tick stops it then.
            is Verdict.Unreachable -> if (!watch.mayPlay()) _held.value = verdict
            Verdict.Unknown -> Unit
        }
    }

    /** Watches the playhead, checks in on time, and stops playing once nothing allows it. */
    suspend fun tick() {
        watch.stretches.observe(player.positionSeconds, player.isPlaying)
        if (watch.due() && (player.isPlaying || !watch.stretches.isEmpty || _held.value != null)) checkIn()
        if (player.isPlaying && !watch.mayPlay()) {
            player.pause()
            // An expired allowance with its answer still on the way reads as checking in.
            _held.value = watch.verdict.takeUnless { it is Verdict.Allowed } ?: Verdict.Unknown
        }
    }

    suspend fun play() {
        wanted = true
        if (ended) return
        if (watch.mayPlay()) player.play() else checkIn()
    }

    fun pause() {
        wanted = false
        player.pause()
    }

    /** A seek isn't watching: the next position starts a new stretch. */
    fun seeked() = watch.stretches.lostTrack()

    /** A session opened from the gate, or the day reset: ask again and carry on from the same spot. */
    suspend fun statusChanged(state: SessionState?) {
        if (_held.value is Verdict.Refused && state == SessionState.OPEN) checkIn()
    }

    /** The video is closing. Report its last seconds; the caller keeps this alive until it returns. */
    suspend fun close() {
        watch.stretches.observe(player.positionSeconds, player.isPlaying)
        player.pause()
        if (!watch.stretches.isEmpty) watch.checkIn()
    }
}
