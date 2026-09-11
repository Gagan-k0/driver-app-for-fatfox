package com.foxwelai.driverforcanon

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.foxwelai.driverforcanon.bt.BluetoothPrinterHub
import com.foxwelai.driverforcanon.print.EscPosEncoder
import com.foxwelai.driverforcanon.usb.UsbPrinterHolder
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

class TransparentPrintActivity : Activity() {
    companion object {
        private const val TAG = "TransparentPrint"
        private const val RECEIPT_80MM_WIDTH_DOTS = EscPosEncoder.WIDTH_80MM_DOTS
    }

    private val usbPrinter by lazy { UsbPrinterHolder.get(applicationContext) }
    private val btHub by lazy { BluetoothPrinterHub.get(applicationContext) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var finishRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun safeFinish(delayMs: Long = 200) {
        finishRunnable?.let { mainHandler.removeCallbacks(it) }
        mainHandler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                finish()
            }
        }, delayMs)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) {
            safeFinish(0)
            return
        }

        val prefs = getSharedPreferences("printfox_settings", MODE_PRIVATE)
        val preferredTransport = prefs.getString("preferredTransport", null)
            ?: prefs.getString("transport", "bluetooth") ?: "bluetooth"
        val receiptBtAddress = prefs.getString("receiptBtAddress", null)
            ?: prefs.getString("receipt_bt_address", "") ?: ""
        val labelBtAddress = prefs.getString("labelBtAddress", null)
            ?: prefs.getString("label_bt_address", "") ?: ""
        val threshold = prefs.getInt("threshold", 160)
        val autoCut = if (prefs.contains("autoCut")) prefs.getBoolean("autoCut", true) else prefs.getBoolean("auto_cut", true)

        // Safety finish timer in case network/rendering times out (6 seconds)
        finishRunnable = Runnable {
            Log.w(TAG, "Print job timeout - safety finish")
            safeFinish(0)
        }
        finishRunnable?.let { mainHandler.postDelayed(it, 6000) }

        // 1. Direct Intent Extras (from LocalHttpServer background HTTP POST)
        val extraData = intent.getStringExtra("data")
        if (!extraData.isNullOrBlank()) {
            val extraType = intent.getStringExtra("type") ?: "html"
            val extraFormat = normalizeFormat(intent.getStringExtra("format"))
            val extraName = intent.getStringExtra("name") ?: if (extraFormat == "kot") "Kitchen Order Ticket" else "Customer Bill"
            Log.i(TAG, "Transparent print intent extras received (${extraData.length} chars, format=$extraFormat)")

            Thread {
                try {
                    renderHtmlAndPrint(
                        htmlData = extraData,
                        jobName = extraName,
                        format = extraFormat,
                        transport = preferredTransport,
                        receiptAddr = receiptBtAddress,
                        labelAddr = labelBtAddress,
                        threshold = threshold,
                        autoCut = autoCut
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Intent extra print job failed", e)
                    safeFinish(0)
                }
            }.start()
            return
        }

        // 2. Deep Link Uri (printfox://print?...)
        val dataUri = intent.data
        if (dataUri == null) {
            safeFinish(0)
            return
        }
        val dataString = intent.dataString ?: dataUri.toString()
        Log.i(TAG, "Transparent print deep link received: $dataString")

        if (!dataUri.scheme.equals("printfox", ignoreCase = true) ||
            !dataUri.host.equals("print", ignoreCase = true)
        ) {
            safeFinish(0)
            return
        }

        val format = normalizeFormat(
            dataUri.getQueryParameter("format") ?: extractQueryParam(dataString, "format")
        )
        val type = (dataUri.getQueryParameter("type") ?: extractQueryParam(dataString, "type") ?: "pdf").lowercase()
        val jobName = dataUri.getQueryParameter("name")
            ?: extractQueryParam(dataString, "name")
            ?: if (format == "kot") "Kitchen Order Ticket" else "Customer Bill"

        Thread {
            try {
                when (type) {
                    "text" -> {
                        val text = dataUri.getQueryParameter("text")
                            ?: extractQueryParam(dataString, "text").orEmpty()
                        if (text.isNotBlank()) {
                            printTextJob(text, format, preferredTransport, receiptBtAddress, labelBtAddress, autoCut)
                        } else {
                            safeFinish(0)
                        }
                    }
                    "html" -> {
                        val htmlData = dataUri.getQueryParameter("data")
                            ?: extractQueryParam(dataString, "data").orEmpty()
                        if (htmlData.isNotBlank()) {
                            renderHtmlAndPrint(
                                htmlData = htmlData,
                                jobName = jobName,
                                format = format,
                                transport = preferredTransport,
                                receiptAddr = receiptBtAddress,
                                labelAddr = labelBtAddress,
                                threshold = threshold,
                                autoCut = autoCut
                            )
                        } else {
                            safeFinish(0)
                        }
                    }
                    "pdf", "image" -> {
                        val url = resolveHttpUrl(dataUri, dataString)
                        if (url.isNotBlank()) {
                            downloadAndPrint(
                                url = url,
                                jobName = jobName,
                                format = format,
                                transport = preferredTransport,
                                receiptAddr = receiptBtAddress,
                                labelAddr = labelBtAddress,
                                threshold = threshold,
                                autoCut = autoCut
                            )
                        } else {
                            safeFinish(0)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Background print job failed", e)
                safeFinish(0)
            }
        }.start()
    }

    private fun renderHtmlAndPrint(
        htmlData: String,
        jobName: String,
        format: String,
        transport: String,
        receiptAddr: String,
        labelAddr: String,
        threshold: Int,
        autoCut: Boolean
    ) {
        mainHandler.post {
            try {
                val webView = android.webkit.WebView(this)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.loadWithOverviewMode = false
                webView.settings.useWideViewPort = false

                val targetWidth = RECEIPT_80MM_WIDTH_DOTS
                webView.layout(0, 0, targetWidth, 2400)

                var captured = false
                webView.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView?, loadedUrl: String?) {
                        if (captured) return
                        captured = true
                        
                        // Safety guard: NEVER print the Login page UI under any circumstances
                        if (loadedUrl?.contains("/#/login", ignoreCase = true) == true ||
                            loadedUrl?.contains("login", ignoreCase = true) == true && loadedUrl?.contains("fatfox") == true
                        ) {
                            Log.e(TAG, "Rejected printing Login page UI ($loadedUrl)")
                            safeFinish(0)
                            return
                        }

                        mainHandler.postDelayed({
                            try {
                                view?.evaluateJavascript(
                                    "(function() { return document.body ? document.body.innerText : ''; })()"
                                ) { text ->
                                    if (text != null && (
                                        text.contains("Welcome to FatFox", ignoreCase = true) ||
                                        text.contains("Manage restaurant operations", ignoreCase = true) ||
                                        text.contains("Sign in to your account", ignoreCase = true)
                                    )) {
                                        Log.e(TAG, "Blocked login page text detected in WebView output")
                                        safeFinish(0)
                                        return@evaluateJavascript
                                    }

                                    try {
                                        val contentH = ((view?.contentHeight ?: 800) * (view?.scale ?: 1f)).toInt().coerceIn(300, 4000)
                                        val rawBitmap = Bitmap.createBitmap(targetWidth, contentH, Bitmap.Config.ARGB_8888)
                                        val canvas = android.graphics.Canvas(rawBitmap)
                                        canvas.drawColor(Color.WHITE)
                                        view?.draw(canvas)

                                        val cropped = cropAndScaleReceiptBitmap(rawBitmap, targetWidth)

                                        Thread {
                                            try {
                                                sendBitmapToPrinter(cropped, format, transport, receiptAddr, labelAddr, threshold, autoCut)
                                            } finally {
                                                if (cropped !== rawBitmap) rawBitmap.recycle()
                                                rawBitmap.recycle()
                                                safeFinish(300)
                                            }
                                        }.start()
                                    } catch (e: Exception) {
                                        Log.e(TAG, "WebView capture failed", e)
                                        safeFinish(0)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "WebView evaluateJavascript failed", e)
                                safeFinish(0)
                            }
                        }, 500)
                    }
                }
                webView.loadDataWithBaseURL("https://staging.fatfox.testfox.in", htmlData, "text/html", "UTF-8", null)
            } catch (e: Exception) {
                Log.e(TAG, "WebView init error", e)
                safeFinish(0)
            }
        }
    }

    private fun downloadAndPrint(
        url: String,
        jobName: String,
        format: String,
        transport: String,
        receiptAddr: String,
        labelAddr: String,
        threshold: Int,
        autoCut: Boolean
    ) {
        try {
            Log.i(TAG, "Downloading receipt from URL: $url")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "PrintFox/1.0 (Android)")
            conn.connect()
            if (conn.responseCode !in 200..299) {
                Log.e(TAG, "HTTP error: ${conn.responseCode}")
                safeFinish(0)
                return
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) {
                Log.e(TAG, "Downloaded empty receipt payload")
                safeFinish(0)
                return
            }

            val bodyStr = String(bytes, Charsets.UTF_8)
            if (bodyStr.contains("code\":404") || bodyStr.contains("Page not found")) {
                Log.e(TAG, "Receipt 404 response from server")
                safeFinish(0)
                return
            }

            val isPdf = bytes.size >= 4 &&
                bytes[0] == '%'.code.toByte() &&
                bytes[1] == 'P'.code.toByte() &&
                bytes[2] == 'D'.code.toByte() &&
                bytes[3] == 'F'.code.toByte()

            if (isPdf) {
                Log.i(TAG, "Rendering PDF receipt payload (${bytes.size} bytes)")
                val pdfFile = File(cacheDir, "deeplink_pdf_${System.currentTimeMillis()}.pdf")
                pdfFile.writeBytes(bytes)
                val bitmap = renderPdfToBitmap(pdfFile.absolutePath, RECEIPT_80MM_WIDTH_DOTS)
                if (bitmap != null) {
                    try {
                        sendBitmapToPrinter(bitmap, format, transport, receiptAddr, labelAddr, threshold, autoCut)
                    } finally {
                        bitmap.recycle()
                        pdfFile.delete()
                        safeFinish(300)
                    }
                } else {
                    pdfFile.delete()
                    safeFinish(0)
                }
            } else {
                Log.i(TAG, "Rendering HTML receipt payload (${bytes.size} bytes)")
                renderHtmlAndPrint(bodyStr, jobName, format, transport, receiptAddr, labelAddr, threshold, autoCut)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download and print failed", e)
            safeFinish(0)
        }
    }

    private fun renderPdfToBitmap(path: String, targetW: Int): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        return try {
            android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                android.graphics.pdf.PdfRenderer(pfd).use { renderer ->
                    if (renderer.pageCount == 0) return null
                    renderer.openPage(0).use { page ->
                        val aspect = page.height.toFloat() / page.width.toFloat()
                        val targetH = (targetW * aspect).toInt().coerceAtLeast(64)
                        val rawBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                        rawBitmap.eraseColor(Color.WHITE)
                        page.render(rawBitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        val cropped = cropAndScaleReceiptBitmap(rawBitmap, targetW)
                        if (cropped !== rawBitmap) rawBitmap.recycle()
                        cropped
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "renderPdfToBitmap failed", e)
            null
        }
    }

    private fun sendBitmapToPrinter(
        bitmap: Bitmap,
        format: String,
        transport: String,
        receiptAddr: String,
        labelAddr: String,
        threshold: Int,
        autoCut: Boolean
    ) {
        val role = if (format == "bill" || format == "label") {
            BluetoothPrinterHub.ROLE_LABEL
        } else {
            BluetoothPrinterHub.ROLE_RECEIPT
        }
        val targetRole = if (role == BluetoothPrinterHub.ROLE_LABEL && btHub.isRoleConnected(BluetoothPrinterHub.ROLE_LABEL)) {
            BluetoothPrinterHub.ROLE_LABEL
        } else {
            BluetoothPrinterHub.ROLE_RECEIPT
        }
        
        val preferredAddr = if (targetRole == BluetoothPrinterHub.ROLE_LABEL && labelAddr.isNotBlank()) labelAddr else receiptAddr
        val escJob = EscPosEncoder.printBitmapJob(bitmap, RECEIPT_80MM_WIDTH_DOTS, threshold, autoCut)

        val addressesToTry = mutableListOf<String>()
        if (preferredAddr.isNotBlank()) addressesToTry.add(preferredAddr)

        // Gather all bonded Bluetooth printers as fallbacks
        val bonded = btHub.listBondedPrinters()
        for (b in bonded) {
            val bAddr = b["address"]?.toString() ?: ""
            if (bAddr.isNotBlank() && !addressesToTry.contains(bAddr)) {
                addressesToTry.add(bAddr)
            }
        }

        Log.i(TAG, "Sending ESC/POS bitmap job (${escJob.size} bytes), candidate devices: $addressesToTry")
        var printed = false

        for (targetAddr in addressesToTry) {
            var connected = btHub.isRoleConnected(targetRole)
            if (!connected) {
                Log.i(TAG, "Connecting Bluetooth printer role=$targetRole to $targetAddr...")
                val connRes = btHub.connect(targetRole, targetAddr)
                connected = connRes["ok"] == true
                if (!connected) {
                    Log.w(TAG, "Could not connect to Bluetooth printer $targetAddr: ${connRes["error"]}")
                    continue
                }
            }

            var writeRes = btHub.write(targetRole, escJob)
            if (writeRes["ok"] != true) {
                Log.w(TAG, "Write attempt 1 failed to $targetAddr: ${writeRes["error"]}. Retrying in 100ms...")
                try { Thread.sleep(100) } catch (_: Exception) {}
                if (!btHub.isRoleConnected(targetRole)) {
                    btHub.connect(targetRole, targetAddr)
                }
                writeRes = btHub.write(targetRole, escJob)
            }

            if (writeRes["ok"] == true) {
                printed = true
                Log.i(TAG, "Successfully printed via Bluetooth to $targetAddr")
                break
            } else {
                Log.w(TAG, "Bluetooth write failed to $targetAddr after retry: ${writeRes["error"]}")
            }
        }

        if (!printed) {
            Log.i(TAG, "Attempting USB fallback print...")
            if (usbPrinter.isConnected() || usbPrinter.connect()["ok"] == true) {
                val res = usbPrinter.write(escJob)
                Log.i(TAG, "USB write result: $res")
            }
        }
    }

    private fun printTextJob(
        text: String,
        format: String,
        transport: String,
        receiptAddr: String,
        labelAddr: String,
        autoCut: Boolean
    ) {
        val role = if (format == "bill" || format == "label") BluetoothPrinterHub.ROLE_LABEL else BluetoothPrinterHub.ROLE_RECEIPT
        val addr = if (role == BluetoothPrinterHub.ROLE_LABEL && labelAddr.isNotBlank()) labelAddr else receiptAddr
        val lines = text.split('\n').ifEmpty { listOf(text) }
        val escJob = EscPosEncoder.textLinesJob(lines, autoCut)

        var printed = false
        if (transport.equals("bluetooth", true) || btHub.isRoleConnected(role) || addr.isNotBlank()) {
            if (!btHub.isRoleConnected(role) && addr.isNotBlank()) btHub.connect(role, addr)
            val res = btHub.write(role, escJob)
            printed = res["ok"] == true
        }

        if (!printed) {
            if (usbPrinter.isConnected() || usbPrinter.connect()["ok"] == true) {
                usbPrinter.write(escJob)
            }
        }
        safeFinish(300)
    }

    private fun cropAndScaleReceiptBitmap(src: Bitmap, targetWidth: Int = RECEIPT_80MM_WIDTH_DOTS): Bitmap {
        val w = src.width
        val h = src.height

        var minY = h
        var maxY = -1

        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = src.getPixel(x, y)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                val lum = (r * 30 + g * 59 + b * 11) / 100
                if (lum < 235) {
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (maxY < minY) return src

        val padY = 8
        val cropY = (minY - padY).coerceAtLeast(0)
        val cropH = (maxY - cropY + padY).coerceAtMost(h - cropY)

        if (cropH <= 10) return src

        val cropped = Bitmap.createBitmap(src, 0, cropY, w, cropH)
        val scale = targetWidth.toFloat() / w.toFloat()
        if (scale == 1.0f) return cropped
        val scaledH = (cropH * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(cropped, targetWidth, scaledH, true)

        if (cropped !== src) cropped.recycle()
        return scaled
    }

    private fun normalizeFormat(raw: String?): String {
        val v = (raw ?: "").trim().lowercase()
        return when {
            v.isEmpty() -> "kot"
            v == "bill" || v == "label" || v == "labels" || v.contains("bill") || v.contains("label") -> "bill"
            else -> "kot"
        }
    }

    private fun resolveHttpUrl(uri: Uri, dataString: String): String {
        val fromParam = uri.getQueryParameter("url") ?: extractQueryParam(dataString, "url") ?: ""
        val decoded = try { URLDecoder.decode(fromParam, "UTF-8") } catch (_: Exception) { fromParam }
        
        fun isValidPdfOrApiUrl(u: String): Boolean {
            if (!u.startsWith("http://") && !u.startsWith("https://")) return false
            if (u.contains("/#/") || u.contains("dineIn-food-categories") || u.contains("print-dinein")) {
                Log.w(TAG, "Rejected SPA hash route URL from deep link print: $u")
                return false
            }
            return true
        }

        if (isValidPdfOrApiUrl(decoded)) return decoded
        val marker = "url="
        val idx = dataString.indexOf(marker, ignoreCase = true)
        if (idx >= 0) {
            var rest = dataString.substring(idx + marker.length)
            val nameIdx = rest.indexOf("&name=")
            if (nameIdx > 0) rest = rest.substring(0, nameIdx)
            val typeIdx = rest.indexOf("&type=")
            if (typeIdx > 0) rest = rest.substring(0, typeIdx)
            val formatIdx = rest.indexOf("&format=")
            if (formatIdx > 0) rest = rest.substring(0, formatIdx)
            val decodedRest = try { URLDecoder.decode(rest, "UTF-8") } catch (_: Exception) { rest }
            if (isValidPdfOrApiUrl(decodedRest)) return decodedRest
        }
        return ""
    }

    private fun extractQueryParam(dataString: String, key: String): String? {
        val marker = "$key="
        val idx = dataString.indexOf(marker, ignoreCase = true)
        if (idx < 0) return null
        var value = dataString.substring(idx + marker.length)
        if (key != "data") {
            val nextAmp = value.indexOf('&')
            if (nextAmp >= 0) value = value.substring(0, nextAmp)
        } else {
            // If data parameter has trailing intent metadata (e.g. #Intent;...), strip it
            val intentIdx = value.indexOf("#Intent;")
            if (intentIdx >= 0) value = value.substring(0, intentIdx)
        }
        return safeUrlDecode(value)
    }

    private fun safeUrlDecode(input: String): String {
        return try {
            URLDecoder.decode(input, "UTF-8")
        } catch (_: Exception) {
            try {
                val sanitized = input.replace(Regex("""%(?![0-9a-fA-F]{2})"""), "%25")
                URLDecoder.decode(sanitized, "UTF-8")
            } catch (_: Exception) {
                input
            }
        }
    }
}


