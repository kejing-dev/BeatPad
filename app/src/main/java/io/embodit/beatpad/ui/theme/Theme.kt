package io.embodit.beatpad.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme
import io.embodit.beatpad.domain.beat.Language
import io.embodit.beatpad.platform.Graph
import java.util.Locale

/** design palette: walnut / brass deck, record red, count-in amber; UI on PicoTheme glass with ink text. */
object BeatColors {
    val Primary = Color(0xFF5A3E2B)
    val PrimaryDeep = Color(0xFF4A3223)
    val OnPrimary = Color(0xFFFFFFFF)
    val Record = Color(0xFFB8322E)
    val OnRecord = Color(0xFFFFFFFF)
    val CountIn = Color(0xFF8A5A12)
    val Error = Color(0xFFA33A1E)
    val OnError = Color(0xFFFFFFFF)
    val Ink = Color(0xFF1B1F24)
    val InkSecondary = Color(0xFF3A3F45)
    val Accent = Color(0xFF6E4A16)
    val CardFill = Color(0x33FFFFFF)
    val IconBg = Color(0xFF3B281C)
}

/**
 * PicoTheme with the 16 roles (Cloud Air pattern). The app language localises resources below this point through a
 * ContextWrapper that only overrides getResources (a bare configuration context breaks SpatialView).
 */
@Composable
fun BeatTheme(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val graph = remember(base) { Graph.from(base) }
    val settings by graph.flow.settings.collectAsStateWithLifecycle()
    val localized = remember(base, settings.language) { base.localized(settings.language) }
    val system = systemColorScheme(localized)
    val scheme = system.copy(
        labelPrimaryLight = BeatColors.OnPrimary,
        labelPrimary = BeatColors.Ink,
        labelSecondary = BeatColors.InkSecondary,
        error = BeatColors.Error,
        interaction = BeatColors.PrimaryDeep,
    )
    CompositionLocalProvider(LocalContext provides localized) {
        PicoTheme(colorScheme = scheme, typography = PicoTheme.typography.withNotoSans(), content = content)
    }
}

fun Context.localized(language: Language): Context {
    val locale = when (language) {
        Language.SYSTEM -> return this
        Language.ZH -> Locale.SIMPLIFIED_CHINESE
        Language.EN -> Locale.ENGLISH
    }
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    val res = createConfigurationContext(config).resources
    return object : ContextWrapper(this) {
        override fun getResources(): Resources = res
    }
}
