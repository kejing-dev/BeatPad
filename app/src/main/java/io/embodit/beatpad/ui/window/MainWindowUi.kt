package io.embodit.beatpad.ui.window

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.platform.ViewPoint
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import io.embodit.beatpad.BuildConfig
import io.embodit.beatpad.R
import io.embodit.beatpad.domain.beat.BeatFlow
import io.embodit.beatpad.domain.beat.BeatState
import io.embodit.beatpad.domain.beat.Confirm
import io.embodit.beatpad.domain.beat.Deck
import io.embodit.beatpad.domain.beat.Grooves
import io.embodit.beatpad.domain.beat.Kits
import io.embodit.beatpad.domain.beat.Language
import io.embodit.beatpad.domain.beat.Notice
import io.embodit.beatpad.domain.beat.Page
import io.embodit.beatpad.domain.beat.Settings
import io.embodit.beatpad.domain.beat.Sub
import io.embodit.beatpad.platform.AppLog
import io.embodit.beatpad.platform.Graph
import io.embodit.beatpad.ui.Containers
import io.embodit.beatpad.ui.stage.ActiveScene
import io.embodit.beatpad.ui.theme.BeatColors
import kotlin.system.exitProcess

/**
 * The one system window (windowModel single_window_with_tabbar): three pages switched by the native SpatialUI TabBar (D8)
 * — 演奏 Play / 节拍库 Beats / 设置 Settings — plus the D6 place-mode page and the D7 pick-a-pad page (TabBar hidden).
 * Every page ≤ 7 controls (B31), buttons 64 dp, content centred (D44). Confirmations open inside the page (D26).
 */
@Composable
fun MainWindowUi() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.flow.settings.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> graph.window.onPanelLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(state.page, state.sub, state.deck) { AppLog.i("PAGE ${state.page}/${state.sub} ${state.stateId}") }
    // notices fade after 4 s (window-inner hint bar, P5)
    LaunchedEffect(state.noticeAt) { if (state.notice != Notice.NONE) { kotlinx.coroutines.delay(4000); graph.flow.clearNotice() } }

    Box(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 22.dp)) {
        when {
            state.deck == Deck.PLACING -> PlacePage(settings, graph)
            state.deck == Deck.PICKING && state.picked == null -> PickPage(graph)
            state.page == Page.PLAY -> PlayPage(state, settings, graph)
            state.page == Page.LIBRARY -> LibraryPage(state, settings, graph)
            else -> SettingsPages(state, settings, graph)
        }
    }

    // The TabBar is its own system window: labels resolved here in the app language and passed in (tabbar-lang gate).
    if (!state.tabBarHidden) {
        val tabs = listOf(
            Triple(Page.PLAY, R.drawable.ic_music, stringResource(R.string.tab_play)),
            Triple(Page.LIBRARY, R.drawable.ic_library, stringResource(R.string.tab_library)),
            Triple(Page.SETTINGS, R.drawable.ic_settings, stringResource(R.string.tab_settings)),
        )
        key(settings.language) { MainTabBar(tabs, state.page) { graph.flow.page(it) } }
    }
}

// ------------------------------------------------------------------ shared bits

@Composable
private fun CenteredPage(gap: Dp = 24.dp, content: @Composable ColumnScope.() -> Unit) {
    // D44: content ≥ 75 % of the 540 dp window (blank above + below ≤ 25 %), centred
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally, content = content)
}

@Composable
private fun BackTitle(title: String, tag: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(R.drawable.ic_back, stringResource(R.string.btn_back), "$tag/back", onBack)
        Spacer(Modifier.width(14.dp))
        Txt(title, "$tag/title", style = PicoTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 2)
    }
}

