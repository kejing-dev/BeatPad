package io.embodit.beatpad.domain

import io.embodit.beatpad.domain.beat.Layout
import io.embodit.beatpad.domain.math.Quat4
import io.embodit.beatpad.domain.math.Vec3
import io.embodit.beatpad.domain.place.Arrange
import io.embodit.beatpad.domain.place.HeadSampler
import io.embodit.beatpad.domain.place.LevelGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * q1-2 / q1-3 / q1-4: the one placement function (Arrange) and the D29 head sample.
 *  - D29: look at the button, press, turn to front and hold still → the deck faces front within 2°, for every button
 *    position (the window can be anywhere around the player), including staying on the button longer than 300 ms.
 *  - D9: cold start with the head pitched down 30°, up 30°, rolled 20° → the deck is upright (pitch / roll dropped).
 *  - every window open path (cold start, reopen after ×, HUD [打开面板], [叫回面前], debug recall, facing the deck)
 *    leaves the window ≥ 10° clear of the deck and the ring (D41), and [回到面前] too.
 */
class PlacementTest {
    private val eye = Vec3(0.3f, 1.62f, -0.2f)
    private fun rad(d: Float) = Math.toRadians(d.toDouble()).toFloat()
    private fun deg(r: Float) = Math.toDegrees(r.toDouble()).toFloat()

    /** Head forward for a yaw (deg, + left) and pitch (deg, + up). */
    private fun fwd(yawDeg: Float, pitchDeg: Float = 0f): Vec3 {
        val y = rad(yawDeg); val p = rad(pitchDeg)
        return Vec3(-sin(y) * cos(p), sin(p), -cos(y) * cos(p))
    }

    /** Gap (deg) between the window outline (+ TabBar capsule) and the deck body / ring, seen from the eye. */
    private fun windowGap(deckYaw: Float, windowYaw: Float, dist: Float = 1.75f, extra: Double = 0.12): Double {
        val winAz = abs(Arrange.diffDeg(windowYaw, deckYaw)).toDouble()
        val half = Math.toDegrees(atan2(0.22 + extra, dist.toDouble()))
        return winAz - half - Layout.BODY_AZ
    }

    // ------------------------------------------------------------------ D29

    /** Simulates: look at the button (yaw [button]) for [dwellOnButtonMs], press, keep looking [afterPressMs], turn to [front] in 400 ms, hold. */
    private fun sample(button: Float, front: Float, afterPressMs: Long, pitchAtButton: Float = -8f): HeadSampler.Sample {
        val s = HeadSampler()
        var t = 1000L
        s.start(t, fwd(button, pitchAtButton))
        val frame = 14L // ~72 Hz
        var out: HeadSampler.Sample? = null
        while (t < 1000L + afterPressMs && out == null) { out = s.step(t, eye, fwd(button, pitchAtButton)); t += frame }
        val turnStart = t
        while (t < turnStart + 400L && out == null) {
            val u = (t - turnStart) / 400f
            val e = u * u * (3 - 2 * u)
            out = s.step(t, eye, fwd(button + Arrange.diffDeg(rad(front), rad(button)) * e, pitchAtButton * (1 - e)))
            t += frame
        }
        while (out == null && t < turnStart + 20_000L) { out = s.step(t, eye, fwd(front)); t += frame }
        return out!!
    }

    @Test fun lookAtTheButtonPressThenTurnFrontWithinTwoDegrees() {
        // the window (and so the button) at every az around the player; front at several yaws
        for (front in listOf(0f, 37f, -90f, 160f)) for (buttonRel in listOf(-180f, -120f, -61f, -30f, -12f, 12f, 30f, 67f, 120f)) for (stay in listOf(0L, 300L, 900L, 2000L)) {
            val button = front + buttonRel
            val s = sample(button, front, stay)
            val err = abs(HeadSampler.angleDiff(s.yawDeg, front))
            assertTrue("front=$front button=$button stay=$stay sampled ${s.yawDeg} err $err", err < 2f)
            assertEquals(0f, s.forward.y, 1e-6f)
        }
    }

    @Test fun pressingWhileAlreadyFacingFrontTakesThatDirection() {
        // the button is straight ahead and the user does not turn: after the settle time the press direction is used
        val s = sample(25f, 25f, 0L)
        assertTrue(abs(HeadSampler.angleDiff(s.yawDeg, 25f)) < 2f)
    }

