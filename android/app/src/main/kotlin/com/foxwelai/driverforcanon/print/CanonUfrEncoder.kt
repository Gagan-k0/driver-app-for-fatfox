package com.foxwelai.driverforcanon.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.TimeZone

/**
 * Canon UFR II LT / NCAP-family job builder for LBP6030B.
 *
 * Outer framing matches reverse-engineered UFR/CARPS USB jobs (`CD CA 10` blocks).
 * LBP6030 officially uses closed "SLIM"/NCAP compression inside Canon's
 * `libcanonncapr` / `libcanon_slimsfp`. This encoder builds a valid framed job with
 * PackBits page bands (algorithm present in Canon's NCAP stack) for A5 landscape
 * at 600 DPI — the best open implementation path without a live USB capture.
 */
object CanonUfrEncoder {
    const val DPI = 600

    /** A5 landscape printable area @ 600 DPI (CARPS reference margins). */
    const val A5_LANDSCAPE_WIDTH = 4724
    const val A5_LANDSCAPE_HEIGHT = 3259

    /** Control blocks stay modest; print chunks match real UFR dumps (up to 0xFFFF). */
    private const val MAX_CONTROL_LEN = 4096 - 20
    private const val MAX_PRINT_LEN = 0xFFFF

    private const val DATA_CONTROL = 0x00
    private const val DATA_PRINT = 0x02

    private const val BLOCK_DOC_INFO = 0x12
    private const val BLOCK_END = 0x13
    private const val BLOCK_BEGIN1 = 0x14
    private const val BLOCK_END1 = 0x16
    private const val BLOCK_BEGIN2 = 0x17
    private const val BLOCK_PARAMS = 0x18
    private const val BLOCK_END2 = 0x19
    private const val BLOCK_PRINT = 0x1a
    private const val BLOCK_DOC_INFO_NEW = 0x6b

    private const val PAPER_A5 = 16
    private const val WEIGHT_PLAIN = 20

