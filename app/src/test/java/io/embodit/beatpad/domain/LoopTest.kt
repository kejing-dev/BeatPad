package io.embodit.beatpad.domain

import io.embodit.beatpad.domain.TestKit.run
import io.embodit.beatpad.domain.TestKit.runTo
import io.embodit.beatpad.domain.beat.Deck
import io.embodit.beatpad.domain.beat.Confirm
import io.embodit.beatpad.domain.beat.Grooves
import io.embodit.beatpad.domain.beat.Notice
import io.embodit.beatpad.domain.beat.Pattern
import io.embodit.beatpad.domain.beat.Tempo
import io.embodit.beatpad.domain.beat.UNDO_DEPTH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Record / overdub / undo / count-in through the real flow + mixer (verification.unitTests, M2 M3 M18). */
class LoopTest {
    private val sr = 48000
    private val snare = 1

    /** Presses [录] and renders through the 4 count-in clicks; returns the bar start frame. */
    private fun countIn(f: io.embodit.beatpad.domain.beat.BeatFlow, m: io.embodit.beatpad.platform.audio.Mixer): Double {
        f.recButton("test")
        assertEquals(Deck.COUNT_IN, f.s.deck)
        val snap = m.snapshot()
        runTo(m, snap.recStart + 1)
        f.tick(m.rendered.toDouble())
        assertEquals(Deck.RECORDING, f.s.deck)
        return snap.recStart
    }

    @Test fun recordFourSnaresOnTheBeat() {
        val (f, m, _) = TestKit.flow()
        val bar0 = countIn(f, m)
        val step = Tempo.stepFrames(96.0, sr)
        val offsets = listOf(-40, 25, 40, -10) // ms off the beat
        for ((i, s) in listOf(0, 4, 8, 12).withIndex()) {
            val at = bar0 + s * step + offsets[i] * 48.0
            runTo(m, maxOf(at, m.rendered.toDouble()))
            f.hit(snare, 0.8f, at, "test")
        }
        assertEquals(listOf(0, 4, 8, 12), f.s.pattern.steps(snare))
        f.recButton("test") // stop recording → loop keeps playing
        assertEquals(Deck.PLAYING, f.s.deck)
        // every pass sounds the same 4 snares + nothing else (each pass triggers exactly 4)
        runTo(m, bar0 + Tempo.barFrames(96.0, sr) - 10)
        val before = m.triggerCount
        run(m, Tempo.barFrames(96.0, sr).toInt() * 3)
        assertEquals(12, m.triggerCount - before)
    }

    @Test fun countInTailLandsOnStepZero() {
        val (f, m, _) = TestKit.flow()
        f.recButton("test")
        val snap = m.snapshot()
        val tail = snap.recStart - Tempo.barFrames(96.0, sr) / 40 // inside the last 1/32
        runTo(m, tail)
        f.hit(snare, 0.8f, tail, "test")
        assertEquals(listOf(0), f.s.pattern.steps(snare))
        // the step-0 occurrence that was not rendered yet must not sound a second time (R1-05b)
        val before = m.triggerCount
        runTo(m, snap.recStart + Tempo.stepFrames(96.0, sr) / 2)
        assertEquals(0, m.triggerCount - before)
    }

    @Test fun earlyHitIsNotReplayedThisPass() {
        val (f, m, _) = TestKit.flow()
        val bar0 = countIn(f, m)
        val step = Tempo.stepFrames(96.0, sr)
        // the player hits 30 ms before step 4 (we have rendered up to the hit; step 4 is still ahead)
        val at = bar0 + 4 * step - 30 * 48.0
        runTo(m, at)
        f.hit(snare, 0.8f, at, "test")
        val before = m.triggerCount
        runTo(m, bar0 + 5 * step)
        assertEquals("step 4 must not double the live hit", 0, m.triggerCount - before)
        runTo(m, bar0 + Tempo.barFrames(96.0, sr) + 5 * step)
        assertEquals("next pass plays it", 1, m.triggerCount - before)
    }

    @Test fun overdubKeepsTheLouderAndUndoTakesOneLayer() {
        val (f, m, _) = TestKit.flow()
        val bar0 = countIn(f, m)
        val step = Tempo.stepFrames(96.0, sr)
        f.hit(snare, 0.4f, bar0 + 4 * step, "test")
        f.recButton("test")
        val layer1 = f.s.pattern
        // second layer while playing: count-in on the last 4 beats, recording from the next bar
        f.recButton("test")
        val snap = m.snapshot()
        assertTrue(snap.recStart > m.rendered)
        runTo(m, snap.recStart + 1); f.tick(m.rendered.toDouble())
        assertEquals(Deck.RECORDING, f.s.deck)
        val b = snap.recStart
        f.hit(snare, 1.0f, b + 4 * step, "test")
        f.hit(2, 1.0f, b + 0 * step, "test")
        assertEquals(3, f.s.pattern[snare, 4])
        f.recButton("test")
        f.undoButton()
        assertEquals(layer1, f.s.pattern)
    }

    @Test fun undoInsideARecordingKeepsRecording() {
        val (f, m, _) = TestKit.flow()
        val bar0 = countIn(f, m)
        f.hit(snare, 0.8f, bar0, "test")
        f.undoButton()
        assertEquals(Deck.RECORDING, f.s.deck)
        assertTrue(f.s.pattern.isEmpty)
    }

