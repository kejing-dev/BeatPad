package io.embodit.beatpad.domain.beat

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt

/** Bar / step lengths in audio frames (the audio clock is the only clock, audioEngine.clock). */
object Tempo {
    fun stepFrames(bpm: Double, sr: Int): Double = sr * 60.0 / bpm / 4.0
    fun beatFrames(bpm: Double, sr: Int): Double = sr * 60.0 / bpm
    fun barFrames(bpm: Double, sr: Int): Double = stepFrames(bpm, sr) * STEPS

    /**
     * Nearest sixteenth for a musical frame, relative to any bar start [barStart] (loopEngine.record: always quantised;
     * a hit in the last 1/32 of the bar rounds to step 0 of the next pass).
     */
    fun quantize(frame: Double, barStart: Double, bpm: Double, sr: Int): Int {
        val bar = barFrames(bpm, sr)
        val rel = ((frame - barStart) % bar + bar) % bar
        return (rel / stepFrames(bpm, sr)).roundToInt() % STEPS
    }
}

/**
 * Speed knob (T9): hold and drag up / down, 1 cm = 2 BPM for every input; 60…160; soft snap at 80 / 100 / 120 within
 * ±2 BPM (hysteresis: once snapped it stays until the raw value leaves the ±2 band).
 */
class HubDrag(startBpm: Int) {
    private val start = startBpm.toFloat()
    var snappedTo: Int? = null
        private set

    /** [dyM] = upward travel of the pointer in metres since the press. Returns the new BPM and whether it just snapped. */
    fun move(dyM: Float): Pair<Int, Boolean> {
        val raw = (start + dyM * 100f * 2f).coerceIn(BPM_MIN.toFloat(), BPM_MAX.toFloat())
        val near = SNAPS.firstOrNull { abs(raw - it) <= SNAP_BAND }
        val justSnapped = near != null && near != snappedTo
        snappedTo = near
        return (near ?: raw.roundToInt()) to justSnapped
    }

    companion object {
        val SNAPS = listOf(80, 100, 120)
        const val SNAP_BAND = 2f
    }
}

/**
 * Beat-ring slot under a dragged stone (beatRingEditing.dragStone): angle on the ring plane → nearest slot with 0.35-slot
 * hysteresis; further than [OFF_M] outside the outer ring = "delete on release". Slot 0 at 12 o'clock, clockwise.
 */
class RingDrag(var slot: Int) {
    var off = false
        private set

    /** [x] right, [y] up in the ring plane, metres from the ring centre. */
    fun move(x: Float, y: Float): Int {
        val r = kotlin.math.sqrt(x * x + y * y)
        off = r > RING_OUTER_M + OFF_M
        if (r < 0.02f) return slot
        val a = atan2(x.toDouble(), y.toDouble()) // 0 at top, + clockwise (x right)
        var pos = (a / (2 * Math.PI) * STEPS).toFloat()
        if (pos < 0) pos += STEPS
        // distance from the current slot, wrapped to ±8
        var d = pos - slot
        while (d > STEPS / 2f) d -= STEPS
        while (d < -STEPS / 2f) d += STEPS
        if (abs(d) > 0.5f + HYSTERESIS) slot = ((floor(pos + 0.5f).toInt()) % STEPS + STEPS) % STEPS
        return slot
    }

    companion object {
        const val HYSTERESIS = 0.35f
        const val RING_OUTER_M = 0.215f
        const val OFF_M = 0.10f
        fun slotAngle(slot: Int): Double = 2 * Math.PI * slot / STEPS
    }
}

/** input.stickHit.velocity: 0.4 → 3.0 m/s on a log curve → 0.35 → 1.0; feel light / normal / heavy shifts it 30 %. */
object HitCurve {
    const val MIN_SPEED = 0.4f
    const val MAX_SPEED = 3.0f
    fun gain(speed: Float, feel: Int = 1): Float {
        val k = when (feel) { 0 -> 1.3f; 2 -> 0.7f; else -> 1f }
        val s = (speed * k).coerceIn(MIN_SPEED, MAX_SPEED)
        val t = (ln(s / MIN_SPEED) / ln(MAX_SPEED / MIN_SPEED)).toFloat()
        return 0.35f + 0.65f * t
    }
}