@Composable
private fun IconButton(icon: Int, label: String, tag: String, onClick: () -> Unit, enabled: Boolean = true, withText: Boolean = true, filled: Color? = null, onFilled: Color = PicoTheme.colorScheme.labelPrimaryLight, modifier: Modifier = Modifier, height: Dp = 64.dp) {
    val fg = if (filled != null) onFilled else PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier,
        size = ButtonDefaults.Regular.copy(height = height, minWidth = 64.dp), shape = RoundedCornerShape(height / 2),
        colors = ButtonDefaults.buttonColors(containerColor = filled ?: PicoTheme.colorScheme.fillSecondary, contentColor = fg),
    ) {
        Icon(painter = painterResource(icon), contentDescription = label, tint = fg, modifier = Modifier.size(24.dp))
        if (withText) {
            Spacer(Modifier.width(8.dp))
            // design-style: inherited-content-color Button
            Txt(label, "$tag/button", style = PicoTheme.typography.labelLarge, color = fg)
        }
    }
}

@Composable
private fun NoticeLine(st: BeatState) {
    val text = noticeText(st) ?: return
    val bad = st.notice in setOf(Notice.IMPORT_FAIL, Notice.FULL, Notice.EMPTY_PLAY, Notice.STOP_FIRST, Notice.NO_FILES)
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (bad) Color(0xFFF6E1DA) else Color(0xFFEDE4D3)).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Txt(text, "notice/${st.notice}", style = PicoTheme.typography.bodyMedium, color = if (bad) Color(0xFF6E200C) else BeatColors.Ink, maxLines = 2)
    }
}

@Composable
private fun noticeText(st: BeatState): String? = when (st.notice) {
    Notice.NONE -> null
    Notice.SAVED -> stringResource(R.string.notice_saved, st.noticeArg)
    Notice.FULL -> stringResource(R.string.notice_full)
    Notice.EMPTY_PLAY -> stringResource(R.string.notice_empty_play)
    Notice.STOP_FIRST -> stringResource(R.string.notice_stop_first)
    Notice.LOADED_NEXT_BAR -> stringResource(R.string.notice_loaded_next)
    Notice.IMPORT_OK -> st.noticeArg.split('|').let { stringResource(R.string.import_ok, it[0], it.getOrElse(1) { "" }) }
    Notice.IMPORT_FAIL -> stringResource(R.string.import_fail)
    Notice.IMPORT_LONG -> stringResource(R.string.import_long)
    Notice.RESTORED -> stringResource(R.string.restored)
    Notice.NO_FILES -> stringResource(R.string.no_files)
}

@Composable
internal fun statusLine(st: BeatState): String = stringResource(
    when (st.statusKey) {
        "D0" -> R.string.status_d0; "D1.empty" -> R.string.status_d1_empty; "D1.hasBeat" -> R.string.status_d1_beat; "D2" -> R.string.status_d2
        "D3" -> R.string.status_d3; "D4" -> R.string.status_d4; "D5" -> R.string.status_d5; "D6" -> R.string.status_d6; else -> R.string.status_d7
    },
)

private fun zh(lang: Language, context: Context) = when (lang) { Language.ZH -> true; Language.EN -> false; Language.SYSTEM -> context.resources.configuration.locales[0].language == "zh" }

@Composable
private fun beatTitle(st: BeatState, settings: Settings): String {
    val zh = zh(settings.language, LocalContext.current)
    return when {
        st.beatName.startsWith("my:") -> stringResource(R.string.my_beat, st.beatName.removePrefix("my:").toIntOrNull() ?: 0)
        st.beatName.isNotEmpty() -> Grooves.of(st.beatName)?.let { if (zh) it.zh else it.en } ?: st.beatName
        st.pattern.isEmpty -> stringResource(R.string.beat_blank)
        else -> stringResource(R.string.beat_unsaved)
    }
}

// ------------------------------------------------------------------ P1 Play

