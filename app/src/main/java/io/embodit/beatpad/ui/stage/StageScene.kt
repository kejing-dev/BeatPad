package io.embodit.beatpad.ui.stage

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.EnvironmentLightingSettingsComponent
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import io.embodit.beatpad.R
import io.embodit.beatpad.domain.beat.BeatState
import io.embodit.beatpad.domain.beat.Deck
import io.embodit.beatpad.domain.beat.Drag
import io.embodit.beatpad.domain.beat.HitCurve
import io.embodit.beatpad.domain.beat.Kits
import io.embodit.beatpad.domain.beat.Language
import io.embodit.beatpad.domain.beat.Layout
import io.embodit.beatpad.domain.beat.PADS
import io.embodit.beatpad.domain.beat.PadFace
import io.embodit.beatpad.domain.beat.Pattern
import io.embodit.beatpad.domain.beat.RingDrag
import io.embodit.beatpad.domain.beat.STEPS
import io.embodit.beatpad.domain.beat.Settings
import io.embodit.beatpad.domain.beat.StickRig
import io.embodit.beatpad.domain.beat.DrumGate
import io.embodit.beatpad.domain.beat.StrikeDetector
import io.embodit.beatpad.domain.beat.Velocity
import io.embodit.beatpad.domain.math.Quat4
import io.embodit.beatpad.domain.math.Vec3
import io.embodit.beatpad.domain.place.Arrange
import io.embodit.beatpad.domain.place.HeadSampler
import io.embodit.beatpad.platform.AppLog
import io.embodit.beatpad.platform.Graph
import io.embodit.beatpad.platform.HeadPose
import io.embodit.beatpad.platform.HeadTracker
import io.embodit.beatpad.platform.input.Controllers
import io.embodit.beatpad.platform.input.Hands
import io.embodit.beatpad.platform.input.Pose
import io.embodit.beatpad.ui.stage.input.InputSource
import io.embodit.beatpad.ui.theme.localized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/** What a pointer hit resolves to (names only from [Names], replayed in HitReplayTest). */
sealed class Hit {
    data class Pad(val i: Int) : Hit()
    data class Slot(val k: Int) : Hit()
    data object Hub : Hit()
    data object Rec : Hit()
    data object DeckBody : Hit()
}

object Names {
    fun pad(i: Int) = "pad:$i"
    fun slot(k: Int) = "slot:$k"
    const val HUB = "hub"
    const val REC = "rec"
    const val DECK = "deck"

    fun resolve(name: String): Hit? = when {
        name.startsWith("pad:") -> name.removePrefix("pad:").toIntOrNull()?.takeIf { it in 0 until PADS }?.let { Hit.Pad(it) }
        name.startsWith("slot:") -> name.removePrefix("slot:").toIntOrNull()?.takeIf { it in 0 until STEPS }?.let { Hit.Slot(it) }
        name == HUB -> Hit.Hub
        name == REC -> Hit.Rec
        name == DECK || name.startsWith("$DECK:") -> Hit.DeckBody
        else -> null
    }
}

class BeatFrameSystem : System() {
    override fun update(context: SceneUpdateContext) { ActiveScene.scene?.onFrame() }
}

internal object ActiveScene { @Volatile var scene: StageScene? = null; @Volatile var limb: com.pico.spatial.ui.platform.StageUpperLimbRenderModelManager? = null }

/** Ids of the Stage attachment panels (only the HUD: loading / level hint / [打开面板] after ×). */
object PanelIds { const val HUD = "hud"; val all = listOf(HUD) }

/**
 * The Stage side: the deck, the beat ring and the sticks (no app-drawn ray, D41). One root whose local space is Stage metres; the deck root
 * carries the [Layout.DeckPose]. State → entities in [render]; sticks, strikes, the playhead, animations in [onFrame]
 * (one ECS system, main thread). Pointer input arrives through the board-input template ([onTap] / drags).
 */
class StageScene(private val graph: Graph, private val context: Context) {
    private val flow get() = graph.flow
    private val mixer get() = graph.audio.mixer
    val head = HeadTracker()
    private val controllers = Controllers()
    private val hands = Hands()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var res: SceneRes? = null
    private var content: SpatialViewContent? = null
    private var root: Entity? = null
    private var deckRoot: Entity? = null
    var deckPose: Layout.DeckPose? = null
        private set
    val ready = mutableStateOf(false)
    /** Where the deck is from the current gaze: −1 left, 0 in view, +1 right (the window says「鼓台在你右手边」). */
    val deckSide = mutableStateOf(0)
    /** True while the sticks are at the deck (DrumGate): StageScreen hides the system upper-limb / controller rendering. */
    val drumming = mutableStateOf(false)
    private val drumGate = DrumGate()
    val panelShown = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    private var hudHolder: Entity? = null
    private var hudBound = false
    private var startedMs = 0L
    private var lastPose: HeadPose? = null
    /** debug `hold`: animations stop at the current frame. */
    var freeze = false
    private var animNs = 0L
    private var lastFrameNs = 0L
    private val sampler = HeadSampler()
    private var pendingFront = false
    /** [回到面前] pressed, waiting for the user to face front and hold still (D29): the D6 page says so. */
    val frontWaiting = mutableStateOf(false)
    private var arrangedSeq = 0

    // entities
    private val padHolders = ArrayList<Entity>()
    private val padVis = ArrayList<Entity>()
    private val padGlow = ArrayList<Entity>()
    private val padPick = ArrayList<Entity>()
    private val labels = ArrayList<Entity>()
    private val labelMats = ArrayList<UnlitMaterial>()
    private val slotStone = ArrayList<Entity>()
    private val slotEmpty = ArrayList<Entity>()
    private val lanes = arrayOfNulls<Entity>(PADS)
    private var laneShown = arrayOfNulls<String>(PADS)
    private var frame: Entity? = null
    private var playheadPivot: Entity? = null
    private var hubText: Entity? = null
    private var hubTextMat: UnlitMaterial? = null
    private var recCap: Entity? = null
    private var caption: Entity? = null
    private var captionMat: UnlitMaterial? = null
    private var target: Entity? = null
    private var dragStone: Entity? = null
    private var deleteText: Entity? = null
    private var deleteMat: UnlitMaterial? = null
    private var outline: Entity? = null
    private var deckColliders: Entity? = null
    private val sticks = arrayOfNulls<Entity>(2)
    private val fly = ArrayList<Fly>()
    private var ringRoot: Entity? = null

