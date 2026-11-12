package io.embodit.beatpad.platform.audio

import io.embodit.beatpad.domain.beat.KitId
import io.embodit.beatpad.domain.beat.Kits
import io.embodit.beatpad.domain.beat.Layout
import io.embodit.beatpad.domain.beat.PADS
import io.embodit.beatpad.domain.beat.Pattern
import io.embodit.beatpad.domain.beat.STEPS
import io.embodit.beatpad.domain.beat.Tempo
import io.embodit.beatpad.domain.beat.Velocity
import kotlin.math.abs
import kotlin.math.sin

/**
 * The app's own low-latency mixer + sequencer (audioEngine): every sample is decoded to PCM at start, every loop hit is
 * placed on an exact audio frame, and the audio clock (frames rendered) is the only clock. Pure Kotlin, no Android:
 * the same object runs inside the AudioTrack thread ([AudioEngine]) and offline in unit tests (loudness / solo renders,
 * quantisation, count-in). All public methods and [render] are synchronised on this object; render buffers are
 * ≤ 4 ms so a UI call never waits longer than that.
 *
 *  - 24 voices; a new hit on the same pad fades the previous one out in 10 ms; closed hat chokes open hat; when all
 *    voices are busy the oldest fades out.
 *  - pan by pad azimuth (az −27° → L 0.72 / R 0.28); ±3 % pitch jitter per hit (D51); bus limiter at −1 dBFS.
 *  - transport: stopped / playing, count-in (4 metronome clicks), recording with quantise-on-the-fly and the
 *    "already scheduled" guard (R1-05b): a hit quantised to a step this pass has not rendered yet is not replayed.
 */
class Mixer(val sr: Int = 48000, seed: Long = 7L) {
    // ------------------------------------------------------------------ samples
    private val samples = HashMap<String, FloatArray>()
    private val samplePeak = HashMap<String, Float>()
    fun putSample(key: String, data: FloatArray) = synchronized(this) { samples[key] = data; samplePeak[key] = peakAt(data, 1.0) }

    /**
     * q1-1 (M1「每下都一样响」): the ±3 % pitch jitter resamples with linear interpolation, which moves the peak by up to
     * ~1 dB. Each jittered hit is scaled back to the sample's own peak, so equal swings give equal peaks (< 1 dB).
     */
    private fun peakAt(data: FloatArray, rate: Double): Float {
        val end = minOf(data.size - 1, (sr * PEAK_SCAN_S).toInt())
        var p = 0.0; var m = 0f
        while (true) {
            val idx = p.toInt(); if (idx + 1 > end) break
            val f = (p - idx).toFloat()
            val s = abs(data[idx] + (data[idx + 1] - data[idx]) * f)
            if (s > m) m = s
            p += rate
        }
        return m
    }
    fun hasSample(key: String) = synchronized(this) { samples.containsKey(key) }
    fun removeSample(key: String) = synchronized(this) { samples.remove(key) }

    // ------------------------------------------------------------------ settings (read under the lock)
    var master = 0.75f
        set(v) = synchronized(this) { field = v }
    var muted = false
        set(v) = synchronized(this) { field = v }
    /** Metronome level relative to master: −12 dB by default (assets.sounds 节拍器 比鼓轻 −12 dB). */
    var metroGain = 0.25f
        set(v) = synchronized(this) { field = v }
    /** 0 = only during the count-in, 1 = always while playing, 2 = never. */
    var metroMode = 0
        set(v) = synchronized(this) { field = v }
    var kit = KitId.ACOUSTIC
        set(v) = synchronized(this) { field = v }
    /** Pad → sample key of an imported sound (any kit). */
    var overrides: Map<Int, String> = emptyMap()
        set(v) = synchronized(this) { field = v }

    // ------------------------------------------------------------------ transport
    var pattern: Pattern = Pattern.EMPTY
        @Synchronized get
        @Synchronized set
    var bpm = 96.0
        private set
    var playing = false
        private set
    var recording = false
        private set
    /** Frames rendered so far (the audio clock). */
    var rendered = 0L
        private set
    private var barStart = 0.0
    private var nextStepTime = 0.0
    private var nextStepIdx = 0
    private var countIn: CountIn? = null
    private var pendingLoad: Triple<Pattern, Double, KitId>? = null
    private val suppress = BooleanArray(PADS * STEPS)

    private class CountIn(val clicks: DoubleArray, val recStart: Double, val whilePlaying: Boolean) { var next = 0 }