@Composable
private fun PlayPage(st: BeatState, settings: Settings, graph: Graph) {
    val flow = graph.flow
    CenteredPage {
        // title row: beat name + BPM (read-only) + [静音] (D15, always on this page)
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt(beatTitle(st, settings), "play/title", style = PicoTheme.typography.titleLarge)
                Txt(stringResource(R.string.bpm_label, st.bpm) + " · " + Kits.kitName(st.kit, zh(settings.language, LocalContext.current)), "play/bpm", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary)
            }
            MuteKey(settings.muted, { flow.mute(!settings.muted) })
        }
        // status (same source as the ring caption, D38) + where the deck is when it is out of sight
        Column(Modifier.fillMaxWidth().height(if (st.notice != Notice.NONE) 120.dp else 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
            val side = ActiveScene.scene?.deckSide?.value ?: 0
            val line = when {
                side > 0 && st.deck == Deck.IDLE -> stringResource(R.string.deck_right_hint)
                side < 0 && st.deck == Deck.IDLE -> stringResource(R.string.deck_left_hint)
                else -> statusLine(st)
            }
            Txt(line, "play/status", style = PicoTheme.typography.titleMedium, maxLines = 2)
            NoticeLine(st)
        }
        // [录]: 120 dp round, recording = red「停录」, count-in = the count + cancel
        val recColor = when (st.deck) { Deck.RECORDING -> BeatColors.Record; Deck.COUNT_IN -> BeatColors.CountIn; else -> PicoTheme.colorScheme.interaction }
        val recLabel = when (st.deck) { Deck.RECORDING -> stringResource(R.string.btn_stop_rec); Deck.COUNT_IN -> "${st.countLeft} · " + stringResource(R.string.btn_cancel_count); else -> stringResource(R.string.btn_rec) }
        Button(
            onClick = { flow.recButton("window") }, enabled = st.deck != Deck.LOADING,
            size = ButtonDefaults.Regular.copy(height = 140.dp, minWidth = 140.dp), shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = recColor, contentColor = Color.White),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(painter = painterResource(if (st.deck == Deck.RECORDING) R.drawable.ic_stop else R.drawable.ic_record), contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                // design-style: inherited-content-color Button
                Txt(recLabel, "play/rec", style = PicoTheme.typography.titleMedium, color = Color.White, align = TextAlign.Center)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val looping = st.deck == Deck.PLAYING || st.deck == Deck.RECORDING || st.deck == Deck.COUNT_IN
            IconButton(if (looping) R.drawable.ic_stop else R.drawable.ic_play, stringResource(if (looping) R.string.btn_stop else R.string.btn_play), "play/play",
                { flow.playButton("window") }, enabled = st.deck != Deck.LOADING && (looping || !st.pattern.isEmpty), modifier = Modifier.weight(1f))
            IconButton(R.drawable.ic_undo, stringResource(R.string.btn_undo), "play/undo", { flow.undoButton() }, enabled = st.canUndo || st.deck == Deck.RECORDING, modifier = Modifier.weight(1f))
        }
    }
}

// ------------------------------------------------------------------ P2 Library

