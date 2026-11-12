package io.embodit.beatpad.domain.beat

import io.embodit.beatpad.platform.audio.Mixer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Deck axis D0–D7 (D5 dragging is a modifier, [BeatState.drag]). */
enum class Deck(val id: String) { LOADING("D0"), IDLE("D1"), COUNT_IN("D2"), RECORDING("D3"), PLAYING("D4"), PLACING("D6"), PICKING("D7") }

enum class Page { PLAY, LIBRARY, SETTINGS }
enum class Sub { NONE, SOUND, KIT, PAD_SOUND, MORE, ABOUT }
enum class Confirm { NONE, EXIT, SLOT, LOAD }
enum class Notice { NONE, SAVED, FULL, EMPTY_PLAY, STOP_FIRST, IMPORT_OK, IMPORT_FAIL, IMPORT_LONG, RESTORED, LOADED_NEXT_BAR, NO_FILES }
enum class Language { SYSTEM, ZH, EN }

/** What is being dragged (D5 stone, the speed knob, the deck in D6). */
sealed class Drag {
    data class Stone(val pad: Int, val from: Int, val slot: Int, val off: Boolean, val vel: Int) : Drag()
    data class Hub(val startBpm: Int) : Drag()
    data object DeckMove : Drag()
}

data class Settings(
    val language: Language = Language.SYSTEM,
    val muted: Boolean = false,
    /** 0 / 1 / 2 = low / mid / high (40 / 75 / 100 %). */
    val master: Int = 1,
    /** Metronome level 0 / 1 / 2. */
    val metro: Int = 1,
    /** 0 = count-in only, 1 = always, 2 = off. */
    val metroMode: Int = 0,
    /** 0 = light hands, 1 = normal, 2 = heavy hands. */
    val feel: Int = 1,
    val panelRight: Boolean = false,
    val coachDone: Boolean = false,
)

data class BeatState(
    val deck: Deck = Deck.LOADING,
    /** Where D2 / D6 / D7 go back to. */
    val back: Deck = Deck.IDLE,
    val drag: Drag? = null,
    val page: Page = Page.PLAY,
    val sub: Sub = Sub.NONE,
    val confirm: Confirm = Confirm.NONE,
    val notice: Notice = Notice.NONE,
    val noticeArg: String = "",
    val noticeAt: Long = 0L,
    val pattern: Pattern = Pattern.EMPTY,
    val bpm: Int = BPM_DEFAULT,
    val kit: KitId = KitId.ACOUSTIC,
    /** Pad → file path of an imported sound (any kit); the display name is [displayName]. */
    val overrides: Map<Int, String> = emptyMap(),
    val focus: Int = 1,
    /** Name of the beat on the ring: a groove id, "my:N", or "" (blank). */
    val beatName: String = "",
    /** Library card 0 = blank, 1…6 grooves, 7…18 my slots. */
    val card: Int = 0,
    val slots: List<SavedBeat?> = List(MY_SLOTS) { null },
    val canUndo: Boolean = false,
    val countLeft: Int = 4,
    val picked: Int? = null,
    val hasHitOnce: Boolean = false,
    val lastHitPad: Int? = null,
    val lastHitAt: Long = 0L,
) {
    val stateId: String get() = deck.id + if (drag is Drag.Stone) "+D5" else ""
    val tabBarHidden: Boolean get() = deck == Deck.PLACING || (deck == Deck.PICKING && picked == null)
    val busy: Boolean get() = deck == Deck.RECORDING || deck == Deck.COUNT_IN

    /** content.tone.statusLines key — the one source for the window status line and the ring caption (D38). */
    val statusKey: String get() = when {
        drag is Drag.Stone -> "D5"
        deck == Deck.LOADING -> "D0"
        deck == Deck.IDLE -> if (pattern.isEmpty) "D1.empty" else "D1.hasBeat"
        else -> deck.id
    }

    companion object {
        const val CARDS = 1 + 6 + MY_SLOTS
    }
}

