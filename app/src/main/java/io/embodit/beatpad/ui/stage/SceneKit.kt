package io.embodit.beatpad.ui.stage

import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import io.embodit.beatpad.domain.beat.Layout
import io.embodit.beatpad.domain.beat.PADS
import io.embodit.beatpad.domain.math.Quat4
import io.embodit.beatpad.domain.math.Vec3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal fun Vec3.sdk() = Vector3(x, y, z)
internal fun Vector3.domain() = Vec3(x, y, z)
internal fun Quat4.sdk() = Quat(x, y, z, w)
internal fun Color.c4(alpha: Float = this.alpha) = Color4(red, green, blue, alpha)
internal fun hex(rgb: Long) = Color(0xFF000000 or rgb)

internal fun Entity.place(position: Vec3? = null, rotation: Quat4? = null, scale: Vec3? = null) {
    val t = components[TransformComponent::class.java] ?: return
    if (position != null) t.setPosition(position.sdk())
    if (rotation != null) t.setQuaternion(rotation.sdk())
    if (scale != null) t.setScaleVector(scale.sdk())
}

internal fun Entity.scaleAll(k: Float) = place(scale = Vec3(k, k, k))
internal fun Entity.setMaterial(m: Material) { components[ModelComponent::class.java]?.materials?.set(0, m) }
internal fun Entity.show(on: Boolean) { if (enabled != on) enabled = on }

/** palette.scene / palette.pads / palette.states */
internal object Pal {
    val WALNUT = hex(0x5A3E2B); val ALU = hex(0xB9BCC0); val RUBBER = hex(0x1E1F21); val BRASS = hex(0xB08D57)
    val BACKING = hex(0x2A2B2E); val PLAYHEAD = hex(0xF3E6C8); val REC = hex(0xD9534F); val COUNT = hex(0xE0A458)
    /** The count-in frame: the palette amber read cream on the thin tube under the key light + glow (QA #1), so the tube itself is a deeper amber. */
    val COUNT_TUBE = hex(0xD0701A)
    val MAPLE = hex(0xE2C79B); val GOLD = hex(0xE6B94F)
    val PADS = listOf(0xC9A15B, 0xD9785B, 0xB65A4A, 0x7E8C6A, 0xD8C27A, 0x8FA7A0, 0xC48C9A, 0x9AA3B5).map { hex(it.toLong()) }
    /** Ceramic stones: the pad colour, a little less saturated (palette ceramicStone 按鼓垫色（低饱和）). */
    fun stone(i: Int): Color { val c = PADS[i]; val g = (c.red + c.green + c.blue) / 3f; return Color(c.red * 0.8f + g * 0.2f, c.green * 0.8f + g * 0.2f, c.blue * 0.8f + g * 0.2f) }
}

/**
 * Shared meshes / materials / textures of the Stage, made concurrently off the main thread (resource creation waits on
 * the renderer) and kept global until [close]. Every visible part is PBR (walnut, brushed aluminium, matte rubber, brass,
 * ceramic, maple — assets._rule); unlit only for light effects (glow, ray, playhead, outlines) and text plates.
 */
internal class SceneRes private constructor() {
    private val all = ArrayList<Resource>()
    fun <T : Resource> keep(r: T): T { r.toGlobal(); synchronized(all) { all += r }; return r }
    fun close() { synchronized(all) { all.forEach { runCatching { it.close() } }; all.clear() } }

    lateinit var physics: PhysicsMaterialResource
    lateinit var body: MeshResource; lateinit var trim: MeshResource; lateinit var leg: MeshResource; lateinit var outline: MeshResource
    lateinit var padBase: MeshResource; lateinit var padRim: MeshResource; lateinit var padFace: MeshResource
    lateinit var padGlow: MeshResource; lateinit var padPick: MeshResource
    lateinit var backing: MeshResource; lateinit var frame: MeshResource; lateinit var stone: MeshResource; lateinit var emptySlot: MeshResource
    lateinit var target: MeshResource; lateinit var hubKnob: MeshResource; lateinit var hubFace: MeshResource
    lateinit var recBase: MeshResource; lateinit var recCap: MeshResource; lateinit var rod: MeshResource; lateinit var stick: MeshResource
    lateinit var dot: MeshResource; lateinit var label: MeshResource; lateinit var caption: MeshResource; lateinit var small: MeshResource
    lateinit var bpmPlate: MeshResource

