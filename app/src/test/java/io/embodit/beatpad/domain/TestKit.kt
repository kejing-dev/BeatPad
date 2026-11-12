package io.embodit.beatpad.domain

import io.embodit.beatpad.domain.beat.BeatFlow
import io.embodit.beatpad.domain.beat.BeatState
import io.embodit.beatpad.domain.beat.FlowEffects
import io.embodit.beatpad.domain.beat.SavedBeat
import io.embodit.beatpad.domain.beat.Settings
import io.embodit.beatpad.platform.audio.Mixer
import io.embodit.beatpad.platform.audio.Pcm
import java.io.File

/** Shared test rig: a real Mixer with the real sample files, a flow with recording effects. */
object TestKit {
    val assets: File = listOf(File("src/main/assets/sounds"), File("app/src/main/assets/sounds")).first { it.isDirectory }

    fun mixer(): Mixer {
        val m = Mixer(48000)
        assets.listFiles()!!.filter { it.extension == "wav" }.forEach { f -> f.inputStream().use { m.putSample(f.nameWithoutExtension, Pcm.readWav(it)) } }
        return m
    }

    class Fx : FlowEffects {
        val logs = ArrayList<String>()
        var savedSlots: List<SavedBeat?>? = null
        var t = 0L
        override fun saveSlots(slots: List<SavedBeat?>) { savedSlots = slots }
        override fun saveSettings(s: Settings) {}
        override fun saveCurrent(st: BeatState) {}
        override fun uiTick(soft: Boolean) {}
        override fun audition(pad: Int) {}
        override fun requestImport(pad: Int) {}
        override fun ensureLoaded(path: String) = File(path).isFile
        override fun log(line: String) { logs += line }
        override fun now() = t
    }

    fun flow(m: Mixer = mixer()): Triple<BeatFlow, Mixer, Fx> {
        val fx = Fx()
        val f = BeatFlow(m, fx)
        f.start(Settings(), List(12) { null }, null)
        f.loaded()
        return Triple(f, m, fx)
    }

    /** Renders [frames] in 192-frame bursts (like the AudioTrack thread). */
    fun run(m: Mixer, frames: Int, out: FloatArray? = null) {
        val buf = FloatArray(192 * 2)
        var done = 0
        while (done < frames) {
            val n = minOf(192, frames - done)
            m.render(buf, n)
            out?.let { System.arraycopy(buf, 0, it, done * 2, n * 2) }
            done += n
        }
    }

    /** Renders up to frame [target] (absolute mixer clock). */
    fun runTo(m: Mixer, target: Double) { val n = (target - m.rendered).toInt(); if (n > 0) run(m, n) }
}
