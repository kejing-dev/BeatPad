package io.embodit.beatpad.domain.beat

/**
 * What the app attaches to a controller, per deck state (profile D41 更正 2026-11-13, 节奏鼓台 v0.1.1 双射线).
 *
 * Full Space draws its own controller ray, so the app never draws one: in drum states (the controller is a stick) the
 * app shows the stick only; in window / pick / place states the single ray is the system's. SDK 6.1.9 / 6.2.8 have no
 * API named for the ray; the only candidate is `UpperLimbRenderMode` (Stage upper-limb / controller rendering). The app
 * sets it to Hidden while [DrumGate] says the sticks are at the deck and back to Default otherwise — whether that also
 * hides the system ray is 待核实 on the headset (feedback/pico-cli-feedback.md, ui-audit.md).
 */
object StickRig {
    fun stickName(hand: Int) = "stick:$hand"

    /** Entity names the app puts on a tracked controller in [deck]. Never a ray or a ray dot. */
    fun attached(deck: Deck): List<String> = if (deck == Deck.LOADING) emptyList() else listOf(stickName(0), stickName(1))

    /** Rays the app itself draws per controller in [deck]: always 0 (the system ray is the only one). */
    fun appRays(@Suppress("UNUSED_PARAMETER") deck: Deck): Int = 0

    /** Drum states: the controller is a stick and strikes pads. */
    fun isDrumMode(deck: Deck) = deck == Deck.IDLE || deck == Deck.COUNT_IN || deck == Deck.RECORDING || deck == Deck.PLAYING

    fun isRayName(name: String?): Boolean = name != null && Regex("(?i)(^|[^a-z])(ray|raydot|beam|laser)").containsMatchIn(name)
}

/**
 * 「进入敲鼓」= a tracked stick tip near the pads in a drum state. Enter at ≤ [enterM] from the nearest pad face centre,
 * leave only after every tip is > [exitM] for [exitHoldMs] (pointing up at the window / library), or immediately when
 * the deck leaves the drum states (placing, picking, loading) or no controller is tracked.
 */
class DrumGate(private val enterM: Float = 0.30f, private val exitM: Float = 0.45f, private val exitHoldMs: Long = 400) {
    var drumming = false; private set
    private var farSinceMs = -1L

    /** [nearestTipM] = distance of the closest tracked stick tip to a pad centre, null when no controller is tracked. */
    fun update(deck: Deck, nearestTipM: Float?, nowMs: Long): Boolean {
        if (!StickRig.isDrumMode(deck) || nearestTipM == null) { drumming = false; farSinceMs = -1; return drumming }
        if (!drumming) { if (nearestTipM <= enterM) { drumming = true; farSinceMs = -1 }; return drumming }
        if (nearestTipM > exitM) {
            if (farSinceMs < 0) farSinceMs = nowMs
            if (nowMs - farSinceMs >= exitHoldMs) { drumming = false; farSinceMs = -1 }
        } else farSinceMs = -1
        return drumming
    }
}
