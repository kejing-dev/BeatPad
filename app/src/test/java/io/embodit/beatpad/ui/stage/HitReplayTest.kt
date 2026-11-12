package io.embodit.beatpad.ui.stage

import io.embodit.beatpad.ui.stage.input.parseLoggedPath
import io.embodit.beatpad.ui.stage.input.resolvePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * board-input 命中回放: every kind of hit — a pad's collider holder and any of its visible parts (face, rim, base, glow),
 * a ring slot and its stone / empty marker, the speed knob and its digits, the 3D rec key and its cap, the deck body
 * colliders (D6) — resolves through the same pure parser the Stage uses (Names.resolve); names come only from Names.
 */
class HitReplayTest {
    private fun r(path: String) = resolvePath(parseLoggedPath(path), Names::resolve)

    @Test fun everyHitResolves() {
        for (i in 0 until 8) {
            assertEquals(Hit.Pad(i), r(Names.pad(i)))
            for (part in listOf("padFace", "padRim", "padBase", "padGlow", "padPick")) assertEquals(Hit.Pad(i), r("$part:$i < padVis:$i < ${Names.pad(i)} < deckRoot < stageRoot"))
        }
        for (k in 0 until 16) {
            assertEquals(Hit.Slot(k), r("stone:$k < ${Names.slot(k)} < ringRoot < deckRoot < stageRoot"))
            assertEquals(Hit.Slot(k), r("empty:$k < ${Names.slot(k)} < ringRoot < deckRoot"))
        }
        assertEquals(Hit.Hub, r("hubKnob < hub < ringRoot < deckRoot"))
        assertEquals(Hit.Hub, r("hubText < hub < ringRoot"))
        assertEquals(Hit.Rec, r("recCap < rec < ringRoot < deckRoot"))
        assertEquals(Hit.Rec, r("recBase < rec < ringRoot"))
        assertEquals(Hit.DeckBody, r("deck:-2 < deckColliders < deckRoot < stageRoot"))
    }

    @Test fun nonTargetsResolveToNull() {
        assertNull(r("pad:8"))
        assertNull(r("slot:16"))
        assertNull(r("lane:3 < ringRoot < deckRoot < stageRoot"))
        assertNull(r("ringBacking < ringRoot < deckRoot"))
        assertNull(r("caption < ringRoot < deckRoot"))
        assertNull(r("label:2 < deckRoot < stageRoot"))
        assertNull(r("body < deckRoot < stageRoot"))
        assertNull(r("<none>"))
    }
}