    lateinit var walnut: PhysicallyBasedMaterial; lateinit var alu: PhysicallyBasedMaterial; lateinit var rubber: PhysicallyBasedMaterial
    lateinit var brass: PhysicallyBasedMaterial; lateinit var backingMat: PhysicallyBasedMaterial; lateinit var maple: PhysicallyBasedMaterial
    lateinit var frameRec: PhysicallyBasedMaterial; lateinit var frameCount: PhysicallyBasedMaterial; lateinit var framePlay: PhysicallyBasedMaterial
    lateinit var recMat: PhysicallyBasedMaterial; lateinit var recLit: PhysicallyBasedMaterial; lateinit var emptyMat: PhysicallyBasedMaterial
    lateinit var rims: List<PhysicallyBasedMaterial>; lateinit var stones: List<PhysicallyBasedMaterial>; lateinit var stonesLit: List<PhysicallyBasedMaterial>
    lateinit var stoneGrey: PhysicallyBasedMaterial
    lateinit var glows: List<UnlitMaterial>; lateinit var pick: UnlitMaterial; lateinit var warm: UnlitMaterial; lateinit var ray: UnlitMaterial
    lateinit var playhead: UnlitMaterial

    companion object {
        fun pbr(c: Color, rough: Float, metal: Float = 0f, tex: TextureResource? = null, glow: Color? = null): PhysicallyBasedMaterial =
            PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                if (tex != null) { setBaseColor(Color4(1f, 1f, 1f, 1f)); setBaseColorTexture(tex) } else setBaseColor(c.c4(1f))
                setRoughness(rough); setMetallic(metal)
                if (glow != null) setEmissiveColor(glow.c4(1f))
            }

        fun unlit(c: Color, opacity: Float = 1f, tex: TextureResource? = null): UnlitMaterial =
            UnlitMaterial.create(if (opacity < 1f) BlendingMode.TRANSPARENT else BlendingMode.OPAQUE).apply {
                setBaseColor(c.c4(1f))
                tex?.let { setBaseColorTexture(it) }
                if (opacity < 1f) { setOpacity(opacity); setDepthWrite(false) }
                setCullingMode(MaterialCullingMode.NONE)
                runCatching { setApplyToneMapping(false) }
            }

        private fun scale(c: Color, k: Float) = Color((c.red * k).coerceAtMost(1f), (c.green * k).coerceAtMost(1f), (c.blue * k).coerceAtMost(1f))