    @Test fun undoStackThirtyAndEveryKind() {
        val (f, _, _) = TestKit.flow()
        repeat(40) { f.tapSlot(it % 16) }
        assertEquals(UNDO_DEPTH, f.undoDepth)
        // tap, drag, load, clear (blank card) each undo back
        val (g, _, _) = TestKit.flow()
        g.tapSlot(3); val afterTap = g.s.pattern
        g.dragStoneStart(3); g.dragStoneMove(7, false); g.dragStoneEnd()
        assertEquals(2, g.s.pattern[1, 7])
        g.undoButton(); assertEquals(afterTap, g.s.pattern)
        // an unsaved beat on the deck: the card asks first (QA #1), [换掉] loads
        g.cardTo(1); g.loadCard(); assertEquals(Confirm.LOAD, g.s.confirm); assertEquals(afterTap, g.s.pattern)
        g.confirmLoad(); assertEquals(Grooves.all[0].pattern, g.s.pattern); assertEquals(Confirm.NONE, g.s.confirm)
        g.undoButton(); assertEquals(afterTap, g.s.pattern)
        g.cardTo(0); g.loadCard(); g.confirmLoad(); assertTrue(g.s.pattern.isEmpty)
        g.undoButton(); assertEquals(afterTap, g.s.pattern)
        g.undoButton(); assertTrue(g.s.pattern.isEmpty)
    }

    @Test fun loadingACardAsksOnlyWhenTheBeatIsUnsaved() {
        val (g, _, _) = TestKit.flow()
        g.cardTo(2); g.loadCard(); assertEquals(Confirm.NONE, g.s.confirm) // blank deck: no question
        g.cardTo(3); g.loadCard(); assertEquals(Confirm.NONE, g.s.confirm) // an untouched groove: no question
        g.tapSlot(5)
        g.cardTo(4); g.loadCard(); assertEquals(Confirm.LOAD, g.s.confirm)
        g.confirmCancel(); assertEquals(Confirm.NONE, g.s.confirm); assertEquals(Grooves.all[2].pattern != g.s.pattern, true)
        g.saveButton() // saved into a my-slot → loading again does not ask
        g.cardTo(1); g.loadCard(); assertEquals(Confirm.NONE, g.s.confirm)
    }

    @Test fun stoneDragMovesMergesAndDeletes() {
        val (f, _, _) = TestKit.flow()
        f.tapSlot(2); f.tapSlot(6)
        f.dragStoneStart(2); f.dragStoneMove(6, false); f.dragStoneEnd()
        assertEquals(listOf(6), f.s.pattern.steps(1)) // merged
        f.dragStoneStart(6); f.dragStoneMove(6, true); f.dragStoneEnd()
        assertTrue(f.s.pattern.isEmpty)
        assertNull(f.s.drag)
    }

    @Test fun tempoChangeWhilePlayingDropsNoBeat() {
        val (f, m, _) = TestKit.flow()
        f.cardTo(1); f.loadCard() // garage, 100 BPM
        f.playButton("test")
        val perBar = Grooves.all[0].pattern.hitCount
        val before = m.triggerCount
        run(m, (Tempo.barFrames(100.0, sr) * 0.37).toInt())
        f.hubStart(); f.hubMove(0.1f); f.hubEnd() // 100 → 120
        assertEquals(120, f.s.bpm)
        val left = Tempo.barFrames(120.0, sr) * (1 - 0.37) + Tempo.barFrames(120.0, sr) * 2
        run(m, left.toInt() - 200)
        assertEquals(perBar * 3, m.triggerCount - before)
    }

    @Test fun playOnEmptyRingSaysWhy() {
        val (f, _, _) = TestKit.flow()
        f.playButton("test")
        assertEquals(Deck.IDLE, f.s.deck)
        assertEquals(Notice.EMPTY_PLAY, f.s.notice)
    }

    @Test fun focusFollowsTheLastHitOnlyWhenStopped() {
        val (f, m, fx) = TestKit.flow()
        f.hit(4, 0.8f, null, "stick"); assertEquals(4, f.s.focus)
        f.cardTo(1); f.loadCard(); f.playButton("test")
        fx.t = 1000; f.hit(2, 0.8f, null, "stick"); assertEquals(4, f.s.focus) // looping: one stick hit does not move it
        fx.t = 1700; f.hit(2, 0.8f, null, "stick"); assertEquals(2, f.s.focus) // 700 ms of the same pad
        fx.t = 1800; f.hit(6, 0.8f, null, "tap"); assertEquals(6, f.s.focus)    // pointer tap does
        m.stop()
    }

    @Test fun loadWhilePlayingWaitsForTheBar() {
        val (f, m, _) = TestKit.flow()
        f.cardTo(1); f.loadCard(); f.playButton("test")
        run(m, 20000)
        f.cardTo(4); f.loadCard()
        assertEquals(Notice.LOADED_NEXT_BAR, f.s.notice)
        assertEquals(Pattern.EMPTY != m.pattern, true)
        assertEquals(Grooves.all[0].pattern, m.pattern)
        run(m, Tempo.barFrames(100.0, sr).toInt())
        assertEquals(Grooves.all[3].pattern, m.pattern)
    }

    @Test fun sameSoundSwitchBackAndForthThreeTimes() {
        // D48: switching beats 3 times keeps sound and picture (pattern + triggers)
        val (f, m, _) = TestKit.flow()
        repeat(3) { k ->
            f.cardTo(1 + (k % 6)); f.loadCard(); f.playButton("test")
            val before = m.triggerCount
            run(m, Tempo.barFrames(f.s.bpm.toDouble(), sr).toInt() + 100)
            assertTrue(m.triggerCount - before >= f.s.pattern.hitCount)
            f.playButton("test")
        }
    }
}
