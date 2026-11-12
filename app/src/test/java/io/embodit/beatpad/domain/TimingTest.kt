package io.embodit.beatpad.domain

import io.embodit.beatpad.domain.beat.HitCurve
import io.embodit.beatpad.domain.beat.HubDrag
import io.embodit.beatpad.domain.beat.Layout
import io.embodit.beatpad.domain.beat.PadFace
import io.embodit.beatpad.domain.beat.RingDrag
import io.embodit.beatpad.domain.beat.StrikeDetector
import io.embodit.beatpad.domain.beat.Tempo
import io.embodit.beatpad.domain.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class TimingTest {
    private val pad = PadFace(Vec3(0f, 1f, -0.5f), Vec3(0f, 1f, 0f), 0.06f)
    private val frameNs = 13_888_889L // 72 fps

    /** Feeds a vertical tip path over the pad centre: list of (height above face, frames). */
    private fun swing(det: StrikeDetector, heights: List<Float>, startNs: Long = 0L, x: Float = 0f) =
        heights.mapIndexed { i, h -> det.feed(Vec3(x, 1f + 0.015f + h, -0.5f), startNs + i * frameNs, listOf(pad)) }.flatten()

    @Test fun twoStrikesLouderSecond() {
        // down at 1.2 m/s → up 2 cm → down at 2.5 m/s (input.stickHit.verify)
        val d = StrikeDetector()
        val v1 = 1.2f * frameNs / 1e9f; val v2 = 2.5f * frameNs / 1e9f
        val hs = ArrayList<Float>()
        var h = 0.08f
        while (h > -0.02f) { hs += h; h -= v1 }
        hs += -0.02f; hs += 0.0f; hs += 0.02f; hs += 0.04f
        h = 0.04f
        while (h > -0.03f) { h -= v2; hs += h }
        val e = swing(d, hs)
        assertEquals(2, e.size)
        assertTrue(HitCurve.gain(e[1].speed) > HitCurve.gain(e[0].speed))
        assertEquals(1.2f, e[0].speed, 0.05f)
    }

    @Test fun noRearmNoSecondStrike() {
        val d = StrikeDetector()
        val e = swing(d, listOf(0.05f, 0.02f, -0.01f, 0.008f, -0.02f)) // rises only 0.8 cm
        assertEquals(1, e.size)
    }

    @Test fun outsideTheTipZoneDoesNotCount() {
        val d = StrikeDetector()
        assertTrue(swing(d, listOf(0.05f, 0.02f, -0.01f), x = 0.075f).isEmpty()) // face r 6 cm + 1 cm < 7.5 cm
        val d2 = StrikeDetector()
        assertEquals(1, swing(d2, listOf(0.05f, 0.02f, -0.01f), x = 0.065f).size)
    }

    @Test fun slowTouchAndFiftyMsGuard() {
        val d = StrikeDetector()
        assertTrue(swing(d, listOf(0.003f, 0.0f, -0.003f)).isEmpty()) // 0.2 m/s
        val d2 = StrikeDetector(rearmM = 0.0f)
        // second crossing 2 frames (28 ms) later: same pad within 50 ms → ignored
        val e = swing(d2, listOf(0.05f, -0.01f, 0.03f, -0.02f))
        assertEquals(1, e.size)
    }

    @Test fun crossingTimeIsInterpolated() {
        val d = StrikeDetector()
        d.feed(Vec3(0f, 1.015f + 0.03f, -0.5f), 0L, listOf(pad))
        val e = d.feed(Vec3(0f, 1.015f - 0.01f, -0.5f), frameNs, listOf(pad))
        assertEquals(1, e.size)
        assertEquals((frameNs * 3 / 4).toDouble(), e[0].timeNs.toDouble(), 50.0)
    }

    @Test fun quantizeNearestSixteenth() {
        val sr = 48000; val bpm = 96.0
        val step = Tempo.stepFrames(bpm, sr)
        for (s in listOf(0, 4, 8, 12)) for (ms in listOf(-40, 40)) {
            assertEquals(s, Tempo.quantize(s * step + ms * 48.0, 0.0, bpm, sr))
        }
        // last 1/32 of the bar wraps to step 0
        assertEquals(0, Tempo.quantize(Tempo.barFrames(bpm, sr) - step / 3, 0.0, bpm, sr))
    }

    @Test fun hubDragTwoBpmPerCentimetreClampAndSnap() {
        val h = HubDrag(90)
        assertEquals(96, h.move(0.03f).first)
        assertEquals(84, h.move(-0.03f).first)
        assertEquals(160, h.move(1f).first)
        assertEquals(60, h.move(-1f).first)
        val s = HubDrag(96)
        val (b, snapped) = s.move(0.017f) // raw 99.4 → snaps to 100
        assertEquals(100, b); assertTrue(snapped)
        assertEquals(100, s.move(0.021f).first) // 100.2 still snapped, no new tick
        assertFalse(s.move(0.021f).second)
        assertEquals(103, s.move(0.035f).first) // 103 leaves the band
    }

    @Test fun ringDragHysteresisJitter() {
        // a pointer resting between slot 3 and 4 with σ 4 / 8 mm noise: < 2 % slot changes
        val rnd = java.util.Random(3)
        for (sigma in listOf(0.004f, 0.008f)) {
            val d = RingDrag(3)
            var jumps = 0; var last = 3
            val a = 2 * Math.PI * 3.3 / 16
            repeat(1000) {
                val x = (Layout.STEP_R * sin(a)).toFloat() + (rnd.nextGaussian() * sigma).toFloat()
                val y = (Layout.STEP_R * cos(a)).toFloat() + (rnd.nextGaussian() * sigma).toFloat()
                val s = d.move(x, y); if (s != last) { jumps++; last = s }
            }
            assertTrue("sigma $sigma jumps $jumps", jumps < 20)
        }
        val d = RingDrag(0)
        val (x, y) = Layout.slotXY(5)
        assertEquals(5, d.move(x, y)); assertFalse(d.off)
        val (ox, oy) = Layout.slotXY(5, RingDrag.RING_OUTER_M + 0.11f)
        d.move(ox, oy); assertTrue(d.off)
    }

    @Test fun velocityCurve() {
        assertEquals(0.35f, HitCurve.gain(0.4f), 1e-3f)
        assertEquals(1.0f, HitCurve.gain(3.0f), 1e-3f)
        assertTrue(HitCurve.gain(1f, feel = 0) > HitCurve.gain(1f) && HitCurve.gain(1f) > HitCurve.gain(1f, feel = 2))
    }
}
