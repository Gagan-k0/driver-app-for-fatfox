package com.foxwelai.driverforcanon.server

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.foxwelai.driverforcanon.bt.BluetoothPrinterHub
import com.foxwelai.driverforcanon.print.EscPosEncoder
import com.foxwelai.driverforcanon.usb.UsbPrinterHolder
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

class LocalHttpServer(private val context: Context, private val port: Int = 9123) {
    companion object {
        private const val TAG = "LocalHttpServer"
        private const val RECEIPT_80MM_WIDTH_DOTS = EscPosEncoder.WIDTH_80MM_DOTS

        @Volatile
        private var instance: LocalHttpServer? = null

        fun get(context: Context): LocalHttpServer {
            return instance ?: synchronized(this) {
                instance ?: LocalHttpServer(context.applicationContext).also { instance = it }
            }
        }
    }

    private val btHub by lazy { BluetoothPrinterHub.get(context) }
    private val usbPrinter by lazy { UsbPrinterHolder.get(context) }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false

    @Synchronized
    fun start() {
        if (isRunning) return
        isRunning = true
        Thread {
            try {
                serverSocket = ServerSocket(port)
                Log.i(TAG, "Local Print HTTP Server started on port $port")
                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    Thread { handleClient(clientSocket) }.start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error on port $port", e)
            } finally {
                isRunning = false
            }
        }.start()
    }

    @Synchronized
    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
            val out = PrintWriter(socket.getOutputStream())

            val requestLine = reader.readLine() ?: ""
            if (requestLine.isEmpty()) {
                socket.close()
                return
            }

