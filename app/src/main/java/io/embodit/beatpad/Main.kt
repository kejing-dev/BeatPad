package io.embodit.beatpad

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.beatpad.ui.Containers
import io.embodit.beatpad.ui.stage.StageScreen
import io.embodit.beatpad.ui.theme.BeatTheme
import io.embodit.beatpad.ui.window.MainWindowUi

/**
 * One Mixed Stage (manifest pico.spatial.stage.style = 1: passthrough, Full Space) holds the deck, the beat ring and the
 * sticks. All 2D UI lives in one Planar WindowContainer 440 × 540 dp with the native TabBar (windowModel
 * single_window_with_tabbar), opened from the Stage through the level gate (WindowCtl). Dragging / closing are the
 * system window controls (D21).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage { BeatTheme { StageScreen() } }
        WindowContainer(
            Containers.MAIN,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = 440.dp, height = 540.dp),
        ) {
            BeatTheme { Box(Modifier.windowConstraints(minWidth = 440.dp, minHeight = 540.dp, maxWidth = 600.dp, maxHeight = 640.dp)) { MainWindowUi() } }
        }
    }
