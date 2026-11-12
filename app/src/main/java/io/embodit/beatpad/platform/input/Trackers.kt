package io.embodit.beatpad.platform.input

import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerActionData
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import com.pico.spatial.tracking.hand.HandJoint
import com.pico.spatial.tracking.hand.HandTrackingProvider
import io.embodit.beatpad.domain.math.Quat4
import io.embodit.beatpad.domain.math.Vec3
import io.embodit.beatpad.platform.AppLog
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** One controller's pose in Stage space. */
data class Pose(val position: Vec3, val rotation: Quat4) {
    val forward: Vec3 get() = rotation.rotate(Vec3.AHEAD)
}

/**
 * Both controllers (input.stickHit: the controllers are the sticks). Poses come from ControllerTrackingProvider.latestData
 * on the main thread; buttons from the action listener (its own thread → atomics, read once per frame):
 * A / X = record (press edge), B / Y = play / stop, thumbstick x for turning the deck in D6 (T14, T17).
 * Same approach as pruning-class ControllerInput / qubic-tower PadInput.
 */
class Controllers {
    private var provider: ControllerTrackingProvider? = null
    private val recPresses = AtomicInteger(0)
    private val playPresses = AtomicInteger(0)
    private val stickBits = AtomicInteger(0)
    private var recDown = false
    private var playDown = false
    private var lastSupport: DataProvider.SupportState? = null
    @Volatile var started = "not started"
        private set

    private val listener = ControllerTrackingProvider.ControllerActionListener { a: ControllerActionData ->
        val rec = runCatching { a.right.aButtonPressed || a.left.xButtonPressed }.getOrDefault(false)
        val play = runCatching { a.right.bButtonPressed || a.left.yButtonPressed }.getOrDefault(false)
        if (rec && !recDown) recPresses.incrementAndGet()
        if (play && !playDown) playPresses.incrementAndGet()
        recDown = rec; playDown = play
        val r = runCatching { a.right.thumbstickValue.x }.getOrDefault(0f)
        val l = runCatching { a.left.thumbstickValue.x }.getOrDefault(0f)
        stickBits.set(java.lang.Float.floatToIntBits(if (abs(r) >= abs(l)) r else l))
    }

    fun start() {
        if (provider != null) return
        runCatching {
            val p = ControllerTrackingProvider()
            p.addControllerActionListener(listener)
            val r = p.start()
            lastSupport = p.supportState
            started = "start=$r support=$lastSupport"
            AppLog.i("TRACK controller $started")
            provider = p
        }.onFailure { started = "failed $it"; AppLog.w("TRACK controller unavailable: $it") }
    }

    fun stop() {
        runCatching { provider?.removeControllerActionListener(listener); provider?.stop() }
        provider = null
    }

    fun takeRecPresses() = recPresses.getAndSet(0)
    fun takePlayPresses() = playPresses.getAndSet(0)
    val stickX: Float get() = java.lang.Float.intBitsToFloat(stickBits.get())

    /** (left, right) poses, null when that controller does not deliver one (emulator: provider has no data). */
    fun poses(): Pair<Pose?, Pose?> {
        val p = provider ?: return null to null
        val support = runCatching { p.supportState }.getOrNull()
        if (support != lastSupport) { lastSupport = support; AppLog.i("TRACK controller support=$support") }
        if (support != DataProvider.SupportState.SUPPORTED) return null to null
        val d = runCatching { p.latestData }.getOrNull() ?: return null to null
        fun conv(c: com.pico.spatial.tracking.controller.ControllerPose?): Pose? {
            c ?: return null
            val pos = Vec3(c.position.x, c.position.y, c.position.z)
            val q = Quat4(c.rotation.x, c.rotation.y, c.rotation.z, c.rotation.w)
            if (!pos.isFinite() || (pos == Vec3.ZERO && q.w == 1f)) return null
            return Pose(pos, q.normalized())
        }
        return conv(d.left) to conv(d.right)
    }
}

/**
 * Hand tracking (input.handHit): index tip and palm of each hand, only when the system has hand tracking on (Full
 * Space). No prompt when it is off — controllers and pointing are the main paths.
 */
class Hands {
    private var provider: HandTrackingProvider? = null
    private var lastSupport: DataProvider.SupportState? = null

    fun start() {
        if (provider != null) return
        runCatching {
            val p = HandTrackingProvider()
            val r = p.start()
            lastSupport = p.supportState
            AppLog.i("TRACK hand start=$r support=$lastSupport")
            provider = p
        }.onFailure { AppLog.w("TRACK hand unavailable: $it") }
    }

    fun stop() { runCatching { provider?.stop() }; provider = null }

    /** Tips to test for strikes: [left index, left palm, right index, right palm] (null when not tracked). */
    fun tips(): List<Vec3?> {
        val p = provider ?: return EMPTY
        val support = runCatching { p.supportState }.getOrNull()
        if (support != lastSupport) { lastSupport = support; AppLog.i("TRACK hand support=$support") }
        if (support != DataProvider.SupportState.SUPPORTED) return EMPTY
        val d = runCatching { p.latestData }.getOrNull() ?: return EMPTY
        fun j(h: com.pico.spatial.tracking.hand.HandPose?, i: HandJoint.Index): Vec3? = runCatching {
            h?.joint(i)?.position?.let { Vec3(it.x, it.y, it.z) }?.takeIf { it.isFinite() && it != Vec3.ZERO }
        }.getOrNull()
        return listOf(j(d.left, HandJoint.Index.INDEX_TIP), j(d.left, HandJoint.Index.PALM), j(d.right, HandJoint.Index.INDEX_TIP), j(d.right, HandJoint.Index.PALM))
    }

    private companion object { val EMPTY = listOf<Vec3?>(null, null, null, null) }
}