    @Test fun samplerWaitsForALevelHead() {
        val s = HeadSampler()
        s.start(0L, fwd(0f))
        var t = 0L
        // turned to 40° but looking down 30° for 4 s: no sample
        while (t < 4000L) { assertNull(s.step(t, eye, fwd(40f, -30f))); t += 14 }
        var out: HeadSampler.Sample? = null
        while (out == null && t < 6000L) { out = s.step(t, eye, fwd(40f, 0f)); t += 14 }
        assertNotNull(out); assertTrue(abs(out!!.yawDeg - 40f) < 2f)
    }

    @Test fun frontAfterLookingAtTheWindowKeepsTheWindowOffTheRing() {
        // q1-2: the window straight ahead, the user looks at it, presses, turns to various fronts
        val windowYaw = rad(10f)
        for (frontDeg in listOf(10f, 20f, -5f, 40f, 75f, -70f, 100f, 180f)) for (right in listOf(false, true)) {
            val s = sample(10f, frontDeg, 300L)
            val p = Arrange.front(s.position, rad(s.yawDeg), windowYaw, right)
            val gap = windowGap(p.yaw, windowYaw)
            assertTrue("front=$frontDeg right=$right gap=$gap", gap >= 10.0)
            // when the asked front is already clear, the deck faces it within 2°
            val clear = abs(Arrange.diffDeg(rad(frontDeg), windowYaw)) >= (if (Arrange.diffDeg(rad(frontDeg), windowYaw) > 0) Arrange.SEP_RIGHT else Arrange.SEP_LEFT)
            if (clear) assertTrue("front=$frontDeg deck=${deg(p.yaw)}", abs(Arrange.diffDeg(p.yaw, rad(frontDeg))) < 2f)
        }
        // no window up: straight ahead of the sample
        val s = sample(10f, 10f, 300L)
        assertEquals(10f, deg(Arrange.front(s.position, rad(s.yawDeg), null, false).yaw), 2f)
    }

    @Test fun recentreHasNoDeadZone() {
        // D29: the deck off by 12° either side, [回到面前] once → < 2°, and a 3° turn is honoured too (no ±3° dead zone)
        for (off in listOf(-12f, 12f, 3f, -3f)) {
            val s = sample(off + 40f, off, 300L) // button elsewhere, front = off
            val p = Arrange.front(s.position, rad(s.yawDeg), null, false)
            assertEquals(off, deg(p.yaw), 2f)
        }
    }

    // ------------------------------------------------------------------ D9 cold start

    @Test fun coldStartWithTheHeadDownUpOrRolledIsUpright() {
        val gate = LevelGate()
        for ((pitch, roll) in listOf(-30f to 0f, 30f to 0f, 0f to 20f, -30f to 20f)) {
            // the window open waits for a level head: pitched 30° never opens
            gate.ask("start", 0L)
            var t = 0L
            while (t < 3000L) { assertNull("pitch $pitch", if (abs(pitch) > 10f) gate.step(t, pitch) else null); t += 14 }
            var why: String? = null
            while (why == null && t < 5000L) { why = gate.step(t, 0f); t += 14 }
            assertEquals("start", why)
            // the window yaw comes from the flattened forward of whatever head pose: pitch / roll never reach the deck
            val q = Quat4.axisAngle(Vec3.UP, rad(20f)) * Quat4.axisAngle(Vec3.RIGHT, rad(pitch)) * Quat4.axisAngle(Vec3(0f, 0f, -1f), rad(roll))
            val f = q.rotate(Vec3.AHEAD)
            val flat = Vec3(f.x, 0f, f.z).normalized()
            val headYaw = Layout.yawOf(flat)
            assertEquals(20f, deg(headYaw), 0.01f)
            val deck = Arrange.onWindowOpen(null, eye, headYaw, false)
            val up = deck.rotation.rotate(Vec3.UP)
            val tilt = Math.toDegrees(acos(up.dot(Vec3.UP).coerceIn(-1f, 1f).toDouble()))
            assertTrue("pitch $pitch roll $roll tilt $tilt", tilt < 1.0)
            assertEquals(eye, deck.origin)
            assertEquals(20f - 61f, deg(deck.yaw), 0.01f) // window at az −61° of the deck (placementProof)
            assertTrue(windowGap(deck.yaw, headYaw) >= 10.0)
        }
    }