    data class Snapshot(
        val playing: Boolean, val recording: Boolean, val countingIn: Boolean, val countClicksPlayedBefore: DoubleArray?,
        val recStart: Double, val barStart: Double, val bpm: Double, val rendered: Long, val pattern: Pattern,
    )

    fun snapshot(): Snapshot = synchronized(this) {
        Snapshot(playing, recording, countIn != null, countIn?.clicks?.copyOf(), countIn?.recStart ?: 0.0, barStart, bpm, rendered, pattern)
    }

    private val stepF get() = Tempo.stepFrames(bpm, sr)
    private val barF get() = Tempo.barFrames(bpm, sr)
    private val beatF get() = Tempo.beatFrames(bpm, sr)

    /** Starts the loop from step 0 at the next render. */
    fun play() = synchronized(this) {
        if (playing) return
        playing = true
        barStart = rendered.toDouble(); nextStepTime = barStart; nextStepIdx = 0
        suppress.fill(false)
    }

    /** Stops everything (loop, count-in, recording); ringing voices finish naturally. */
    fun stop() = synchronized(this) { playing = false; recording = false; countIn = null; pendingLoad?.let { applyLoad(it) }; pendingLoad = null }

    /**
     * [录] (loopEngine.record / countInWhilePlaying): stopped → 4 clicks from now, then the loop starts at step 0 and
     * records; playing → the 4 clicks fall on the 4 beats before the next bar start that is at least 4 beats away.
     */
    fun startCountIn() = synchronized(this) {
        if (countIn != null || recording) return
        val now = rendered.toDouble()
        if (!playing) {
            val clicks = DoubleArray(4) { now + it * beatF }
            countIn = CountIn(clicks, now + 4 * beatF, false)
        } else {
            var b = barStart
            while (b - now < 4 * beatF - barF / 32) b += barF
            countIn = CountIn(DoubleArray(4) { b - (4 - it) * beatF }, b, true)
        }
    }

    /**
     * debug `hold D2` (QA: the count-in kept running under hold): while true the count-in does not advance — stopped:
     * every click and the record start slide with the clock; playing: they move a whole bar later whenever the next
     * click is due, so they stay on the beat grid.
     */
    @Volatile var holdCountIn = false

    private fun holdStep(start: Long, end: Long) {
        val c = countIn ?: return
        val shift = if (!c.whilePlaying) (end - start).toDouble() else if (c.next < c.clicks.size && c.clicks[c.next] < end + barF / 16) barF else 0.0
        if (shift == 0.0) return
        countIn = CountIn(DoubleArray(c.clicks.size) { c.clicks[it] + shift }, c.recStart + shift, c.whilePlaying).also { it.next = c.next }
    }

    /** [录] again during the count-in: cancel, back to whatever was before. */
    fun cancelCountIn() = synchronized(this) { countIn = null }

    fun stopRecording() = synchronized(this) { recording = false }

    /** Phase-preserving tempo change (M5: no dropped beat). */
    fun setBpm(newBpm: Int) = synchronized(this) {
        val nb = newBpm.toDouble()
        if (nb == bpm) return
        val k = bpm / nb // new step = old step × old bpm / new bpm
        val now = rendered.toDouble()
        if (playing) {
            nextStepTime = now + (nextStepTime - now) * k
            barStart = nextStepTime - nextStepIdx * (Tempo.stepFrames(nb, sr))
        }
        countIn?.let { c ->
            for (i in c.clicks.indices) c.clicks[i] = now + (c.clicks[i] - now) * k
            countIn = CountIn(c.clicks, now + (c.recStart - now) * k, c.whilePlaying).also { it.next = c.next }
        }
        bpm = nb
    }

    /** Load a whole beat: now when stopped, at the next bar start when playing (T10). */
    fun load(p: Pattern, newBpm: Int, newKit: KitId) = synchronized(this) {
        if (playing) pendingLoad = Triple(p, newBpm.toDouble(), newKit) else applyLoad(Triple(p, newBpm.toDouble(), newKit))
    }

    val hasPendingLoad: Boolean get() = synchronized(this) { pendingLoad != null }

    private fun applyLoad(l: Triple<Pattern, Double, KitId>) {
        pattern = l.first; kit = l.third
        if (l.second != bpm) setBpm(l.second.toInt())
        suppress.fill(false)
    }

