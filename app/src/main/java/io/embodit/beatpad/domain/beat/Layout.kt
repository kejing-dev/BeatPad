package io.embodit.beatpad.domain.beat

import io.embodit.beatpad.domain.math.Quat4
import io.embodit.beatpad.domain.math.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * spatialLayout in deck-local metres: origin = the eye at placement, +X right, +Y up, −Z ahead; azimuth az 0° = ahead,
 * negative = left. Everything (pads, labels, ring, rec key) hangs off one deck root, so moving / turning the deck moves
 * all of it. Pure Kotlin so reach, gaps and occlusion are unit-tested.
 */
object Layout {
    const val FRONT_R = 0.55f
    const val BACK_R = 0.74f
    const val FRONT_TOP_Y = -0.55f
    const val BACK_TOP_Y = -0.45f
    const val FRONT_TILT_DEG = 12f
    const val BACK_TILT_DEG = 15f
    val AZ = floatArrayOf(-27f, -9f, 9f, 27f)
    const val PAD_FACE_D = 0.12f
    const val PAD_OUTER_D = 0.128f
    const val PAD_HEIGHT = 0.035f

    // deck body: inner 0.47, step riser 0.645, outer 0.83, az ±37°, levels −0.575 / −0.475, 5 cm thick
    const val BODY_INNER_R = 0.47f
    const val BODY_STEP_R = 0.645f
    const val BODY_OUTER_R = 0.83f
    const val BODY_AZ = 37f
    const val BODY_FRONT_Y = -0.575f
    const val BODY_BACK_Y = -0.475f
    const val BODY_THICK = 0.05f

    // beat ring
    val RING_CENTER = Vec3(0f, -0.10f, -1.00f)
    const val RING_TILT_DEG = -10f        // top edge leans back
    const val STEP_R = 0.17f
    const val FRAME_R = 0.215f
    const val FRAME_TUBE = 0.005f
    const val STONE_HIT_D = 0.044f
    const val HUB_R = 0.04f
    const val HUB_DEPTH = 0.025f
    const val REC_R = 0.03f
    const val REC_Y_WORLD = -0.37f
    const val CAPTION_Y_WORLD = -0.44f
    const val CAPTION_W = 0.32f
    const val CAPTION_H = 0.02f
    fun laneR(pad: Int) = 0.055f + 0.01f * pad

    // sticks
    const val STICK_LEN = 0.32f
    const val TIP_R = 0.015f
    const val STICK_DOWN_DEG = 15f

    // control window (az −61°, 0.9 m, 440 × 540 dp) — the system decides where it really is (see WindowSide)
    const val WINDOW_AZ_LEFT = -61f
    const val WINDOW_AZ_RIGHT = 67f

    fun isFront(pad: Int) = pad < 4
    fun padAzDeg(pad: Int) = AZ[pad % 4]
    fun padRadius(pad: Int) = if (isFront(pad)) FRONT_R else BACK_R
    private fun dir(azDeg: Float): Vec3 { val a = Math.toRadians(azDeg.toDouble()); return Vec3(sin(a).toFloat(), 0f, -cos(a).toFloat()) }

    /** Centre of the pad's top face (deck-local). */
    fun padCenter(pad: Int): Vec3 {
        val d = dir(padAzDeg(pad)) * padRadius(pad)
        return Vec3(d.x, if (isFront(pad)) FRONT_TOP_Y else BACK_TOP_Y, d.z)
    }

    /** Face normal: up, leaned toward the player by 12° (front) / 15° (back). */
    fun padNormal(pad: Int): Vec3 {
        val t = Math.toRadians((if (isFront(pad)) FRONT_TILT_DEG else BACK_TILT_DEG).toDouble())
        val inward = -dir(padAzDeg(pad))
        return (Vec3.UP * cos(t).toFloat() + inward * sin(t).toFloat()).normalized()
    }

    /** Rotation that takes local +Y to the pad normal (the pad mesh is built along +Y). */
    fun padRotation(pad: Int): Quat4 = Quat4.fromTo(Vec3.UP, padNormal(pad))

    /** Ring plane rotation: local +Z toward the player, top leaned back 10°. */
    val ringRotation: Quat4 get() = Quat4.axisAngle(Vec3.RIGHT, Math.toRadians(RING_TILT_DEG.toDouble()).toFloat())

