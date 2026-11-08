package org.voltr.antisocial.core

import kotlin.math.max
import kotlin.math.min
import kotlin.time.TimeSource

/**
 * The stretches of a video that played since they were last taken, merged. Positions come from
 * polling the player: a step forward of a moment is watching, anything else (a seek, a pause,
 * a stall) only moves the starting point.
 */
class WatchedStretches(private val maxStepSeconds: Double = 3.0) {
    private val merged = mutableListOf<DoubleArray>()
    private var last: Double? = null

    val isEmpty get() = merged.isEmpty()

    fun observe(positionSeconds: Double, playing: Boolean) {
        val previous = last
        last = positionSeconds
        if (!playing || previous == null) return
        val step = positionSeconds - previous
        if (step > 0 && step <= maxStepSeconds) add(previous, positionSeconds)
    }

    /** Forget the last position, so the next observation starts fresh, as after a seek. */
    fun lostTrack() {
        last = null
    }

    fun add(start: Double, end: Double) {
        if (end <= start) return
        var lo = start
        var hi = end
        val iterator = merged.iterator()
        while (iterator.hasNext()) {
            val s = iterator.next()
            if (s[1] >= lo && s[0] <= hi) {
                lo = min(lo, s[0])
                hi = max(hi, s[1])
                iterator.remove()
            }
        }
        merged.add(doubleArrayOf(lo, hi))
        merged.sortBy { it[0] }
    }

    fun take(): List<Stretch> = merged.map { listOf(it[0], it[1]) }.also { merged.clear() }

    /** Return stretches a failed check-in couldn't report, so the next one does. */
    fun putBack(stretches: List<Stretch>) = stretches.forEach { add(it[0], it[1]) }
}

/** Whether playback may go on, from the latest check-in. */
sealed interface Verdict {
    /** Nothing asked yet. */
    data object Unknown : Verdict

    data class Allowed(val status: Status) : Verdict

    /** The server said stop. [status] says why and what happens next. */
    data class Refused(val outOfTime: Boolean, val status: Status) : Verdict

    /** The server couldn't be asked. Playback goes on until the last expiry. */
    data class Unreachable(val message: String) : Verdict

    data object SignedOut : Verdict
}

/**
 * Check-ins for one video (ADR 0001). Before it plays, and about every 15 seconds while it
 * does, the client reports what it watched; the server charges it and answers with an expiry.
 * Playback stops when the server refuses, or when the expiry passes without an answer.
 *
 * The expiry is measured on this device's monotonic clock from when the check-in was sent, so
 * a wrong wall clock on either side can't stretch it.
 */
class Watch(
    private val api: Api,
    private val details: VideoDetails,
    private val now: () -> Long = monotonicMillis(),
) {
    val stretches = WatchedStretches()

    var verdict: Verdict = Verdict.Unknown
        private set

    private var allowedUntil = Long.MIN_VALUE
    private var lastAnswer: Long? = null
    private var everyMillis = 15_000L
    private var inFlight = false

    /** Whether the latest check-in still covers playing. */
    fun mayPlay(): Boolean = now() < allowedUntil

    /** Whether it's time to check in again. Never while one is still waiting for its answer. */
    fun due(): Boolean = !inFlight && lastAnswer.let { it == null || now() - it >= everyMillis }

    /**
     * Report what was watched since the last check-in, and hear whether to go on. While one is
     * waiting for its answer, another call only returns the latest verdict.
     */
    suspend fun checkIn(): Verdict {
        if (inFlight) return verdict
        inFlight = true
        try {
            return send()
        } finally {
            inFlight = false
        }
    }

    private suspend fun send(): Verdict {
        val sent = stretches.take()
        val sentAt = now()
        val reply = try {
            api.checkin(Checkin(details.id, details.title, details.channel, sent))
        } catch (e: ApiError) {
            stretches.putBack(sent)
            lastAnswer = sentAt
            if (e.signedOut) {
                allowedUntil = Long.MIN_VALUE
                return Verdict.SignedOut.also { verdict = it }
            }
            // Keep the last expiry: a moment offline shouldn't stop the video.
            return Verdict.Unreachable(e.message ?: "Can't reach the anti-social server.").also { verdict = it }
        }
        lastAnswer = sentAt
        everyMillis = reply.status.checkinSeconds.coerceAtLeast(5) * 1000L
        allowedUntil = if (reply.allowed) {
            sentAt + ((reply.expiresAt - reply.status.now) * 1000).toLong()
        } else {
            Long.MIN_VALUE
        }
        verdict = if (reply.allowed) {
            Verdict.Allowed(reply.status)
        } else {
            Verdict.Refused(outOfTime = reply.reason == "out_of_time", status = reply.status)
        }
        return verdict
    }
}

fun monotonicMillis(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds }
}