/** Effects the flow asks the platform for (sound, haptics, storage, file picker, log). */
interface FlowEffects {
    fun saveSlots(slots: List<SavedBeat?>)
    fun saveSettings(s: Settings)
    fun saveCurrent(st: BeatState)
    fun uiTick(soft: Boolean)
    fun audition(pad: Int)
    fun requestImport(pad: Int)
    /** Makes sure an imported file is decoded into the mixer; false when the file is gone. */
    fun ensureLoaded(path: String): Boolean
    fun log(line: String)
    fun now(): Long
}

/**
 * The whole BeatPad state machine (states[] D0–D7 × P1–P5). Every input — window buttons, 3D rec key, stick strikes,
 * pointer taps, controller keys, debug broadcasts — calls a method here; nothing else changes state (debug = same path).
 * The [mixer] owns time: the flow drives it and reads its transport back every frame in [tick].
 */
class BeatFlow(private val mixer: Mixer, private val fx: FlowEffects) {
    private val _state = MutableStateFlow(BeatState())
    val state: StateFlow<BeatState> = _state.asStateFlow()
    val s: BeatState get() = _state.value
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private data class Snap(val pattern: Pattern, val bpm: Int, val kit: KitId, val name: String, val load: Boolean = false)
    private val undo = ArrayDeque<Snap>()
    private var layerStart: Snap? = null
    private var focusRunPad = -1
    private var focusRunSince = 0L

    private fun set(f: (BeatState) -> BeatState) {
        val before = _state.value
        val after = f(before).copy(canUndo = undo.isNotEmpty())
        _state.value = after
        if (before.stateId != after.stateId) fx.log("STATE ${before.stateId} -> ${after.stateId}")
    }

    private fun snap() = Snap(s.pattern, s.bpm, s.kit, s.beatName)

    private fun pushUndo(load: Boolean = false) {
        undo.addLast(snap().copy(load = load))
        while (undo.size > UNDO_DEPTH) undo.removeFirst()
    }

    val undoDepth: Int get() = undo.size

    // ------------------------------------------------------------------ start / load

    fun start(settings: Settings, slots: List<SavedBeat?>, current: SavedBeat?) {
        _settings.value = settings
        applySettings(settings)
        val p = current?.pattern ?: Pattern.EMPTY
        mixer.load(p, current?.bpm ?: BPM_DEFAULT, current?.kit ?: KitId.ACOUSTIC)
        set { it.copy(slots = slots, pattern = p, bpm = current?.bpm ?: BPM_DEFAULT, kit = current?.kit ?: KitId.ACOUSTIC, beatName = current?.let { c -> if (c.name > 0) "my:${c.name}" else "" } ?: "") }
        current?.overrides?.let { useOverrides(it) }
    }

    /** Applies imported sounds; a file that is gone falls back to the kit sound with a notice (verification: 路径失效). */
    private fun useOverrides(ov: Map<Int, String>) {
        val ok = ov.filter { (_, path) -> fx.ensureLoaded(path) }
        mixer.overrides = ok.mapValues { importKey(it.value) }
        set { it.copy(overrides = ok) }
        if (ok.size < ov.size) { notice(Notice.IMPORT_FAIL); fx.log("IMPORT missing ${ov.keys - ok.keys}") }
    }

    /** Samples are decoded: D0 → D1. */
    fun loaded() { if (s.deck == Deck.LOADING) set { it.copy(deck = Deck.IDLE) } }

    // ------------------------------------------------------------------ pads

    /** Any strike (stick, hand, pointer tap): sound now, record if recording, move the focus (beatRingEditing.focus). */
    fun hit(pad: Int, vel: Float, musicalFrame: Double?, via: String) { hitReturning(pad, vel, musicalFrame, via) }

