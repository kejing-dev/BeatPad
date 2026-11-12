package io.embodit.beatpad.ui.stage

import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Programmatic meshes (G3: the deck, pads, ring, knob, sticks are procedural PBR — positions must match the colliders to
 * the millimetre). Round parts are true lathe / torus meshes with smooth normals and ≥ 24 segments (M16, gate
 * ring-from-boxes), never boxes around a circle.
 */
internal object Meshes {
    private class B {
        val pos = ArrayList<Vector3>(); val nor = ArrayList<Vector3>(); val uv = ArrayList<Vector2>(); val idx = ArrayList<Int>()
        fun v(p: Vector3, n: Vector3, t: Vector2 = Vector2(0f, 0f)): Int { pos += p; nor += n; uv += t; return pos.size - 1 }
        fun tri(a: Int, b: Int, c: Int) { idx += a; idx += b; idx += c }
        fun build(name: String): MeshResource {
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            for (p in pos) { minX = minOf(minX, p.x); minY = minOf(minY, p.y); minZ = minOf(minZ, p.z); maxX = maxOf(maxX, p.x); maxY = maxOf(maxY, p.y); maxZ = maxOf(maxZ, p.z) }
            val bounds = BoundingBox(Vector3((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2),
                Vector3(max(1e-4f, (maxX - minX) / 2), max(1e-4f, (maxY - minY) / 2), max(1e-4f, (maxZ - minZ) / 2)))
            return MeshResource.createWithMeshModel(MeshModel(positions = pos, triangleIndices = idx, normals = nor, uv0 = uv), bounds, name)
        }
    }

    private fun norm(x: Float, y: Float, z: Float): Vector3 { val l = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-9f); return Vector3(x / l, y / l, z / l) }

    /**
     * Lathe: [profile] (radius, y) from the axis bottom to the top, revolved about +Y with [segments] steps. Normals are
     * smooth around and follow the profile slope; consecutive equal points make a hard edge.
     */
    fun lathe(profile: List<Pair<Float, Float>>, name: String, segments: Int = 48): MeshResource {
        val b = B()
        for (k in 0 until profile.size - 1) {
            val (r0, y0) = profile[k]; val (r1, y1) = profile[k + 1]
            if (r0 == r1 && y0 == y1) continue
            val dr = r1 - r0; val dy = y1 - y0
            // outward normal of the segment in the (r, y) plane: (dy, -dr)
            val nr = dy; val ny = -dr
            val base = b.pos.size
            for (i in 0..segments) {
                val a = 2 * PI * i / segments
                val c = cos(a).toFloat(); val s = sin(a).toFloat()
                val n = norm(nr * c, ny, nr * s)
                b.v(Vector3(r0 * c, y0, r0 * s), n, Vector2(i.toFloat() / segments, k.toFloat() / profile.size))
                b.v(Vector3(r1 * c, y1, r1 * s), n, Vector2(i.toFloat() / segments, (k + 1f) / profile.size))
            }
            for (i in 0 until segments) {
                val a = base + i * 2
                // counter-clockwise seen from outside
                b.tri(a, a + 1, a + 3); b.tri(a, a + 3, a + 2)
            }
        }
        return b.build(name)
    }

    /** Torus about the local Z axis (lying in the XY plane, facing +Z): the beat ring frame. */
    fun torusZ(major: Float, minor: Float, name: String, around: Int = 96, tube: Int = 16): MeshResource {
        val b = B()
        for (i in 0..around) {
            val u = 2 * PI * i / around
            val cu = cos(u).toFloat(); val su = sin(u).toFloat()
            for (j in 0..tube) {
                val v = 2 * PI * j / tube
                val cv = cos(v).toFloat(); val sv = sin(v).toFloat()
                b.v(Vector3((major + minor * cv) * cu, (major + minor * cv) * su, minor * sv), Vector3(cv * cu, cv * su, sv))
            }
        }
        val row = tube + 1
        for (i in 0 until around) for (j in 0 until tube) {
            val a = i * row + j; val c = a + row
            b.tri(a, c, a + 1); b.tri(a + 1, c, c + 1)
        }
        return b.build(name)
    }

    /** Disc in the XY plane facing +Z (ring backing, lane dots, glow). [inner] > 0 = annulus. UV maps the square. */
    fun discZ(radius: Float, name: String, inner: Float = 0f, segments: Int = 64): MeshResource {
        val b = B()
        val n = Vector3(0f, 0f, 1f)
        for (i in 0 until segments) {
            val a0 = 2 * PI * i / segments; val a1 = 2 * PI * (i + 1) / segments
            fun p(r: Float, a: Double) = Vector3((cos(a) * r).toFloat(), (sin(a) * r).toFloat(), 0f)
            fun t(q: Vector3) = Vector2(0.5f + q.x / (2 * radius), 0.5f + q.y / (2 * radius))
            if (inner <= 0f) {
                val c = b.v(Vector3(0f, 0f, 0f), n, Vector2(0.5f, 0.5f)); val p0 = p(radius, a0); val p1 = p(radius, a1)
                b.tri(c, b.v(p0, n, t(p0)), b.v(p1, n, t(p1)))
            } else {
                val o0 = p(radius, a0); val o1 = p(radius, a1); val i0 = p(inner, a0); val i1 = p(inner, a1)
                val a = b.v(o0, n, t(o0)); val bb = b.v(o1, n, t(o1)); val c = b.v(i1, n, t(i1)); val d = b.v(i0, n, t(i0))
                b.tri(a, bb, c); b.tri(a, c, d)
            }
        }
        return b.build(name)
    }