@Composable
private fun LibraryPage(st: BeatState, settings: Settings, graph: Graph) {
    val flow = graph.flow
    val zh = zh(settings.language, LocalContext.current)
    val c = st.card
    val mySlot = if (c > 6) c - 7 else -1
    val saved = if (mySlot >= 0) st.slots[mySlot] else null
    val (name, tag, meta) = when {
        c == 0 -> Triple(stringResource(R.string.beat_blank), stringResource(R.string.beat_blank_tag), null)
        c <= 6 -> Grooves.all[c - 1].let { g -> Triple(if (zh) g.zh else g.en, if (zh) g.tagZh else g.tagEn, stringResource(R.string.card_meta, g.bpm, Kits.kitName(g.kit, zh))) }
        saved != null -> Triple(stringResource(R.string.my_beat, saved.name), stringResource(R.string.card_tap), stringResource(R.string.card_meta, saved.bpm, Kits.kitName(saved.kit, zh)))
        else -> Triple(stringResource(R.string.slot_empty, mySlot + 1), stringResource(R.string.slot_empty_tag), null)
    }
    CenteredPage {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (st.confirm == Confirm.NONE) IconButton(R.drawable.ic_chevron_left, stringResource(R.string.prev), "library/prev", { flow.card(-1) }, withText = false)
            // the card: tap = load (T10)
            Column(
                Modifier.weight(1f).height(320.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFFF3EBDD))
                    .clickable(enabled = st.confirm == Confirm.NONE && (c <= 6 || saved != null)) { flow.loadCard() }.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Txt(name, "library/name", style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold), color = BeatColors.Ink, align = TextAlign.Center, maxLines = 2)
                Txt(tag, "library/tag", style = PicoTheme.typography.bodyLarge, color = BeatColors.InkSecondary, align = TextAlign.Center, maxLines = 2)
                if (meta != null) Txt(meta, "library/meta", style = PicoTheme.typography.labelLarge, color = BeatColors.Accent, align = TextAlign.Center)
                Txt(stringResource(R.string.card_count, c + 1, BeatState.CARDS), "library/count", style = PicoTheme.typography.bodySmall, color = BeatColors.InkSecondary)
            }
            if (st.confirm == Confirm.NONE) IconButton(R.drawable.ic_chevron_right, stringResource(R.string.next), "library/next", { flow.card(1) }, withText = false)
        }
        NoticeLine(st)
        if (st.confirm == Confirm.SLOT) {
            Txt(stringResource(R.string.slot_has_beat), "library/confirm", style = PicoTheme.typography.titleMedium)
            // [存到这里] meant「overwrite」: that is the red primary; clearing is the side option (QA #1)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SecondaryButton(stringResource(R.string.btn_overwrite), "library/overwrite", { flow.confirmOverwrite() }, Modifier.weight(1.1f), danger = true, minWidth = 64.dp)
                SecondaryButton(stringResource(R.string.btn_clear_slot), "library/clear", { flow.confirmClearSlot() }, Modifier.weight(1.1f), minWidth = 64.dp)
                SecondaryButton(stringResource(R.string.btn_cancel), "library/cancel", { flow.confirmCancel() }, Modifier.weight(1f), minWidth = 64.dp)
            }
        } else if (st.confirm == Confirm.LOAD) {
            // loading a card would replace a beat that is not saved: ask first (QA #1)
            Txt(stringResource(R.string.load_ask), "library/load-ask", style = PicoTheme.typography.titleMedium, maxLines = 2, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SecondaryButton(stringResource(R.string.btn_cancel), "library/load-cancel", { flow.confirmCancel() }, Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_replace), "library/load-yes", { flow.confirmLoad() }, Modifier.weight(1f), danger = true)
            }
        } else {
            val full = c <= 6 && flow.freeSlots == 0
            IconButton(R.drawable.ic_save, stringResource(if (c <= 6) R.string.btn_save_new else R.string.btn_save_here), "library/save", { flow.saveButton() },
                enabled = !full && !st.busy, modifier = Modifier.fillMaxWidth())
            if (full) Txt(stringResource(R.string.notice_full), "library/full", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary)
        }
    }
}

// ------------------------------------------------------------------ P3 Settings (+ sub pages)

@Composable
private fun SettingsPages(st: BeatState, settings: Settings, graph: Graph) {
    when (st.sub) {
        Sub.NONE -> SettingsHome(st, graph)
        Sub.SOUND -> SoundPage(settings, graph)
        Sub.KIT -> KitPage(st, settings, graph)
        Sub.PAD_SOUND -> PadSoundPage(st, settings, graph)
        Sub.MORE -> MorePage(st, settings, graph)
        Sub.ABOUT -> AboutPage(graph)
    }
}

