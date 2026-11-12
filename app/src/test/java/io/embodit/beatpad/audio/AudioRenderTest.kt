package io.embodit.beatpad.audio

import io.embodit.beatpad.domain.TestKit
import io.embodit.beatpad.domain.beat.Grooves
import io.embodit.beatpad.domain.beat.KitId
import io.embodit.beatpad.domain.beat.PADS
import io.embodit.beatpad.domain.beat.Tempo
import io.embodit.beatpad.platform.audio.Mixer
import io.embodit.beatpad.platform.audio.Pcm
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Offline renders through the app's own mixer (audioEngine.offlineRender; the emulator does not play our audio):
 * loudness (M7) at the default master level, each pad solo per kit, each ready-made groove 30 s (M6: no clipping /
 * clicks at the loop seam), and 10 equal hits on one pad (M1). Files go to build/beatpad-qa/ for the QA folder.
 */
class AudioRenderTest {
    private val out = File("build/beatpad-qa").apply { mkdirs() }
    private val sr = 48000

    private fun levels(x: FloatArray): Pair<Double, Double> {
        // same rule as bin/ui_gates.py: 20 ms windows above peak − 30 dB
        val peak = x.maxOf { abs(it) }.toDouble()
        val win = 960
        val e = ArrayList<Double>()
        var k = 0
        while (k + win <= x.size) { var s = 0.0; for (i in k until k + win) s += x[i] * x[i]; e += s / win; k += win }
        val thr = (peak * 0.0316).let { it * it }
        val voiced = e.filter { it >= thr }.ifEmpty { e }
        return 20 * log10(sqrt(voiced.average())) to 20 * log10(peak)
    }

    private fun mixerAtDefaults(): Mixer = TestKit.mixer().apply { master = 0.75f }

    private fun render(m: Mixer, seconds: Double): FloatArray { val n = (seconds * sr).toInt(); val buf = FloatArray(n * 2); TestKit.run(m, n, buf); return buf }

    @Test fun loudnessAtDefaultVolume() {
        // every kit's 8 pads struck in turn at a normal swing (0.8), then each groove for 2 bars: the voiced sections
        val m = mixerAtDefaults()
        val parts = ArrayList<FloatArray>()
        for (kit in KitId.entries) { m.kit = kit; for (p in 0 until PADS) { m.trigger(p, 0.8f); parts += render(m, 0.35) } }
        for (g in Grooves.all) { m.load(g.pattern, g.bpm, g.kit); m.play(); parts += render(m, Tempo.barFrames(g.bpm.toDouble(), sr) * 2 / sr); m.stop(); parts += render(m, 0.3) }
        val all = FloatArray(parts.sumOf { it.size }); var o = 0; for (p in parts) { System.arraycopy(p, 0, all, o, p.size); o += p.size }
        Pcm.writeWav(File(out, "loudness-v0.1.1.wav"), all, 2, sr)
        val (rms, peak) = levels(all)
        File(out, "loudness-v0.1.1.txt").writeText("voiced RMS %.1f dBFS, peak %.1f dBFS (rendered by the app mixer at the default 75 %% master, not recorded)\n".format(rms, peak))
        assertTrue("rms $rms", rms >= -20.0)
        assertTrue("peak $peak", peak <= -0.99)
    }

    @Test fun tenEqualHits() {
        // q1-1: 10 equal swings on the snare (and on every pad of every kit) peak within 1 dB of each other
        val m = mixerAtDefaults()
        val peaks = (0 until 10).map { m.trigger(1, 0.8f); render(m, 0.3).maxOf { abs(it) } }
        val spread = 20 * log10(peaks.max() / peaks.min())
        val lines = StringBuilder("snare ×10 peak spread %.2f dB (pitch jitter ±3 %%, each hit peak-normalised after the jitter)\n".format(spread))
        var worst = 0.0
        for (kit in KitId.entries) for (p in 0 until PADS) {
            val k = mixerAtDefaults(); k.kit = kit
            val pk = (0 until 10).map { k.trigger(p, 0.8f); render(k, 0.3).maxOf { abs(it) } }
            val sp = 20 * log10(pk.max() / pk.min())
            worst = maxOf(worst, sp.toDouble())
            lines.append("%s pad %d ×10 spread %.2f dB\n".format(kit.id, p + 1, sp))
        }
        lines.append("worst %.2f dB\n".format(worst))
        File(out, "ten-hits.txt").writeText(lines.toString())
        assertTrue("spread $spread dB", spread < 1.0)
        assertTrue("worst pad spread $worst dB", worst < 1.0)
    }

    @Test fun groovesThirtySecondsNoClipNoSeamClick() {
        for (g in Grooves.all) {
            val m = mixerAtDefaults()
            m.load(g.pattern, g.bpm, g.kit); m.play()
            val x = render(m, 30.0)
            Pcm.writeWav(File(out, "groove-${g.id}-30s.wav"), x, 2, sr)
            val peak = x.maxOf { abs(it) }
            assertTrue("${g.id} peak $peak", peak <= Mixer.CEILING + 1e-4f)
            // seam: the sample-to-sample jump at every bar boundary stays small (no discontinuity click)
            val bar = Tempo.barFrames(g.bpm.toDouble(), sr)
            var b = bar
            while (b < 30 * sr - 2) { val i = b.toInt(); val jump = abs(x[2 * (i + 1)] - x[2 * i]); assertTrue("${g.id} seam $jump", jump < 0.5f); b += bar }
        }
    }

    @Test fun solos() {
        for (kit in KitId.entries) {
            val m = mixerAtDefaults(); m.kit = kit
            val parts = ArrayList<FloatArray>()
            for (p in 0 until PADS) { m.trigger(p, 0.45f); parts += render(m, 0.4); m.trigger(p, 0.95f); parts += render(m, 0.5) }
            val all = FloatArray(parts.sumOf { it.size }); var o = 0; for (q in parts) { System.arraycopy(q, 0, all, o, q.size); o += q.size }
            Pcm.writeWav(File(out, "solo-${kit.id}.wav"), all, 2, sr)
        }
    }
}