        suspend fun create(): SceneRes = coroutineScope {
            val r = SceneRes()
            fun <T : Resource> T.k(): T = r.keep(this)
            val io = Dispatchers.IO
            val woodTex = async(io) { TextureResource.create(Textures.walnut()).k() }
            val aluTex = async(io) { TextureResource.create(Textures.brushed()).k() }
            listOf(
                async(io) { r.physics = PhysicsMaterialResource().k() },
                async(io) { r.body = Meshes.deckBody(Layout.BODY_INNER_R, Layout.BODY_STEP_R, Layout.BODY_OUTER_R, Layout.BODY_FRONT_Y, Layout.BODY_BACK_Y, Layout.BODY_THICK, Layout.BODY_AZ, "deckBody").k() },
                async(io) { r.trim = Meshes.arcStrip(Layout.BODY_INNER_R, Layout.BODY_INNER_R + 0.012f, Layout.BODY_FRONT_Y + 0.0006f, Layout.BODY_AZ, "deckTrim").k() },
                async(io) { r.outline = Meshes.deckOutline(Layout.BODY_INNER_R - 0.012f, Layout.BODY_OUTER_R + 0.012f, Layout.BODY_FRONT_Y + 0.002f, Layout.BODY_BACK_Y + 0.002f, Layout.BODY_AZ + 1.5f, 0.006f, "deckOutline").k() },
                // aluminium legs: 24-segment tube, 0.6 m (decorative, no floor detection)
                async(io) { r.leg = Meshes.lathe(listOf(0f to -0.6f, 0.012f to -0.6f, 0.012f to 0f, 0f to 0f), "leg", 24).k() },
                // pad parts in pad-local space, top face at y = 0 (48 segments, M16)
                async(io) { r.padBase = Meshes.lathe(listOf(0f to -0.035f, 0.064f to -0.035f, 0.064f to -0.007f, 0.0605f to -0.006f, 0f to -0.006f), "padBase", 48).k() },
                async(io) { r.padRim = Meshes.lathe(listOf(0.060f to -0.006f, 0.064f to -0.006f, 0.064f to -0.001f, 0.0625f to 0.0012f, 0.060f to 0.0004f), "padRim", 48).k() },
                async(io) { r.padFace = Meshes.lathe(listOf(0.0600f to -0.006f, 0.0600f to 0f, 0.045f to 0.0007f, 0f to 0.0011f), "padFace", 48).k() },
                async(io) { r.padGlow = Meshes.ringY(0.040f, 0.059f, "padGlow").k() },
                async(io) { r.padPick = Meshes.ringY(0.064f, 0.072f, "padPick").k() },
                async(io) { r.backing = Meshes.discZ(Layout.FRAME_R + 0.02f, "ringBacking", segments = 96).k() },
                async(io) { r.frame = Meshes.torusZ(Layout.FRAME_R, 0.005f, "ringFrame").k() },
                // ceramic stone: flattened sphere (lathe, 24 around), axis along +Y, scaled by velocity
                async(io) { r.stone = Meshes.lathe(List(13) { i -> val a = Math.PI * i / 12 - Math.PI / 2; (0.5f * kotlin.math.cos(a).toFloat()) to (0.22f * kotlin.math.sin(a).toFloat()) }, "stone", 24).k() },
                async(io) { r.emptySlot = Meshes.discZ(0.008f, "emptySlot", inner = 0.0055f, segments = 32).k() },
                async(io) { r.target = Meshes.dashedZ(0.024f, 0.027f, "target").k() },
                async(io) { r.hubKnob = Meshes.lathe(listOf(0f to 0f, 0.040f to 0f, 0.040f to 0.020f, 0.038f to 0.025f, 0f to 0.025f), "hubKnob", 48).k() },
                async(io) { r.hubFace = Meshes.discZ(0.030f, "hubFace", segments = 48).k() },
                async(io) { r.recBase = Meshes.lathe(listOf(0f to 0f, 0.036f to 0f, 0.036f to 0.006f, 0.032f to 0.008f, 0f to 0.008f), "recBase", 48).k() },
                async(io) { r.recCap = Meshes.lathe(listOf(0f to 0.006f, 0.030f to 0.006f, 0.030f to 0.016f, 0.026f to 0.020f, 0f to 0.021f), "recCap", 48).k() },
                async(io) { r.rod = Meshes.lathe(listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f), "rod", 16).k() },
                // maple stick along +Y: shaft, taper, oval wooden tip (lathe, 24 segments)
                async(io) { r.stick = Meshes.lathe(listOf(0f to 0f, 0.0075f to 0f, 0.0075f to 0.2f, 0.0048f to 0.28f, 0.0042f to 0.293f, 0.0068f to 0.300f, 0.0080f to 0.309f, 0.0062f to 0.317f, 0f to 0.32f), "stick", 24).k() },
                async(io) { r.dot = Meshes.lathe(List(9) { i -> val a = Math.PI * i / 8 - Math.PI / 2; (0.5f * kotlin.math.cos(a).toFloat()) to (0.5f * kotlin.math.sin(a).toFloat()) }, "dot", 16).k() },
                async(io) { r.label = Meshes.quadZ(0.11f, 0.02f, "label").k() },
                async(io) { r.caption = Meshes.quadZ(Layout.CAPTION_W, 0.032f, "caption").k() },
                async(io) { r.small = Meshes.quadZ(0.12f, 0.02f, "smallText").k() },
                async(io) { r.bpmPlate = Meshes.quadZ(0.05f, 0.03f, "bpmPlate").k() },
                async(io) { r.rubber = pbr(Pal.RUBBER, 0.92f).k() },
                async(io) { r.brass = pbr(Pal.BRASS, 0.42f, 0.35f).k() },
                async(io) { r.backingMat = pbr(Pal.BACKING, 0.95f).k() },
                async(io) { r.maple = pbr(Pal.MAPLE, 0.6f).k() },
                async(io) { r.frameRec = pbr(Pal.REC, 0.5f, glow = scale(Pal.REC, 0.55f)).k() },
                async(io) { r.frameCount = pbr(Pal.COUNT_TUBE, 0.6f, glow = scale(Pal.COUNT_TUBE, 0.45f)).k() },
                async(io) { r.framePlay = pbr(Pal.BRASS, 0.42f, 0.35f, glow = scale(Pal.PLAYHEAD, 0.12f)).k() },
                async(io) { r.recMat = pbr(Pal.REC, 0.75f).k() },
                async(io) { r.recLit = pbr(Pal.REC, 0.6f, glow = scale(Pal.REC, 0.8f)).k() },
                async(io) { r.emptyMat = pbr(hex(0x8A8D92), 0.8f).k() },
                async(io) { r.stoneGrey = pbr(hex(0x7A7D82), 0.6f).k() },
                async(io) { r.rims = Pal.PADS.map { pbr(it, 0.55f).k() } },
                async(io) { r.stones = List(PADS) { pbr(Pal.stone(it), 0.5f).k() } },
                async(io) { r.stonesLit = List(PADS) { pbr(Pal.stone(it), 0.45f, glow = scale(Pal.PADS[it], 0.9f)).k() } },
                async(io) { r.glows = List(PADS) { unlit(Pal.PLAYHEAD, 0.0f).k() } },
                async(io) { r.pick = unlit(Pal.GOLD).k() },
                async(io) { r.warm = unlit(Pal.PLAYHEAD, 0.85f).k() },
                async(io) { r.ray = unlit(Color.White, 0.55f).k() },
                async(io) { r.playhead = unlit(Pal.PLAYHEAD).k() },
            ).awaitAll()
            r.walnut = pbr(Pal.WALNUT, 0.7f, tex = woodTex.await()).k()
            r.alu = pbr(Pal.ALU, 0.45f, 0.25f, tex = aluTex.await()).k()
            r
        }
    }
}