    // animation state
    private val pressNs = LongArray(PADS) { Long.MIN_VALUE / 2 }
    private val glowLevel = FloatArray(PADS)
    private val glowNs = LongArray(PADS) { Long.MIN_VALUE / 2 }
    private val loopGlowNs = LongArray(PADS) { Long.MIN_VALUE / 2 }
    private val stoneLitNs = LongArray(STEPS) { Long.MIN_VALUE / 2 }
    private var lastPhase = 0.0
    private var coachUntilNs = 0L

    // strikes
    private val stickDet = arrayOf(StrikeDetector(), StrikeDetector())
    private val handDet = Array(4) { StrikeDetector(minSpeed = 0.3f, tipRadius = 0.008f) }
    private val lastStickStrikeMs = LongArray(PADS)
    private var stickStepArmed = true

    // drags
    private var dragKind: Hit? = null
    private var dragStartXY = 0f to 0f
    private var ringDrag: RingDrag? = null
    private var deckDragStart: Layout.DeckPose? = null

    private class Fly(val e: Entity) { var from = Vec3.ZERO; var to = Vec3.ZERO; var startNs = 0L; var active = false; var pad = 0 }

    // ------------------------------------------------------------------ lifecycle

    suspend fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        this.content = content
        startedMs = SystemClock.elapsedRealtime()
        val root = Entity().apply { setName("stageRoot") }
        content.addEntity(root)
        // an entity added to a full-size SpatialView is not at the Stage origin: move the root so its local space = Stage
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rot = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply { setPosition(offset); setQuaternion(rot) }
        this.root = root
        AppLog.i("STAGE root offset=(${offset.x}, ${offset.y}, ${offset.z})")
        ActiveScene.scene = this
        head.start(); controllers.start(); hands.start()
        hudHolder = Entity().apply { setName("holder:hud") }.also { root.addChild(it); it.place(position = Vec3(0f, -5f, 0f), scale = Vec3(0.001f, 0.001f, 0.001f)) }
        bind(attachments)
        val t0 = SystemClock.elapsedRealtime()
        val r = SceneRes.create()
        res = r
        build(r, root)
        graph.stageRelease = { release() }
        ready.value = true
        AppLog.i("SCENE ready resources=${SystemClock.elapsedRealtime() - t0} ms total=${SystemClock.elapsedRealtime() - startedMs} ms")
        render(flow.s, flow.settings.value)
    }

    fun bind(attachments: SpatialViewAttachments) {
        if (hudBound) return
        val h = hudHolder ?: return
        val panel = attachments.entity(PanelIds.HUD) ?: return
        h.addChild(panel); hudBound = true
    }

    fun release() {
        runCatching { head.stop() }; runCatching { controllers.stop() }; runCatching { hands.stop() }
    }

    fun dispose() {
        release()
        scope.cancel()
        if (ActiveScene.scene === this) ActiveScene.scene = null
        runCatching { hudHolder?.removeAllChildren() }
        runCatching { root?.destroy(true) }
        res?.close(); res = null
    }

    // ------------------------------------------------------------------ build

    private fun model(r: SceneRes, mesh: com.pico.spatial.core.ecs.resource.MeshResource, mat: com.pico.spatial.core.ecs.resource.Material, name: String, parent: Entity, at: Vec3 = Vec3.ZERO, rot: Quat4? = null, scale: Vec3? = null): Entity =
        ModelEntity(mesh, mat).apply { setName(name); parent.addChild(this); place(at, rot, scale) }

    private fun holder(name: String, parent: Entity, at: Vec3, rot: Quat4? = null): Entity =
        Entity().apply { setName(name); parent.addChild(this); place(at, rot) }

    /** Collider + interactable + system hover on a holder; its visible children get the highlight (board-input 通用模式). */
    private fun pickable(e: Entity, size: Vec3, r: SceneRes, offset: Vec3 = Vec3.ZERO, hover: Boolean = true) {
        val shape = ShapeResource.createBox(size.sdk()).let { if (offset == Vec3.ZERO) it else it.offsetByTranslation(offset.sdk()) }
        e.components.set(CollisionComponent(collisionShape = listOf(shape), physicsMaterial = r.physics))
        e.components.set(InteractableComponent())
        if (hover) e.components.set(HoverEffectComponent())
    }

    private fun build(r: SceneRes, root: Entity) {
        val deck = Entity().apply { setName("deckRoot") }
        root.addChild(deck)
        deck.place(position = Vec3(0f, -20f, 0f)) // hidden until the head pose is known
        deckRoot = deck
        runCatching { root.components.set(EnvironmentLightingSettingsComponent(1.0f)) }.onFailure { AppLog.w("ENV LIGHT unavailable: $it") }
        // B24: one moderate warm key light from above front-right, soft
        runCatching {
            val light = Entity().apply { setName("keyLight") }
            light.components.set(DirectionalLightComponent(Color4(1f, 0.95f, 0.88f, 1f), 0.9f, true))
            light.place(rotation = Quat4.axisAngle(Vec3.UP, Math.toRadians(-25.0).toFloat()) * Quat4.axisAngle(Vec3.RIGHT, Math.toRadians(-55.0).toFloat()))
            deck.addChild(light)
        }.onFailure { AppLog.w("LIGHT unavailable: $it") }

        // body, trim, legs, outline (D6)
        model(r, r.body, r.walnut, "body", deck)
        model(r, r.trim, r.alu, "trim", deck)
        for (sx in listOf(-1f, 1f)) {
            val a = Math.toRadians(sx * 30.0)
            val rr = (Layout.BODY_STEP_R + 0.02f)
            model(r, r.leg, r.alu, "leg", deck, Vec3((kotlin.math.sin(a) * rr).toFloat(), Layout.BODY_FRONT_Y - Layout.BODY_THICK, (-kotlin.math.cos(a) * rr).toFloat()))
        }
        outline = model(r, r.outline, r.warm, "outline", deck).also { it.show(false) }
        // invisible deck-body colliders, live only in D6 (so rays reach the pads otherwise); gate-free: ShapeResource only
        deckColliders = holder("deckColliders", deck, Vec3.ZERO).also { dc ->
            for (k in -2..2) {
                val az = k * 15f
                val a = Math.toRadians(az.toDouble())
                val rr = (Layout.BODY_INNER_R + Layout.BODY_OUTER_R) / 2f
                val h = holder("${Names.DECK}:$k", dc, Vec3((sin(a) * rr).toFloat(), (Layout.BODY_FRONT_Y + Layout.BODY_BACK_Y) / 2f, (-kotlin.math.cos(a) * rr).toFloat()), Quat4.axisAngle(Vec3.UP, (-a).toFloat()))
                pickable(h, Vec3(0.2f, 0.12f, Layout.BODY_OUTER_R - Layout.BODY_INNER_R), r, hover = false)
            }
            dc.show(false)
        }

        // pads
        for (i in 0 until PADS) {
            val h = holder(Names.pad(i), deck, Layout.padCenter(i), Layout.padRotation(i))
            pickable(h, Vec3(0.14f, 0.06f, 0.14f), r, offset = Vec3(0f, -0.015f, 0f))
            val vis = holder("padVis:$i", h, Vec3.ZERO)
            model(r, r.padBase, r.alu, "padBase:$i", vis)
            model(r, r.padRim, r.rims[i], "padRim:$i", vis)
            model(r, r.padFace, r.rubber, "padFace:$i", vis)
            val glow = model(r, r.padGlow, r.glows[i], "padGlow:$i", vis, Vec3(0f, 0.0016f, 0f))
            val pick = model(r, r.padPick, r.pick, "padPick:$i", vis, Vec3(0f, 0.0022f, 0f)).also { it.show(false) }
            padHolders += h; padVis += vis; padGlow += glow; padPick += pick
            // name plate on the riser (front-row names on the body's front edge, back-row on the step riser)
            val m = SceneRes.unlit(Pal.WALNUT).also { r.keep(it) }
            labelMats += m
            labels += model(r, r.label, m, "label:$i", deck, Layout.labelCenter(i), Quat4.axisAngle(Vec3.UP, Math.toRadians(Layout.labelYawDeg(i).toDouble()).toFloat()))
        }

        // beat ring
        val ring = holder("ringRoot", deck, Layout.RING_CENTER, Layout.ringRotation)
        ringRoot = ring
        model(r, r.backing, r.backingMat, "ringBacking", ring, Vec3(0f, 0f, -0.004f))
        frame = model(r, r.frame, r.brass, "ringFrame", ring)
        for (k in 0 until STEPS) {
            val (x, y) = Layout.slotXY(k)
            val h = holder(Names.slot(k), ring, Vec3(x, y, 0f))
            pickable(h, Vec3(Layout.STONE_HIT_D, Layout.STONE_HIT_D, 0.03f), r)
            slotStone += model(r, r.stone, r.stones[1], "stone:$k", h, Vec3(0f, 0f, 0.006f), Quat4.axisAngle(Vec3.RIGHT, (PI / 2).toFloat()), Vec3(0.044f, 0.044f, 0.044f)).also { it.show(false) }
            slotEmpty += model(r, r.emptySlot, r.emptyMat, "empty:$k", h, Vec3(0f, 0f, 0.0015f))
        }
        val hub = holder(Names.HUB, ring, Vec3(0f, 0f, 0f))
        pickable(hub, Vec3(0.08f, 0.08f, 0.04f), r, offset = Vec3(0f, 0f, 0.012f))
        model(r, r.hubKnob, r.alu, "hubKnob", hub, rot = Quat4.axisAngle(Vec3.RIGHT, (PI / 2).toFloat()))
        hubTextMat = SceneRes.unlit(Pal.ALU).also { r.keep(it) }
        hubText = model(r, r.bpmPlate, hubTextMat!!, "hubText", hub, Vec3(0f, 0f, 0.0262f))
        playheadPivot = holder("playheadPivot", ring, Vec3(0f, 0f, 0.003f)).also { p ->
            model(r, r.rod, r.playhead, "playhead", p, Vec3(0f, 0.042f, 0f), scale = Vec3(0.0016f, Layout.FRAME_R - 0.045f, 0.0016f))
        }
        val recY = Layout.ringYForWorld(Layout.REC_Y_WORLD)
        val rec = holder(Names.REC, ring, Vec3(0f, recY, 0f))
        pickable(rec, Vec3(0.064f, 0.064f, 0.04f), r, offset = Vec3(0f, 0f, 0.01f))
        model(r, r.recBase, r.brass, "recBase", rec, rot = Quat4.axisAngle(Vec3.RIGHT, (PI / 2).toFloat()))
        recCap = model(r, r.recCap, r.recMat, "recCap", rec, rot = Quat4.axisAngle(Vec3.RIGHT, (PI / 2).toFloat()))
        captionMat = SceneRes.unlit(Pal.BACKING).also { r.keep(it) }
        caption = model(r, r.caption, captionMat!!, "caption", ring, Vec3(0f, Layout.ringYForWorld(Layout.CAPTION_Y_WORLD), 0f))
        target = model(r, r.target, r.warm, "target", ring, Vec3(0f, 0f, 0.004f)).also { it.show(false) }
        dragStone = model(r, r.stone, r.stones[1], "dragStone", ring, rot = Quat4.axisAngle(Vec3.RIGHT, (PI / 2).toFloat()), scale = Vec3(0.053f, 0.053f, 0.053f)).also { it.show(false) }
        deleteMat = SceneRes.unlit(Pal.BACKING).also { r.keep(it) }
        deleteText = model(r, r.small, deleteMat!!, "deleteText", ring, Vec3(0f, Layout.FRAME_R + 0.13f, 0.004f)).also { it.show(false) }

        // flying stones (pool of 8)
        repeat(8) { i -> fly += Fly(model(r, r.stone, r.stones[0], "fly:$i", deck, scale = Vec3(0.04f, 0.04f, 0.04f)).also { it.show(false) }) }

        // sticks only (Stage space). No ray of our own (profile D41 更正 2026-11-13): in Full Space the system already draws
        // the controller ray; a second, self-drawn one was the v0.1.1「两个手柄射线」. StickRig is the single list of what
        // rides on a controller, checked by RayPolicyTest.
        for (h in 0..1) {
            sticks[h] = model(r, r.stick, r.maple, StickRig.stickName(h), root).also { it.show(false) }
        }
        AppLog.i("SCENE built")
    }

    // ------------------------------------------------------------------ placement

    /** D9: the deck at the eye, turned so the control window sits at az −61° (left) or +67° (right) — R1-10. */
    private fun placeDeck(origin: Vec3, yaw: Float, why: String) {
        val p = Layout.DeckPose(origin, yaw)
        deckPose = p
        deckRoot?.place(position = origin, rotation = p.rotation)
        AppLog.i("PLACE deck why=$why origin=${fmt(origin)} yawDeg=${"%.1f".format(Math.toDegrees(yaw.toDouble()))} window=${graph.window.estimateYaw?.let { "%.1f".format(Math.toDegrees(it.toDouble())) }}")
    }

    private fun placeDeck(p: Layout.DeckPose, why: String) = placeDeck(p.origin, p.yaw, why)

    fun turnDeck(deg: Float) {
        val p = deckPose ?: return
        val n = p.turned(-deg) // + deg = clockwise seen from above = to the right
        placeDeck(n.origin, n.yaw, "turn $deg")
    }

    /** The window's yaw when it is up (null when closed: nothing to keep clear of). */
    private fun windowYawIfOpen(): Float? = if (graph.window.open) graph.window.estimateYaw else null

    /** [回到面前]: wait for the user to turn to front and hold still (D29), then [Arrange.front]. */
    fun bringToFront() {
        pendingFront = true
        frontWaiting.value = true
        sampler.start(SystemClock.elapsedRealtime(), lastPose?.forward)
        AppLog.i("PLACE front requested pressYaw=${lastPose?.let { "%.1f".format(HeadSampler.yawDeg(it.forward)) }}")
    }

    /** [面板换到右边 / 左边]: the deck turns around the eye so the window is on the other side. */
    fun panelSide(right: Boolean) {
        val p = deckPose ?: return
        val wy = graph.window.estimateYaw ?: (p.yaw + Math.toRadians(if (right) -67.0 else 61.0).toFloat())
        placeDeck(Arrange.panelSide(p, lastPose?.position ?: p.origin, wy, right), "panel ${if (right) "right" else "left"}")
    }

    /** Every window open (first, reopen after ×, HUD [打开面板], [叫回面前], recall): one function, [Arrange.onWindowOpen]. */
    private fun arrangeForWindow(pose: HeadPose?) {
        val w = graph.window
        if (w.openSeq == arrangedSeq) return
        val wy = w.estimateYaw ?: return
        val head = w.estimateHead ?: pose?.position ?: return
        arrangedSeq = w.openSeq
        val before = deckPose
        val after = Arrange.onWindowOpen(before, head, wy, flow.settings.value.panelRight, recenter = w.openWhy.startsWith("recall"))
        if (after != before) placeDeck(after, if (before == null) "start/window" else "window-open#${w.openSeq}")
        else AppLog.i("PLACE deck kept window-open#${w.openSeq} (already clear)")
    }

    // ------------------------------------------------------------------ frame

    fun onFrame() {
        val nowNs = java.lang.System.nanoTime()
        val dtNs = if (lastFrameNs == 0L) 0L else nowNs - lastFrameNs
        lastFrameNs = nowNs
        if (!freeze) animNs += dtNs
        val now = SystemClock.elapsedRealtime()
        val pose = head.pose()
        if (pose != null) lastPose = pose
        graph.window.stepFrame(pose?.let { it.position to it.forward }, pose?.pitchDeg)
        if (res == null) return
        // first placement: after the head is steady and the window has opened (or 4 s without a window)
        if (pose != null && head.steady) arrangeForWindow(pose)
        if (deckPose == null && pose != null && head.steady && graph.window.estimateYaw == null && now - startedMs > 4000) {
            placeDeck(Arrange.front(pose.position, Layout.yawOf(pose.forward), null, flow.settings.value.panelRight), "start/nowindow")
        }
        if (pendingFront && pose != null) {
            sampler.step(now, pose.position, pose.forward)?.let { s ->
                pendingFront = false
                frontWaiting.value = false
                placeDeck(Arrange.front(s.position, Layout.yawOf(s.forward), windowYawIfOpen(), flow.settings.value.panelRight), "front sampleYaw=${"%.1f".format(s.yawDeg)}")
            }
        }
        logPlacement(now)
        val played = graph.audio.playedFrameAt(nowNs)
        flow.tick(played)
        val st = flow.s
        controllerFrame(st, nowNs)
        animate(st, played)
        if (!st.hasHitOnce && !flow.settings.value.coachDone && st.deck == Deck.IDLE && coachUntilNs == 0L) coachUntilNs = animNs + 1_000_000_000L
        hudFrame(st)
        updateDeckSide(pose)
    }

    private fun updateDeckSide(pose: HeadPose?) {
        val d = deckPose ?: return
        val p = pose ?: return
        var diff = Math.toDegrees((d.yaw - Layout.yawOf(p.forward)).toDouble()).toFloat()
        while (diff > 180f) diff -= 360f
        while (diff < -180f) diff += 360f
        // yaw + = left; the deck is "out of view" past 40° (back in view under 30°)
        val cur = deckSide.value
        val next = when { diff < -40f -> 1; diff > 40f -> -1; abs(diff) < 30f -> 0; else -> cur }
        if (next != cur) deckSide.value = next
    }

    private val logged = BooleanArray(3)
    private fun logPlacement(now: Long) {
        val t = now - startedMs
        for ((i, s) in listOf(1000L, 3000L, 5000L).withIndex()) if (!logged[i] && t >= s) {
            logged[i] = true
            val h = lastPose
            val d = deckPose
            val w = graph.window.estimate
            val ringW = d?.toWorld(Layout.RING_CENTER)
            val az = if (h != null && d != null && ringW != null) Layout.azOf(Layout.DeckPose(h.position, Layout.yawOf(h.forward)), h.position, ringW) else null
            AppLog.i("DIAG t=${s / 1000}s head=${h?.let { fmt(it.position) }} deck=${d?.let { fmt(it.origin) + " yaw=" + "%.0f".format(Math.toDegrees(it.yaw.toDouble())) }} ring=${ringW?.let { fmt(it) }} ringAzFromGaze=${az?.let { "%.0f".format(it) }} window=${w?.let { fmt(it) }} panel=${graph.window.panelState}")
        }
    }

    private fun controllerFrame(st: BeatState, nowNs: Long) {
        val (left, right) = controllers.poses()
        val d = deckPose
        val faces = d?.let { pose -> (0 until PADS).map { PadFace(pose.toWorld(Layout.padCenter(it)), pose.dirToWorld(Layout.padNormal(it)), Layout.PAD_FACE_D / 2f) } }
        var nearest: Float? = null
        for ((h, c) in listOf(left, right).withIndex()) {
            val stick = sticks[h]
            if (c == null) { stick?.show(false); stickDet[h].reset(); continue }
            val (base, dir) = stickOf(c)
            stick?.place(position = base, rotation = Quat4.fromTo(Vec3.UP, dir)); stick?.show(true)
            val tip = base + dir * (Layout.STICK_LEN - 0.012f)
            faces?.forEach { f -> val dd = (tip - f.center).length; if (nearest == null || dd < nearest!!) nearest = dd }
            if (faces != null && st.deck != Deck.LOADING) {
                for (e in stickDet[h].feed(tip, nowNs, faces)) onStrike(e.pad, e.speed, e.timeNs, "stick-${if (h == 0) "left" else "right"}", h == 1)
            }
        }
        val drum = drumGate.update(st.deck, nearest, SystemClock.elapsedRealtime())
        if (drum != drumming.value) { drumming.value = drum; AppLog.i("DRUM ${if (drum) "enter" else "leave"} deck=${st.deck.id} tip=${nearest?.let { "%.2f".format(it) }}") }
        // hands (index tip / palm), only while tracked
        if (faces != null && st.deck != Deck.LOADING) {
            hands.tips().forEachIndexed { i, tip -> if (tip != null) for (e in handDet[i].feed(tip, nowNs, faces)) onStrike(e.pad, e.speed, e.timeNs, "hand", i >= 2) else handDet[i].reset() }
        }
        // A / X = record, B / Y = play / stop (ignored while placing / picking)
        val recs = controllers.takeRecPresses(); val plays = controllers.takePlayPresses()
        if (st.deck != Deck.PLACING && st.deck != Deck.PICKING) {
            repeat(recs) { flow.recButton("button-AX") }
            repeat(plays) { flow.playButton("button-BY") }
        }
        // D6: thumbstick left / right turns the deck 15° per push (back to centre before the next)
        if (st.deck == Deck.PLACING) stickTurn(controllers.stickX)
    }

    fun stickTurn(x: Float) {
        if (abs(x) < 0.2f) { stickStepArmed = true; return }
        if (abs(x) > 0.6f && stickStepArmed) { stickStepArmed = false; turnDeck(if (x > 0) 15f else -15f); AppLog.i("STICK x=${"%.2f".format(x)} turn") }
    }

    /** Stick mount: from the grip front, along the controller, tilted down 15° (spatialLayout.sticks.mount). */
    private fun stickOf(c: Pose): Pair<Vec3, Vec3> {
        val t = Math.toRadians(Layout.STICK_DOWN_DEG.toDouble())
        val dir = c.rotation.rotate(Vec3(0f, -sin(t).toFloat(), -kotlin.math.cos(t).toFloat())).normalized()
        return (c.position + c.forward * 0.03f) to dir
    }

    /** A stick or hand strike: sound now; when recording it lands on the ring via the musical frame of [timeNs]. */
    private fun onStrike(pad: Int, speed: Float, timeNs: Long, via: String, right: Boolean) {
        val vel = HitCurve.gain(speed, flow.settings.value.feel)
        val musical = graph.audio.playedFrameAt(timeNs)
        lastStickStrikeMs[pad] = SystemClock.elapsedRealtime()
        val detectMs = (java.lang.System.nanoTime() - timeNs) / 1e6
        AppLog.i("STRIKE p${pad + 1} speed=${"%.2f".format(speed)} vel=${"%.2f".format(vel)} via=$via LAT hit→sound=${"%.1f".format(detectMs + graph.audio.outLatencyMs)}ms (detect ${"%.1f".format(detectMs)} + out ${"%.1f".format(graph.audio.outLatencyMs)})")
        strikeVisual(pad, vel)
        val step = flow.hitReturning(pad, vel, musical, via)
        if (step != null) launchFly(pad, step)
        runCatching { graph.haptic?.invoke(right, vel) }
    }

    fun strikeVisual(pad: Int, vel: Float) {
        pressNs[pad] = animNs; glowNs[pad] = animNs; glowLevel[pad] = vel
    }

    private fun launchFly(pad: Int, step: Int): Fly? {
        val f = fly.firstOrNull { !it.active } ?: return null
        val d = deckPose ?: return null
        f.from = Layout.padCenter(pad); f.to = Layout.slotPoint(step); f.startNs = animNs + fly.count { it.active } * 25_000_000L; f.active = true; f.pad = pad
        res?.let { f.e.setMaterial(it.stones[pad]) }
        if (d.origin.y < -100f) return null
        return f
    }

    // ------------------------------------------------------------------ animation

    private fun animate(st: BeatState, played: Double) {
        val r = res ?: return
        val t = animNs
        // pad press: down 6 mm in 30 ms, spring back (damped) over ~60–90 ms
        for (i in 0 until PADS) {
            val dt = (t - pressNs[i]) / 1e9f
            val y = when {
                dt < 0f -> 0f
                dt < 0.03f -> -0.006f * (dt / 0.03f)
                dt < 0.15f -> { val u = dt - 0.03f; -0.006f * exp(-u * 30f) * kotlin.math.cos(u * 60f) }
                else -> 0f
            }
            padVis[i].place(position = Vec3(0f, y, 0f))
        }
        // loop: steps the playhead crossed since the last frame light their pads and the focus stones (D4 鼓台在自己打)
        val phase = if (mixer.playing) mixer.phaseAt(played) else 0.0
        if (mixer.playing && !freeze) {
            val a = (lastPhase * STEPS); val b = (phase * STEPS).let { if (phase < lastPhase) it + STEPS else it }
            var k = kotlin.math.ceil(a).toInt()
            while (k <= kotlin.math.floor(b).toInt() && b - a < 4) {
                val s = ((k % STEPS) + STEPS) % STEPS
                val pat = st.pattern
                for (p in 0 until PADS) if (pat[p, s] > 0) { loopGlowNs[p] = t; if (p == st.focus) stoneLitNs[s] = t }
                k++
            }
        }
        lastPhase = phase
        playheadPivot?.place(rotation = Quat4.axisAngle(Vec3(0f, 0f, 1f), (-phase * 2 * PI).toFloat()))
        playheadPivot?.show(st.deck == Deck.PLAYING || st.deck == Deck.RECORDING || (st.deck == Deck.COUNT_IN))
        // glow: strike ring 250 ms; loop micro-glow 120 ms; D7 breathing; coach pulse on the snare
        for (i in 0 until PADS) {
            val ds = (t - glowNs[i]) / 1e9f
            var o = if (ds in 0f..0.25f) glowLevel[i] * (1f - ds / 0.25f) else 0f
            var sc = if (ds in 0f..0.25f) 0.35f + 0.65f * (1f - (1f - ds / 0.25f).let { it * it }) else 1f
            val dl = (t - loopGlowNs[i]) / 1e9f
            if (dl in 0f..0.12f) o = maxOf(o, 0.35f * (1f - dl / 0.12f))
            if (st.deck == Deck.PICKING && st.picked == null) o = maxOf(o, 0.18f + 0.14f * sin(t / 1e9f * 3f).toFloat())
            if (i == 1 && coachUntilNs > 0 && t < coachUntilNs) { val c = (coachUntilNs - t) / 1e9f; o = maxOf(o, 0.6f * sin(c * PI.toFloat() * 2f).let { it * it }) }
            if (st.deck == Deck.LOADING) o = 0f
            r.glows[i].setOpacity(o.coerceIn(0f, 1f))
            padGlow[i].scaleAll(sc)
            padGlow[i].show(o > 0.01f)
        }
        // stones: lit 120 ms when the playhead passes
        for (s in 0 until STEPS) {
            val lit = (t - stoneLitNs[s]) / 1e9f in 0f..0.12f
            val lvl = st.pattern[st.focus, s]
            if (lvl > 0) slotStone[s].setMaterial(if (lit) r.stonesLit[st.focus] else r.stones[st.focus])
        }
        // flying stones: 200 ms arc, tiny bounce on landing
        for (f in fly) {
            if (!f.active) continue
            val u = (t - f.startNs) / 2e8f
            if (u < 0f) { f.e.show(false); continue }
            if (u >= 1.4f) { f.active = false; f.e.show(false); continue }
            val k = u.coerceAtMost(1f)
            val p = f.from.lerp(f.to, k) + Vec3(0f, 0.18f * 4f * k * (1f - k), 0f)
            val bounce = if (u > 1f) 1f + 0.1f * sin((u - 1f) / 0.4f * PI.toFloat()) else 1f
            f.e.place(position = p, scale = Vec3(0.04f * bounce, 0.04f * bounce, 0.04f * bounce)); f.e.show(u <= 1.0f)
        }
        // frame colour by state
        frame?.setMaterial(when (st.deck) { Deck.RECORDING -> r.frameRec; Deck.COUNT_IN -> r.frameCount; Deck.PLAYING -> r.framePlay; else -> r.brass })
        recCap?.setMaterial(if (st.deck == Deck.RECORDING) (if ((t / 500_000_000L) % 2L == 0L) r.recLit else r.recMat) else r.recMat)
    }

    // ------------------------------------------------------------------ state → entities

    private var lastRenderKey = ""

    fun render(st: BeatState, s: Settings) {
        val r = res ?: return
        val zh = isZh(s.language)
        // stones of the focus pad
        val pad = st.focus
        for (k in 0 until STEPS) {
            val lvl = st.pattern[pad, k]
            val dragging = (st.drag as? Drag.Stone)?.let { it.from == k } == true
            slotStone[k].show(lvl > 0 && !dragging)
            slotEmpty[k].show(lvl == 0 || dragging)
            if (lvl > 0) { val dm = Velocity.stoneDiameter(lvl); slotStone[k].place(scale = Vec3(dm, dm, dm)); slotStone[k].setMaterial(r.stones[pad]) }
        }
        // D5 drag: dragged stone, target slot (dashed), off-ring = grey + "release to delete"
        val d = st.drag as? Drag.Stone
        if (d != null) {
            val (x, y) = dragXY ?: Layout.slotXY(d.slot)
            dragStone?.place(position = Vec3(x, y, 0.012f), scale = Vec3(0.053f, 0.053f, 0.053f))
            dragStone?.setMaterial(if (d.off) r.stoneGrey else r.stones[d.pad])
            dragStone?.show(true)
            val (tx, ty) = Layout.slotXY(d.slot)
            target?.place(position = Vec3(tx, ty, 0.004f)); target?.show(!d.off)
            deleteText?.place(position = Vec3(x, y + 0.045f, 0.012f))
            deleteText?.show(d.off)
            if (d.off) setText(deleteMat, str(R.string.ring_release_delete, s.language), 768, 128, Pal.BACKING, hex(0xF08A80), "delete")
        } else { dragStone?.show(false); target?.show(false); deleteText?.show(false) }
        // D6 outline + deck colliders; D7 pick rings
        outline?.show(st.deck == Deck.PLACING)
        deckColliders?.show(st.deck == Deck.PLACING)
        for (i in 0 until PADS) padPick[i].show(st.deck == Deck.PICKING && st.picked == i)
        // labels (kit + language), hub (BPM or count-in), caption
        val key = "${st.kit}|$zh|${st.overrides.keys}"
        if (key != lastRenderKey) {
            lastRenderKey = key
            for (i in 0 until PADS) {
                val name = if (st.overrides.containsKey(i)) "★ " + Kits.padName(st.kit, i, zh) else Kits.padName(st.kit, i, zh)
                setText(labelMats[i], name, 704, 128, hex(0x3B281C), hex(0xF1E3C8), "label/p${i + 1}")
            }
        }
        val hubStr = if (st.deck == Deck.COUNT_IN) "${st.countLeft}" else "${st.bpm}"
        setText(hubTextMat, hubStr, 320, 192, Pal.ALU, hex(0x1E1F21), "hub", sizeFrac = 0.8f)
        setText(captionMat, captionText(st, s.language), 1280, 128, Pal.BACKING, Pal.PLAYHEAD, "caption", sizeFrac = 0.62f)
        // lanes (inner rings): every pad's hits as small dots, baked per pad and rebuilt only when that pad changes
        for (p in 0 until PADS) {
            val sig = (0 until STEPS).joinToString("") { st.pattern[p, it].toString() }
            if (laneShown[p] != sig) { laneShown[p] = sig; rebuildLane(p, st.pattern) }
        }
    }

    private var dragXY: Pair<Float, Float>? = null

    fun captionText(st: BeatState, lang: Language): String {
        val zh = isZh(lang)
        val short = when (st.statusKey) {
            "D0" -> R.string.ring_d0; "D1.empty" -> R.string.ring_d1_empty; "D1.hasBeat" -> R.string.ring_d1_beat; "D2" -> R.string.ring_d2
            "D3" -> R.string.ring_d3; "D4" -> R.string.ring_d4; "D5" -> R.string.ring_d5; "D6" -> R.string.ring_d6; else -> R.string.ring_d7
        }
        val line = str(short, lang)
        return if (st.statusKey in setOf("D0", "D6", "D7")) line else "${Kits.padName(st.kit, st.focus, zh)} · $line"
    }

    private fun str(id: Int, lang: Language) = context.localized(lang).getString(id)
    private fun isZh(lang: Language) = when (lang) { Language.ZH -> true; Language.EN -> false; Language.SYSTEM -> context.resources.configuration.locales[0].language == "zh" }

    private val textCache = HashMap<String, TextureResource>()
    private val textWanted = HashMap<UnlitMaterial, String>()

    /** Draws [text] into a texture (off the main thread, cached) and puts it on [mat]. */
    private fun setText(mat: UnlitMaterial?, text: String, w: Int, h: Int, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color, tag: String, sizeFrac: Float = 0.72f) {
        mat ?: return
        val key = "$text|$w|$h|${bg.value}|${fg.value}|$sizeFrac"
        if (textWanted[mat] == key) return
        textWanted[mat] = key
        textCache[key]?.let { mat.setBaseColor(Color4(1f, 1f, 1f, 1f)); mat.setBaseColorTexture(it); return }
        scope.launch {
            val tex = withContext(Dispatchers.IO) {
                runCatching {
                    val (bmp, fits) = Textures.textPlate(text, w, h, android.graphics.Color.argb(255, (bg.red * 255).toInt(), (bg.green * 255).toInt(), (bg.blue * 255).toInt()),
                        android.graphics.Color.argb(255, (fg.red * 255).toInt(), (fg.green * 255).toInt(), (fg.blue * 255).toInt()), sizeFrac = sizeFrac)
                    if (!fits) AppLog.w("OVERFLOW 3d/$tag \"$text\"")
                    TextureResource.create(bmp).also { res?.keep(it) }
                }.getOrNull()
            } ?: return@launch
            textCache[key] = tex
            if (textWanted[mat] == key) { mat.setBaseColor(Color4(1f, 1f, 1f, 1f)); mat.setBaseColorTexture(tex) }
        }
    }

    private fun rebuildLane(pad: Int, p: Pattern) {
        val ring = ringRoot ?: return
        val r = res ?: return
        val steps = p.steps(pad)
        scope.launch {
            val mesh = if (steps.isEmpty()) null else withContext(Dispatchers.IO) { runCatching { r.keep(laneMesh(pad, steps)) }.getOrNull() }
            lanes[pad]?.let { old -> runCatching { old.destroy(true) } }
            lanes[pad] = mesh?.let { model(r, it, r.rims[pad], "lane:$pad", ring, Vec3(0f, 0f, 0.0015f)) }
        }
    }

    /** One pad's inner lane: a 0.6 cm dot per hit at the step angle (laneMap; read-only, no collider). */
    private fun laneMesh(pad: Int, steps: List<Int>): com.pico.spatial.core.ecs.resource.MeshResource {
        val rr = Layout.laneR(pad)
        val pos = ArrayList<Vector3>(); val nor = ArrayList<Vector3>(); val idx = ArrayList<Int>()
        val n = Vector3(0f, 0f, 1f)
        for (s in steps) {
            val (cx, cy) = Layout.slotXY(s, rr)
            val c0 = pos.size
            pos += Vector3(cx, cy, 0f); nor += n
            for (j in 0 until 16) { val a = 2 * PI * j / 16; pos += Vector3(cx + 0.003f * kotlin.math.cos(a).toFloat(), cy + 0.003f * kotlin.math.sin(a).toFloat(), 0f); nor += n }
            for (j in 0 until 16) { idx += c0; idx += c0 + 1 + j; idx += c0 + 1 + (j + 1) % 16 }
        }
        return com.pico.spatial.core.ecs.resource.MeshResource.createWithMeshModel(
            com.pico.spatial.core.ecs.resource.MeshModel(positions = pos, triangleIndices = idx, normals = nor),
            com.pico.spatial.core.ecs.BoundingBox(Vector3(0f, 0f, 0f), Vector3(0.14f, 0.14f, 0.001f)), "lane$pad")
    }

    // ------------------------------------------------------------------ HUD (Stage attachment: loading / level / reopen)

    private fun hudFrame(st: BeatState) {
        val h = hudHolder ?: return
        val w = graph.window
        val want = !w.exiting && (!ready.value || w.levelHint || (!w.open && w.everOpened))
        if (panelShown[PanelIds.HUD] != want) panelShown[PanelIds.HUD] = want
        val d = deckPose
        val at = when {
            d != null -> d.toWorld(Vec3(-0.42f, -0.22f, -0.62f))
            else -> lastPose?.let { it.position + Vec3(it.forward.x, 0f, it.forward.z).normalized() * 1.0f + Vec3(0f, -0.15f, 0f) }
        }
        if (want && at != null) {
            val eye = lastPose?.position ?: Vec3(0f, 1.6f, 0f)
            val toEye = Vec3(eye.x - at.x, 0f, eye.z - at.z).normalized()
            h.place(position = at, rotation = Quat4.fromTo(Vec3(0f, 0f, 1f), toEye), scale = Vec3(1f, 1f, 1f))
        } else h.place(position = Vec3(0f, -20f, 0f), scale = Vec3(0.001f, 0.001f, 0.001f))
    }

    // ------------------------------------------------------------------ pointer input (board-input listener)

    fun onTap(hit: Hit?, source: InputSource) {
        val st = flow.s
        AppLog.i("TAP src=${source.label} hit=$hit state=${st.stateId}")
        when (hit) {
            is Hit.Pad -> {
                // a stick strike on this pad a moment ago with the same controller = finger on the trigger while swinging
                if (source == InputSource.CONTROLLER && SystemClock.elapsedRealtime() - lastStickStrikeMs[hit.i] < 120) { AppLog.i("TAP ignored: stick strike <120 ms"); return }
                if (st.deck == Deck.PLACING) return
                if (st.deck != Deck.PICKING) strikeVisual(hit.i, Velocity.TAP)
                val musical = graph.audio.playedFrameAt(java.lang.System.nanoTime())
                val step = flow.hitReturning(hit.i, Velocity.TAP, musical, "tap")
                if (step != null) launchFly(hit.i, step)
            }
            is Hit.Slot -> flow.tapSlot(hit.k)
            Hit.Rec -> flow.recButton("rec3d")
            Hit.Hub, Hit.DeckBody, null -> Unit
        }
    }

    fun onDragStart(hit: Hit, source: InputSource): Boolean {
        val st = flow.s
        if (st.deck == Deck.PLACING && (hit is Hit.DeckBody || hit is Hit.Pad)) {
            deckDragStart = deckPose; dragKind = Hit.DeckBody; AppLog.i("DRAG deck start src=${source.label}"); return deckPose != null
        }
        return when (hit) {
            is Hit.Slot -> if (flow.dragStoneStart(hit.k)) { dragKind = hit; dragStartXY = Layout.slotXY(hit.k); ringDrag = RingDrag(hit.k); true } else false
            Hit.Hub -> { flow.hubStart(); dragKind = hit; true }
            else -> false
        }
    }

    /** [total] = pointer travel since the press, Stage metres. */
    fun onDragMove(total: Vec3) {
        val d = deckPose ?: return
        when (dragKind) {
            is Hit.Slot -> {
                val right = d.dirToWorld(Layout.ringRotation.rotate(Vec3.RIGHT)); val up = d.dirToWorld(Layout.ringRotation.rotate(Vec3.UP))
                val x = dragStartXY.first + total.dot(right); val y = dragStartXY.second + total.dot(up)
                val rd = ringDrag ?: return
                val slot = rd.move(x, y)
                dragXY = x to y
                flow.dragStoneMove(slot, rd.off)
                render(flow.s, flow.settings.value)
            }
            Hit.Hub -> flow.hubMove(total.y)
            Hit.DeckBody -> deckDragStart?.let { s -> val o = s.origin + total; deckPose = s.copy(origin = o); deckRoot?.place(position = o) }
            else -> Unit
        }
    }

    fun onDragEnd() {
        when (dragKind) {
            is Hit.Slot -> { flow.dragStoneEnd(); dragXY = null; ringDrag = null }
            Hit.Hub -> flow.hubEnd()
            Hit.DeckBody -> AppLog.i("PLACE deck dragged to ${deckPose?.let { fmt(it.origin) }}")
            else -> Unit
        }
        dragKind = null
    }

    // ------------------------------------------------------------------ debug hooks (same paths as real input)

    fun debugDragStone(from: Int, to: Int?, off: Boolean) {
        if (!onDragStart(Hit.Slot(from), InputSource.UNKNOWN)) { AppLog.i("CMD drag: no stone at $from"); return }
        val d = deckPose ?: return
        val right = d.dirToWorld(Layout.ringRotation.rotate(Vec3.RIGHT)); val up = d.dirToWorld(Layout.ringRotation.rotate(Vec3.UP))
        val (x0, y0) = Layout.slotXY(from)
        val (x1, y1) = if (off) Layout.slotXY(from, RingDrag.RING_OUTER_M + 0.15f) else Layout.slotXY(to ?: from)
        for (k in 1..8) { val u = k / 8f; onDragMove(right * ((x1 - x0) * u) + up * ((y1 - y0) * u)) }
    }

    fun debugDragEnd() = onDragEnd()

    /** debug `hold`: stop animation time so a < 3 s state can be photographed (D36). */
    fun hold(on: Boolean) { freeze = on; mixer.holdCountIn = on; AppLog.i("HOLD $on") }

    /** debug `fly`: launch stones; while held (freeze) they are put mid-flight so a still shows them in the air (D3 qaCapture). */
    fun debugFly(pad: Int, steps: List<Int>) {
        steps.forEachIndexed { k, s ->
            val f = launchFly(pad, s)
            if (freeze && f != null) f.let { f.startNs = animNs - 60_000_000L - k * 35_000_000L }
        }
    }

    fun dumpLine(): String {
        val d = deckPose; val h = lastPose
        return "deck=${d?.let { fmt(it.origin) + " yaw=" + "%.1f".format(Math.toDegrees(it.yaw.toDouble())) }} head=${h?.let { fmt(it.position) + " yaw=" + "%.1f".format(Math.toDegrees(Layout.yawOf(it.forward).toDouble())) + " pitch=" + "%.1f".format(it.pitchDeg) }} window=${graph.window.panelState} est=${graph.window.estimate?.let { fmt(it) }} ctrl=${controllers.started} drumming=${drumming.value} rays=${rayEntities()} sticks=${sticks.count { it != null }}"
    }

    /** Every entity under our root whose name marks a ray / ray dot (must be 0: RayPolicy). */
    fun rayEntities(): Int {
        var n = 0
        fun walk(e: Entity) { if (StickRig.isRayName(e.getName())) n++; e.getChildren().forEach { walk(it) } }
        root?.let { walk(it) }
        return n
    }

    companion object {
        fun fmt(v: Vec3?) = v?.let { "(%.3f, %.3f, %.3f)".format(it.x, it.y, it.z) } ?: "null"
    }
}