    /** Same as [hit]; returns the ring step the strike was recorded on (null = not recorded). */
    fun hitReturning(pad: Int, vel: Float, musicalFrame: Double?, via: String): Int? {
        val st = s
        if (st.deck == Deck.LOADING) return null
        if (st.deck == Deck.PICKING) { pickPad(pad); return null }
        if (st.deck == Deck.PLACING) return null
        mixer.trigger(pad, vel)
        val step = if (musicalFrame != null) mixer.recordHit(pad, vel, musicalFrame) else null
        fx.log("HIT p${pad + 1} v=${"%.2f".format(vel)} via=$via step=${step ?: "-"} state=${st.stateId}")
        val now = fx.now()
        // focus: stopped → last hit; looping → only a pointer tap or 600 ms of the same pad; locked while dragging a stone
        val looping = st.deck == Deck.RECORDING || st.deck == Deck.PLAYING || st.deck == Deck.COUNT_IN
        if (pad != focusRunPad) { focusRunPad = pad; focusRunSince = now }
        val newFocus = when {
            st.drag is Drag.Stone -> st.focus
            !looping -> pad
            via == "tap" -> pad
            now - focusRunSince >= 600 -> pad
            else -> st.focus
        }
        set { it.copy(pattern = if (step != null) mixer.pattern else it.pattern, focus = newFocus, hasHitOnce = true, lastHitPad = pad, lastHitAt = now) }
        if (step != null) fx.saveCurrent(s)
        return step
    }

    // ------------------------------------------------------------------ transport

    /** [录] / 3D rec key / A·X. */
    fun recButton(via: String) {
        fx.log("REC via=$via state=${s.stateId}")
        when (s.deck) {
            Deck.IDLE, Deck.PLAYING -> {
                mixer.startCountIn()
                set { it.copy(deck = Deck.COUNT_IN, back = it.deck, countLeft = 4) }
            }
            Deck.COUNT_IN -> {
                mixer.cancelCountIn()
                set { it.copy(deck = it.back) }
            }
            Deck.RECORDING -> {
                mixer.stopRecording()
                layerStart = null
                set { it.copy(deck = Deck.PLAYING, pattern = mixer.pattern) }
                completeCoach()
                fx.saveCurrent(s)
            }
            else -> Unit
        }
    }

    /** [▶ 播放] / [■ 停] / B·Y. */
    fun playButton(via: String) {
        fx.log("PLAY via=$via state=${s.stateId}")
        when (s.deck) {
            Deck.IDLE -> {
                if (s.pattern.isEmpty) { notice(Notice.EMPTY_PLAY); return }
                mixer.play(); set { it.copy(deck = Deck.PLAYING) }
            }
            Deck.PLAYING, Deck.RECORDING, Deck.COUNT_IN -> stop()
            else -> Unit
        }
    }

    fun stop() {
        mixer.stop()
        layerStart = null
        set { it.copy(deck = Deck.IDLE, drag = if (it.drag is Drag.Stone) null else it.drag, pattern = mixer.pattern) }
        fx.saveCurrent(s)
    }

    /** Every Stage frame: follow the audio transport (count-in → recording, count-in numbers). */
    fun tick(playedFrame: Double) {
        val st = s
        if (st.deck != Deck.COUNT_IN) return
        val snap = mixer.snapshot()
        if (snap.recording && !snap.countingIn) {
            pushUndo()
            layerStart = snap()
            set { it.copy(deck = Deck.RECORDING, pattern = mixer.pattern) }
            return
        }
        val clicks = snap.countClicksPlayedBefore ?: return
        val n = clicks.count { it <= playedFrame }
        val left = (4 - (n - 1)).coerceIn(1, 4)
        if (left != st.countLeft) set { it.copy(countLeft = left) }
    }

    fun undoButton() {
        val st = s
        if (st.deck == Deck.RECORDING) {
            // D3: take back what this layer recorded so far, keep recording
            val ls = layerStart ?: return
            mixer.pattern = ls.pattern
            set { it.copy(pattern = ls.pattern) }
            fx.log("UNDO layer (recording continues)")
            return
        }
        val last = undo.removeLastOrNull() ?: return
        mixer.pattern = last.pattern
        // a load step brings back the old tempo / kit / name too; other steps only the stones (the knob is not a step)
        if (last.load) {
            if (last.bpm != st.bpm) mixer.setBpm(last.bpm)
            if (last.kit != st.kit) mixer.kit = last.kit
            set { it.copy(pattern = last.pattern, bpm = last.bpm, kit = last.kit, beatName = last.name) }
        } else set { it.copy(pattern = last.pattern) }
        fx.log("UNDO -> depth ${undo.size}")
        fx.saveCurrent(s)
    }

    // ------------------------------------------------------------------ the ring