    /**
     * Writes a live hit into the loop (recording, or in the last 1/32 of the count-in → step 0). [musicalFrame] is the
     * audio frame the player heard when they hit (AudioEngine maps the hit time through the output timestamp).
     * Returns the step, or null when not recording.
     */
    fun recordHit(pad: Int, vel: Float, musicalFrame: Double): Int? = synchronized(this) {
        val lvl = Velocity.level(vel)
        val c = countIn
        if (c != null && musicalFrame >= c.recStart - barF / 32) {
            pattern = pattern.merge(pad, 0, lvl)
            if (c.recStart >= rendered) suppress[pad * STEPS] = true
            return 0
        }
        if (!recording) return null
        val rel = ((musicalFrame - barStart) % barF + barF) % barF
        val q = Math.round(rel / stepF).toInt()
        val step = q % STEPS
        val occurrence = musicalFrame - rel + q * stepF
        if (occurrence >= rendered) suppress[pad * STEPS + step] = true
        pattern = pattern.merge(pad, step, lvl)
        return step
    }

    /** Loop position 0…1 at an audio frame (playhead, stone lights); 0 when stopped. */
    fun phaseAt(frame: Double): Double = synchronized(this) {
        if (!playing) return 0.0
        val rel = ((frame - barStart) % barF + barF) % barF
        rel / barF
    }

    // ------------------------------------------------------------------ voices
    private class Voice {
        var data: FloatArray? = null
        var pos = 0.0
        var rate = 1.0
        var gl = 0f
        var gr = 0f
        var pad = -1
        var group = 0
        var fadeLeft = -1
        var fadeLen = 1
        var delay = 0
        var age = 0L
        val active get() = data != null
    }

    private val voices = Array(MAX_VOICES) { Voice() }
    private var ageCounter = 0L
    private val rng = java.util.Random(seed)

    /** Last trigger per pad for tests / logs. */
    var lastTriggerKey: String? = null
        private set
    var triggerCount = 0
        private set

    /** A live hit or a loop hit: [pad] 0…7, [vel] 0…1, [delay] frames into the next render. */
    fun trigger(pad: Int, vel: Float, delay: Int = 0) = synchronized(this) { startPad(pad, vel, delay) }

    /** A UI / metronome sound by key (no pan, no choke). */
    fun sound(key: String, gain: Float, delay: Int = 0) = synchronized(this) { startVoice(key, gain, 0.5f, -1, 0, delay, jitter = false) }

    private fun startPad(pad: Int, vel: Float, delay: Int) {
        val heavy = vel >= 0.6f
        val key = overrides[pad]?.takeIf { samples.containsKey(it) } ?: Kits.sampleKey(kit, pad, heavy)
        val group = Kits.chokeGroup(kit, pad)
        // same pad: fade the previous hit out in 10 ms; choke group: the other hat too
        // q1-1: the new hit starts once the old one has faded (3 ms), so the old tail never adds to the new attack —
        // otherwise its random phase moves the peak of fast repeats by ~1 dB
        var wait = 0
        for (v in voices) if (v.active && v.fadeLeft < 0 && (v.pad == pad || (group != 0 && v.group == group))) {
            val left = v.delay + RETRIGGER_FADE
            fade(v, RETRIGGER_FADE)
            if (v.delay <= delay) wait = maxOf(wait, left - delay)
        }
        val az = Math.toRadians(Layout.padAzDeg(pad).toDouble())
        val right = (0.5 + 0.5 * sin(az)).toFloat()
        startVoice(key, vel.coerceIn(0f, 1f), right, pad, group, delay + wait.coerceAtLeast(0), jitter = true)
        lastTriggerKey = key
        triggerCount++
    }

    private fun startVoice(key: String, gain: Float, right: Float, pad: Int, group: Int, delay: Int, jitter: Boolean) {
        val data = samples[key] ?: return
        var v = voices.firstOrNull { !it.active }
        if (v == null) {
            // steal: fade the oldest, start the new one in its place once faded — simplest: take the oldest now with a short fade-in skip
            v = voices.minByOrNull { it.age }!!
        }
        v.data = data
        v.pos = 0.0
        v.rate = if (jitter) 1.0 + (rng.nextDouble() * 2 - 1) * PITCH_JITTER else 1.0
        val norm = if (jitter) { val ref = samplePeak[key] ?: 0f; val now = peakAt(data, v.rate); if (ref > 0f && now > 0f) ref / now else 1f } else 1f
        val gain = gain * norm
        // balance pan with the design's L/R ratio (az −27° → 0.72 : 0.28), full level on the near side
        v.gl = gain * minOf(1f, (1f - right) / right.coerceAtLeast(0.05f))
        v.gr = gain * minOf(1f, right / (1f - right).coerceAtLeast(0.05f))
        v.pad = pad
        v.group = group
        v.fadeLeft = -1
        v.delay = delay
        v.age = ageCounter++
    }

