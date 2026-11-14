package io.embodit.beatpad.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.embodit.beatpad.domain.beat.Deck
import io.embodit.beatpad.domain.beat.Grooves
import io.embodit.beatpad.domain.beat.KitId
import io.embodit.beatpad.domain.beat.Language
import io.embodit.beatpad.domain.beat.Notice
import io.embodit.beatpad.domain.beat.Page
import io.embodit.beatpad.domain.beat.Sub
import io.embodit.beatpad.ui.stage.ActiveScene
import io.embodit.beatpad.ui.stage.Hit
import io.embodit.beatpad.ui.stage.input.InputSource
import java.io.File

/**
 * Debug builds only (verification.debugHooks). Every command goes through the same flow / scene entry as real input.
 *
 *   adb shell am broadcast -a io.embodit.beatpad.DEBUG -p io.embodit.beatpad --es cmd <cmd> [--es a "<arg>"]
 *
 *   tap pad N | slot N | stone N | hub | rec        (N from 1; same as a pointer tap)
 *   hit N VEL                                         (a stick strike on pad N, velocity 0–1, at the heard frame)
 *   drag stone N TO | drag stone N off · drag hub CM  (ring-plane drag through the drag path)
 *   stick X (D6 thumbstick) · key A|B|X|Y · rec · play · stop · undo
 *   hold D0…D7 [on|off] · groove ID · kit acoustic|electronic|hand · bpm N · card N · load · save · confirm overwrite|clear|cancel
 *   page play|library|settings · sub none|sound|kit|padsound|more|about · lang zh|en|system · mute on|off · volume 0-2
 *   place · placedone · turn ±DEG · front · side left|right · pick · pickpad N · pickend · import PATH · importdrop
 *   notice NAME · exitask · wipe · dump · fly N S1,S2… · testtex on|off
 *   limb hidden|visible|default   (device experiment only: SDK UpperLimbRenderMode, does it touch the system ray? D41 待核实)
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = Graph.from(context)
        val flow = graph.flow
        val scene = ActiveScene.scene
        val cmd = intent.getStringExtra("cmd").orEmpty().trim()
        val a = intent.getStringExtra("a").orEmpty().trim().trim('"')
        val p = a.split(' ', ',').filter { it.isNotBlank() }
        val on = a == "on" || a == "true" || a == "1"
        AppLog.i("CMD $cmd $a")
        fun n(i: Int) = p.getOrNull(i)?.toIntOrNull()
        when (cmd) {
            "limb" -> {
                val mode = when (a) { "hidden" -> com.pico.spatial.ui.platform.ability.UpperLimbRenderMode.Hidden; "visible" -> com.pico.spatial.ui.platform.ability.UpperLimbRenderMode.Visible; else -> com.pico.spatial.ui.platform.ability.UpperLimbRenderMode.Default }
                val r = runCatching { ActiveScene.limb!!.setUpperLimbRenderMode(mode) }
                AppLog.i("LIMB mode=$mode result=${r.getOrNull()} err=${r.exceptionOrNull()?.javaClass?.simpleName}")
            }
            "tap" -> {
                val hit = when (p.getOrNull(0)) {
                    "pad" -> n(1)?.let { Hit.Pad(it - 1) }; "slot", "stone" -> n(1)?.let { Hit.Slot(it - 1) }
                    "hub" -> Hit.Hub; "rec" -> Hit.Rec; else -> null
                }
                scene?.onTap(hit, InputSource.UNKNOWN)
            }
            "hit" -> {
                val pad = (n(0) ?: 1) - 1
                val vel = p.getOrNull(1)?.toFloatOrNull() ?: 0.8f
                scene?.strikeVisual(pad, vel)
                val step = flow.hitReturning(pad, vel, graph.audio.playedFrameAt(System.nanoTime()), "debug-stick")
                if (step != null) scene?.debugFly(pad, listOf(step))
            }
            "drag" -> when (p.getOrNull(0)) {
                "stone" -> { val from = (n(1) ?: 1) - 1; val off = p.getOrNull(2) == "off"; scene?.debugDragStone(from, n(2)?.minus(1), off); if (p.getOrNull(3) != "hold") scene?.debugDragEnd() }
                "hub" -> { flow.hubStart(); flow.hubMove((p.getOrNull(1)?.toFloatOrNull() ?: 0f) / 100f); flow.hubEnd() }
                "end" -> scene?.debugDragEnd()
            }
            "stick" -> { scene?.stickTurn(a.toFloatOrNull() ?: 0f); scene?.stickTurn(0f) }
            "key" -> when (a.uppercase()) { "A", "X" -> flow.recButton("debug-key"); "B", "Y" -> flow.playButton("debug-key") }
            "rec" -> flow.recButton("debug")
            "play" -> flow.playButton("debug")
            "stop" -> flow.stop()
            "undo" -> flow.undoButton()
            "hold" -> { val off = p.getOrNull(1) == "off"; scene?.hold(!off); AppLog.i("HOLD ${p.getOrNull(0)} state=${flow.s.stateId}") }
            "unhold" -> scene?.hold(false)
            "groove" -> Grooves.all.indexOfFirst { it.id == a }.takeIf { it >= 0 }?.let { flow.cardTo(it + 1); flow.loadCard() }
            "kit" -> flow.setKit(KitId.of(a))
            "bpm" -> flow.setBpm(a.toIntOrNull() ?: 96)
            "card" -> flow.cardTo(n(0) ?: 0)
            "load" -> flow.loadCard()
            "save" -> flow.saveButton()
            "confirm" -> when (a) { "overwrite" -> flow.confirmOverwrite(); "clear" -> flow.confirmClearSlot(); "load" -> flow.confirmLoad(); else -> flow.confirmCancel() }
            "page" -> flow.page(when (a) { "library" -> Page.LIBRARY; "settings" -> Page.SETTINGS; else -> Page.PLAY })
            "sub" -> flow.sub(when (a) { "sound" -> Sub.SOUND; "kit" -> Sub.KIT; "padsound" -> Sub.PAD_SOUND; "more" -> Sub.MORE; "about" -> Sub.ABOUT; else -> Sub.NONE })
            "lang" -> flow.updateSettings { it.copy(language = when (a) { "zh" -> Language.ZH; "en" -> Language.EN; else -> Language.SYSTEM }) }
            "mute" -> flow.mute(on)
            "volume" -> flow.updateSettings { it.copy(master = (a.toIntOrNull() ?: 1).coerceIn(0, 2), muted = false) }
            "metro" -> flow.updateSettings { it.copy(metroMode = (a.toIntOrNull() ?: 0).coerceIn(0, 2)) }
            "place" -> flow.placeStart()
            "placedone" -> flow.placeDone()
            "turn" -> scene?.turnDeck(a.toFloatOrNull() ?: 15f)
            "front" -> scene?.bringToFront()
            "side" -> { val right = a == "right"; flow.updateSettings { it.copy(panelRight = right) }; scene?.panelSide(right) }
            "pick" -> flow.pickStart()
            "pickpad" -> flow.pickPad((n(0) ?: 2) - 1)
            "pickend" -> flow.pickEnd(true)
            "import" -> { val pad = flow.s.picked ?: flow.s.focus; graph.importFile(pad, File(a)) }
            "importdrop" -> graph.importFromDropDir(flow.s.picked ?: flow.s.focus)
            "importui" -> flow.importButton()
            "notice" -> runCatching { flow.notice(Notice.valueOf(p[0].uppercase()), p.getOrElse(1) { "" }) }
            "exitask" -> flow.exitAsk()
            "closewindow" -> graph.window.close()
            "recall" -> graph.window.recall()
            "wipe" -> graph.wipe()
            "fly" -> scene?.debugFly((n(0) ?: 2) - 1, p.drop(1).mapNotNull { it.toIntOrNull()?.minus(1) })
            "dump" -> {
                val s = flow.s
                AppLog.i("DUMP state=${s.stateId} page=${s.page}/${s.sub} confirm=${s.confirm} bpm=${s.bpm} kit=${s.kit.id} focus=p${s.focus + 1} beat=${s.beatName} undo=${flow.undoDepth} notice=${s.notice} pattern=${s.pattern} slots=${s.slots.count { it != null }} overrides=${s.overrides} settings=${flow.settings.value}")
                AppLog.i("DUMP mixer playing=${graph.audio.mixer.playing} rec=${graph.audio.mixer.recording} rendered=${graph.audio.mixer.rendered} triggers=${graph.audio.mixer.triggerCount} last=${graph.audio.mixer.lastTriggerKey} latency=${"%.1f".format(graph.audio.outLatencyMs)}ms burst=${graph.audio.burst} underruns=${graph.audio.underruns} lowLatency=${graph.audio.fastTrack}")
                scene?.let { AppLog.i("DUMP scene ${it.dumpLine()}") }
            }
            else -> AppLog.w("CMD unknown: $cmd")
        }
        if (cmd == "hold" && flow.s.deck == Deck.LOADING) AppLog.i("HOLD D0")
    }
}
