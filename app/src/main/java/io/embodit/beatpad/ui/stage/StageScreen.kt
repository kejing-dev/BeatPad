package io.embodit.beatpad.ui.stage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.HandController
import com.pico.spatial.ui.platform.HandControllerHapticType
import com.pico.spatial.ui.platform.LocalHandControllerHaptic
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.beatpad.R
import io.embodit.beatpad.domain.math.Vec3
import io.embodit.beatpad.platform.AppLog
import io.embodit.beatpad.platform.Graph
import io.embodit.beatpad.ui.Containers
import io.embodit.beatpad.ui.stage.input.BoardInputListener
import io.embodit.beatpad.ui.stage.input.InputSource
import io.embodit.beatpad.ui.stage.input.Offset3M
import io.embodit.beatpad.ui.stage.input.TargetResolver
import io.embodit.beatpad.ui.stage.input.boardInput
import io.embodit.beatpad.ui.theme.BeatColors
import io.embodit.beatpad.ui.theme.localized
import kotlinx.coroutines.launch

/** The Mixed Stage: one full-size SpatialView with the deck and the beat ring; the HUD is the only attachment. */
@Composable
fun StageScreen() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val navigator = LocalSpatialNavigator.current
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.flow.settings.collectAsStateWithLifecycle()
    val scene = remember(graph) { StageScene(graph, context.applicationContext) }
    val stageScope = rememberCoroutineScope()
    val haptic = LocalHandControllerHaptic.current

    DisposableEffect(scene) {
        registerSystem<BeatFrameSystem>()
        graph.window.attach(
            open = {
                if (graph.window.panelState == "ON_STOP") runCatching { navigator.restoreWindowContainer(Containers.MAIN, Containers.MAIN_TAG) }.onFailure { AppLog.w("WINDOW restore: $it") }
                navigator.openWindowContainer(Containers.MAIN, Containers.MAIN_TAG, android.os.Bundle())
            },
            close = { navigator.closeWindowContainer(Containers.MAIN, Containers.MAIN_TAG) },
        )
        graph.window.onClosed = { graph.flow.windowClosed() }
        graph.window.closeStage = { stageScope.launch { runCatching { navigator.closeStage() } } }
        // needsCheck「手柄震动」: SpatialUI's LocalHandControllerHaptic is the public haptics path (no ECS API)
        graph.haptic = { right, strength ->
            val level = (40 + 60 * strength).toInt().coerceIn(1, 100)
            haptic.feedback(HandControllerHapticType.custom(level, 80, 12), if (right) HandController.Right else HandController.Left)
        }
        graph.window.request("start")
        AppLog.i("STAGE enter")
        onDispose {
            unregisterSystem<BeatFrameSystem>()
            graph.window.detach()
            graph.haptic = null
            scene.dispose()
            AppLog.i("STAGE disposed")
        }
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> { AppLog.i("LIFECYCLE background"); graph.flow.let { if (it.s.busy) it.stop() } }
                Lifecycle.Event.ON_START -> AppLog.i("LIFECYCLE foreground")
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(state, settings, scene.ready.value) { scene.render(state, settings) }

    SpatialView(
        modifier = Modifier.fillMaxSize().boardInput(
            TargetResolver.byName(Names::resolve),
            object : BoardInputListener<Hit> {
                override fun onTap(target: Hit?, source: InputSource, position: com.pico.spatial.ui.geometry.Offset3D?) = scene.onTap(target, source)
                override fun onDragStart(target: Hit, source: InputSource): Boolean = scene.onDragStart(target, source)
                override fun onDragMove(totalM: Offset3M) = scene.onDragMove(Vec3(totalM.x, totalM.y, totalM.z))
                override fun onDragEnd() = scene.onDragEnd()
                override fun onDragCancel() = scene.onDragEnd()
                // the system HoverEffect on pads / stones / knob / rec key is the visible feedback (D45: hover never changes state)
                override fun onHover(target: Hit?, source: InputSource) = Unit
            },
        ),
        attachments = { PanelIds.all.forEach { id -> AttachmentPanel(id) { HudRoot(scene, graph) } } },
        initial = { content, attachments -> scene.attach(content, attachments) },
        update = { _, attachments -> scene.bind(attachments) },
    )
}

@Composable
private fun HudRoot(scene: StageScene, graph: Graph) {
    val base = LocalContext.current
    val s by graph.flow.settings.collectAsStateWithLifecycle()
    val shown = scene.panelShown[PanelIds.HUD] == true
    key(s.language) {
        CompositionLocalProvider(LocalContext provides base.localized(s.language)) {
            PicoTheme { if (!shown) Box(Modifier.size(8.dp)) else Hud(graph, scene) }
        }
    }
}

@Composable
private fun HudText(text: String, tag: String) {
    androidx.compose.foundation.text.BasicText(
        text, style = TextStyle(fontSize = 20.sp, color = BeatColors.Ink, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center), maxLines = 2,
        onTextLayout = { if (it.hasVisualOverflow) AppLog.w("OVERFLOW hud/$tag \"$text\"") },
    )
}

/** Loading, the level gate「平视前方」, or [打开面板] after the window was closed with ×. */
@Composable
private fun Hud(graph: Graph, scene: StageScene) {
    val window = graph.window
    if (window.exiting) return
    Box(Modifier.size(360.dp, 110.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xF2FBF8F2)), contentAlignment = Alignment.Center) {
        when {
            !scene.ready.value -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                Box(Modifier.width(12.dp))
                HudText(stringResource(R.string.status_d0), "loading")
            }
            window.levelHint -> HudText(stringResource(R.string.level_hint), "level")
            else -> Button(
                onClick = { window.request("hud") }, size = ButtonDefaults.Regular.copy(height = 64.dp, minWidth = 240.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = PicoTheme.colorScheme.labelPrimaryLight),
            ) {
                // design-style: inherited-content-color Button
                androidx.compose.foundation.text.BasicText(stringResource(R.string.open_panel), style = TextStyle(fontSize = 18.sp, color = PicoTheme.colorScheme.labelPrimaryLight, fontWeight = FontWeight.SemiBold))
            }
        }
    }
}