    /** T7: tap an empty slot = add a mid hit (and play it); tap a stone = delete it (soft tick). */
    fun tapSlot(slot: Int) {
        val st = s
        if (st.deck == Deck.LOADING || st.deck == Deck.PICKING || st.deck == Deck.PLACING) return
        val pad = st.focus
        pushUndo()
        val p = if (st.pattern[pad, slot] > 0) { fx.uiTick(true); st.pattern.with(pad, slot, 0) } else { mixer.trigger(pad, Velocity.TAP); st.pattern.with(pad, slot, 2) }
        mixer.pattern = p
        set { it.copy(pattern = p) }
        fx.log("SLOT p${pad + 1} s=$slot -> ${p[pad, slot]}")
        fx.saveCurrent(s)
    }

    fun dragStoneStart(slot: Int): Boolean {
        val st = s
        val pad = st.focus
        if (st.pattern[pad, slot] == 0 || st.deck == Deck.PICKING || st.deck == Deck.PLACING || st.deck == Deck.LOADING) return false
        set { it.copy(drag = Drag.Stone(pad, slot, slot, false, st.pattern[pad, slot])) }
        fx.log("DRAG stone p${pad + 1} from=$slot")
        return true
    }

    fun dragStoneMove(slot: Int, off: Boolean) {
        val d = s.drag as? Drag.Stone ?: return
        if (d.slot != slot || d.off != off) set { it.copy(drag = d.copy(slot = slot, off = off)) }
    }

    /** Release: off the ring = delete; on another slot = move there (two stones merge, the louder stays); sound on landing. */
    fun dragStoneEnd() {
        val d = s.drag as? Drag.Stone ?: return
        pushUndo()
        var p = mixer.pattern.with(d.pad, d.from, 0)
        if (d.off) fx.uiTick(true)
        else { p = p.merge(d.pad, d.slot, d.vel); mixer.trigger(d.pad, Velocity.gain(d.vel)) }
        mixer.pattern = p
        set { it.copy(drag = null, pattern = p) }
        fx.log("DROP stone p${d.pad + 1} ${d.from} -> ${if (d.off) "off (deleted)" else d.slot}")
        fx.saveCurrent(s)
    }

    private var hub: HubDrag? = null
    fun hubStart() { if (s.deck == Deck.LOADING) return; hub = HubDrag(s.bpm); set { it.copy(drag = Drag.Hub(it.bpm)) } }
    fun hubMove(dyM: Float) {
        val h = hub ?: return
        val (bpm, snapped) = h.move(dyM)
        if (snapped) fx.uiTick(true)
        if (bpm != s.bpm) { mixer.setBpm(bpm); set { it.copy(bpm = bpm) } }
    }
    fun hubEnd() { if (hub != null) { hub = null; set { it.copy(drag = null) }; fx.log("BPM ${s.bpm}"); fx.saveCurrent(s) } }

    /** debug `bpm n` and the library loads. */
    fun setBpm(bpm: Int) { val b = bpm.coerceIn(BPM_MIN, BPM_MAX); mixer.setBpm(b); set { it.copy(bpm = b) } }

    // ------------------------------------------------------------------ library

    fun card(delta: Int) = set { it.copy(card = ((it.card + delta) % BeatState.CARDS + BeatState.CARDS) % BeatState.CARDS, confirm = Confirm.NONE) }
    fun cardTo(i: Int) = set { it.copy(card = i.coerceIn(0, BeatState.CARDS - 1), confirm = Confirm.NONE) }

    /**
     * The beat on the deck differs from where it came from (a my-slot, a ready-made groove, or blank): loading a card
     * would replace it without a word, so the library asks first (QA #1 non-blocking).
     */
    val unsaved: Boolean get() {
        val st = s
        val n = st.beatName
        return when {
            n.startsWith("my:") -> st.slots.firstOrNull { it != null && "my:${it.name}" == n }?.let { it.pattern != st.pattern || it.bpm != st.bpm || it.kit != st.kit } ?: !st.pattern.isEmpty
            n.isNotEmpty() -> Grooves.of(n)?.let { it.pattern != st.pattern } ?: !st.pattern.isEmpty
            else -> !st.pattern.isEmpty
        }
    }

