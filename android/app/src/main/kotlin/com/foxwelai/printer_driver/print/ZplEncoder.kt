package com.foxwelai.driverforcanon.print

import android.graphics.Bitmap
import kotlin.math.ceil

/**
 * Encodes monochrome bitmaps as ZPL/BPLZ ^GFA graphic fields.
 */
object ZplEncoder {
    private val hexDigits =
        charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F')

    fun bitmapToZpl(
        bitmap: Bitmap,
        labelWidthDots: Int = bitmap.width,
        labelHeightDots: Int = bitmap.height,
        threshold: Int = 160
    ): String {
        // Prefer compact ASCII hex built into a StringBuilder once.
        return String(bitmapToZplBytes(bitmap, labelWidthDots, labelHeightDots, threshold), Charsets.US_ASCII)
    }

    /** Same as [bitmapToZpl] but as raw bytes for USB write (avoids an extra UTF-8 copy). */
    fun bitmapToZplBytes(
        bitmap: Bitmap,
        labelWidthDots: Int = bitmap.width,
        labelHeightDots: Int = bitmap.height,
        threshold: Int = 160
    ): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val bytesPerRow = ceil(width / 8.0).toInt()
        val totalBytes = bytesPerRow * height
        val packed = ByteArray(totalBytes)
        val pixels = IntArray(width)

        for (y in 0 until height) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
            val rowOffset = y * bytesPerRow
            for (x in 0 until width) {
                val pixel = pixels[x]
                val luminance =
                    ((pixel shr 16 and 0xFF) * 299 +
                        (pixel shr 8 and 0xFF) * 587 +
                        (pixel and 0xFF) * 114) / 1000
                if (luminance < threshold) {
                    val byteIndex = rowOffset + (x ushr 3)
                    packed[byteIndex] =
                        (packed[byteIndex].toInt() or (0x80 ushr (x and 7))).toByte()
                }
            }
        }

        val header =
            "^XA\n^CI28\n^PW$labelWidthDots\n^LL$labelHeightDots\n^LH0,0\n^FO0,0^GFA,$totalBytes,$totalBytes,$bytesPerRow,"
        val footer = "^FS\n^XZ\n"
        val out = ByteArray(header.length + totalBytes * 2 + footer.length)
        var o = 0
        for (c in header) out[o++] = c.code.toByte()
        for (b in packed) {
            val v = b.toInt() and 0xFF
            out[o++] = hexDigits[v ushr 4].code.toByte()
            out[o++] = hexDigits[v and 0x0F].code.toByte()
        }
        for (c in footer) out[o++] = c.code.toByte()
        return out
    }

    fun testLabelZpl(
        widthDots: Int = 1678,
        heightDots: Int = 1183,
        title: String = "PrintFox"
    ): String {
        return """
            ^XA
            ^CI28
            ^PW$widthDots
            ^LL$heightDots
            ^LH0,0
            ^FO40,40^A0N,48,48^FD$title^FS
            ^FO40,110^A0N,28,28^FDUSB Print Ready^FS
            ^FO40,160^A0N,24,24^FD203 DPI  |  UFR / BPLZ/ZPL^FS
            ^FO40,220^GB${widthDots - 80},2,2^FS
            ^FO40,260^BY2^BCN,80,Y,N,N^FDFOXWEL-PRINT^FS
            ^FO40,370^A0N,22,22^FDPrintFox^FS
            ^XZ
        """.trimIndent() + "\n"
    }

    fun textLabelZpl(
        text: String,
        widthDots: Int = 1678,
        heightDots: Int = 1183
    ): String {
        val maxChars = (widthDots / 12).coerceIn(40, 120)
        val maxLines = (heightDots / 36).coerceIn(12, 40)
        val lines = text.lines().take(maxLines)
        val body = buildString {
            append("^XA\n^CI28\n^PW$widthDots\n^LL$heightDots\n^LH0,0\n")
            var y = 30
            for (line in lines) {
                val safe = line.replace("^", " ").replace("~", " ").take(maxChars)
                append("^FO30,$y^A0N,28,28^FD$safe^FS\n")
                y += 36
            }
            append("^XZ\n")
        }
        return body
    }
}
