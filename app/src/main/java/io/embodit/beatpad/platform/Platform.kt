package io.embodit.beatpad.platform

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import com.pico.spatial.ui.foundation.dsl.launch
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity
import io.embodit.beatpad.data.BeatCodec
import io.embodit.beatpad.domain.beat.BeatFlow
import io.embodit.beatpad.domain.beat.BeatState
import io.embodit.beatpad.domain.beat.FlowEffects
import io.embodit.beatpad.domain.beat.Language
import io.embodit.beatpad.domain.beat.SavedBeat
import io.embodit.beatpad.domain.beat.Settings
import io.embodit.beatpad.mainApp
import io.embodit.beatpad.platform.audio.AudioEngine
import io.embodit.beatpad.platform.audio.Pcm
import io.embodit.beatpad.ui.stage.input.BoardInputLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LaunchActivity : SpatialLaunchActivity()

class SpatialApplication : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        BoardInputLog.attach(this)
        graph = Graph(this)
        launch(::mainApp)
    }
}

/**
 * Process-wide objects (native-window-move.md §3.4): the Stage and the window share them — the audio engine, the flow,
 * the window controller, storage.
 */
class Graph(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val audio = AudioEngine(context)
    val window = WindowCtl()
    private val beatsFile = File(context.filesDir, "beats.json")
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val importDir = File(context.filesDir, "imports")
    /** Fallback folder when no system picker answers (T13): adb push files here. */
    val dropDir: File? get() = context.getExternalFilesDir("Music")

    /** Haptic pulse on a stick strike (set by the Stage: SpatialUI LocalHandControllerHaptic). */
    @Volatile var haptic: ((right: Boolean, strength: Float) -> Unit)? = null

    private val effects = object : FlowEffects {
        override fun saveSlots(slots: List<SavedBeat?>) = writeBeats()
        override fun saveSettings(s: Settings) = writeSettings(s)
        override fun saveCurrent(st: BeatState) = writeBeats()
        override fun uiTick(soft: Boolean) = audio.mixer.sound(if (soft) "ui_tick" else "ui_snap", 0.18f)
        override fun audition(pad: Int) = audio.mixer.trigger(pad, 0.85f)
        override fun requestImport(pad: Int) = startImport(pad)
        override fun ensureLoaded(path: String): Boolean = audio.mixer.hasSample(BeatFlow.importKey(path)) || (File(path).isFile && audio.loadFile(path, BeatFlow.importKey(path)))
        override fun log(line: String) = AppLog.i(line)
        override fun now(): Long = SystemClock.elapsedRealtime()
    }

    val flow = BeatFlow(audio.mixer, effects)

    init {
        val (slots, current) = loadBeats()
        flow.start(readSettings(), slots, current)
        audio.start()
        scope.launch(Dispatchers.IO) {
            audio.loadAssets()
            withContext(Dispatchers.Main) { flow.loaded() }
        }
    }

    // ------------------------------------------------------------------ import (T13)

    private fun startImport(pad: Int) {
        val i = Intent(context, ImportActivity::class.java).putExtra("pad", pad).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }.onFailure { AppLog.w("IMPORT activity failed: $it"); importFromDropDir(pad) }
    }

    /** No picker: the newest audio file in Android/data/<pkg>/files/Music (adb push). */
    fun importFromDropDir(pad: Int) {
        val f = dropDir?.listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("wav", "mp3", "ogg", "m4a") }?.maxByOrNull { it.lastModified() }
        if (f == null) { flow.notice(io.embodit.beatpad.domain.beat.Notice.NO_FILES); return }
        importFile(pad, f)
    }

    fun importFile(pad: Int, f: File) = scope.launch {
        val r = withContext(Dispatchers.IO) { audio.decode(f)?.let { finish(pad, it, f.nameWithoutExtension) } }
        deliver(pad, r)
    }

    fun importUri(pad: Int, uri: Uri) = scope.launch {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()?.substringBeforeLast('.') ?: "sound"
        val r = withContext(Dispatchers.IO) { audio.decode(uri)?.let { finish(pad, it, name) } }
        deliver(pad, r)
    }

    private data class Done(val path: String, val seconds: Float, val trimmed: Boolean)

    private fun finish(pad: Int, raw: FloatArray, name: String): Done? {
        val c = Pcm.clean(raw) ?: return null
        val safe = name.replace(Regex("[^\\p{L}\\p{N} _-]"), "").take(32).ifBlank { "sound" }
        val file = File(importDir, "${System.currentTimeMillis()}__$safe.wav")
        Pcm.writeWav(file, c.data)
        audio.mixer.putSample(BeatFlow.importKey(file.absolutePath), c.data)
        AppLog.i("IMPORT p${pad + 1} name=$safe ${"%.2f".format(c.seconds)}s trimmed=${c.trimmed} -> ${file.name}")
        return Done(file.absolutePath, c.seconds, c.trimmed)
    }

    private fun deliver(pad: Int, r: Done?) {
        if (r == null) flow.imported(pad, null, 0f, false) else flow.imported(pad, r.path, r.seconds, r.trimmed)
    }

    // ------------------------------------------------------------------ storage

    private fun loadBeats(): Pair<List<SavedBeat?>, SavedBeat?> =
        runCatching { if (beatsFile.exists()) BeatCodec.decode(beatsFile.readText()) else null }
            .onFailure { AppLog.e("BEATS load failed", it) }.getOrNull() ?: (List(12) { null } to null)

    private fun writeBeats() {
        val st = flow.s
        val cur = SavedBeat(st.beatName.removePrefix("my:").toIntOrNull() ?: 0, st.pattern, st.bpm, st.kit, st.overrides)
        val text = BeatCodec.encode(st.slots, cur)
        scope.launch(Dispatchers.IO) {
            runCatching {
                val tmp = File(beatsFile.parentFile, "beats.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(beatsFile)) { beatsFile.delete(); tmp.renameTo(beatsFile) }
            }.onFailure { AppLog.e("BEATS save failed", it) }
        }
    }

    private fun readSettings(): Settings = runCatching {
        Settings(
            language = Language.valueOf(prefs.getString("language", Language.SYSTEM.name)!!),
            muted = prefs.getBoolean("muted", false),
            master = prefs.getInt("master", 1).coerceIn(0, 2),
            metro = prefs.getInt("metro", 1).coerceIn(0, 2),
            metroMode = prefs.getInt("metroMode", 0).coerceIn(0, 2),
            feel = prefs.getInt("feel", 1).coerceIn(0, 2),
            panelRight = prefs.getBoolean("panelRight", false),
            coachDone = prefs.getBoolean("coachDone", false),
        )
    }.getOrElse { Settings() }

    private fun writeSettings(s: Settings) {
        prefs.edit().putString("language", s.language.name).putBoolean("muted", s.muted).putInt("master", s.master).putInt("metro", s.metro)
            .putInt("metroMode", s.metroMode).putInt("feel", s.feel).putBoolean("panelRight", s.panelRight).putBoolean("coachDone", s.coachDone).apply()
    }

    /** Debug `wipe`: first-run state on the next launch. */
    fun wipe() {
        beatsFile.delete()
        importDir.deleteRecursively()
        prefs.edit().clear().commit()
    }

    /** Stops trackers and sound (exit, D26). */
    @Volatile var stageRelease: (() -> Unit)? = null

    fun release() {
        runCatching { stageRelease?.invoke() }
        runCatching { audio.stop() }
        window.detach()
    }

    companion object {
        fun from(context: Context): Graph = (context.applicationContext as SpatialApplication).graph
    }
}

fun nowMs() = SystemClock.elapsedRealtime()
