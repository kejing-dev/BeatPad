package io.embodit.beatpad.domain.beat

import io.embodit.beatpad.domain.math.Vec3

/** One pad's top face in Stage space: centre, unit normal (tilted toward the player), face radius. */
data class PadFace(val center: Vec3, val normal: Vec3, val radius: Float)

data class StrikeEvent(val pad: Int, val speed: Float, val timeNs: Long, val at: Vec3)

/**
 * Stick-tip (or finger-tip) strike detection (input.stickHit). Only the tip counts (Paradiddle): a strike is the tip
 * centre crossing a pad's top plane from above, inside face radius + 1 cm, with a normal speed ≥ [minSpeed]. The
 * crossing time is interpolated between the two frames (a 3 m/s swing moves 4 cm per frame). After a strike the tip
 * must rise [rearmM] above the face before that pad counts again; one pad takes at most one strike per [minGapNs].
 * Pure Kotlin: the Stage feeds one tip sample per frame per tracked tip.
 */
class StrikeDetector(
    private val minSpeed: Float = 0.4f,
    private val rearmM: Float = 0.015f,
    private val minGapNs: Long = 50_000_000L,
    private val extraRadius: Float = 0.01f,
    private val tipRadius: Float = 0.015f,
) {
    private var prev: Vec3? = null
    private var prevNs = 0L
    private val armed = BooleanArray(PADS) { true }
    private val lastNs = LongArray(PADS) { Long.MIN_VALUE / 2 }

    fun reset() { prev = null }

    fun feed(tip: Vec3, timeNs: Long, pads: List<PadFace>): List<StrikeEvent> {
        val p0 = prev
        val t0 = prevNs
        prev = tip; prevNs = timeNs
        val out = ArrayList<StrikeEvent>(1)
        for ((i, pad) in pads.withIndex()) {
            // the tip sphere's lowest point touches the face when its centre is tipRadius above it
            val d1 = (tip - pad.center).dot(pad.normal) - tipRadius
            if (!armed[i] && d1 >= rearmM) armed[i] = true
            if (p0 == null || timeNs <= t0) continue
            val d0 = (p0 - pad.center).dot(pad.normal) - tipRadius
            if (!(d0 > 0f && d1 <= 0f) || !armed[i]) continue
            val f = d0 / (d0 - d1)
            val at = p0 + (tip - p0) * f
            val radial = (at - pad.center).rejectFrom(pad.normal).length
            if (radial > pad.radius + extraRadius) continue
            val dt = (timeNs - t0) / 1e9f
            val speed = (d0 - d1) / dt
            if (speed < minSpeed) continue
            val hitNs = t0 + ((timeNs - t0) * f).toLong()
            if (hitNs - lastNs[i] < minGapNs) continue
            armed[i] = false
            lastNs[i] = hitNs
            out += StrikeEvent(i, speed, hitNs, at)
        }
        return out
    }
}
