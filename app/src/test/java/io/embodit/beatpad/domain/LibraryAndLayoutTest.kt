package io.embodit.beatpad.domain

import io.embodit.beatpad.data.BeatCodec
import io.embodit.beatpad.domain.beat.Confirm
import io.embodit.beatpad.domain.beat.Deck
import io.embodit.beatpad.domain.beat.Grooves
import io.embodit.beatpad.domain.beat.Layout
import io.embodit.beatpad.domain.beat.MY_SLOTS
import io.embodit.beatpad.domain.beat.Notice
import io.embodit.beatpad.domain.beat.PADS
import io.embodit.beatpad.domain.beat.STEPS
import io.embodit.beatpad.domain.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2

class LibraryAndLayoutTest {
    @Test fun grooveContentIsWellFormed() {
        assertEquals(6, Grooves.all.size)
        for (g in Grooves.all) { assertTrue(g.pattern.hitCount > 0); assertTrue(g.bpm in 60..160) }
    }

    @Test fun twelveSlotsSurviveARestartAndTheThirteenthIsRefused() {
        val (f, _, fx) = TestKit.flow()
        f.tapSlot(0)
        repeat(MY_SLOTS) { f.cardTo(0); f.saveButton() }
        assertEquals(MY_SLOTS, f.s.slots.count { it != null })
        f.cardTo(0); f.saveButton()
        assertEquals(Notice.FULL, f.s.notice)
        // restart: encode → decode → a new flow
        val text = BeatCodec.encode(fx.savedSlots!!, null)
        val (slots, _) = BeatCodec.decode(text)
        val (g, _, _) = TestKit.flow()
        g.start(io.embodit.beatpad.domain.beat.Settings(), slots, null)
        assertEquals(MY_SLOTS, g.s.slots.count { it != null })
        assertEquals(f.s.slots[5]!!.pattern, g.s.slots[5]!!.pattern)
        // overwrite / clear go through the confirm
        g.cardTo(7); g.saveButton(); assertEquals(Confirm.SLOT, g.s.confirm)
        g.confirmClearSlot(); assertEquals(null, g.s.slots[0])
    }

    @Test fun missingImportFallsBackWithANotice() {
        val (f, _, _) = TestKit.flow()
        val b = io.embodit.beatpad.domain.beat.SavedBeat(1, Grooves.all[0].pattern, 100, Grooves.all[0].kit, mapOf(1 to "/nope/123__clap.wav"))
        f.start(io.embodit.beatpad.domain.beat.Settings(), listOf(b) + List(11) { null }, null)
        f.cardTo(7); f.loadCard()
        assertTrue(f.s.overrides.isEmpty())
        assertEquals(Notice.IMPORT_FAIL, f.s.notice)
    }

    @Test fun statusSourceIsShared() {
        // D38: the window line and the ring caption both read BeatState.statusKey
        val (f, m, _) = TestKit.flow()
        assertEquals("D1.empty", f.s.statusKey)
        f.tapSlot(0); assertEquals("D1.hasBeat", f.s.statusKey)
        f.recButton("t"); assertEquals("D2", f.s.statusKey)
        f.recButton("t"); f.placeStart(); assertEquals("D6", f.s.statusKey); assertTrue(f.s.tabBarHidden)
        f.placeDone(); f.pickStart(); assertEquals("D7", f.s.statusKey); assertTrue(f.s.tabBarHidden)
        f.pickPad(3); assertFalse(f.s.tabBarHidden); assertEquals(3, f.s.picked)
        f.pickEnd(true); assertEquals(Deck.IDLE, f.s.deck)
        f.tapSlot(5); f.dragStoneStart(5); assertEquals("D5", f.s.statusKey)
        m.stop()
    }

    @Test fun placingIgnoresStrikesAndOnlyDoneExits() {
        val (f, _, _) = TestKit.flow()
        f.placeStart()
        f.hit(1, 1f, null, "stick"); f.recButton("button-AX")
        assertEquals(Deck.PLACING, f.s.deck)
        f.windowClosed(); assertEquals(Deck.IDLE, f.s.deck) // R1-09: closing the window = done
    }

    // ------------------------------------------------------------------ geometry (M11, M17, D17)

    @Test fun everyPadWithinReachAndGapsWide() {
        for (p in 0 until PADS) assertTrue("p${p + 1} reach ${Layout.reach(p)}", Layout.reach(p) <= Layout.REACH_M)
        for (row in listOf(0, 4)) for (k in 0 until 3) {
            val g = Layout.neighbourGap(row + k, row + k + 1)
            assertTrue("gap $g", g >= 0.015f && g >= 0.3f * Layout.PAD_OUTER_D)
        }
        // stick zones (face radius + 1 cm = Ø 14 cm) do not overlap anywhere
        for (a in 0 until PADS) for (b in a + 1 until PADS) assertTrue(Layout.padCenter(a).distanceTo(Layout.padCenter(b)) > 0.14f)
        // front-row centre-to-centre spacing between the rows ≥ 0.19 − 0.128 radial
        assertTrue(Layout.padCenter(5).distanceTo(Layout.padCenter(1)) - Layout.PAD_OUTER_D >= 0.015f)
    }

