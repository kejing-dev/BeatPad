package io.embodit.beatpad.domain.place

import io.embodit.beatpad.domain.beat.Layout
import io.embodit.beatpad.domain.math.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The one placement function (q1-2 / q1-3, profile 48「窗口摆位只写一个函数」): cold start, every window open (reopen
 * after ×, HUD [打开面板], [叫回面前], debug recall), [回到面前] and [面板换到右边 / 左边] all end here, so the control
 * window and the deck never cover each other.
 *
 * The system opens the window straight ahead of the head and code cannot move it (placementProof R1-10), so the window
 * is the fixed thing and the deck turns around the player's eye: the window must sit at least [SEP_LEFT] (window on the
 * deck's left) or [SEP_RIGHT] (window on the deck's right) away from the deck's forward. Only yaw is used (D9): pitch
 * and roll of the head never reach the deck.
 *
 * Yaw convention as [Layout.yawOf]: radians, + = turned left.
 */
object Arrange {
    const val SEP_LEFT = -Layout.WINDOW_AZ_LEFT   // 61°: window on the deck's left
    const val SEP_RIGHT = Layout.WINDOW_AZ_RIGHT  // 67°: window on the deck's right
    /** Head farther than this (horizontal) from the deck's eye point when the window opens = the player walked away. */
    const val WALKED_M = 0.6f

    private fun rad(d: Float) = Math.toRadians(d.toDouble()).toFloat()
    private fun deg(r: Float) = Math.toDegrees(r.toDouble()).toFloat()

    /** Signed angle a − b in degrees, in (−180, 180]. */
    fun diffDeg(a: Float, b: Float): Float { var d = deg(a - b); while (d > 180f) d -= 360f; while (d <= -180f) d += 360f; return d }

    /**
     * Deck yaw closest to [wantYaw] that keeps the window clear. [windowYaw] null = no window up (free).
     * Want exactly where the window is → the deck goes to the side the setting asks for (window left by default).
     */
    fun deckYaw(wantYaw: Float, windowYaw: Float?, panelRight: Boolean): Float {
        if (windowYaw == null) return wantYaw
        val d = diffDeg(wantYaw, windowYaw) // + = deck turned left of the window → window on the deck's right
        val deckLeftOfWindow = when {
            abs(d) < 0.5f -> panelRight
            else -> d > 0f
        }
        val sep = if (deckLeftOfWindow) SEP_RIGHT else SEP_LEFT
        if (abs(d) >= sep && (d > 0f) == deckLeftOfWindow) return wantYaw
        return windowYaw + rad(if (deckLeftOfWindow) sep else -sep)
    }

    /**
     * Every window open: the window has just opened straight ahead of the head ([headYaw] at [head]). The deck keeps
     * where it is when it is already clear ([recenter] = [叫回面前]: set up again at the head like a cold start); otherwise it turns around the player (vertical axis through the head) just
     * far enough. No deck yet, or the player walked away → the deck is set up at the head like a cold start.
     */
    fun onWindowOpen(deck: Layout.DeckPose?, head: Vec3, headYaw: Float, panelRight: Boolean, recenter: Boolean = false): Layout.DeckPose {
        val windowYaw = headYaw
        // [叫回面前] (settings / debug recall) brings everything back like a cold start: window ahead, deck beside it
        if (recenter || deck == null || horizontal(deck.origin - head) > WALKED_M) return Layout.DeckPose(head, deckYaw(headYaw, windowYaw, panelRight))
        val yaw = deckYaw(deck.yaw, windowYaw, panelRight)
        if (abs(diffDeg(yaw, deck.yaw)) < 0.01f) return deck
        return turnAround(deck, head, yaw - deck.yaw)
    }

    /** [回到面前]: the deck straight ahead of the sampled head (D29), pushed aside just enough if the window is there. */
    fun front(sampleHead: Vec3, sampleYaw: Float, windowYaw: Float?, panelRight: Boolean): Layout.DeckPose =
        Layout.DeckPose(sampleHead, deckYaw(sampleYaw, windowYaw, panelRight))

    /** [面板换到右边 / 左边]: window on the asked side at exactly 61° / 67°. */
    fun panelSide(deck: Layout.DeckPose, head: Vec3, windowYaw: Float, panelRight: Boolean): Layout.DeckPose =
        turnAround(deck, head, Layout.deckYawForWindow(windowYaw, panelRight) - deck.yaw)

    /** Rotates the deck pose by [dYaw] about the vertical line through [pivot]. */
    fun turnAround(deck: Layout.DeckPose, pivot: Vec3, dYaw: Float): Layout.DeckPose {
        val o = deck.origin - pivot
        val c = cos(dYaw); val s = sin(dYaw)
        // yaw + = left (counter-clockwise from above): x' = x cos + z sin, z' = −x sin + z cos
        val r = Vec3(o.x * c + o.z * s, o.y, -o.x * s + o.z * c)
        return Layout.DeckPose(pivot + r, deck.yaw + dYaw)
    }

    private fun horizontal(v: Vec3) = kotlin.math.sqrt(v.x * v.x + v.z * v.z)
}