    private fun fade(v: Voice, frames: Int) { v.fadeLeft = frames; v.fadeLen = frames }

    // ------------------------------------------------------------------ render
    private var limGain = 1f

    /** Renders [frames] stereo frames into [out] (interleaved L R), advancing the clock. */
    fun render(out: FloatArray, frames: Int) = synchronized(this) {
        java.util.Arrays.fill(out, 0, frames * 2, 0f)
        val start = rendered
        val end = start + frames
        if (holdCountIn) holdStep(start, end)
        scheduleEvents(start, end)
        val g = if (muted) 0f else master
        for (v in voices) {
            val data = v.data ?: continue
            var i = v.delay.coerceAtMost(frames)
            v.delay -= i
            if (v.delay > 0) continue
            while (i < frames) {
                val p = v.pos
                val idx = p.toInt()
                if (idx + 1 >= data.size) { v.data = null; break }
                val frac = (p - idx).toFloat()
                var s = data[idx] + (data[idx + 1] - data[idx]) * frac
                if (v.fadeLeft >= 0) {
                    s *= v.fadeLeft.toFloat() / v.fadeLen
                    v.fadeLeft--
                    if (v.fadeLeft < 0) { v.data = null; break }
                }
                out[2 * i] += s * v.gl * g
                out[2 * i + 1] += s * v.gr * g
                v.pos = p + v.rate
                i++
            }
        }
        // bus limiter: instant attack, 50 ms release, never above −1 dBFS
        val rel = (1.0 - Math.exp(-1.0 / (0.05 * sr))).toFloat()
        for (i in 0 until frames) {
            val l = out[2 * i]; val r = out[2 * i + 1]
            val peak = maxOf(abs(l), abs(r))
            val need = if (peak * limGain > CEILING) CEILING / peak else 1f
            limGain = if (need < limGain) need else limGain + (minOf(1f, need) - limGain) * rel
            out[2 * i] = l * limGain
            out[2 * i + 1] = r * limGain
        }
        rendered = end
    }

    /** Loop steps, count-in clicks and metronome clicks that fall in [start, end) become voices with a frame delay. */
    private fun scheduleEvents(start: Long, end: Long) {
        // count-in clicks
        countIn?.let { c ->
            while (c.next < c.clicks.size && c.clicks[c.next] < end) {
                val t = c.clicks[c.next]
                if (t >= start - 1 && metroMode != 2) click(c.next == 0, (t - start).toInt().coerceAtLeast(0))
                c.next++
            }
            if (!c.whilePlaying && c.recStart < end) {
                // the loop starts here, recording
                playing = true; recording = true
                barStart = c.recStart; nextStepTime = c.recStart; nextStepIdx = 0
                countIn = null
            }
        }
        if (!playing) return
        while (nextStepTime < end) {
            val t = nextStepTime
            val delay = (t - start).toInt().coerceAtLeast(0)
            if (nextStepIdx == 0) {
                barStart = t
                pendingLoad?.let { applyLoad(it); pendingLoad = null }
                countIn?.let { c -> if (c.whilePlaying && abs(c.recStart - t) < 2) { recording = true; countIn = null } }
            }
            val p = pattern
            for (pad in 0 until PADS) {
                val lvl = p[pad, nextStepIdx]
                val k = pad * STEPS + nextStepIdx
                if (suppress[k]) { suppress[k] = false; continue }
                if (lvl > 0) startPad(pad, Velocity.gain(lvl), delay)
            }
            if (metroMode == 1 && nextStepIdx % 4 == 0 && countIn == null) click(nextStepIdx == 0, delay)
            nextStepIdx = (nextStepIdx + 1) % STEPS
            nextStepTime += stepF
        }
    }

    private fun click(accent: Boolean, delay: Int) {
        startVoice(if (accent) "metro_hi" else "metro_lo", metroGain * (if (accent) 1f else 0.6f), 0.5f, -2, 0, delay, jitter = false)
    }

    companion object {
        const val MAX_VOICES = 24
        const val FADE_FRAMES = 480 // 10 ms at 48 kHz
        const val RETRIGGER_FADE = 144 // 3 ms at 48 kHz: same pad / choke group
        const val PITCH_JITTER = 0.03
        const val PEAK_SCAN_S = 0.25
        const val CEILING = 0.891f // −1 dBFS
    }
}