    /** A point given in the ring plane (x right, y up, metres from the ring centre) → deck-local. */
    fun ringPoint(x: Float, y: Float, z: Float = 0f): Vec3 = RING_CENTER + ringRotation.rotate(Vec3(x, y, z))

    fun slotXY(slot: Int, r: Float = STEP_R): Pair<Float, Float> {
        val a = RingDrag.slotAngle(slot)
        return (r * sin(a)).toFloat() to (r * cos(a)).toFloat()
    }

    fun slotPoint(slot: Int): Vec3 = slotXY(slot).let { (x, y) -> ringPoint(x, y) }

    /** Ring-plane y of something placed at a world height below the ring centre (rec key, caption). */
    fun ringYForWorld(yWorld: Float): Float = (yWorld - RING_CENTER.y) / cos(Math.toRadians(RING_TILT_DEG.toDouble())).toFloat()

    val recCenter: Vec3 get() = ringPoint(0f, ringYForWorld(REC_Y_WORLD))
    val captionCenter: Vec3 get() = ringPoint(0f, ringYForWorld(CAPTION_Y_WORLD))

    /** Label centres: front-row names on the body's front edge riser, back-row names on the step riser. */
    fun labelCenter(pad: Int): Vec3 {
        val d = dir(padAzDeg(pad))
        return if (isFront(pad)) Vec3(d.x * (BODY_INNER_R - 0.002f), BODY_FRONT_Y - BODY_THICK / 2f, d.z * (BODY_INNER_R - 0.002f))
        else Vec3(d.x * (BODY_STEP_R - 0.002f), -0.495f, d.z * (BODY_STEP_R - 0.002f))
    }

    /** Label faces the player: yaw so its +Z points back toward the eye axis. */
    fun labelYawDeg(pad: Int): Float = -padAzDeg(pad)

    // ------------------------------------------------------------------ shoulder reach (R1-01)

    val SHOULDERS = listOf(Vec3(-0.18f, -0.25f, 0.08f), Vec3(0.18f, -0.25f, 0.08f))
    const val REACH_M = 0.87f
    fun reach(pad: Int): Float = SHOULDERS.minOf { it.distanceTo(padCenter(pad)) }

    /** Nearest outer-edge gap between two neighbouring pads (D17: ≥ 1.5 cm and ≥ 0.3 × 12.8 cm). */
    fun neighbourGap(a: Int, b: Int): Float = padCenter(a).distanceTo(padCenter(b)) - PAD_OUTER_D

    // ------------------------------------------------------------------ world placement

    /** Where the deck sits in Stage space: the eye at placement + a yaw (radians, + = turned left). */
    data class DeckPose(val origin: Vec3, val yaw: Float) {
        val rotation: Quat4 get() = Quat4.axisAngle(Vec3.UP, yaw)
        fun toWorld(local: Vec3): Vec3 = origin + rotation.rotate(local)
        fun dirToWorld(local: Vec3): Vec3 = rotation.rotate(local)
        fun toLocal(world: Vec3): Vec3 = rotation.conjugate().rotate(world - origin)
        fun turned(deg: Float) = copy(yaw = yaw + Math.toRadians(deg.toDouble()).toFloat())
    }

    /** Yaw (radians, + left) of a horizontal forward vector. */
    fun yawOf(forward: Vec3): Float = atan2(-forward.x, -forward.z)

    /**
     * placementProof (R1-10): the system opens the control window ahead of the head and code cannot move it, so the deck
     * is turned away from the window: window at az −61° of the deck (deck yaw = window yaw − 61°), or +67° when the
     * panel is on the right.
     */
    fun deckYawForWindow(windowYaw: Float, panelRight: Boolean): Float =
        windowYaw + Math.toRadians((if (panelRight) -WINDOW_AZ_RIGHT else -WINDOW_AZ_LEFT).toDouble()).toFloat() * -1f

    /** Horizontal angle (deg) of a world point seen from [eye], relative to the deck's forward (+ = right, like az). */
    fun azOf(pose: DeckPose, eye: Vec3, world: Vec3): Float {
        val l = pose.rotation.conjugate().rotate(world - eye)
        return Math.toDegrees(atan2(l.x.toDouble(), -l.z.toDouble())).toFloat()
    }
}
