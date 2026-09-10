package com.foxwelai.driverforcanon.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Network (Wi‑Fi / Ethernet) raw printer client.
 *
 * TVSE Blaze SD-30NW is a host-based GDI laser. On Android we rasterize the page
 * and send a single-page PDF over JetDirect (TCP 9100), which most Blaze/Pantum-
 * class firmwares accept for mobile/network jobs.
 */
class WifiPrinterClient {
    companion object {
        private const val TAG = "WifiPrinter"
        private const val DEFAULT_PORT = 9100
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val SO_TIMEOUT_MS = 60_000
    }

    @Volatile
    var host: String = ""

    @Volatile
    var port: Int = DEFAULT_PORT

    @Volatile
    var useGdi: Boolean = true

    fun configured(): Boolean = host.isNotBlank() && port in 1..65535

    fun status(): Map<String, Any?> = mapOf(
        "transport" to "wifi",
        "connected" to false, // connection is per-job for TCP
        "hasDevice" to configured(),
        "wifiHost" to host,
        "wifiPort" to port,
        "useGdi" to useGdi,
        "protocol" to if (useGdi) "GDI_PDF_RAW" else "RAW_9100",
        "hint" to "TVSE Blaze SD-30NW · GDI host-based · TCP $port"
    )

    fun testConnection(): Map<String, Any?> {
        if (!configured()) {
            return mapOf("ok" to false, "error" to "Set Wi‑Fi printer IP address first")
        }
        return try {
            Socket().use { socket ->
                socket.soTimeout = CONNECT_TIMEOUT_MS
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                mapOf(
                    "ok" to true,
                    "host" to host,
                    "port" to port,
                    "message" to "Connected to $host:$port"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Wi‑Fi connect failed $host:$port", e)
            mapOf("ok" to false, "error" to (e.message ?: "Connection failed"))
        }
    }

    fun write(bytes: ByteArray): Map<String, Any?> {
        if (!configured()) {
            return mapOf("ok" to false, "error" to "Wi‑Fi printer not configured")
        }
        if (bytes.isEmpty()) {
            return mapOf("ok" to false, "error" to "Empty job")
        }
        return try {
            Socket().use { socket ->
                socket.soTimeout = SO_TIMEOUT_MS
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket.getOutputStream().use { out ->
                    out.write(bytes)
                    out.flush()
                }
            }
            mapOf(
                "ok" to true,
                "bytesWritten" to bytes.size,
                "transport" to "wifi",
                "protocol" to if (useGdi) "GDI_PDF_RAW" else "RAW_9100",
                "host" to host,
                "port" to port
            )
        } catch (e: Exception) {
            Log.e(TAG, "Wi‑Fi write failed", e)
            mapOf("ok" to false, "error" to (e.message ?: "Wi‑Fi print failed"))
        }
    }

    fun writeFile(path: String): Map<String, Any?> {
        val file = File(path)
        if (!file.exists()) return mapOf("ok" to false, "error" to "File not found: $path")
        return write(file.readBytes())
    }

    /** Encode an ARGB bitmap as a one-page PDF (GDI-friendly payload). */
    fun bitmapToPdfBytes(bitmap: Bitmap, title: String = "Foxwel"): ByteArray {
        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create()
        val page = doc.startPage(pageInfo)
        page.canvas.drawColor(Color.WHITE)
        page.canvas.drawBitmap(bitmap, 0f, 0f, null)
        doc.finishPage(page)
        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }

    fun printBitmapGdi(bitmap: Bitmap, title: String = "Foxwel"): Map<String, Any?> {
        val pdf = bitmapToPdfBytes(bitmap, title)
        Log.i(TAG, "GDI PDF job ${pdf.size} bytes (${bitmap.width}x${bitmap.height})")
        val result = write(pdf)
        return result + mapOf(
            "paper" to "${bitmap.width}x${bitmap.height}",
            "gdi" to true
        )
    }

    fun pngFileToPdfFile(pngPath: String, outPdf: File): Map<String, Any?> {
        val bmp = android.graphics.BitmapFactory.decodeFile(pngPath)
            ?: return mapOf("ok" to false, "error" to "Cannot decode PNG")
        return try {
            val pdfBytes = bitmapToPdfBytes(bmp)
            FileOutputStream(outPdf).use { it.write(pdfBytes) }
            mapOf("ok" to true, "path" to outPdf.absolutePath, "bytes" to pdfBytes.size)
        } finally {
            bmp.recycle()
        }
    }
}