@Composable
private fun SettingsHome(st: BeatState, graph: Graph) {
    val flow = graph.flow
    val context = LocalContext.current
    val navigator = LocalSpatialNavigator.current
    CenteredPage(28.dp) {
        Txt(stringResource(R.string.tab_settings), "settings/title", style = PicoTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth())
        if (st.confirm != Confirm.EXIT) {
            SecondaryButton(stringResource(R.string.set_sound), "settings/sound", { flow.sub(Sub.SOUND) }, Modifier.fillMaxWidth(), height = 76.dp)
            SecondaryButton(stringResource(R.string.set_kit), "settings/kit", { flow.sub(Sub.KIT) }, Modifier.fillMaxWidth(), height = 76.dp)
            SecondaryButton(stringResource(R.string.set_more), "settings/more", { flow.sub(Sub.MORE) }, Modifier.fillMaxWidth(), height = 76.dp)
            // D44: [叫回面前] next to [退出应用] on the first screen — closes the window and reopens it ahead of you;
            // the deck turns aside through the same Arrange function as every other open (q1-5)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SecondaryButton(stringResource(R.string.btn_recall), "settings/recall", { graph.window.recall() }, Modifier.weight(1f), height = 76.dp, minWidth = 64.dp)
                SecondaryButton(stringResource(R.string.btn_exit_app), "settings/exit", { flow.exitAsk() }, Modifier.weight(1f), danger = true, height = 76.dp, minWidth = 64.dp)
            }
        } else {
            // D26: the confirmation opens in place, inside this window; the confirm key is an action word, not ✕ (D52).
            // Only the question and its two keys while asking (≤ 7 controls with the TabBar, QA #1)
            Column(Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFFF3EBDD)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(painter = painterResource(R.drawable.ic_exit), contentDescription = null, tint = BeatColors.Accent, modifier = Modifier.size(44.dp))
                Txt(stringResource(R.string.exit_ask), "settings/exit-ask", style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold), color = BeatColors.Ink, align = TextAlign.Center, maxLines = 2)
                Txt(stringResource(R.string.exit_note), "settings/exit-note", style = PicoTheme.typography.bodyLarge, color = BeatColors.InkSecondary, align = TextAlign.Center, maxLines = 2)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SecondaryButton(stringResource(R.string.btn_cancel), "settings/exit-cancel", { flow.confirmCancel() }, Modifier.weight(1f), height = 76.dp)
                SecondaryButton(stringResource(R.string.btn_exit), "settings/exit-yes", { exitApp(context, navigator, graph) }, Modifier.weight(1f), danger = true, height = 76.dp)
            }
        }
    }
}

@Composable
private fun CycleRow(text: String, tag: String, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(42.dp)).background(PicoTheme.colorScheme.fillSecondary)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(text, "$tag/label", style = PicoTheme.typography.labelLarge, modifier = Modifier.weight(1f), color = if (enabled) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelSecondary)
        Txt("›", "$tag/next", style = PicoTheme.typography.titleMedium, color = BeatColors.Accent)
    }
}

@Composable
private fun SoundPage(settings: Settings, graph: Graph) {
    val flow = graph.flow
    CenteredPage {
        BackTitle(stringResource(R.string.set_sound).trimEnd('…'), "sound") { flow.sub(Sub.NONE) }
        Txt(stringResource(R.string.set_master), "sound/master-label", style = PicoTheme.typography.labelLarge, modifier = Modifier.fillMaxWidth())
        VolumeSlider(settings.master, { v -> flow.updateSettings { it.copy(master = v, muted = false) } }, Modifier.fillMaxWidth().height(64.dp), stringResource(R.string.set_master), BeatFlow.MASTER, "sound/master")
        Txt(stringResource(R.string.set_metro), "sound/metro-label", style = PicoTheme.typography.labelLarge, modifier = Modifier.fillMaxWidth())
        VolumeSlider(settings.metro, { v -> flow.updateSettings { it.copy(metro = v) } }, Modifier.fillMaxWidth().height(64.dp), stringResource(R.string.set_metro), intArrayOf(15, 25, 40), "sound/metro")
        val mode = stringResource(when (settings.metroMode) { 0 -> R.string.metro_countin; 1 -> R.string.metro_always; else -> R.string.metro_off })
        CycleRow(stringResource(R.string.metro_mode, mode), "sound/mode", { flow.updateSettings { it.copy(metroMode = (it.metroMode + 1) % 3) } })
    }
}

@Composable
private fun KitPage(st: BeatState, settings: Settings, graph: Graph) {
    val flow = graph.flow
    val zh = zh(settings.language, LocalContext.current)
    CenteredPage(32.dp) {
        BackTitle(stringResource(R.string.set_kit).trimEnd('…'), "kit") { flow.sub(Sub.NONE) }
        CycleRow(stringResource(R.string.kit_row, Kits.kitName(st.kit, zh)), "kit/kit", { flow.kitNext() })
        val canPick = !(st.busy || st.deck == Deck.PLAYING)
        CycleRow(stringResource(R.string.btn_pad_sound), "kit/pad", { flow.pickStart() }, enabled = canPick)
        if (!canPick) Txt(stringResource(R.string.notice_stop_first), "kit/stop-first", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.fillMaxWidth())
        val feel = stringResource(when (settings.feel) { 0 -> R.string.feel_light; 2 -> R.string.feel_heavy; else -> R.string.feel_normal })
        CycleRow(stringResource(R.string.feel_row, feel), "kit/feel", { flow.updateSettings { it.copy(feel = (it.feel + 1) % 3) } })
    }
}