    /** T10: tap the card = load it (next bar start when playing); an unsaved beat on the deck asks first. */
    fun loadCard(force: Boolean = false) {
        val st = s
        if (st.busy) { notice(Notice.STOP_FIRST); return }
        if (!force && unsaved) { set { it.copy(confirm = Confirm.LOAD) }; fx.log("LOAD ask (unsaved beat on the deck)"); return }
        val c = st.card
        val (p, bpm, kit, name) = when {
            c == 0 -> Quad(Pattern.EMPTY, st.bpm, st.kit, "")
            c <= 6 -> Grooves.all[c - 1].let { g -> Quad(g.pattern, g.bpm, g.kit, g.id) }
            else -> st.slots[c - 7]?.let { b -> Quad(b.pattern, b.bpm, b.kit, "my:${b.name}") } ?: return
        }
        pushUndo(load = true)
        if (c > 6) useOverrides(st.slots[c - 7]?.overrides.orEmpty())
        mixer.load(p, bpm, kit)
        val nextBar = mixer.hasPendingLoad
        set { it.copy(pattern = p, bpm = bpm, kit = kit, beatName = name, notice = if (nextBar) Notice.LOADED_NEXT_BAR else it.notice, noticeAt = if (nextBar) fx.now() else it.noticeAt) }
        fx.log("LOAD card=$c name=$name bpm=$bpm kit=${kit.id} nextBar=$nextBar")
        fx.saveCurrent(s)
    }

    private data class Quad(val p: Pattern, val bpm: Int, val kit: KitId, val name: String)

    /** The one button under the card: [存到新空位] (blank / ready-made card) or [存到这里] (a my-slot card). */
    fun saveButton() {
        val st = s
        val c = st.card
        if (c <= 6) {
            val free = st.slots.indexOfFirst { it == null }
            if (free < 0) { notice(Notice.FULL); return }
            saveTo(free)
        } else {
            if (st.slots[c - 7] != null) { set { it.copy(confirm = Confirm.SLOT) }; return }
            saveTo(c - 7)
        }
    }

    private fun saveTo(i: Int) {
        val st = s
        val b = SavedBeat(i + 1, st.pattern, st.bpm, st.kit, st.overrides)
        val slots = st.slots.toMutableList().also { it[i] = b }
        set { it.copy(slots = slots, confirm = Confirm.NONE, beatName = "my:${i + 1}", notice = Notice.SAVED, noticeArg = "${i + 1}", noticeAt = fx.now()) }
        fx.saveSlots(slots)
        fx.log("SAVE slot=${i + 1}")
    }

    fun confirmOverwrite() { val c = s.card; if (c > 6) saveTo(c - 7) }
    fun confirmClearSlot() {
        val c = s.card
        if (c <= 6) return
        val slots = s.slots.toMutableList().also { it[c - 7] = null }
        set { it.copy(slots = slots, confirm = Confirm.NONE) }
        fx.saveSlots(slots)
        fx.log("CLEAR slot=${c - 6}")
    }

    fun confirmLoad() { set { it.copy(confirm = Confirm.NONE) }; loadCard(force = true) }
    fun confirmCancel() = set { it.copy(confirm = Confirm.NONE) }
    val freeSlots: Int get() = s.slots.count { it == null }

    // ------------------------------------------------------------------ pages / settings

    fun page(p: Page) {
        if (s.tabBarHidden) return
        set { it.copy(page = p, sub = Sub.NONE, confirm = Confirm.NONE) }
    }
    fun sub(x: Sub) = set { it.copy(sub = x, confirm = Confirm.NONE) }

    fun updateSettings(f: (Settings) -> Settings) {
        val n = f(_settings.value)
        _settings.value = n
        applySettings(n)
        fx.saveSettings(n)
    }

    private fun applySettings(n: Settings) {
        mixer.muted = n.muted
        mixer.master = MASTER[n.master.coerceIn(0, 2)] / 100f
        mixer.metroGain = METRO[n.metro.coerceIn(0, 2)]
        mixer.metroMode = n.metroMode
    }