    /** Flat annulus in the XZ plane facing +Y (pad glow ring, pick ring, deck outline). */
    fun ringY(inner: Float, outer: Float, name: String, segments: Int = 64, dashes: Int = 0): MeshResource {
        val b = B()
        val n = Vector3(0f, 1f, 0f)
        for (i in 0 until segments) {
            if (dashes > 0 && (i * dashes / segments) % 2 == 1) continue
            val a0 = 2 * PI * i / segments; val a1 = 2 * PI * (i + 1) / segments
            fun p(r: Float, a: Double) = Vector3((cos(a) * r).toFloat(), 0f, (sin(a) * r).toFloat())
            val o0 = b.v(p(outer, a0), n); val o1 = b.v(p(outer, a1), n); val i1 = b.v(p(inner, a1), n); val i0 = b.v(p(inner, a0), n)
            b.tri(o0, i1, o1); b.tri(o0, i0, i1)
        }
        return b.build(name)
    }

    /** Dashed annulus in the XY plane facing +Z (D5 target slot). */
    fun dashedZ(inner: Float, outer: Float, name: String, dashes: Int = 12, segments: Int = 48): MeshResource {
        val b = B()
        val n = Vector3(0f, 0f, 1f)
        for (i in 0 until segments) {
            if ((i * dashes * 2 / segments) % 2 == 1) continue
            val a0 = 2 * PI * i / segments; val a1 = 2 * PI * (i + 1) / segments
            fun p(r: Float, a: Double) = Vector3((cos(a) * r).toFloat(), (sin(a) * r).toFloat(), 0f)
            val o0 = b.v(p(outer, a0), n); val o1 = b.v(p(outer, a1), n); val i1 = b.v(p(inner, a1), n); val i0 = b.v(p(inner, a0), n)
            b.tri(o0, o1, i1); b.tri(o0, i1, i0)
        }
        return b.build(name)
    }

    /** Quad [w] × [h] in the XY plane facing +Z, uv (0,0) bottom-left (text plates). */
    fun quadZ(w: Float, h: Float, name: String): MeshResource {
        val b = B(); val n = Vector3(0f, 0f, 1f)
        val a = b.v(Vector3(-w / 2, -h / 2, 0f), n, Vector2(0f, 0f)); val c = b.v(Vector3(w / 2, -h / 2, 0f), n, Vector2(1f, 0f))
        val d = b.v(Vector3(w / 2, h / 2, 0f), n, Vector2(1f, 1f)); val e = b.v(Vector3(-w / 2, h / 2, 0f), n, Vector2(0f, 1f))
        b.tri(a, c, d); b.tri(a, d, e)
        return b.build(name)
    }