@Composable
private fun PadSoundPage(st: BeatState, settings: Settings, graph: Graph) {
    val flow = graph.flow
    val zh = zh(settings.language, LocalContext.current)
    val pad = st.picked ?: st.focus
    val padName = Kits.padName(st.kit, pad, zh)
    val now = st.overrides[pad]?.let { BeatFlow.displayName(it) } ?: (stringResource(R.string.sound_default) + " · " + padName)
    CenteredPage(28.dp) {
        BackTitle(stringResource(R.string.pad_sound_title, padName), "padsound") { flow.pickEnd(true) }
        Box(Modifier.fillMaxWidth().height(if (st.notice != Notice.NONE) 120.dp else 96.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFFF3EBDD)).padding(horizontal = 18.dp, vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
            // gate:allow button-gap two text lines (current sound name + notice) inside one info card, no buttons in this Column
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(stringResource(R.string.pad_sound_now, now), "padsound/now", style = PicoTheme.typography.titleMedium, color = BeatColors.Ink, maxLines = 2)
                NoticeLine(st)
            }
        }
        IconButton(R.drawable.ic_library, stringResource(R.string.btn_import), "padsound/import", { flow.importButton() }, modifier = Modifier.fillMaxWidth(), filled = PicoTheme.colorScheme.interaction, height = 80.dp)
        IconButton(R.drawable.ic_undo, stringResource(R.string.btn_restore), "padsound/restore", { flow.restoreDefault() }, modifier = Modifier.fillMaxWidth(), enabled = st.overrides.containsKey(pad), height = 80.dp)
    }
}

@Composable
private fun MorePage(st: BeatState, settings: Settings, graph: Graph) {
    val flow = graph.flow
    val langs = listOf(Language.SYSTEM to stringResource(R.string.lang_system), Language.ZH to stringResource(R.string.lang_zh), Language.EN to stringResource(R.string.lang_en))
    CenteredPage(32.dp) {
        BackTitle(stringResource(R.string.set_more).trimEnd('…'), "more") { flow.sub(Sub.NONE) }
        CycleRow(stringResource(R.string.lang_row, langs.first { it.first == settings.language }.second), "more/lang", {
            val i = langs.indexOfFirst { it.first == settings.language }
            val next = langs[(i + 1) % langs.size].first
            flow.updateSettings { it.copy(language = next) }
        })
        CycleRow(stringResource(R.string.btn_place), "more/place", { flow.placeStart() }, enabled = !st.busy)
        if (st.busy) Txt(stringResource(R.string.notice_stop_first), "more/stop-first", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.fillMaxWidth())
        CycleRow(stringResource(R.string.btn_about), "more/about", { flow.sub(Sub.ABOUT) })
    }
}

@Composable
private fun AboutPage(graph: Graph) {
    val flow = graph.flow
    CenteredPage(28.dp) {
        BackTitle(stringResource(R.string.btn_about), "about") { flow.sub(Sub.MORE) }
        Column(Modifier.fillMaxWidth().height(224.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFFF3EBDD)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
            Txt(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), "about/version", style = PicoTheme.typography.titleMedium, color = BeatColors.Ink)
            Txt(stringResource(R.string.about_license), "about/license", style = PicoTheme.typography.bodyMedium, color = BeatColors.InkSecondary, maxLines = 4)
        }
        IconButton(R.drawable.ic_music, stringResource(R.string.btn_coach), "about/coach", { flow.replayCoach() }, modifier = Modifier.fillMaxWidth(), height = 80.dp)
    }
}

// ------------------------------------------------------------------ D6 place mode / D7 pick a pad (TabBar hidden)

