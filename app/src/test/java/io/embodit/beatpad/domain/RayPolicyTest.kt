package io.embodit.beatpad.domain

import io.embodit.beatpad.domain.beat.Deck
import io.embodit.beatpad.domain.beat.StickRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 门 C 2026-11-13「进入敲鼓的时候就只有棒子没射线」: the app draws no ray in any state, sticks only in drum states. */
class RayPolicyTest {
    @Test fun drumModeHasSticksAndNoRayEntity() {
        val drum = Deck.values().filter { StickRig.isDrumMode(it) }
        assertEquals(listOf(Deck.IDLE, Deck.COUNT_IN, Deck.RECORDING, Deck.PLAYING), drum)
        for (d in drum) {
            val names = StickRig.attached(d)
            assertEquals("$d sticks", listOf("stick:0", "stick:1"), names)
            assertTrue("$d no ray entity: $names", names.none { StickRig.isRayName(it) })
            assertEquals(0, StickRig.appRays(d))
        }
    }

    @Test fun noAppRayInAnyState() {
        for (d in Deck.values()) {
            assertEquals("$d", 0, StickRig.appRays(d))
            assertTrue("$d", StickRig.attached(d).none { StickRig.isRayName(it) })
        }
    }

    @Test fun rayNameMatcher() {
        for (n in listOf("ray:0", "rayDot:1", "beam", "stick-ray")) assertTrue(n, StickRig.isRayName(n))
        for (n in listOf("stick:0", "playhead", "tray", "array", "deck", null)) assertFalse("$n", StickRig.isRayName(n))
    }

    /** The scene source creates no ray entities and draws no ray geometry (the v0.1.1 rays[h] / rayDots[h]). */
    @Test fun sceneSourceHasNoSelfDrawnRay() {
        val dir = File("src/main/java/io/embodit/beatpad/ui/stage")
        assertTrue(dir.absolutePath, dir.isDirectory)
        val bad = Regex("""\"ray:|\"rayDot|\brays\[|\brayDots\[|r\.ray\b|rayLength\(""")
        dir.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            f.readLines().forEachIndexed { i, l -> assertFalse("${f.name}:${i + 1} $l", bad.containsMatchIn(l)) }
        }
    }
}

class DrumGateTest {
    @Test fun entersNearPadsLeavesAfterHoldFar() {
        val g = io.embodit.beatpad.domain.beat.DrumGate()
        assertFalse(g.update(Deck.IDLE, 0.8f, 0))            // pointing from afar: system ray state
        assertTrue(g.update(Deck.IDLE, 0.25f, 100))          // stick at the pads: drum mode
        assertTrue(g.update(Deck.RECORDING, 0.40f, 200))     // between enter and exit: stays (hysteresis)
        assertTrue(g.update(Deck.RECORDING, 0.60f, 300))     // far, but not yet 400 ms
        assertTrue(g.update(Deck.RECORDING, 0.60f, 650))
        assertFalse(g.update(Deck.RECORDING, 0.60f, 700))    // far for 400 ms → back to the window / panel
    }

    @Test fun nonDrumStatesAndNoControllerAreNeverDrumming() {
        for (d in listOf(Deck.LOADING, Deck.PLACING, Deck.PICKING)) {
            val g = io.embodit.beatpad.domain.beat.DrumGate()
            assertFalse("$d", g.update(d, 0.05f, 0))
        }
        val g = io.embodit.beatpad.domain.beat.DrumGate()
        assertTrue(g.update(Deck.PLAYING, 0.1f, 0))
        assertFalse(g.update(Deck.PLAYING, null, 10))         // controller lost
        assertTrue(g.update(Deck.PLAYING, 0.1f, 20))
        assertFalse(g.update(Deck.PLACING, 0.1f, 30))         // [摆放] leaves drum mode at once
    }
}