    /**
     * The curved deck body (spatialLayout.deck.body): two stepped levels between [rIn]…[rStep] (front, top [yFront]) and
     * [rStep]…[rOut] (back, top [yBack]), [thick] deep, spanning ±[azDeg]; risers on the inner edge, the step and the
     * outer edge; end caps. UV: top surfaces planar (x, z) for the wood grain, walls (arc length, y).
     */
    fun deckBody(rIn: Float, rStep: Float, rOut: Float, yFront: Float, yBack: Float, thick: Float, azDeg: Float, name: String, segments: Int = 64): MeshResource {
        val b = B()
        val a0 = Math.toRadians(-azDeg.toDouble()); val a1 = Math.toRadians(azDeg.toDouble())
        fun at(r: Float, a: Double, y: Float) = Vector3((sin(a) * r).toFloat(), y, (-cos(a) * r).toFloat())
        fun ang(i: Int) = a0 + (a1 - a0) * i / segments
        val uvS = 0.35f
        // horizontal band facing up (+1) or down (−1)
        fun band(r0: Float, r1: Float, y: Float, up: Boolean) {
            val n = Vector3(0f, if (up) 1f else -1f, 0f)
            for (i in 0 until segments) {
                val p00 = at(r0, ang(i), y); val p01 = at(r0, ang(i + 1), y); val p10 = at(r1, ang(i), y); val p11 = at(r1, ang(i + 1), y)
                fun t(p: Vector3) = Vector2(p.x / uvS + 0.5f, p.z / uvS + 0.5f)
                val a = b.v(p00, n, t(p00)); val bb = b.v(p01, n, t(p01)); val c = b.v(p11, n, t(p11)); val d = b.v(p10, n, t(p10))
                if (up) { b.tri(a, bb, c); b.tri(a, c, d) } else { b.tri(a, c, bb); b.tri(a, d, c) }
            }
        }
        // vertical wall at radius r from y0 to y1, facing toward the axis (inward = true) or away
        fun wall(r: Float, y0: Float, y1: Float, inward: Boolean) {
            for (i in 0 until segments) {
                val aa = ang(i); val ab = ang(i + 1)
                fun n(a: Double) = if (inward) Vector3(-sin(a).toFloat(), 0f, cos(a).toFloat()) else Vector3(sin(a).toFloat(), 0f, -cos(a).toFloat())
                val u0 = (r * (aa - a0)).toFloat() / uvS; val u1 = (r * (ab - a0)).toFloat() / uvS
                val p = b.v(at(r, aa, y0), n(aa), Vector2(u0, y0 / uvS)); val q = b.v(at(r, ab, y0), n(ab), Vector2(u1, y0 / uvS))
                val s = b.v(at(r, ab, y1), n(ab), Vector2(u1, y1 / uvS)); val t = b.v(at(r, aa, y1), n(aa), Vector2(u0, y1 / uvS))
                if (inward) { b.tri(p, q, s); b.tri(p, s, t) } else { b.tri(p, s, q); b.tri(p, t, s) }
            }
        }
        val bottom = yFront - thick
        band(rIn, rStep, yFront, true)
        band(rStep, rOut, yBack, true)
        band(rIn, rOut, bottom, false)
        wall(rIn, bottom, yFront, true)
        wall(rStep, yFront, yBack, true)
        wall(rOut, bottom, yBack, false)
        // end caps (flat, facing ±tangent)
        for ((a, sign) in listOf(a0 to -1f, a1 to 1f)) {
            val n = Vector3((cos(a) * sign).toFloat(), 0f, (sin(a) * sign).toFloat())
            val outline = listOf(at(rIn, a, bottom), at(rOut, a, bottom), at(rOut, a, yBack), at(rStep, a, yBack), at(rStep, a, yFront), at(rIn, a, yFront))
            val ids = outline.map { b.v(it, n, Vector2(it.x / uvS, it.y / uvS)) }
            // two quads: front block (rIn..rStep) and back block (rStep..rOut)
            fun quad(p: Int, q: Int, r: Int, s: Int) { if (sign > 0) { b.tri(p, q, r); b.tri(p, r, s) } else { b.tri(p, r, q); b.tri(p, s, r) } }
            val stepBottom = b.v(at(rStep, a, bottom), n, Vector2(0f, 0f))
            quad(ids[0], stepBottom, ids[4], ids[5])
            quad(stepBottom, ids[1], ids[2], ids[3])
            quad(stepBottom, ids[3], ids[4], ids[4])
        }
        return b.build(name)
    }

    /** A thin arc strip on top of the deck's front edge (brushed aluminium trim), facing up. */
    fun arcStrip(r0: Float, r1: Float, y: Float, azDeg: Float, name: String, segments: Int = 64): MeshResource {
        val b = B(); val n = Vector3(0f, 1f, 0f)
        val a0 = Math.toRadians(-azDeg.toDouble()); val a1 = Math.toRadians(azDeg.toDouble())
        fun at(r: Float, a: Double) = Vector3((sin(a) * r).toFloat(), y, (-cos(a) * r).toFloat())
        for (i in 0 until segments) {
            val aa = a0 + (a1 - a0) * i / segments; val ab = a0 + (a1 - a0) * (i + 1) / segments
            val p = b.v(at(r0, aa), n); val q = b.v(at(r0, ab), n); val s = b.v(at(r1, ab), n); val t = b.v(at(r1, aa), n)
            b.tri(p, s, q); b.tri(p, t, s)
        }
        return b.build(name)
    }

    /** Dashed outline around the deck (D6 "描一圈暖白虚边"): a flat ring band following the body outline, facing up. */
    fun deckOutline(rIn: Float, rOut: Float, yIn: Float, yOut: Float, azDeg: Float, band: Float, name: String, dashes: Int = 40): MeshResource {
        val b = B(); val n = Vector3(0f, 1f, 0f)
        val a0 = Math.toRadians(-azDeg.toDouble()); val a1 = Math.toRadians(azDeg.toDouble())
        fun at(r: Float, a: Double) = Vector3((sin(a) * r).toFloat(), if (r < (rIn + rOut) / 2) yIn else yOut, (-cos(a) * r).toFloat())
        val seg = dashes * 2
        for (r in listOf(rIn, rOut)) for (i in 0 until seg step 2) {
            val aa = a0 + (a1 - a0) * i / seg; val ab = a0 + (a1 - a0) * (i + 1) / seg
            val p = b.v(at(r - band / 2, aa), n); val q = b.v(at(r - band / 2, ab), n); val s = b.v(at(r + band / 2, ab), n); val t = b.v(at(r + band / 2, aa), n)
            b.tri(p, s, q); b.tri(p, t, s)
        }
        return b.build(name)
    }

    /** Unit-length strip along +Z (0…1) of width 1 in X, facing +Y — scaled into rays and the playhead. */
    fun unitRod(name: String, segments: Int = 12): MeshResource =
        lathe(listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f), name, segments)
}