@Composable
private fun PlacePage(settings: Settings, graph: Graph) {
    val flow = graph.flow
    val scene = ActiveScene.scene
    CenteredPage {
        // D29: after [回到面前] the page asks the user to face front and hold still (the press direction is not used)
        val waiting = scene?.frontWaiting?.value == true
        Txt(stringResource(if (waiting) R.string.front_wait else R.string.status_d6), "place/hint", style = PicoTheme.typography.titleMedium, maxLines = 2, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconButton(R.drawable.ic_rotate_left, stringResource(R.string.btn_turn_left), "place/left", { scene?.turnDeck(-15f) }, modifier = Modifier.weight(1f), height = 72.dp)
            IconButton(R.drawable.ic_rotate_right, stringResource(R.string.btn_turn_right), "place/right", { scene?.turnDeck(15f) }, modifier = Modifier.weight(1f), height = 72.dp)
        }
        IconButton(R.drawable.ic_center, stringResource(R.string.btn_front), "place/front", { scene?.bringToFront() }, modifier = Modifier.fillMaxWidth(), height = 72.dp)
        IconButton(R.drawable.ic_swap, stringResource(if (settings.panelRight) R.string.btn_panel_left else R.string.btn_panel_right), "place/side", {
            val right = !settings.panelRight
            flow.updateSettings { it.copy(panelRight = right) }
            scene?.panelSide(right)
        }, modifier = Modifier.fillMaxWidth(), height = 72.dp)
        IconButton(R.drawable.ic_check, stringResource(R.string.btn_place_done), "place/done", { flow.placeDone() }, modifier = Modifier.fillMaxWidth(), filled = PicoTheme.colorScheme.interaction, height = 72.dp)
    }
}

@Composable
private fun PickPage(graph: Graph) {
    CenteredPage(40.dp) {
        Column(Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFFF3EBDD)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painter = painterResource(R.drawable.ic_music), contentDescription = null, tint = BeatColors.Accent, modifier = Modifier.size(48.dp))
            Txt(stringResource(R.string.status_d7), "pick/hint", style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold), color = BeatColors.Ink, maxLines = 2, align = TextAlign.Center)
        }
        IconButton(R.drawable.ic_back, stringResource(R.string.btn_cancel), "pick/cancel", { graph.flow.pickEnd(false) }, modifier = Modifier.fillMaxWidth(), height = 84.dp)
    }
}

/**
 * D26 / D44 exit: stop audio and trackers, close the window and the Stage (both containers), then remove every task
 * and end the process.
 */
internal fun exitApp(context: Context, navigator: SpatialNavigator, graph: Graph) {
    AppLog.i("EXIT release + close window + closeStage + finishAndRemoveTask + exitProcess")
    graph.window.exiting = true
    runCatching { graph.flow.stop() }
    runCatching { graph.release() }
    runCatching { io.embodit.beatpad.ui.stage.ActiveScene.limb?.setUpperLimbRenderMode(com.pico.spatial.ui.platform.ability.UpperLimbRenderMode.Default) } // D41: restore what drumming hid
    runCatching { navigator.closeWindowContainer(Containers.MAIN, Containers.MAIN_TAG) }.onFailure { AppLog.w("EXIT close window: $it") }
    runCatching { graph.window.closeStage?.invoke() }.onFailure { AppLog.w("EXIT close stage: $it") }
    val main = Handler(Looper.getMainLooper())
    main.postDelayed({
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
        }
        AppLog.i("EXIT finishAndRemoveTask")
        main.postDelayed({ exitProcess(0) }, 300)
    }, 400)
}

/** The native TabBar with labels already resolved in the app language (no string lookups inside). */
@Composable
private fun MainTabBar(tabs: List<Triple<Page, Int, String>>, current: Page, onPick: (Page) -> Unit) {
    TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
        tabs.forEach { (page, icon, label) ->
            item(
                selected = current == page,
                onClick = { onPick(page) },
                mainContent = { Icon(painter = painterResource(icon), contentDescription = label) },
                supportContent = { Txt(label, "tabbar/${page.name}", style = PicoTheme.typography.labelMedium) },
            )
        }
    }
}
