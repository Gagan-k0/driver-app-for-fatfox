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
        const val WIDTH_58MM_DOTS = 384  // 384 px @ 203 DPI (58mm standard)

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
    private val wifiPrinter by lazy { com.foxwelai.driverforcanon.net.WifiPrinterClient() }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false

    private data class QueuedJob(
        val htmlData: String,
        val format: String,
        val jobName: String,
        val paperWidth: String,
        val kotAutoCut: Boolean
    )
    private val printQueue = java.util.concurrent.LinkedBlockingQueue<QueuedJob>()
    private val workerRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun ensurePrintWorker() {
        if (workerRunning.getAndSet(true)) return
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            Log.i(TAG, "FIFO print worker started")
            try {
                while (isRunning) {
                    val job = printQueue.take()
                    try { renderAndPrintSync(job) }
                    catch (t: Throwable) { Log.e(TAG, "FIFO job failed: ${job.jobName}", t) }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                workerRunning.set(false)
            }
        }.start()
    }

    @Synchronized
    fun start() {
        if (isRunning && serverSocket != null && serverSocket?.isClosed == false) return
        isRunning = true
        Thread {
            var attempt = 0
            while (isRunning && attempt < 5) {
                try {
                    val ss = ServerSocket()
                    ss.reuseAddress = true
                    ss.bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), port))
                    serverSocket = ss
                    Log.i(TAG, "Local Print HTTP Server started successfully on port $port (loopback 127.0.0.1)")
                    while (isRunning && !ss.isClosed) {
                        val clientSocket = ss.accept()
                        Thread { handleClient(clientSocket) }.start()
                    }
                    break
                } catch (e: Exception) {
                    attempt++
                    Log.e(TAG, "Server socket error on port $port (attempt $attempt/5)", e)
                    try { Thread.sleep(500) } catch (_: Exception) {}
                }
            }
            if (attempt >= 5) {
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

    private fun readHeaderLine(input: java.io.InputStream): String {
        val baos = java.io.ByteArrayOutputStream()
        var b: Int
        while (input.read().also { b = it } != -1) {
            if (b == '\n'.code) break
            if (b != '\r'.code) baos.write(b)
        }
        return baos.toString("UTF-8")
    }

    private fun handleClient(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val out = PrintWriter(socket.getOutputStream())

            val requestLine = readHeaderLine(input)
            if (requestLine.isEmpty()) {
                socket.close()
                return
            }

            var contentLength = 0
            while (true) {
                val line = readHeaderLine(input)
                if (line.isEmpty()) break
                if (line.lowercase().startsWith("content-length:")) {
                    contentLength = line.substring(15).trim().toIntOrNull() ?: 0
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

            var bodyBytes = ByteArray(0)
            if (contentLength > 0) {
                bodyBytes = ByteArray(contentLength)
                var bytesRead = 0
                while (bytesRead < contentLength) {
                    val count = input.read(bodyBytes, bytesRead, contentLength - bytesRead)
                    if (count == -1) break
                    bytesRead += count
                }
                if (bytesRead < contentLength) {
                    Log.w(TAG, "Truncated body: got $bytesRead bytes, expected $contentLength")
                    bodyBytes = bodyBytes.copyOf(bytesRead)
                }
            }
            val bodyStr = if (bodyBytes.isNotEmpty()) String(bodyBytes, Charsets.UTF_8) else ""

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
                    val postedPaper = try {
                        val json2 = JSONObject(bodyStr)
                        json2.optString("paperWidth", "80mm").trim().lowercase()
                    } catch (_: Exception) { "80mm" }
                    val paperWidth = if (postedPaper == "58mm") "58mm" else "80mm"

                    val kotAutoCut = try {
                        val json3 = JSONObject(bodyStr)
                        if (json3.has("kotAutoCut")) json3.getBoolean("kotAutoCut") else true
                    } catch (_: Exception) { true }

                    ensurePrintWorker()
                    printQueue.offer(QueuedJob(htmlData, format, name, paperWidth, kotAutoCut))
                    Log.i(TAG, "Queued: format=$format, paper=$paperWidth, autoCut=$kotAutoCut, depth=${printQueue.size}")
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
    private fun renderAndPrintSync(job: QueuedJob) {
        val latch = java.util.concurrent.CountDownLatch(1)
        mainHandler.post {
            try {
                try {
                    android.webkit.WebView.enableSlowWholeDocumentDraw()
                } catch (_: Exception) {}

                val prefs = context.getSharedPreferences("printfox_settings", Context.MODE_PRIVATE)
                val preferredTransport = prefs.getString("preferredTransport", null)
                    ?: prefs.getString("transport", "bluetooth") ?: "bluetooth"
                val receiptBtAddress = prefs.getString("receiptBtAddress", null)
                    ?: prefs.getString("receipt_bt_address", "") ?: ""
                val labelBtAddress = prefs.getString("labelBtAddress", null)
                    ?: prefs.getString("label_bt_address", "") ?: ""
                val wifiHost = prefs.getString("wifi_host", "") ?: ""
                val wifiPort = prefs.getInt("wifi_port", 9100)
                val threshold = prefs.getInt("threshold", 160)
                val prefsAutoCut = if (prefs.contains("autoCut")) prefs.getBoolean("autoCut", true) else prefs.getBoolean("auto_cut", true)
                val autoCut = if (job.format == "kot") job.kotAutoCut else prefsAutoCut
                val targetWidth = if (job.paperWidth == "58mm") WIDTH_58MM_DOTS else RECEIPT_80MM_WIDTH_DOTS

                val themedContext = android.view.ContextThemeWrapper(context.applicationContext, android.R.style.Theme_DeviceDefault)
                val parent = android.widget.FrameLayout(themedContext)
                val webView = android.webkit.WebView(themedContext)
                webView.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                parent.addView(webView, android.view.ViewGroup.LayoutParams(targetWidth, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))

                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.loadWithOverviewMode = false
                webView.settings.useWideViewPort = false

                val measureSpecW = android.view.View.MeasureSpec.makeMeasureSpec(targetWidth, android.view.View.MeasureSpec.EXACTLY)
                val measureSpecH = android.view.View.MeasureSpec.makeMeasureSpec(8000, android.view.View.MeasureSpec.AT_MOST)
                parent.measure(measureSpecW, measureSpecH)
                parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)
                webView.measure(measureSpecW, measureSpecH)
                webView.layout(0, 0, webView.measuredWidth, webView.measuredHeight)

                var captured = false
                webView.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView?, loadedUrl: String?) {
                        if (captured) return
                        captured = true

                        Thread {
                            try {
                                var attempt = 0
                                var rawBitmap: Bitmap? = null
                                while (attempt < 5) {
                                    Thread.sleep(400)
                                    val contentH = ((view!!.contentHeight * (view.scale ?: 1f)).toInt()).coerceIn(300, 4000)
                                    val specW = android.view.View.MeasureSpec.makeMeasureSpec(targetWidth, android.view.View.MeasureSpec.EXACTLY)
                                    val specH = android.view.View.MeasureSpec.makeMeasureSpec(contentH, android.view.View.MeasureSpec.EXACTLY)
                                    val bmp = Bitmap.createBitmap(targetWidth, contentH, Bitmap.Config.ARGB_8888)
                                    var isBlank = true
                                    
                                    val syncLatch = java.util.concurrent.CountDownLatch(1)
                                    mainHandler.post {
                                        try {
                                            parent.measure(specW, specH)
                                            parent.layout(0, 0, targetWidth, contentH)
                                            view.measure(specW, specH)
                                            view.layout(0, 0, targetWidth, contentH)
                                            
                                            val canvas = android.graphics.Canvas(bmp)
                                            canvas.drawColor(Color.WHITE)
                                            view.draw(canvas)
                                        } finally {
                                            syncLatch.countDown()
                                        }
                                    }
                                    syncLatch.await(2, java.util.concurrent.TimeUnit.SECONDS)
                                    
                                    val checkH = contentH.coerceAtMost(200)
                                    for (y in 0 until checkH step 2) {
                                        for (x in 0 until targetWidth step 4) {
                                            val c = bmp.getPixel(x, y)
                                            val lum = (Color.red(c)*30 + Color.green(c)*59 + Color.blue(c)*11)/100
                                            if (lum < threshold) {
                                                isBlank = false
                                                break
                                            }
                                        }
                                        if (!isBlank) break
                                    }
                                    
                                    if (isBlank && attempt < 4) {
                                        bmp.recycle()
                                        attempt++
                                        continue
                                    }
                                    rawBitmap = bmp
                                    break
                                }
                                
                                if (rawBitmap != null) {
                                    val cropped = cropAndScaleReceiptBitmap(rawBitmap, targetWidth, threshold)
                                    sendBitmapToPrinterDirect(
                                        bitmap = cropped,
                                        format = job.format,
                                        transport = preferredTransport,
                                        receiptAddr = receiptBtAddress,
                                        labelAddr = labelBtAddress,
                                        wifiHost = wifiHost,
                                        wifiPort = wifiPort,
                                        threshold = threshold,
                                        autoCut = autoCut
                                    )
                                    if (cropped !== rawBitmap) cropped.recycle()
                                    rawBitmap.recycle()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Background WebView capture failed", e)
                            } finally {
                                latch.countDown()
                            }
                        }.start()
                    }
                }
                webView.loadDataWithBaseURL("https://staging.fatfox.testfox.in", job.htmlData, "text/html", "UTF-8", null)
            } catch (e: Exception) {
                Log.e(TAG, "Background WebView init error", e)
                latch.countDown()
            }
        }
        latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun sendBitmapToPrinterDirect(
        bitmap: Bitmap,
        format: String,
        transport: String,
        receiptAddr: String,
        labelAddr: String,
        wifiHost: String,
        wifiPort: Int,
        threshold: Int,
        autoCut: Boolean
    ) {
        val cleanTransport = transport.trim().lowercase()
        val rollWidth = if (bitmap.width <= (WIDTH_58MM_DOTS + 16)) WIDTH_58MM_DOTS else RECEIPT_80MM_WIDTH_DOTS
        val escJob = EscPosEncoder.printBitmapJob(bitmap, rollWidth, threshold, autoCut)
        var printed = false

        Log.i(TAG, "sendBitmapToPrinterDirect: transport=$cleanTransport, format=$format, receiptAddr=$receiptAddr, labelAddr=$labelAddr, wifiHost=$wifiHost:$wifiPort")

        val needsBt = cleanTransport != "wifi" && cleanTransport != "usb"
        if (needsBt && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val btPermOk = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.BLUETOOTH_CONNECT
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!btPermOk) {
                Log.e(TAG, "BLUETOOTH_CONNECT permission missing in server context. Cannot print via Bluetooth. Grant permission in app UI.")
                if (usbPrinter.isConnected() || usbPrinter.connect()["ok"] == true) {
                    val usbRes = usbPrinter.write(escJob)
                    if (usbRes["ok"] == true) {
                        Log.i(TAG, "Successfully printed via USB fallback due to missing BT permission")
                    }
                }
                return
            }
        }

        if (cleanTransport == "wifi") {
            if (wifiHost.isNotBlank()) {
                wifiPrinter.host = wifiHost
                wifiPrinter.port = wifiPort
                Log.i(TAG, "Sending print job over Wi-Fi transport to $wifiHost:$wifiPort...")
                val wifiRes = wifiPrinter.write(escJob)
                if (wifiRes["ok"] == true) {
                    printed = true
                    Log.i(TAG, "Successfully printed via background HTTP server over Wi-Fi to $wifiHost:$wifiPort")
                } else {
                    Log.w(TAG, "Wi-Fi print failed to $wifiHost:$wifiPort: ${wifiRes["error"]}")
                }
            } else {
                Log.w(TAG, "Wi-Fi transport requested but wifiHost is not configured")
            }
        } else if (cleanTransport == "usb") {
            if (usbPrinter.isConnected() || usbPrinter.connect()["ok"] == true) {
                val usbRes = usbPrinter.write(escJob)
                if (usbRes["ok"] == true) {
                    printed = true
                    Log.i(TAG, "Successfully printed via background HTTP server over USB")
                } else {
                    Log.w(TAG, "USB write failed: ${usbRes["error"]}")
                }
            }
        } else {
            // Bluetooth transport (default)
            val role = if (format == "bill" || format == "label") BluetoothPrinterHub.ROLE_LABEL else BluetoothPrinterHub.ROLE_RECEIPT
            val targetRole = if (role == BluetoothPrinterHub.ROLE_LABEL && btHub.isRoleConnected(BluetoothPrinterHub.ROLE_LABEL)) {
                BluetoothPrinterHub.ROLE_LABEL
            } else {
                BluetoothPrinterHub.ROLE_RECEIPT
            }

            val preferredAddr = if (targetRole == BluetoothPrinterHub.ROLE_LABEL && labelAddr.isNotBlank()) labelAddr else receiptAddr
            val addressesToTry = mutableListOf<String>()
            if (preferredAddr.isNotBlank()) {
                addressesToTry.add(preferredAddr)
            } else {
                val bonded = btHub.listBondedPrinters()
                for (b in bonded) {
                    val bAddr = b["address"]?.toString() ?: ""
                    if (bAddr.isNotBlank() && !addressesToTry.contains(bAddr)) {
                        addressesToTry.add(bAddr)
                    }
                }
            }

            Log.i(TAG, "Background server sending Bluetooth ESC/POS bitmap job (${escJob.size} bytes), role=$targetRole, candidates=$addressesToTry")

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
                    Log.w(TAG, "Write attempt 1 failed to $targetAddr: ${writeRes["error"]}. Reconnecting and retrying...")
                    try { Thread.sleep(100) } catch (_: Exception) {}
                    btHub.disconnect(targetRole)
                    val connRes = btHub.connect(targetRole, targetAddr)
                    if (connRes["ok"] == true) {
                        writeRes = btHub.write(targetRole, escJob)
                    }
                }

                if (writeRes["ok"] == true) {
                    printed = true
                    Log.i(TAG, "Successfully printed via background HTTP server to $targetAddr")
                    break
                } else {
                    Log.w(TAG, "Bluetooth write failed to $targetAddr after reconnect retry: ${writeRes["error"]}")
                    btHub.disconnect(targetRole)
                }
            }
        }

        if (!printed && cleanTransport != "usb") {
            Log.i(TAG, "Primary transport ($cleanTransport) failed. Checking USB fallback...")
            if (usbPrinter.isConnected() || usbPrinter.connect()["ok"] == true) {
                val usbRes = usbPrinter.write(escJob)
                if (usbRes["ok"] == true) {
                    Log.i(TAG, "Successfully printed via USB fallback")
                }
            }
        }
    }

    private fun cropAndScaleReceiptBitmap(src: Bitmap, targetWidth: Int = RECEIPT_80MM_WIDTH_DOTS, encoderThreshold: Int): Bitmap {
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
                if (lum < encoderThreshold) {
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (maxY < minY) {
            Log.w(TAG, "cropAndScaleReceiptBitmap: bitmap is ALL-WHITE (no dark pixels detected) - WebView render may be blank")
            return src
        }

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
