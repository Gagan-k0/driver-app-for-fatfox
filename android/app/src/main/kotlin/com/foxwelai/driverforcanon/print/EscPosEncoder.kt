package com.foxwelai.driverforcanon.print

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream

/**
 * ESC/POS helpers for 80mm thermal receipt / label printers.
 * Auto-cut: GS V m (partial/full) — ignored harmlessly if no cutter.
 */
object EscPosEncoder {
    /** Common 80mm printable width @ 203 DPI (~72 mm). */
    const val WIDTH_80MM_DOTS = 576

    /** Init + code page */
    fun init(): ByteArray = byteArrayOf(0x1B, 0x40)

    /** Feed n lines */
    fun feed(lines: Int = 3): ByteArray =
        byteArrayOf(0x1B, 0x64, lines.coerceIn(0, 255).toByte())

    /**
     * Paper cut.
     * @param partial true = partial cut (GS V 1), false = full cut (GS V 0)
     */
    fun cut(partial: Boolean = true): ByteArray =
        if (partial) byteArrayOf(0x1D, 0x56, 0x01)
        else byteArrayOf(0x1D, 0x56, 0x00)

    /** Alternate cut used by some TVS/SNBC firmwares: ESC i */
    fun cutEscI(): ByteArray = byteArrayOf(0x1B, 0x69)

    fun alignCenter(): ByteArray = byteArrayOf(0x1B, 0x61, 0x01)
    fun alignLeft(): ByteArray = byteArrayOf(0x1B, 0x61, 0x00)

    /**
     * GS v 0 raster bit-image (most compatible for graphics on ESC/POS).
     * Width padded to multiple of 8.
     */
    fun bitmapToRaster(
        src: Bitmap,
        targetWidth: Int = WIDTH_80MM_DOTS,
        threshold: Int = 160
    ): ByteArray {
        val w = targetWidth.coerceAtLeast(8).let { it - (it % 8) }
        val scale = w.toFloat() / src.width.toFloat()
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        return try {
            val bytesPerRow = w / 8
            val out = ByteArrayOutputStream()
            out.write(init())
            out.write(alignLeft())

            // GS v 0 m xL xH yL yH d1...dk
            out.write(0x1D)
            out.write(0x76)
            out.write(0x30)
            out.write(0x00) // normal
            out.write(bytesPerRow and 0xFF)
            out.write((bytesPerRow shr 8) and 0xFF)
            out.write(h and 0xFF)
            out.write((h shr 8) and 0xFF)

            val row = ByteArray(bytesPerRow)
            for (y in 0 until h) {
                row.fill(0)
                for (x in 0 until w) {
                    val c = scaled.getPixel(x, y)
                    val r = Color.red(c)
                    val g = Color.green(c)
                    val b = Color.blue(c)
                    val lum = (r * 30 + g * 59 + b * 11) / 100
                    if (lum < threshold) {
                        val byteIndex = x / 8
                        val bit = 7 - (x % 8)
                        row[byteIndex] = (row[byteIndex].toInt() or (1 shl bit)).toByte()
                    }
                }
                out.write(row)
            }
            out.toByteArray()
        } finally {
            if (scaled !== src) scaled.recycle()
        }
    }

    /** Full job: raster + feed + cut commands (partial + ESC i fallback). */
    fun printBitmapJob(
        src: Bitmap,
        targetWidth: Int = WIDTH_80MM_DOTS,
        threshold: Int = 160,
        autoCut: Boolean = true,
        partialCut: Boolean = true
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(bitmapToRaster(src, targetWidth, threshold))
        out.write(feed(3))
        if (autoCut) {
            out.write(cut(partialCut))
            // Some cutters only react to ESC i
            out.write(cutEscI())
        } else {
            out.write(feed(2))
        }
        return out.toByteArray()
    }

    fun textLinesJob(
        lines: List<String>,
        autoCut: Boolean = true
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(init())
        out.write(alignLeft())
        for (line in lines) {
            out.write(line.toByteArray(Charsets.UTF_8))
            out.write(0x0A)
        }
        out.write(feed(3))
        if (autoCut) {
            out.write(cut(true))
            out.write(cutEscI())
        }
        return out.toByteArray()
    }
}