    // ------------------------------------------------------------------ every open path (q1-3)

    @Test fun everyWindowOpenKeepsTheWindowOffTheDeckAndRing() {
        // reopen after ×, HUD [打开面板], [叫回面前], debug recall: the window opens straight ahead of the head, wherever the
        // player faces; the deck must end up ≥ 10° clear (both panel sides, every facing every 5°)
        for (right in listOf(false, true)) for (deckDeg in listOf(-61f, 0f, 45f)) for (face in 0 until 360 step 5) {
            val deck = Layout.DeckPose(eye, rad(deckDeg))
            val headYaw = rad(deckDeg + face)
            val after = Arrange.onWindowOpen(deck, eye + Vec3(0.05f, 0f, -0.03f), headYaw, right)
            val gap = windowGap(after.yaw, headYaw)
            assertTrue("right=$right deck=$deckDeg face=$face gap=$gap", gap >= 10.0)
            // already clear → untouched
            val sep = Arrange.diffDeg(deck.yaw, headYaw).let { d -> if (d > 0) Arrange.SEP_RIGHT else Arrange.SEP_LEFT }
            if (abs(Arrange.diffDeg(deck.yaw, headYaw)) >= sep) assertEquals(deck, after)
            // the deck stays level and at the same distance from the player
            assertEquals(deck.origin.y, after.origin.y, 1e-5f)
        }
    }

    @Test fun reopenWhileFacingTheDeckMovesItAside() {
        // q1-3 exactly: facing the deck level, close the window and open it again → it opens over the ring; the deck turns away
        val deck = Layout.DeckPose(eye, rad(-61f))
        val after = Arrange.onWindowOpen(deck, eye, deck.yaw, false)
        assertEquals(-61f - 61f, deg(after.yaw), 0.01f)
        assertTrue(windowGap(after.yaw, deck.yaw) >= 10.0)
        val afterR = Arrange.onWindowOpen(deck, eye, deck.yaw, true)
        assertEquals(-61f + 67f, deg(afterR.yaw), 0.01f)
    }

    @Test fun recallFromSettingsRecentresLikeAColdStart() {
        // [叫回面前]: the deck far round the back (clear of the window) still comes back beside the window
        val deck = Layout.DeckPose(eye, rad(-122f))
        val after = Arrange.onWindowOpen(deck, eye, 0f, false, recenter = true)
        assertEquals(-61f, deg(after.yaw), 0.01f)
        assertTrue(windowGap(after.yaw, 0f) >= 10.0)
        assertEquals(-122f, deg(Arrange.onWindowOpen(deck, eye, 0f, false).yaw), 0.01f) // HUD reopen: kept (already clear)
    }

    @Test fun walkedAwayReopenSetsTheDeckUpAtTheHead() {
        val deck = Layout.DeckPose(eye, 0f)
        val head = eye + Vec3(1.5f, 0f, 0.4f)
        val after = Arrange.onWindowOpen(deck, head, rad(90f), false)
        assertEquals(head, after.origin)
        assertEquals(90f - 61f, deg(after.yaw), 0.01f)
    }

    @Test fun turningAroundTheHeadKeepsTheDistance() {
        val deck = Layout.DeckPose(eye + Vec3(0.1f, 0f, 0.2f), rad(10f))
        val t = Arrange.turnAround(deck, eye, rad(-70f))
        assertEquals(deck.origin.distanceTo(eye), t.origin.distanceTo(eye), 1e-4f)
        // the deck's forward point turns with it: still straight ahead of the deck
        val fwdPoint = t.toWorld(Vec3(0f, 0f, -1f))
        assertEquals(deg(t.yaw), deg(Layout.yawOf((fwdPoint - t.origin))), 0.01f)
    }

    @Test fun panelSideButtonPutsTheWindowExactlyThere() {
        val windowYaw = rad(30f)
        val deck = Layout.DeckPose(eye, rad(-31f))
        assertEquals(30f + 67f, deg(Arrange.panelSide(deck, eye, windowYaw, true).yaw), 0.01f)
        assertEquals(30f - 61f, deg(Arrange.panelSide(deck, eye, windowYaw, false).yaw), 0.01f)
    }
}