            var contentLength = 0
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrEmpty()) break
                if (line!!.lowercase().startsWith("content-length:")) {
                    contentLength = line!!.substring(15).trim().toIntOrNull() ?: 0
                }
            }

            // CORS Preflight
            if (requestLine.startsWith("OPTIONS")) {
                out.print("HTTP/1.1 204 No Content\r\n")
                out.print("Access-Control-Allow-Origin: *\r\n")
                out.print("Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n")
                out.print("Access-Control-Allow-Headers: Content-Type, Authorization, Access-Control-Request-Private-Network\r\n")
                out.print("Access-Control-Allow-Private-Network: true\r\n")
                out.print("Connection: close\r\n\r\n")
                out.flush()
                socket.close()
                return
            }

            var bodyStr = ""
            if (contentLength > 0) {
                val charBuffer = CharArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val count = reader.read(charBuffer, readTotal, contentLength - readTotal)
                    if (count == -1) break
                    readTotal += count
                }
                bodyStr = String(charBuffer, 0, readTotal)
            }

            if (requestLine.startsWith("POST")) {
                var format = "kot"
                var name = "Kitchen Order Ticket"
                var htmlData = ""

                try {
                    val json = JSONObject(bodyStr)
                    format = json.optString("format", "kot")
                    name = json.optString("name", if (format == "bill") "Customer Bill" else "Kitchen Order Ticket")
                    htmlData = json.optString("data", "")
                } catch (_: Exception) {
                    val pairs = bodyStr.split("&")
                    for (pair in pairs) {
                        val kv = pair.split("=")
                        if (kv.size == 2) {
                            val k = kv[0].lowercase()
                            val v = URLDecoder.decode(kv[1], "UTF-8")
                            when (k) {
                                "format" -> format = v
                                "name" -> name = v
                                "data" -> htmlData = v
                            }
                        }
                    }
                }

                if (htmlData.isNotBlank()) {
                    renderAndPrintBackground(htmlData, format, name)
                }

                val responseJson = JSONObject().put("success", true).put("message", "Print job spooled silently").toString()
                val bytes = responseJson.toByteArray(Charsets.UTF_8)
                out.print("HTTP/1.1 200 OK\r\n")
                out.print("Content-Type: application/json; charset=utf-8\r\n")
                out.print("Access-Control-Allow-Origin: *\r\n")
                out.print("Access-Control-Allow-Private-Network: true\r\n")
                out.print("Content-Length: ${bytes.size}\r\n")
                out.print("Connection: close\r\n\r\n")
                out.print(responseJson)
                out.flush()
            } else {
                // Status check GET /
                val statusJson = JSONObject().put("status", "online").put("app", "PrintFox").toString()
                val bytes = statusJson.toByteArray(Charsets.UTF_8)
                out.print("HTTP/1.1 200 OK\r\n")
                out.print("Content-Type: application/json; charset=utf-8\r\n")
                out.print("Access-Control-Allow-Origin: *\r\n")
                out.print("Access-Control-Allow-Private-Network: true\r\n")
                out.print("Content-Length: ${bytes.size}\r\n")
                out.print("Connection: close\r\n\r\n")
                out.print(statusJson)
                out.flush()
            }
            socket.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client connection", e)
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /**
     * Render HTML in offscreen background WebView without launching ANY Activity or screen.
     * Prevents mobile screen from going black or locking when KOT/Bill is clicked in Chrome.
     */
    private fun renderAndPrintBackground(htmlData: String, format: String, jobName: String) {
        mainHandler.post {
            try {
                val prefs = context.getSharedPreferences("printfox_settings", Context.MODE_PRIVATE)
                val preferredTransport = prefs.getString("preferredTransport", "bluetooth") ?: "bluetooth"
                val receiptBtAddress = prefs.getString("receiptBtAddress", "") ?: ""
                val labelBtAddress = prefs.getString("labelBtAddress", "") ?: ""
                val threshold = prefs.getInt("threshold", 160)
                val autoCut = prefs.getBoolean("autoCut", true)

                val themedContext = android.view.ContextThemeWrapper(context.applicationContext, android.R.style.Theme_DeviceDefault)
                val webView = android.webkit.WebView(themedContext)
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
                                        Log.e(TAG, "Blocked login page text detected in background WebView output")
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
                                                sendBitmapToPrinterDirect(cropped, format, preferredTransport, receiptBtAddress, labelAddr = labelBtAddress, threshold = threshold, autoCut = autoCut)
                                            } finally {
                                                if (cropped !== rawBitmap) rawBitmap.recycle()
                                                rawBitmap.recycle()
                                            }
                                        }.start()
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Background WebView capture failed", e)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Background WebView evaluateJavascript failed", e)
                            }
                        }, 400)
                    }
                }
                webView.loadDataWithBaseURL("https://staging.fatfox.testfox.in", htmlData, "text/html", "UTF-8", null)
            } catch (e: Exception) {
                Log.e(TAG, "Background WebView init error", e)
            }
        }
    }

    private fun sendBitmapToPrinterDirect(
        bitmap: Bitmap,
        format: String,
        transport: String,
        receiptAddr: String,
        labelAddr: String,
        threshold: Int,
        autoCut: Boolean
    ) {
        val role = if (format == "bill" || format == "label") BluetoothPrinterHub.ROLE_LABEL else BluetoothPrinterHub.ROLE_RECEIPT
        val targetRole = if (role == BluetoothPrinterHub.ROLE_LABEL && btHub.isRoleConnected(BluetoothPrinterHub.ROLE_LABEL)) {
            BluetoothPrinterHub.ROLE_LABEL
        } else {
            BluetoothPrinterHub.ROLE_RECEIPT
        }

        val preferredAddr = if (targetRole == BluetoothPrinterHub.ROLE_LABEL && labelAddr.isNotBlank()) labelAddr else receiptAddr
        val escJob = EscPosEncoder.printBitmapJob(bitmap, RECEIPT_80MM_WIDTH_DOTS, threshold, autoCut)

        val addressesToTry = mutableListOf<String>()
        if (preferredAddr.isNotBlank()) addressesToTry.add(preferredAddr)

        val bonded = btHub.listBondedPrinters()
        for (b in bonded) {
            val bAddr = b["address"]?.toString() ?: ""
            if (bAddr.isNotBlank() && !addressesToTry.contains(bAddr)) {
                addressesToTry.add(bAddr)
            }
        }

        Log.i(TAG, "Background server sending ESC/POS bitmap job (${escJob.size} bytes), candidate devices: $addressesToTry")
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
                Log.i(TAG, "Successfully printed via background HTTP server to $targetAddr")
                break
            } else {
                Log.w(TAG, "Bluetooth write failed to $targetAddr after retry: ${writeRes["error"]}")
            }
        }

        if (!printed) {
            if (usbPrinter.isConnected() || usbPrinter.connect()["ok"] == true) {
                usbPrinter.write(escJob)
            }
        }
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
}