    @Test fun ringStonesBigEnoughAndSpaced() {
        val pitch = Layout.slotPoint(0).distanceTo(Layout.slotPoint(1))
        assertTrue(pitch - Layout.STONE_HIT_D >= 0.015f)
        val eyeToRing = Layout.RING_CENTER.length
        val deg = Math.toDegrees(2 * atan2(Layout.STONE_HIT_D / 2.0, eyeToRing.toDouble()))
        assertTrue("stone ${"%.2f".format(deg)}°", deg >= 2.0)
        // rec key clear of the frame and of the 6 o'clock stone
        val recY = Layout.ringYForWorld(Layout.REC_Y_WORLD)
        assertTrue(-(recY + Layout.REC_R) - (Layout.FRAME_R + Layout.FRAME_TUBE) >= 0.019f)
        assertTrue(-(recY + Layout.REC_R) - (Layout.STEP_R + Layout.STONE_HIT_D / 2) >= 0.04f)
    }

    private fun segHitsSphere(a: Vec3, b: Vec3, c: Vec3, r: Float): Boolean {
        val d = b - a; val t = ((c - a).dot(d) / d.dot(d)).coerceIn(0f, 1f)
        return (a + d * t).distanceTo(c) < r
    }

    @Test fun occlusionFromEveryEyeOnTheRing() {
        // eyes: origin, ±30° yaw (same point), sit 0.10 lower, stand 0.40 higher; targets: caption corners, rec key, BPM digits, stones
        val eyes = listOf(Vec3.ZERO, Vec3(0f, -0.10f, 0f), Vec3(0f, 0.40f, 0f))
        val cap = Layout.captionCenter
        val targets = ArrayList<Vec3>()
        for (sx in listOf(-1f, 1f)) for (sy in listOf(-1f, 1f)) targets += cap + Layout.ringRotation.rotate(Vec3(sx * Layout.CAPTION_W / 2, sy * 0.016f, 0f))
        targets += Layout.recCenter
        targets += Layout.RING_CENTER
        for (k in 0 until STEPS) targets += Layout.slotPoint(k)
        // occluders: every pad as a sphere around its top face (outer radius, raised by the rim), the deck's back edge
        for (e in eyes) for (t in targets) for (p in 0 until PADS) {
            val c = Layout.padCenter(p) + Vec3(0f, -0.01f, 0f)
            assertFalse("eye $e → $t blocked by p${p + 1}", segHitsSphere(e, t, c, Layout.PAD_OUTER_D / 2 + 0.005f))
        }
        // back labels visible over the front pads (R1-11)
        for (e in eyes.take(2)) for (b in 4 until PADS) {
            val l = Layout.labelCenter(b) + Vec3(0f, -0.008f, 0f)
            for (p in 0 until 4) assertFalse("label p${b + 1} blocked by p${p + 1}", segHitsSphere(e, l, Layout.padCenter(p) + Vec3(0f, -0.006f, 0f), Layout.PAD_OUTER_D / 2))
        }
    }

    @Test fun windowClearOfTheDeckByTenDegrees() {
        // the window opens straight ahead of the head (system default, ~1.75 m) and the deck is turned so the window is at
        // az −61° (or +67°); window 440 dp ≈ 0.44 m wide + the TabBar capsule (assume up to 0.12 m beyond the window edge)
        // (distance, extra width for the TabBar capsule): the system default distance with the capsule, and the design's
        // 0.9 m case (spatialLayout.controlWindow.why) without it
        for ((right, dist, extra) in listOf(Triple(false, 1.75f, 0.12), Triple(false, 0.9f, 0.0), Triple(true, 1.75f, 0.12), Triple(true, 0.9f, 0.0))) {
            val windowYaw = 0.3f
            val deckYaw = Layout.deckYawForWindow(windowYaw, right)
            val winAz = Math.toDegrees((windowYaw - deckYaw).toDouble()).let { -it } // + = right of the deck forward
            val half = Math.toDegrees(atan2(0.22 + extra, dist.toDouble()))
            val deckEdge = Layout.BODY_AZ.toDouble()
            val gap = abs(winAz) - half - deckEdge
            assertTrue("right=$right dist=$dist gap=${"%.1f".format(gap)}°", gap >= 10.0)
            assertEquals(if (right) 67.0 else -61.0, winAz, 0.01)
        }
    }

    @Test fun padNormalsLeanTowardThePlayer() {
        for (p in 0 until PADS) {
            val n = Layout.padNormal(p); val c = Layout.padCenter(p)
            val toEye = Vec3(-c.x, 0f, -c.z).normalized()
            assertTrue(n.dot(toEye) > 0.15f)
            assertNotNull(Layout.padRotation(p))
        }
    }
}