    fun mute(on: Boolean) = updateSettings { it.copy(muted = on) }
    fun kitNext() { val k = s.kit.next(); mixer.kit = k; set { it.copy(kit = k) }; fx.log("KIT ${k.id}"); fx.saveCurrent(s) }
    fun setKit(k: KitId) { mixer.kit = k; set { it.copy(kit = k) }; fx.saveCurrent(s) }

    fun exitAsk() = set { it.copy(confirm = Confirm.EXIT, page = Page.SETTINGS, sub = Sub.NONE) }

    fun replayCoach() { updateSettings { it.copy(coachDone = false) }; set { it.copy(hasHitOnce = false, page = Page.PLAY, sub = Sub.NONE) } }
    private fun completeCoach() { if (!_settings.value.coachDone) updateSettings { it.copy(coachDone = true) } }

    fun notice(n: Notice, arg: String = "") { set { it.copy(notice = n, noticeArg = arg, noticeAt = fx.now()) }; fx.log("NOTICE $n $arg") }
    fun clearNotice() { if (s.notice != Notice.NONE) set { it.copy(notice = Notice.NONE) } }

    // ------------------------------------------------------------------ D6 place the deck

    fun placeStart() {
        if (s.busy) { notice(Notice.STOP_FIRST); return }
        set { it.copy(deck = Deck.PLACING, back = it.deck) }
    }
    fun placeDone() { if (s.deck == Deck.PLACING) set { it.copy(deck = if (mixer.playing) Deck.PLAYING else Deck.IDLE, drag = null) } }

    // ------------------------------------------------------------------ D7 change one pad's sound

    fun pickStart() {
        if (s.busy || s.deck == Deck.PLAYING) { notice(Notice.STOP_FIRST); return }
        set { it.copy(deck = Deck.PICKING, back = it.deck, picked = null) }
    }

    fun pickPad(pad: Int) {
        if (s.deck != Deck.PICKING) return
        fx.audition(pad)
        set { it.copy(picked = pad, page = Page.SETTINGS, sub = Sub.PAD_SOUND, focus = pad) }
        fx.log("PICK p${pad + 1}")
    }

    /** settings.padSound [‹ 返回] ends D7 (back to settings.kit); pickPad [取消] / closing the window cancels. */
    fun pickEnd(toKit: Boolean) {
        if (s.deck != Deck.PICKING) return
        set { it.copy(deck = Deck.IDLE, picked = null, page = Page.SETTINGS, sub = if (toKit) Sub.KIT else it.sub) }
    }

    fun importButton() { val p = s.picked ?: return; fx.requestImport(p) }

    /** The platform decoded a file for [pad] into [path] (null = could not open it), [seconds] long, [trimmed] to 4 s. */
    fun imported(pad: Int, path: String?, seconds: Float, trimmed: Boolean) {
        if (path == null) { notice(Notice.IMPORT_FAIL); return }
        mixer.overrides = mixer.overrides + (pad to importKey(path))
        set { it.copy(overrides = it.overrides + (pad to path)) }
        notice(if (trimmed) Notice.IMPORT_LONG else Notice.IMPORT_OK, "${displayName(path)}|${"%.1f".format(seconds)}")
        fx.audition(pad)
        fx.saveCurrent(s)
    }

    fun restoreDefault() {
        val p = s.picked ?: return
        mixer.overrides = mixer.overrides - p
        set { it.copy(overrides = it.overrides - p) }
        notice(Notice.RESTORED)
        fx.audition(p)
        fx.saveCurrent(s)
    }

    /** The window was closed by the system ×: D6 = [放好了], D7 = cancel (R1-09). */
    fun windowClosed() {
        when (s.deck) { Deck.PLACING -> placeDone(); Deck.PICKING -> pickEnd(false); else -> Unit }
    }

    companion object {
        val MASTER = intArrayOf(40, 75, 100)
        val METRO = floatArrayOf(0.15f, 0.25f, 0.4f)
        fun importKey(path: String) = "file:$path"
        /** Imported files are saved as `<millis>__<original name>.wav`; the original name is shown. */
        fun displayName(path: String) = path.substringAfterLast('/').substringAfter("__").removeSuffix(".wav")
    }
}
