package io.embodit.beatpad.ui.stage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedural textures (budget: ≤ 4 maps of 1024²; no downloaded texture files — the design suggested an ambientCG CC0
 * walnut map, downloads need the user's OK, so the grain is generated: deviation logged) and the text plates for the
 * 3D words (pad names, ring caption, BPM digits). Text is drawn into a bitmap, uv v = 0 at the bitmap's bottom row
 * (factory finding, checked with the「F↑」test plate: debug `testtex on`).
 */
internal object Textures {
    /** Walnut: dark brown with flowing rings and fine pores (palette walnut #5A3E2B). */
    fun walnut(size: Int = 512, seed: Int = 11): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val rng = Random(seed)
        val ph = FloatArray(6) { rng.nextFloat() * 6.28f }
        val px = IntArray(size * size)
        val r0 = 0x5A; val g0 = 0x3E; val b0 = 0x2B
        for (y in 0 until size) for (x in 0 until size) {
            val fx = x.toFloat() / size; val fy = y.toFloat() / size
            val ring = sin(fx * 26f + sin(fy * 3.2f + ph[0]) * 1.8f + sin(fy * 9f + ph[1]) * 0.35f + ph[2]) * 0.5f + 0.5f
            val streak = sin(fx * 310f + sin(fy * 6f + ph[3]) * 4f + ph[4]) * 0.5f + 0.5f
            val pore = if (rng.nextFloat() < 0.012f) -0.10f else 0f
            val k = 0.84f + 0.16f * ring * ring + 0.05f * streak + pore
            px[y * size + x] = Color.rgb((r0 * k).toInt().coerceIn(0, 255), (g0 * k).toInt().coerceIn(0, 255), (b0 * k).toInt().coerceIn(0, 255))
        }
        bmp.setPixels(px, 0, size, 0, 0, size, size)
        return bmp
    }

    /** Brushed aluminium (#B9BCC0) with fine horizontal hairlines; used on the matte metal parts. */
    fun brushed(size: Int = 256, seed: Int = 5): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val rng = Random(seed)
        val px = IntArray(size * size)
        val rowK = FloatArray(size) { 0.93f + rng.nextFloat() * 0.1f }
        for (y in 0 until size) for (x in 0 until size) {
            val k = rowK[y] * (0.97f + rng.nextFloat() * 0.04f)
            px[y * size + x] = Color.rgb((0xB9 * k).toInt().coerceIn(0, 255), (0xBC * k).toInt().coerceIn(0, 255), (0xC0 * k).toInt().coerceIn(0, 255))
        }
        bmp.setPixels(px, 0, size, 0, 0, size, size)
        return bmp
    }

    /**
     * One line of text on an opaque plate of [wPx] × [hPx] (aspect = the plate's metres). The size shrinks only to keep
     * the whole string on the plate; [minSizeFrac] is logged by the caller if it is reached (a plate too short = shorten
     * the copy, not the font, design D45).
     */
    fun textPlate(text: String, wPx: Int, hPx: Int, bg: Int, fg: Int, bold: Boolean = true, radiusPx: Float = 0f, sizeFrac: Float = 0.72f): Pair<Bitmap, Boolean> {
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (radiusPx > 0f) {
            c.drawColor(Color.TRANSPARENT)
            c.drawRoundRect(RectF(0f, 0f, wPx.toFloat(), hPx.toFloat()), radiusPx, radiusPx, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
        } else c.drawColor(bg)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fg; textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
            textSize = hPx * sizeFrac
        }
        var fits = true
        val maxW = wPx * 0.94f
        while (p.measureText(text) > maxW && p.textSize > hPx * 0.3f) { p.textSize *= 0.95f; fits = false }
        val fm = p.fontMetrics
        c.drawText(text, wPx / 2f, hPx / 2f - (fm.ascent + fm.descent) / 2f, p)
        return bmp to fits
    }

    /** "F↑" orientation test (profile D10). */
    fun orientationTest(w: Int = 256, h: Int = 128): Bitmap = textPlate("F↑", w, h, Color.WHITE, Color.BLACK).first
}