    fun testPageJob(title: String = "Foxwel Canon A5"): ByteArray {
        val bmp = Bitmap.createBitmap(A5_LANDSCAPE_WIDTH, A5_LANDSCAPE_HEIGHT, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 96f
            typeface = Typeface.DEFAULT_BOLD
        }
        c.drawText(title, 120f, 200f, p)
        p.textSize = 56f
        p.typeface = Typeface.DEFAULT
        c.drawText("Canon imageCLASS LBP6030B", 120f, 320f, p)
        c.drawText("A5 Landscape · 600 DPI · UFR/NCAP framed", 120f, 400f, p)
        c.drawText(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date()), 120f, 480f, p)
        p.strokeWidth = 6f
        p.style = Paint.Style.STROKE
        c.drawRect(80f, 80f, A5_LANDSCAPE_WIDTH - 80f, A5_LANDSCAPE_HEIGHT - 80f, p)
        return try {
            bitmapToJob(bmp, title)
        } finally {
            bmp.recycle()
        }
    }

    fun bitmapToJob(
        bitmap: Bitmap,
        title: String = "Receipt",
        copies: Int = 1
    ): ByteArray {
        val mono = toMonoPacked(bitmap)
        val out = ByteArrayOutputStream(mono.size + 64_000)

        // Document header (MF3200/UFR-style 0x6b)
        writeBlock(out, DATA_CONTROL, BLOCK_DOC_INFO_NEW, buildDocInfoNew(title))
        writeBlock(out, DATA_CONTROL, BLOCK_DOC_INFO, byteArrayOf(0x07, 0xD7.toByte(), 0x00, 0x01))
        writeBlock(out, DATA_CONTROL, BLOCK_BEGIN1, ByteArray(4))
        writeBlock(out, DATA_CONTROL, 0x15, byteArrayOf(0x08, 0xB3.toByte(), 0x02))
        writeBlock(out, DATA_CONTROL, 0x15, buildNamedParam(0x082B, title))
        writeBlock(out, DATA_CONTROL, 0x15, byteArrayOf(0x07, 0xD7.toByte(), 0x00, 0x01))
        writeBlock(out, DATA_CONTROL, 0x15, byteArrayOf(0x07, 0xD9.toByte(), 0x17, 0x00, 0x00, 0x00, 0x00))
        writeBlock(out, DATA_CONTROL, 0x15, byteArrayOf(0x07, 0xD8.toByte(), 0x0F))
        writeBlock(out, DATA_CONTROL, 0x15, byteArrayOf(0x08, 0x4A, 0x04))
        writeBlock(out, DATA_CONTROL, 0x15, byteArrayOf(0x07, 0xDA.toByte(), 0x00))
        writeBlock(
            out,
            DATA_CONTROL,
            0x15,
            byteArrayOf(
                0x08, 0xA5.toByte(), 0xFE.toByte(), 0xFE.toByte(), 0xFF.toByte(), 0xFE.toByte(),
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03
            )
        )
        writeBlock(out, DATA_CONTROL, BLOCK_BEGIN2, ByteArray(4))

        // Page / job params
        writeBlock(out, DATA_CONTROL, BLOCK_PARAMS, byteArrayOf(0x00, 0x2E, 0x83.toByte(), 0x00, 0x00))
        writeBlock(out, DATA_CONTROL, BLOCK_PARAMS, byteArrayOf(0x07, 0xD7.toByte(), 0x00, 0x01))
        writeBlock(out, DATA_CONTROL, BLOCK_PARAMS, byteArrayOf(0x07, 0xDF.toByte(), 0x01))
        writeBlock(out, DATA_CONTROL, BLOCK_PARAMS, byteArrayOf(0x07, 0xE0.toByte(), 0x04))
        writeBlock(out, DATA_CONTROL, BLOCK_PARAMS, byteArrayOf(0x07, 0xE1.toByte(), 0x00, 0x00, 0x00, 0x00))
        // copies / media hints
        writeBlock(
            out,
            DATA_CONTROL,
            BLOCK_PARAMS,
            byteArrayOf(0x08, 0x6E, copies.coerceIn(1, 99).toByte(), 0x0B, 0x00, 0x00)
        )
        // 600x600 dpi
        writeBlock(
            out,
            DATA_CONTROL,
            BLOCK_PARAMS,
            byteArrayOf(0x00, 0x3A, 0x08, 0x02, 0x58, 0x02, 0x58)
        )
        writeBlock(
            out,
            DATA_CONTROL,
            BLOCK_PARAMS,
            byteArrayOf(
                0x07, 0xED.toByte(), 0x03, 0xFE.toByte(), 0x00, 0x02,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03
            )
        )
        writeBlock(out, DATA_CONTROL, BLOCK_PARAMS, byteArrayOf(0x09, 0x71, 0xFF.toByte()))
        writeBlock(out, DATA_CONTROL, BLOCK_PARAMS, byteArrayOf(0x09, 0x72, 0x00))

        // Page raster as framed print blocks (LIPSLX-ish preamble + PackBits bands)
        val pagePayload = buildPagePayload(bitmap.width, bitmap.height, mono)
        writePrintPayload(out, pagePayload)

        // Epilogue
        writeBlock(out, DATA_CONTROL, BLOCK_END2, ByteArray(0))
        writeBlock(out, DATA_CONTROL, 0x15, byteArrayOf(0x01, 0x13, 0x00, 0x00, 0x00, 0x01))
        writeBlock(out, DATA_CONTROL, BLOCK_END1, ByteArray(0))
        writeBlock(out, DATA_CONTROL, BLOCK_DOC_INFO, byteArrayOf(0x01, 0x13, 0x00, 0x00, 0x00, 0x01))
        writeBlock(out, DATA_CONTROL, BLOCK_DOC_INFO, byteArrayOf(0x07, 0xD6.toByte(), 0x00, 0x01, 0xFF.toByte(), 0xFC.toByte()))
        writeBlock(out, DATA_CONTROL, BLOCK_END, byteArrayOf(0x00))
        return out.toByteArray()
    }

    private fun buildPagePayload(width: Int, height: Int, mono: ByteArray): ByteArray {
        val bandHeight = 64
        val bytesPerRow = (width + 7) / 8
        val payload = ByteArrayOutputStream()

        // Binary preamble: resolution tags + A5 paper code (from UFR dump patterns)
        payload.write(
            byteArrayOf(
                0x01, 0x01, 0xC1.toByte(), 0x85.toByte(), 0x10, 0x00, 0x10, 0x95.toByte(),
                0xC2.toByte(), 0x00, 0xD8.toByte(), 0x84.toByte(), 0x02, 0x58, // 600
                0xDD.toByte(), 0x80.toByte(), 0xC8.toByte(), 0xF0.toByte(), 0x84.toByte(), 0x08,
                0x00, 0x02, 0xC3.toByte(), 0x00, 0xC5.toByte(), 0x00, 0xC6.toByte(), 0x00,
                0x51, 0xF2.toByte(), 0x00, 0x03
            )
        )
        // ESC-style paper/setup (CARPS-compatible subset many Canon hosts parse)
        val esc = StringBuilder()
            .append("\u0001")
            .append("\u001b%@")
            .append("\u001bP42;$DPI;1J;ImgColor")
            .append("\u001b\\")
            .append("\u001b[11h")
            .append("\u001b[?7;$DPI I")
            .append("\u001b[${WEIGHT_PLAIN}'t")
            .append("\u001b[${PAPER_A5};;;;;;p")
            .append("\u001b[?2h")
            .append("\u001b[1v")
            .append("\u001b[$DPI;1;0;32;;64;0'c")
            .toString()
        payload.write(esc.toByteArray(Charsets.ISO_8859_1))

        var y = 0
        while (y < height) {
            val h = minOf(bandHeight, height - y)
            val rawBand = ByteArray(bytesPerRow * h)
            System.arraycopy(mono, y * bytesPerRow, rawBand, 0, rawBand.size)
            val compressed = packBits(rawBand)
            // Strip header ESC[;width;height;15.P  (15 = Canon/PackBits-style)
            val stripHdr =
                "\u0001\u001b[;$width;$h;15.P".toByteArray(Charsets.ISO_8859_1)
            payload.write(stripHdr)
            // Compressed data header (CARPS)
            val hdr = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            hdr.put(0x01).put(0x02).put(0x04).put(0x08)
            hdr.putShort(0)
            hdr.put(0x50)
            hdr.put(0x00)
            hdr.put(if (y + h >= height) 0x00 else 0x01) // last flag
            hdr.putShort(compressed.size.toShort())
            hdr.putShort(0)
            payload.write(hdr.array())
            // XOR like classic CARPS Canon compression stream
            for (b in compressed) payload.write(b.toInt() xor 0x43)
            payload.write(0x80)
            y += h
        }
        // End of page / end of print data (CARPS)
        payload.write(byteArrayOf(0x01, 0x0C))
        payload.write(byteArrayOf(0x01, 0x1B, 0x50, 0x30, 0x4A, 0x1B, 0x5C))
        payload.write(0x01)
        return payload.toByteArray()
    }

    private fun writePrintPayload(out: ByteArrayOutputStream, payload: ByteArray) {
        var offset = 0
        while (offset < payload.size) {
            val remaining = payload.size - offset
            val len = minOf(MAX_PRINT_LEN, remaining)
            val chunk = payload.copyOfRange(offset, offset + len)
            // Real UFR dumps: middle print chunks use dataType=0x02; the last uses 0x00.
            val dataType = if (offset + len >= payload.size) DATA_CONTROL else DATA_PRINT
            writeBlock(out, dataType, BLOCK_PRINT, chunk)
            offset += len
        }
    }

    private fun writeBlock(
        out: ByteArrayOutputStream,
        dataType: Int,
        blockType: Int,
        payload: ByteArray
    ) {
        val max = if (dataType == DATA_PRINT || blockType == BLOCK_PRINT) MAX_PRINT_LEN else MAX_CONTROL_LEN
        require(payload.size <= max) { "block too large: ${payload.size}" }
        val hdr = ByteArray(20)
        hdr[0] = 0xCD.toByte()
        hdr[1] = 0xCA.toByte()
        hdr[2] = 0x10
        hdr[3] = dataType.toByte()
        hdr[4] = 0x00
        hdr[5] = blockType.toByte()
        // Matches captured UFR II jobs (LBP151 and siblings): flags 00 00, marker FF FF.
        hdr[6] = 0x00
        hdr[7] = 0x00
        hdr[8] = ((payload.size ushr 8) and 0xFF).toByte()
        hdr[9] = (payload.size and 0xFF).toByte()
        hdr[10] = 0xFF.toByte()
        hdr[11] = 0xFF.toByte()
        out.write(hdr)
        out.write(payload)
    }

    private fun buildDocInfoNew(title: String): ByteArray {
        val safeTitle = title.take(32).ifEmpty { "Foxwel" }
        val user = "Android"
        val buf = ByteArrayOutputStream()
        // Record count (matches UFR dumps: 9 records)
        buf.write(byteArrayOf(0x00, 0x09))
        writeNewRecord(buf, 0x00F0, byteArrayOf(0x01))
        writeNewRecord(buf, 0x0130, byteArrayOf(0x03, 0x00, 0x00))
        writeNewRecord(buf, 0x000D, byteArrayOf(0x04))
        writeNewRecord(buf, 0x0004, namedStringPayload(safeTitle))
        writeNewRecord(buf, 0x0117, namedStringPayload(safeTitle))
        writeNewRecord(buf, 0x0006, namedStringPayload(user))
        // Host UUID-ish + hostname padding (64 bytes) — harmless placeholder
        val host = ByteArray(64)
        val hostName = "android".toByteArray(Charsets.US_ASCII)
        System.arraycopy(hostName, 0, host, 32, minOf(hostName.size, 32))
        writeNewRecord(buf, 0x0118, host)
        writeNewRecord(buf, 0x000C, byteArrayOf(0x32))
        writeNewRecord(buf, 0x0009, buildCarpsTime())
        return buf.toByteArray()
    }

    private fun namedStringPayload(text: String): ByteArray {
        val n = text.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(3 + n.size)
        out.write(0x03)
        out.write(0xF2)
        out.write(n.size and 0xFF)
        out.write(n)
        return out.toByteArray()
    }

    private fun writeNewRecord(buf: ByteArrayOutputStream, type: Int, data: ByteArray) {
        buf.write((type ushr 8) and 0xFF)
        buf.write(type and 0xFF)
        buf.write((data.size ushr 8) and 0xFF)
        buf.write(data.size and 0xFF)
        buf.write(data)
    }

    private fun buildNamedParam(tag: Int, name: String): ByteArray {
        val n = name.take(32).toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write((tag ushr 8) and 0xFF)
        out.write(tag and 0xFF)
        out.write(0x03)
        out.write(0xF2)
        out.write(n.size and 0xFF)
        out.write(n)
        return out.toByteArray()
    }

    private fun buildCarpsTime(): ByteArray {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val dow = cal.get(Calendar.DAY_OF_WEEK) // 1=Sun
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val min = cal.get(Calendar.MINUTE)
        val sec = cal.get(Calendar.SECOND)
        val msec = cal.get(Calendar.MILLISECOND)
        // Layout from carps.txt
        val yHi = ((year - 1900) shr 4) and 0xFF
        val yLoMonth = (((year - 1900) and 0x0F) shl 4) or (month and 0x0F)
        val dayDow = ((day and 0x1F) shl 3) or (dow and 0x07)
        val secM = ((sec and 0x3F) shl 2) or ((msec shr 8) and 0x03)
        return byteArrayOf(
            yHi.toByte(),
            yLoMonth.toByte(),
            dayDow.toByte(),
            0x00,
            hour.toByte(),
            min.toByte(),
            secM.toByte(),
            (msec and 0xFF).toByte()
        )
    }

    /** 1-bit MSB-first packed rows, black=1. */
    fun toMonoPacked(bitmap: Bitmap, threshold: Int = 160): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val bpr = (w + 7) / 8
        val out = ByteArray(bpr * h)
        val row = IntArray(w)
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            val rowOff = y * bpr
            for (x in 0 until w) {
                val p = row[x]
                val lum =
                    ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                if (lum < threshold) {
                    out[rowOff + (x ushr 3)] =
                        (out[rowOff + (x ushr 3)].toInt() or (0x80 ushr (x and 7))).toByte()
                }
            }
        }
        return out
    }

    /** Apple PackBits (TIFF). */
    fun packBits(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(input.size)
        var i = 0
        while (i < input.size) {
            // run of identical?
            var run = 1
            while (i + run < input.size && run < 128 && input[i] == input[i + run]) run++
            if (run >= 3) {
                out.write(257 - run) // -N as unsigned
                out.write(input[i].toInt() and 0xFF)
                i += run
                continue
            }
            // literal
            val start = i
            var lit = 0
            while (i < input.size && lit < 128) {
                if (i + 2 < input.size &&
                    input[i] == input[i + 1] && input[i] == input[i + 2]
                ) break
                i++
                lit++
            }
            if (lit == 0) { // force progress
                out.write(0)
                out.write(input[start].toInt() and 0xFF)
                i = start + 1
            } else {
                out.write(lit - 1)
                out.write(input, start, lit)
            }
        }
        return out.toByteArray()
    }

    fun scaleToA5Landscape(src: Bitmap): Bitmap {
        return Bitmap.createScaledBitmap(src, A5_LANDSCAPE_WIDTH, A5_LANDSCAPE_HEIGHT, true)
    }

    fun a5LandscapeDotSize(): Pair<Int, Int> = A5_LANDSCAPE_WIDTH to A5_LANDSCAPE_HEIGHT
}
