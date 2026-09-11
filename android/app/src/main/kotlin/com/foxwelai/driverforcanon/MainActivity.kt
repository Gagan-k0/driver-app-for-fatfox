package com.foxwelai.driverforcanon

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import com.foxwelai.driverforcanon.print.Lp46PrintService
import com.foxwelai.driverforcanon.print.CanonUfrEncoder
import com.foxwelai.driverforcanon.print.ZplEncoder
import com.foxwelai.driverforcanon.print.EscPosEncoder
import com.foxwelai.driverforcanon.net.WifiPrinterClient
import com.foxwelai.driverforcanon.bt.BluetoothPrinterHub
import com.foxwelai.driverforcanon.usb.UsbPrinterHolder
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.FileOutputStream

class MainActivity : FlutterActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val METHOD_CHANNEL = "com.foxwelai.printfox/usb"
        private const val EVENT_CHANNEL = "com.foxwelai.printfox/events"

        /** 80mm thermal receipt @ 203 DPI (printable ~72mm). */
        private const val RECEIPT_80MM_WIDTH_DOTS = EscPosEncoder.WIDTH_80MM_DOTS
        /** A5 landscape kept for legacy USB Canon path. */
        private const val A5_LANDSCAPE_WIDTH_DOTS = 1678
        private const val A5_LANDSCAPE_HEIGHT_DOTS = 1183
        /** 4×6 shipping label @ 203 DPI. */
        private const val LABEL_4X6_WIDTH_DOTS = 812
        private const val LABEL_4X6_HEIGHT_DOTS = 1218
        private const val PAPER_SETTINGS_VERSION = 5
        private const val DEEP_LINK_SCHEME = "printfox"
        private const val BT_PERM_REQ = 4101
    }

    private val usbPrinter by lazy { UsbPrinterHolder.get(applicationContext) }
    private val wifiPrinter = WifiPrinterClient()
    private val btHub by lazy { BluetoothPrinterHub.get(applicationContext) }
    private var eventSink: EventChannel.EventSink? = null
    private var pendingJobPath: String? = null
    private var pendingJobName: String? = null
    private var pendingJobFormat: String = "a5"
    private var preferredTransport: String = "bluetooth"
    private var autoCut: Boolean = true
    private var receiptBtAddress: String = ""
    private var labelBtAddress: String = ""
    @Volatile private var deepLinkInProgress: Boolean = false
    @Volatile private var lastDeepLinkError: String? = null
    private var pendingBtResult: MethodChannel.Result? = null
    private var pendingBtAction: (() -> Unit)? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        loadPersistedSettings()
        try {
            com.foxwelai.driverforcanon.server.PrintFoxServerService.startServer(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Could not start PrintFoxServerService", e)
        }

        usbPrinter.onDeviceChanged = {
            runOnUiThread {
                eventSink?.success(
                    mapOf(
                        "type" to "deviceChanged",
                        "status" to usbPrinter.status()
                    )
                )
            }
        }
        usbPrinter.onPermissionResult = { granted ->
            runOnUiThread {
                eventSink?.success(
                    mapOf(
                        "type" to "permission",
                        "granted" to granted,
                        "status" to usbPrinter.status()
                    )
                )
            }
        }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL)
            .setMethodCallHandler { call, result ->
                try {
                    when (call.method) {
                        "getStatus" -> result.success(mergedStatus())
                        "diagnose" -> {
                            if (preferredTransport == "wifi") {
                                result.success(wifiPrinter.testConnection() + wifiPrinter.status())
                                return@setMethodCallHandler
                            }
                            if (!usbPrinter.isConnected()) {
                                val connect = usbPrinter.connect()
                                if (connect["ok"] != true) {
                                    result.success(connect)
                                    return@setMethodCallHandler
                                }
                            }
                            result.success(usbPrinter.diagnose())
                        }
                        "connect" -> {
                            result.success(
                                if (preferredTransport == "wifi") {
                                    wifiPrinter.testConnection()
                                } else {
                                    usbPrinter.connect()
                                }
                            )
                        }
                        "disconnect" -> {
                            usbPrinter.close()
                            result.success(mapOf("ok" to true))
                        }
                        "listDevices" -> result.success(usbPrinter.listPrinters())
                        "connectWifi" -> {
                            wifiPrinter.host = call.argument<String>("host")?.trim().orEmpty()
                            wifiPrinter.port = call.argument<Int>("port") ?: 9100
                            wifiPrinter.useGdi = call.argument<Boolean>("useGdi") != false
                            preferredTransport = "wifi"
                            persistTransportPrefs()
                            result.success(wifiPrinter.testConnection() + mapOf("transport" to "wifi"))
                        }
                        "testWifi" -> {
                            wifiPrinter.host = call.argument<String>("host")?.trim().orEmpty()
                            wifiPrinter.port = call.argument<Int>("port") ?: 9100
                            wifiPrinter.useGdi = call.argument<Boolean>("useGdi") != false
                            result.success(wifiPrinter.testConnection())
                        }
                        "listBluetoothPrinters" -> {
                            withBluetoothPermission(result) {
                                result.success(btHub.listBondedPrinters())
                            }
                        }
                        "getBluetoothStatus" -> result.success(btHub.status())
                        "connectBluetooth" -> {
                            val role = call.argument<String>("role") ?: "receipt"
                            val address = call.argument<String>("address")?.trim().orEmpty()
                            withBluetoothPermission(result) {
                                val res = btHub.connect(role, address)
                                if (res["ok"] == true) {
                                    preferredTransport = "bluetooth"
                                    if (role.equals("receipt", true)) {
                                        receiptBtAddress = address
                                    } else {
                                        labelBtAddress = address
                                    }
                                    persistTransportPrefs()
                                }
                                result.success(res)
                            }
                        }
                        "disconnectBluetooth" -> {
                            val role = call.argument<String>("role")
                            result.success(btHub.disconnect(role))
                        }
                        "connectAllBluetooth" -> {
                            withBluetoothPermission(result) {
                                val out = mutableMapOf<String, Any?>("ok" to true)
                                if (receiptBtAddress.isNotBlank()) {
                                    out["receipt"] = btHub.connect(
                                        BluetoothPrinterHub.ROLE_RECEIPT,
                                        receiptBtAddress
                                    )
                                }
                                if (labelBtAddress.isNotBlank()) {
                                    out["label"] = btHub.connect(
                                        BluetoothPrinterHub.ROLE_LABEL,
                                        labelBtAddress
                                    )
                                }
                                preferredTransport = "bluetooth"
                                persistTransportPrefs()
                                result.success(out)
                            }
                        }
                        "printSheetPng" -> {
                            val path = call.argument<String>("path")
                                ?: return@setMethodCallHandler result.error("arg", "path required", null)
                            val transport = call.argument<String>("transport") ?: preferredTransport
                            val threshold = call.argument<Int>("threshold") ?: 160
                            runPrintAsync(result) {
                                printSheetPngPath(path, transport, threshold)
                            }
                        }
                        "writeBytes" -> {
                            val bytes = call.argument<ByteArray>("bytes")
                                ?: return@setMethodCallHandler result.error("arg", "bytes required", null)
                            result.success(usbPrinter.write(bytes))
                        }
                        "writeText" -> {
                            val text = call.argument<String>("text")
                                ?: return@setMethodCallHandler result.error("arg", "text required", null)
                            result.success(usbPrinter.writeText(text))
                        }
                        "printImageFile" -> {
                            val path = call.argument<String>("path")
                                ?: return@setMethodCallHandler result.error("arg", "path required", null)
                            val format = normalizeFormat(call.argument<String>("format"))
                            val transport = call.argument<String>("transport") ?: preferredTransport
                            val threshold = call.argument<Int>("threshold") ?: 160
                            val pageIndex = call.argument<Int>("pageIndex") ?: 0
                            runPrintAsync(result) {
                                printJobFile(path, format, transport, threshold, pageIndex, isPdf = false)
                            }
                        }
                        "printPdfFile" -> {
                            val path = call.argument<String>("path")
                                ?: return@setMethodCallHandler result.error("arg", "path required", null)
                            val format = normalizeFormat(call.argument<String>("format"))
                            val transport = call.argument<String>("transport") ?: preferredTransport
                            val threshold = call.argument<Int>("threshold") ?: 160
                            val pageIndex = call.argument<Int>("pageIndex") ?: 0
                            runPrintAsync(result) {
                                printJobFile(path, format, transport, threshold, pageIndex, isPdf = true)
                            }
                        }
                        "printTestLabel" -> {
                            val mode = call.argument<String>("mode") ?: "ufr"
                            val format = normalizeFormat(call.argument<String>("format"))
                            val transport = call.argument<String>("transport") ?: preferredTransport
                            runPrintAsync(result) {
                                printTestJob(format, transport, mode)
                            }
                        }
                        "printText" -> {
                            val text = call.argument<String>("text")
                                ?: return@setMethodCallHandler result.error("arg", "text required", null)
                            val format = normalizeFormat(call.argument<String>("format"))
                            val transport = call.argument<String>("transport") ?: preferredTransport
                            runPrintAsync(result) {
                                printTextJob(text, format, transport)
                            }
                        }
                        "renderPdfPreview" -> {
                            val path = call.argument<String>("path")
                                ?: return@setMethodCallHandler result.error("arg", "path required", null)
                            val width = call.argument<Int>("widthDots") ?: A5_LANDSCAPE_WIDTH_DOTS
                            val height = call.argument<Int>("heightDots") ?: A5_LANDSCAPE_HEIGHT_DOTS
                            val pageIndex = call.argument<Int>("pageIndex") ?: 0
                            result.success(renderPdfPreview(path, width, height, pageIndex))
                        }
                        "saveLabelSettings" -> {
                            val prefs = getSharedPreferences("printfox_settings", MODE_PRIVATE)
                            val format = normalizeFormat(call.argument<String>("format"))
                            val defaults = defaultsForFormat(format)
                            wifiPrinter.host = call.argument<String>("wifiHost")?.trim().orEmpty()
                            wifiPrinter.port = call.argument<Int>("wifiPort") ?: 9100
                            wifiPrinter.useGdi = call.argument<Boolean>("wifiUseGdi") != false
                            val transportArg = call.argument<String>("transport")
                            if (!transportArg.isNullOrBlank()) {
                                preferredTransport = transportArg.lowercase()
                            }
                            autoCut = call.argument<Boolean>("autoCut") != false
                            receiptBtAddress =
                                call.argument<String>("receiptBtAddress")?.trim().orEmpty()
                            labelBtAddress =
                                call.argument<String>("labelBtAddress")?.trim().orEmpty()
                            prefs.edit()
                                .putInt("label_width_dots", call.argument<Int>("widthDots") ?: defaults.first)
                                .putInt("label_height_dots", call.argument<Int>("heightDots") ?: defaults.second)
                                .putInt("threshold", call.argument<Int>("threshold") ?: 160)
                                .putString("print_format", format)
                                .putString("transport", preferredTransport)
                                .putString("preferredTransport", preferredTransport)
                                .putString("sheet_size", call.argument<String>("sheetSize") ?: "a4")
                                .putInt("sheet_rows", call.argument<Int>("rows") ?: 4)
                                .putInt("sheet_columns", call.argument<Int>("columns") ?: 2)
                                .putFloat("label_width_mm", (call.argument<Number>("labelWidthMm")?.toFloat() ?: 99f))
                                .putFloat("label_height_mm", (call.argument<Number>("labelHeightMm")?.toFloat() ?: 67f))
                                .putFloat("gap_h_mm", (call.argument<Number>("gapHorizontalMm")?.toFloat() ?: 2f))
                                .putFloat("gap_v_mm", (call.argument<Number>("gapVerticalMm")?.toFloat() ?: 2f))
                                .putFloat("margin_left_mm", (call.argument<Number>("marginLeftMm")?.toFloat() ?: 5f))
                                .putFloat("margin_top_mm", (call.argument<Number>("marginTopMm")?.toFloat() ?: 10f))
                                .putInt("start_index", call.argument<Int>("startIndex") ?: 0)
                                .putString("wifi_host", wifiPrinter.host)
                                .putInt("wifi_port", wifiPrinter.port)
                                .putBoolean("wifi_use_gdi", wifiPrinter.useGdi)
                                .putBoolean("auto_cut", autoCut)
                                .putBoolean("autoCut", autoCut)
                                .putString("receipt_bt_address", receiptBtAddress)
                                .putString("receiptBtAddress", receiptBtAddress)
                                .putString("label_bt_address", labelBtAddress)
                                .putString("labelBtAddress", labelBtAddress)
                                .putInt("paper_settings_version", PAPER_SETTINGS_VERSION)
                                .apply()
                            result.success(mapOf("ok" to true))
                        }
                        "getLabelSettings" -> {
                            loadPersistedSettings()
                            val prefs = getSharedPreferences("printfox_settings", MODE_PRIVATE)
                            val format = normalizeFormat(prefs.getString("print_format", "a5"))
                            val defaults = defaultsForFormat(format)
                            result.success(
                                mapOf(
                                    "widthDots" to prefs.getInt(
                                        "label_width_dots",
                                        RECEIPT_80MM_WIDTH_DOTS
                                    ),
                                    "heightDots" to prefs.getInt("label_height_dots", defaults.second),
                                    "threshold" to prefs.getInt("threshold", 160),
                                    "format" to format,
                                    "transport" to preferredTransport,
                                    "sheetSize" to prefs.getString("sheet_size", "a4"),
                                    "rows" to prefs.getInt("sheet_rows", 4),
                                    "columns" to prefs.getInt("sheet_columns", 2),
                                    "labelWidthMm" to prefs.getFloat("label_width_mm", 99f),
                                    "labelHeightMm" to prefs.getFloat("label_height_mm", 67f),
                                    "gapHorizontalMm" to prefs.getFloat("gap_h_mm", 2f),
                                    "gapVerticalMm" to prefs.getFloat("gap_v_mm", 2f),
                                    "marginLeftMm" to prefs.getFloat("margin_left_mm", 5f),
                                    "marginTopMm" to prefs.getFloat("margin_top_mm", 10f),
                                    "startIndex" to prefs.getInt("start_index", 0),
                                    "wifiHost" to wifiPrinter.host,
                                    "wifiPort" to wifiPrinter.port,
                                    "wifiUseGdi" to wifiPrinter.useGdi,
                                    "autoCut" to autoCut,
                                    "receiptBtAddress" to receiptBtAddress,
                                    "labelBtAddress" to labelBtAddress,
                                    "bluetooth" to btHub.status()
                                )
                            )
                        }
                        "getPendingPrintJob" -> {
                            val path = pendingJobPath ?: Lp46PrintService.lastJobPath
                            val name = pendingJobName ?: Lp46PrintService.lastJobName
                            if (path != null && File(path).exists()) {
                                result.success(
                                    mapOf(
                                        "path" to path,
                                        "name" to (name ?: "Print job"),
                                        "format" to pendingJobFormat,
                                        "ready" to true
                                    )
                                )
                                // Keep until Flutter opens preview (cleared by clearPendingPrintJob)
                            } else if (deepLinkInProgress) {
                                result.success(
                                    mapOf(
                                        "ready" to false,
                                        "loading" to true,
                                        "name" to (pendingJobName ?: "Website print"),
                                        "format" to pendingJobFormat
                                    )
                                )
                            } else {
                                result.success(null)
                            }
                        }
                        "clearPendingPrintJob" -> {
                            pendingJobPath = null
                            pendingJobName = null
                            pendingJobFormat = "a5"
                            Lp46PrintService.lastJobPath = null
                            Lp46PrintService.lastJobName = null
                            result.success(mapOf("ok" to true))
                        }
                        "getDeepLinkStatus" -> {
                            result.success(
                                mapOf(
                                    "inProgress" to deepLinkInProgress,
                                    "lastError" to lastDeepLinkError,
                                    "pendingPath" to pendingJobPath,
                                    "pendingName" to pendingJobName
                                )
                            )
                        }
                        "isPrintServiceEnabled" -> {
                            result.success(Lp46PrintService.isEnabled(this))
                        }
                        "getPrintServiceStatus" -> {
                            result.success(Lp46PrintService.statusDetails(this))
                        }
                        "openPrintSettings" -> {
                            Lp46PrintService.openPrintSettings(this)
                            result.success(mapOf("ok" to true))
                        }
                        "returnToCaller" -> {
                            // Restore the previous task (usually Chrome / the website).
                            moveTaskToBack(true)
                            result.success(mapOf("ok" to true))
                        }
                        else -> result.notImplemented()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "method ${call.method} failed", e)
                    result.error("error", e.message, null)
                }
            }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    eventSink = events
                    // Deliver any job that arrived before Flutter was ready.
                    val path = pendingJobPath ?: Lp46PrintService.lastJobPath
                    val name = pendingJobName ?: Lp46PrintService.lastJobName
                    if (path != null && File(path).exists()) {
                        events?.success(
                            mapOf(
                                "type" to "printJob",
                                "path" to path,
                                "name" to (name ?: "Print job"),
                                "format" to pendingJobFormat
                            )
                        )
                    } else if (deepLinkInProgress) {
                        events?.success(
                            mapOf(
                                "type" to "deepLinkLoading",
                                "name" to (name ?: "Website print"),
                                "format" to pendingJobFormat
                            )
                        )
                    }
                    lastDeepLinkError?.let { err ->
                        events?.success(
                            mapOf(
                                "type" to "deepLinkError",
                                "error" to err
                            )
                        )
                    }
                }

                override fun onCancel(arguments: Any?) {
                    eventSink = null
                }
            })
    }

    private fun ensureConnectedOrError(result: MethodChannel.Result): Boolean? {
        if (usbPrinter.isConnected()) return true
        val connect = usbPrinter.connect()
        if (connect["ok"] == true) return true
        result.success(connect)
        return null
    }

    private fun runPrintAsync(
        result: MethodChannel.Result,
        block: () -> Map<String, Any?>
    ) {
        Thread {
            try {
                val payload = block()
                runOnUiThread { result.success(payload) }
            } catch (e: Exception) {
                Log.e(TAG, "async print failed", e)
                runOnUiThread { result.error("error", e.message, null) }
            }
        }.start()
    }

    private fun printPdfViaWifi(
        path: String,
        width: Int,
        height: Int,
        pageIndex: Int
    ): Map<String, Any?> {
        if (!wifiPrinter.configured()) {
            return mapOf("ok" to false, "error" to "Set Wi‑Fi printer IP in settings")
        }
        // Prefer sending original PDF bytes for GDI/network lasers (Blaze SD-30NW).
        val file = File(path)
        if (!file.exists()) return mapOf("ok" to false, "error" to "PDF not found")
        if (wifiPrinter.useGdi && path.lowercase().endsWith(".pdf")) {
            val sent = wifiPrinter.writeFile(path)
            if (sent["ok"] == true) return sent + mapOf("gdi" to true, "mode" to "pdf_raw")
        }
        // Fallback: rasterize then PDF-wrap
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val index = pageIndex.coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))
                renderer.openPage(index).use { page ->
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    return try {
                        wifiPrinter.printBitmapGdi(bitmap, file.name)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    private fun loadPersistedSettings() {
        val prefs = getSharedPreferences("printfox_settings", MODE_PRIVATE)
        preferredTransport = prefs.getString("preferredTransport", null)
            ?: prefs.getString("transport", "bluetooth") ?: "bluetooth"
        wifiPrinter.host = prefs.getString("wifi_host", "") ?: ""
        wifiPrinter.port = prefs.getInt("wifi_port", 9100)
        wifiPrinter.useGdi = prefs.getBoolean("wifi_use_gdi", true)
        autoCut = if (prefs.contains("autoCut")) prefs.getBoolean("autoCut", true) else prefs.getBoolean("auto_cut", true)
        receiptBtAddress = prefs.getString("receiptBtAddress", null)
            ?: prefs.getString("receipt_bt_address", "") ?: ""
        labelBtAddress = prefs.getString("labelBtAddress", null)
            ?: prefs.getString("label_bt_address", "") ?: ""
    }

    private fun persistTransportPrefs() {
        getSharedPreferences("printfox_settings", MODE_PRIVATE).edit()
            .putString("transport", preferredTransport)
            .putString("preferredTransport", preferredTransport)
            .putString("wifi_host", wifiPrinter.host)
            .putInt("wifi_port", wifiPrinter.port)
            .putBoolean("wifi_use_gdi", wifiPrinter.useGdi)
            .putBoolean("auto_cut", autoCut)
            .putBoolean("autoCut", autoCut)
            .putString("receipt_bt_address", receiptBtAddress)
            .putString("receiptBtAddress", receiptBtAddress)
            .putString("label_bt_address", labelBtAddress)
            .putString("labelBtAddress", labelBtAddress)
            .apply()
    }

    private fun mergedStatus(): Map<String, Any?> {
        loadPersistedSettings()
        val bt = btHub.status()
        return when (preferredTransport) {
            "wifi" -> {
                val wifi = wifiPrinter.status().toMutableMap()
                val ok = wifiPrinter.configured()
                wifi["connected"] = ok
                wifi["hasDevice"] = ok
                wifi["devices"] = emptyList<Map<String, Any>>()
                wifi["bluetooth"] = bt
                wifi
            }
            "bluetooth" -> {
                val receiptOk = btHub.isRoleConnected(BluetoothPrinterHub.ROLE_RECEIPT)
                val labelOk = btHub.isRoleConnected(BluetoothPrinterHub.ROLE_LABEL)
                mapOf(
                    "transport" to "bluetooth",
                    "connected" to (receiptOk || labelOk),
                    "hasDevice" to (receiptBtAddress.isNotBlank() || labelBtAddress.isNotBlank()),
                    "receiptConnected" to receiptOk,
                    "labelConnected" to labelOk,
                    "bluetooth" to bt,
                    "devices" to emptyList<Map<String, Any>>()
                )
            }
            else -> {
                val usb = usbPrinter.status().toMutableMap()
                usb["transport"] = "usb"
                usb["bluetooth"] = bt
                usb
            }
        }
    }

    private fun printSheetPngPath(
        path: String,
        transport: String,
        threshold: Int
    ): Map<String, Any?> {
        val file = File(path)
        if (!file.exists()) return mapOf("ok" to false, "error" to "Sheet file missing")
        val bitmap = BitmapFactory.decodeFile(path)
            ?: return mapOf("ok" to false, "error" to "Cannot decode sheet PNG")
        return try {
            // Prefer dedicated Bluetooth label printer when connected.
            if (btHub.isRoleConnected(BluetoothPrinterHub.ROLE_LABEL) ||
                transport.equals("bluetooth", true)
            ) {
                ensureBtRole(BluetoothPrinterHub.ROLE_LABEL, labelBtAddress)?.let { return it }
                val job = EscPosEncoder.printBitmapJob(
                    bitmap,
                    targetWidth = EscPosEncoder.WIDTH_80MM_DOTS.coerceAtMost(bitmap.width)
                        .let { if (bitmap.width >= 384) bitmap.width.coerceAtMost(832) else EscPosEncoder.WIDTH_80MM_DOTS },
                    threshold = threshold,
                    autoCut = autoCut
                )
                return btHub.write(BluetoothPrinterHub.ROLE_LABEL, job)
            }
            if (transport.equals("wifi", true) || preferredTransport == "wifi") {
                if (!wifiPrinter.configured()) {
                    return mapOf("ok" to false, "error" to "Set Wi‑Fi printer IP in settings")
                }
                if (wifiPrinter.useGdi) {
                    wifiPrinter.printBitmapGdi(bitmap, "Label sheet")
                } else {
                    wifiPrinter.write(file.readBytes())
                }
            } else {
                if (!usbPrinter.isConnected()) {
                    val connect = usbPrinter.connect()
                    if (connect["ok"] != true) return connect
                }
                if (usbPrinter.isCanonDevice()) {
                    printCanonBitmap(bitmap, "Label sheet")
                } else {
                    val zpl = ZplEncoder.bitmapToZplBytes(
                        bitmap,
                        bitmap.width,
                        bitmap.height,
                        threshold
                    )
                    usbPrinter.write(zpl)
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Route PDF/image to Bluetooth ESC/POS (80mm receipt or label) or legacy USB/Wi‑Fi. */
    private fun printJobFile(
        path: String,
        format: String,
        transport: String,
        threshold: Int,
        pageIndex: Int,
        isPdf: Boolean
    ): Map<String, Any?> {
        val role = if (format == "bill" || format == "label") {
            BluetoothPrinterHub.ROLE_LABEL
        } else {
            BluetoothPrinterHub.ROLE_RECEIPT
        }
        val useBt = transport.equals("bluetooth", true) ||
            preferredTransport == "bluetooth" ||
            btHub.isRoleConnected(role) ||
            btHub.isRoleConnected(BluetoothPrinterHub.ROLE_RECEIPT)

        if (useBt) {
            val targetRole = if (role == BluetoothPrinterHub.ROLE_LABEL && btHub.isRoleConnected(BluetoothPrinterHub.ROLE_LABEL)) {
                BluetoothPrinterHub.ROLE_LABEL
            } else {
                BluetoothPrinterHub.ROLE_RECEIPT
            }
            val addr = if (targetRole == BluetoothPrinterHub.ROLE_LABEL && labelBtAddress.isNotBlank()) labelBtAddress else receiptBtAddress
            ensureBtRole(targetRole, addr)?.let { return it }
            val bitmap = renderToBitmap(path, format, pageIndex, isPdf)
                ?: return mapOf("ok" to false, "error" to "Cannot render print job")
            return try {
                val width = RECEIPT_80MM_WIDTH_DOTS
                val job = EscPosEncoder.printBitmapJob(
                    bitmap,
                    targetWidth = width,
                    threshold = threshold,
                    autoCut = autoCut
                )
                btHub.write(targetRole, job) + mapOf(
                    "paper" to "80mm",
                    "format" to format,
                    "autoCut" to autoCut
                )
            } finally {
                bitmap.recycle()
            }
        }

        val defaults = defaultsForFormat(format)
        if (transport.equals("wifi", true)) {
            return if (isPdf) {
                printPdfViaWifi(path, defaults.first, defaults.second, pageIndex)
            } else {
                mapOf("ok" to false, "error" to "Wi‑Fi image print: use PDF or Bluetooth")
            }
        }
        if (!usbPrinter.isConnected()) {
            val connect = usbPrinter.connect()
            if (connect["ok"] != true) return connect
        }
        return if (isPdf) {
            printPdfPath(path, defaults.first, defaults.second, threshold, pageIndex, format)
        } else {
            printImagePath(path, defaults.first, defaults.second, threshold, format)
        }
    }

    private fun ensureBtRole(role: String, address: String): Map<String, Any?>? {
        if (btHub.isRoleConnected(role)) return null
        if (address.isBlank()) {
            return mapOf(
                "ok" to false,
                "error" to "Assign a Bluetooth $role printer in Settings, then Connect"
            )
        }
        val res = btHub.connect(role, address)
        return if (res["ok"] == true) null else res
    }

    private fun shouldUseBluetooth(transport: String, role: String): Boolean =
        transport.equals("bluetooth", true) ||
            preferredTransport == "bluetooth" ||
            btHub.isRoleConnected(role)

    private fun printTestJob(
        format: String,
        transport: String,
        mode: String
    ): Map<String, Any?> {
        val role = if (format == "label") {
            BluetoothPrinterHub.ROLE_LABEL
        } else {
            BluetoothPrinterHub.ROLE_RECEIPT
        }
        if (shouldUseBluetooth(transport, role)) {
            val addr =
                if (role == BluetoothPrinterHub.ROLE_LABEL) labelBtAddress else receiptBtAddress
            ensureBtRole(role, addr)?.let { return it }
            val lines = if (format == "label") {
                listOf("Foxwel Label", "BT test", java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm", java.util.Locale.US
                ).format(java.util.Date()))
            } else {
                listOf("Foxwel Receipt", "80mm thermal test", java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm", java.util.Locale.US
                ).format(java.util.Date()))
            }
            val job = EscPosEncoder.textLinesJob(lines, autoCut = autoCut)
            return btHub.write(role, job) + mapOf("autoCut" to autoCut)
        }
        if (!usbPrinter.isConnected()) {
            val connect = usbPrinter.connect()
            if (connect["ok"] != true) return connect
        }
        return if (shouldUseCanon(format)) {
            printCanonTest(mode)
        } else {
            val defaults = defaultsForFormat(format)
            val zpl = ZplEncoder.testLabelZpl(defaults.first, defaults.second)
            usbPrinter.writeText(zpl)
        }
    }

    private fun printTextJob(
        text: String,
        format: String,
        transport: String
    ): Map<String, Any?> {
        val role = if (format == "label") {
            BluetoothPrinterHub.ROLE_LABEL
        } else {
            BluetoothPrinterHub.ROLE_RECEIPT
        }
        if (shouldUseBluetooth(transport, role)) {
            val addr =
                if (role == BluetoothPrinterHub.ROLE_LABEL) labelBtAddress else receiptBtAddress
            ensureBtRole(role, addr)?.let { return it }
            val lines = text.split('\n').ifEmpty { listOf(text) }
            val job = EscPosEncoder.textLinesJob(lines, autoCut = autoCut)
            return btHub.write(role, job) + mapOf("autoCut" to autoCut)
        }
        if (!usbPrinter.isConnected()) {
            val connect = usbPrinter.connect()
            if (connect["ok"] != true) return connect
        }
        return if (shouldUseCanon(format)) {
            printCanonText(text)
        } else {
            val defaults = defaultsForFormat(format)
            val zpl = ZplEncoder.textLabelZpl(text, defaults.first, defaults.second)
            usbPrinter.writeText(zpl)
        }
    }

    private fun renderToBitmap(
        path: String,
        format: String,
        pageIndex: Int,
        isPdf: Boolean
    ): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        if (!isPdf) {
            return BitmapFactory.decodeFile(path)
        }
        val targetW = if (format == "label") 576 else RECEIPT_80MM_WIDTH_DOTS
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (renderer.pageCount == 0) return null
                    val index = pageIndex.coerceIn(0, renderer.pageCount - 1)
                    renderer.openPage(index).use { page ->
                        val aspect = page.height.toFloat() / page.width.toFloat()
                        val targetH = (targetW * aspect).toInt().coerceAtLeast(64)
                        val rawBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                        rawBitmap.eraseColor(Color.WHITE)
                        page.render(rawBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        val cropped = cropAndScaleReceiptBitmap(rawBitmap, targetW)
                        if (cropped !== rawBitmap) rawBitmap.recycle()
                        cropped
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "renderToBitmap failed", e)
            null
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

        if (maxY < minY) {
            return src
        }

        val padY = 8
        val cropY = (minY - padY).coerceAtLeast(0)
        val cropH = (maxY - cropY + padY).coerceAtMost(h - cropY)

        if (cropH <= 10) return src

        val cropped = Bitmap.createBitmap(src, 0, cropY, w, cropH)
        val scale = targetWidth.toFloat() / w.toFloat()
        if (scale == 1.0f) {
            return cropped
        }
        val scaledH = (cropH * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(cropped, targetWidth, scaledH, true)

        if (cropped !== src) cropped.recycle()
        return scaled
    }

    private fun withBluetoothPermission(result: MethodChannel.Result, block: () -> Unit) {
        if (hasBluetoothPermission()) {
            block()
            return
        }
        pendingBtResult = result
        pendingBtAction = block
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        if (needed.isEmpty()) {
            block()
            return
        }
        ActivityCompat.requestPermissions(this, needed.toTypedArray(), BT_PERM_REQ)
    }

    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != BT_PERM_REQ) return
        val ok = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        val result = pendingBtResult
        val action = pendingBtAction
        pendingBtResult = null
        pendingBtAction = null
        if (result == null) return
        if (ok && action != null) {
            try {
                action()
            } catch (e: Exception) {
                result.error("bt", e.message, null)
            }
        } else {
            result.success(mapOf("ok" to false, "error" to "Bluetooth permission denied"))
        }
    }

    private fun normalizeFormat(raw: String?): String {
        val v = (raw ?: "").trim().lowercase()
        return when {
            v.isEmpty() -> "kot"
            v == "bill" || v == "label" || v == "labels" || v == "4x6" || v.contains("bill") || v.contains("label") -> "bill"
            v == "kot" || v == "a5" || v.contains("kot") -> "kot"
            else -> "kot"
        }
    }

    private fun defaultsForFormat(format: String): Pair<Int, Int> {
        return if (format == "label") {
            // "label" is now printed on the same 80mm thermal printer (narrow receipt).
            RECEIPT_80MM_WIDTH_DOTS to 800
        } else {
            // 80mm receipt raster width; height grows with content
            RECEIPT_80MM_WIDTH_DOTS to 800
        }
    }

    /** Canon UFR only for A5 jobs on a Canon device; labels always use ZPL. */
    private fun shouldUseCanon(format: String): Boolean {
        return format != "label" && usbPrinter.isCanonDevice()
    }

    private fun printImagePath(
        path: String,
        width: Int,
        height: Int,
        threshold: Int,
        format: String = "a5"
    ): Map<String, Any> {
        val file = File(path)
        if (!file.exists()) return mapOf("ok" to false, "error" to "File not found: $path")
        val bitmap = BitmapFactory.decodeFile(path)
            ?: return mapOf("ok" to false, "error" to "Cannot decode image")
        return try {
            if (shouldUseCanon(format)) {
                printCanonBitmap(bitmap, "Image")
            } else if (format == "label") {
                val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
                try {
                    val zpl = ZplEncoder.bitmapToZplBytes(scaled, width, height, threshold)
                    usbPrinter.write(zpl)
                } finally {
                    if (scaled !== bitmap) scaled.recycle()
                }
            } else {
                val cropped = cropAndScaleReceiptBitmap(bitmap, RECEIPT_80MM_WIDTH_DOTS)
                try {
                    val escJob = EscPosEncoder.printBitmapJob(
                        cropped,
                        targetWidth = RECEIPT_80MM_WIDTH_DOTS,
                        threshold = threshold,
                        autoCut = autoCut
                    )
                    usbPrinter.write(escJob)
                } finally {
                    if (cropped !== bitmap) cropped.recycle()
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun printPdfPath(
        path: String,
        width: Int,
        height: Int,
        threshold: Int,
        pageIndex: Int,
        format: String = "a5"
    ): Map<String, Any> {
        val file = File(path)
        if (!file.exists()) return mapOf("ok" to false, "error" to "PDF not found: $path")

        val useCanon = shouldUseCanon(format)
        val targetW: Int
        val targetH: Int
        if (useCanon) {
            targetW = CanonUfrEncoder.A5_LANDSCAPE_WIDTH
            targetH = CanonUfrEncoder.A5_LANDSCAPE_HEIGHT
        } else {
            targetW = width
            targetH = height
        }

        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount == 0) {
                    return mapOf("ok" to false, "error" to "PDF has no pages")
                }
                val index = pageIndex.coerceIn(0, renderer.pageCount - 1)
                renderer.openPage(index).use { page ->
                    val bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    return try {
                        if (useCanon) {
                            printCanonBitmap(bitmap, file.name)
                        } else if (format == "label") {
                            val zpl = ZplEncoder.bitmapToZplBytes(bitmap, targetW, targetH, threshold)
                            usbPrinter.write(zpl)
                        } else {
                            val cropped = cropAndScaleReceiptBitmap(bitmap, RECEIPT_80MM_WIDTH_DOTS)
                            try {
                                val escJob = EscPosEncoder.printBitmapJob(
                                    cropped,
                                    targetWidth = RECEIPT_80MM_WIDTH_DOTS,
                                    threshold = threshold,
                                    autoCut = autoCut
                                )
                                usbPrinter.write(escJob)
                            } finally {
                                if (cropped !== bitmap) cropped.recycle()
                            }
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    private fun printCanonTest(mode: String): Map<String, Any> {
        usbPrinter.softReset()
        val before = usbPrinter.diagnose()
        Log.i(TAG, "Canon diagnose before print: $before")

        val job: ByteArray
        val jobMode: String
        when (mode) {
            "probe" -> {
                // Known-good UFR II job from another model (JBIG). Tests whether the
                // printer reacts to CD CA 10 framing at all on this USB path.
                job = assets.open("probe_lbp151_ufr.bin").use { it.readBytes() }
                jobMode = "probe_lbp151"
            }
            else -> {
                job = CanonUfrEncoder.testPageJob()
                jobMode = "ufr_packbits"
            }
        }

        Log.i(TAG, "Canon test mode=$jobMode bytes=${job.size} hdr=${job.take(20).joinToString("") { "%02X".format(it) }}")
        val written = usbPrinter.write(job, timeoutMs = 90_000)
        // Drain any status / CPCA reply after the job.
        val reply = usbPrinter.readIn(4096, 1500)
        val afterPort = usbPrinter.getPortStatus()
        val afterId = usbPrinter.getDeviceId()

        @Suppress("UNCHECKED_CAST")
        val base = written as Map<String, Any>
        return base + mapOf(
            "protocol" to "UFR_II_LT",
            "paper" to "A5 landscape",
            "dpi" to CanonUfrEncoder.DPI,
            "mode" to jobMode,
            "jobBytes" to job.size,
            "ieee1284" to (afterId ?: before["ieee1284"]?.toString() ?: ""),
            "portStatusBefore" to (before["portStatus"] ?: -1),
            "portStatusAfter" to (afterPort ?: -1),
            "replyLen" to (reply?.size ?: 0),
            "replyHex" to (reply?.joinToString("") { "%02X".format(it) }?.take(96) ?: ""),
            "hint" to if (base["ok"] == true) {
                "USB accepted $jobMode (${job.size} bytes). If printer stays idle, job language still rejected — need Windows USBPcap of LBP6030."
            } else {
                "USB write failed"
            }
        )
    }

    private fun printCanonBitmap(bitmap: Bitmap, title: String): Map<String, Any> {
        val scaled =
            if (bitmap.width == CanonUfrEncoder.A5_LANDSCAPE_WIDTH &&
                bitmap.height == CanonUfrEncoder.A5_LANDSCAPE_HEIGHT
            ) {
                bitmap
            } else {
                CanonUfrEncoder.scaleToA5Landscape(bitmap)
            }
        return try {
            usbPrinter.softReset()
            val job = CanonUfrEncoder.bitmapToJob(scaled, title)
            Log.i(TAG, "Canon UFR job ${job.size} bytes (${scaled.width}x${scaled.height}@${CanonUfrEncoder.DPI})")
            val result = usbPrinter.write(job, timeoutMs = 60_000)
            val reply = usbPrinter.readIn(2048, 800)
            result + mapOf(
                "protocol" to "UFR_II_LT",
                "paper" to "A5 landscape",
                "dpi" to CanonUfrEncoder.DPI,
                "jobBytes" to job.size,
                "replyLen" to (reply?.size ?: 0)
            )
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun printCanonText(text: String): Map<String, Any> {
        val bmp = Bitmap.createBitmap(
            CanonUfrEncoder.A5_LANDSCAPE_WIDTH,
            CanonUfrEncoder.A5_LANDSCAPE_HEIGHT,
            Bitmap.Config.ARGB_8888
        )
        bmp.eraseColor(Color.WHITE)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 42f
        }
        var y = 120f
        for (line in text.lines().take(40)) {
            c.drawText(line.take(90), 100f, y, p)
            y += 52f
        }
        return try {
            printCanonBitmap(bmp, "Text")
        } finally {
            bmp.recycle()
        }
    }

    private fun renderPdfPreview(
        path: String,
        width: Int,
        height: Int,
        pageIndex: Int
    ): Map<String, Any?> {
        val file = File(path)
        if (!file.exists()) return mapOf("ok" to false, "error" to "PDF not found")
        val outPng = File(cacheDir, "preview_${System.currentTimeMillis()}.png")
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (renderer.pageCount == 0) {
                        return mapOf("ok" to false, "error" to "PDF has no pages")
                    }
                    val index = pageIndex.coerceIn(0, renderer.pageCount - 1)
                    renderer.openPage(index).use { page ->
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        FileOutputStream(outPng).use { fos ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 90, fos)
                        }
                        bitmap.recycle()
                    }
                }
            }
            mapOf("ok" to true, "path" to outPng.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "renderPdfPreview failed for $path", e)
            mapOf("ok" to false, "error" to "Unable to load document: ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            com.foxwelai.driverforcanon.server.LocalHttpServer.get(applicationContext).start()
        } catch (e: Exception) {
            Log.e(TAG, "Could not start local HTTP server in onCreate", e)
        }
        checkAndRequestBluetoothPermissions()
        handleIncomingIntent(intent)
    }

    private fun checkAndRequestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val perms = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
            val missing = perms.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, missing.toTypedArray(), BT_PERM_REQ)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val path = intent.getStringExtra(Lp46PrintService.EXTRA_PRINT_JOB_PATH)
        val name = intent.getStringExtra(Lp46PrintService.EXTRA_PRINT_JOB_NAME)
        if (path != null) {
            acceptJob(path, name ?: "Print job")
        }

        // Website deep link: printfox://print?type=pdf&url=...&format=kot|bill
        val dataUri = intent.data
        if (intent.action == Intent.ACTION_VIEW &&
            dataUri != null &&
            dataUri.scheme.equals(DEEP_LINK_SCHEME, ignoreCase = true) &&
            dataUri.host.equals("print", ignoreCase = true)
        ) {
            moveTaskToBack(true)
            handlePrintfoxDeepLink(dataUri)
            return
        }

        when (intent.action) {
            Intent.ACTION_SEND, Intent.ACTION_VIEW, Lp46PrintService.ACTION_NEW_PRINT_JOB -> {
                if (path != null) return
                val uri = intent.data
                    ?: if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                // Ignore printfox:// here (already handled); only import content:// / file / http streams
                if (uri != null && !uri.scheme.equals(DEEP_LINK_SCHEME, ignoreCase = true)) {
                    try {
                        val ext = when {
                            intent.type == "application/pdf" -> ".pdf"
                            intent.type?.startsWith("image/") == true -> ".png"
                            uri.lastPathSegment?.endsWith(".pdf", true) == true -> ".pdf"
                            else -> ""
                        }
                        val cache = File(cacheDir, "shared_${System.currentTimeMillis()}$ext")
                        contentResolver.openInputStream(uri)?.use { input ->
                            cache.outputStream().use { output -> input.copyTo(output) }
                        }
                        if (cache.exists() && cache.length() > 0) {
                            acceptJob(
                                cache.absolutePath,
                                intent.getStringExtra(Intent.EXTRA_SUBJECT)
                                    ?: uri.lastPathSegment
                                    ?: "Shared print"
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to import shared file", e)
                    }
                } else if (intent.type?.startsWith("text/") == true) {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (!text.isNullOrBlank()) {
                        val file = File(cacheDir, "shared_text_${System.currentTimeMillis()}.txt")
                        file.writeText(text)
                        acceptJob(file.absolutePath, "Shared text")
                    }
                }
            }
        }
    }

    private fun handlePrintfoxDeepLink(uri: android.net.Uri) {
        val dataString = intent?.dataString ?: uri.toString()
        Log.i(TAG, "Deep link received: $dataString")

        val format = normalizeFormat(
            uri.getQueryParameter("format") ?: extractQueryParam(dataString, "format")
        )
        pendingJobFormat = format

        deepLinkInProgress = true
        lastDeepLinkError = null
        emitEvent(
            mapOf(
                "type" to "deepLinkLoading",
                "name" to (uri.getQueryParameter("name") ?: "Website print"),
                "format" to format
            )
        )

        val type = (uri.getQueryParameter("type") ?: extractQueryParam(dataString, "type") ?: "pdf")
            .lowercase()
        val jobName = uri.getQueryParameter("name")
            ?: extractQueryParam(dataString, "name")
            ?: if (format == "label") "Label print" else "A5 bill print"
        pendingJobName = jobName

        when (type) {
            "text" -> {
                val text = uri.getQueryParameter("text")
                    ?: extractQueryParam(dataString, "text").orEmpty()
                if (text.isBlank()) {
                    failDeepLink("Deep link missing text parameter")
                    return
                }
                val file = File(cacheDir, "deeplink_${System.currentTimeMillis()}.txt")
                file.writeText(text)
                deepLinkInProgress = false
                acceptJob(file.absolutePath, jobName, format, isAutoPrint = true)
            }
            "zpl" -> {
                val zpl = uri.getQueryParameter("zpl")
                    ?: extractQueryParam(dataString, "zpl").orEmpty()
                if (zpl.isBlank()) {
                    failDeepLink("Deep link missing zpl parameter")
                    return
                }
                // Save as text preview file so user still sees something, and also print.
                val file = File(cacheDir, "deeplink_${System.currentTimeMillis()}.txt")
                file.writeText(zpl)
                deepLinkInProgress = false
                acceptJob(file.absolutePath, jobName, format)
                Thread {
                    try {
                        if (!usbPrinter.isConnected()) usbPrinter.connect()
                        usbPrinter.writeText(zpl)
                    } catch (e: Exception) {
                        Log.e(TAG, "deeplink ZPL print failed", e)
                    }
                }.start()
            }
            "html" -> {
                val data = uri.getQueryParameter("data")
                    ?: extractQueryParam(dataString, "data").orEmpty()
                if (data.isBlank()) {
                    failDeepLink("Deep link missing HTML print data")
                    return
                }
                Log.i(TAG, "Rendering receipt HTML (${data.length} chars)")
                renderWebUrlToBitmap("https://staging.fatfox.testfox.in", data.toByteArray(Charsets.UTF_8), jobName, format)
            }
            "pdf", "image" -> {
                val url = resolveHttpUrl(uri, dataString)
                if (url.isBlank()) {
                    failDeepLink(
                        "Deep link missing url. Website must open " +
                            "printfox://print?type=pdf&url=<https-pdf-url>&format=a5|label"
                    )
                    return
                }
                Log.i(TAG, "Processing deep link print ($format): $url")
                Thread {
                    try {
                        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                        conn.instanceFollowRedirects = true
                        conn.connectTimeout = 25000
                        conn.readTimeout = 40000
                        conn.setRequestProperty(
                            "User-Agent",
                            "PrintFox/1.0 (Android)"
                        )
                        conn.connect()
                        val code = conn.responseCode
                        if (code !in 200..299) {
                            failDeepLink("Print receipt error: Server returned HTTP $code")
                            return@Thread
                        }
                        val downloadedBytes = (conn.inputStream ?: throw IllegalStateException("No response body")).use { it.readBytes() }
                        if (downloadedBytes.isEmpty()) {
                            failDeepLink("Print receipt error: Server returned empty file")
                            return@Thread
                        }

                        val bodyStr = String(downloadedBytes, Charsets.UTF_8)
                        if (bodyStr.contains("code\":404") || bodyStr.contains("Page not found") || bodyStr.contains("status\":404")) {
                            failDeepLink("Print receipt error: Server returned 404 (Page not found)")
                            return@Thread
                        }

                        val isPdf = downloadedBytes.size >= 4 &&
                            downloadedBytes[0] == '%'.code.toByte() &&
                            downloadedBytes[1] == 'P'.code.toByte() &&
                            downloadedBytes[2] == 'D'.code.toByte() &&
                            downloadedBytes[3] == 'F'.code.toByte()

                        if (isPdf) {
                            val out = File(cacheDir, "deeplink_${System.currentTimeMillis()}.pdf")
                            out.writeBytes(downloadedBytes)
                            Log.i(TAG, "PDF Download ok (${out.length()} bytes) → ${out.absolutePath}")
                            deepLinkInProgress = false
                            runOnUiThread { acceptJob(out.absolutePath, jobName, format, isAutoPrint = true) }
                        } else {
                            Log.i(TAG, "Downloaded content is non-PDF HTML/Web page (${downloadedBytes.size} bytes), rendering via WebView: $url")
                            renderWebUrlToBitmap(url, downloadedBytes, jobName, format)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to download print url, attempting direct WebView rendering: $url", e)
                        renderWebUrlToBitmap(url, null, jobName, format)
                    }
                }.start()
            }
            else -> failDeepLink("Unknown deep link type=$type")
        }
    }

    private fun renderWebUrlToBitmap(
        url: String,
        htmlBytes: ByteArray?,
        jobName: String,
        format: String
    ) {
        runOnUiThread {
            try {
                val webView = android.webkit.WebView(applicationContext)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.loadWithOverviewMode = false
                webView.settings.useWideViewPort = false

                val targetWidth = RECEIPT_80MM_WIDTH_DOTS // 576 dots for 80mm thermal receipt
                webView.layout(0, 0, targetWidth, 2400)

                var captured = false
                webView.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView?, loadedUrl: String?) {
                        if (captured) return
                        captured = true
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            try {
                                val contentH = ((view?.contentHeight ?: 800) * (view?.scale ?: 1f)).toInt().coerceIn(300, 4000)
                                val rawBitmap = Bitmap.createBitmap(targetWidth, contentH, Bitmap.Config.ARGB_8888)
                                val canvas = android.graphics.Canvas(rawBitmap)
                                canvas.drawColor(Color.WHITE)
                                view?.draw(canvas)

                                val cropped = cropAndScaleReceiptBitmap(rawBitmap, targetWidth)

                                val outPng = File(cacheDir, "web_receipt_${System.currentTimeMillis()}.png")
                                FileOutputStream(outPng).use { fos ->
                                    cropped.compress(Bitmap.CompressFormat.PNG, 95, fos)
                                }
                                if (cropped !== rawBitmap) rawBitmap.recycle()
                                cropped.recycle()

                                Log.i(TAG, "WebView rendered receipt PNG (${outPng.length()} bytes)")
                                deepLinkInProgress = false
                                acceptJob(outPng.absolutePath, jobName, format, isAutoPrint = true)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to capture WebView receipt", e)
                                failDeepLink("Web receipt rendering failed: ${e.message}")
                            }
                        }, 1000)
                    }

                    override fun onReceivedError(view: android.webkit.WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                        Log.w(TAG, "WebView error: $description")
                    }
                }

                if (htmlBytes != null && htmlBytes.isNotEmpty()) {
                    val htmlString = String(htmlBytes, Charsets.UTF_8)
                    webView.loadDataWithBaseURL(url, htmlString, "text/html", "UTF-8", null)
                } else {
                    webView.loadUrl(url)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize WebView for print", e)
                failDeepLink("WebView error: ${e.message}")
            }
        }
    }

    private fun resolveHttpUrl(uri: android.net.Uri, dataString: String): String {
        val fromParam = uri.getQueryParameter("url")
            ?: extractQueryParam(dataString, "url")
            ?: ""
        val decoded = try {
            java.net.URLDecoder.decode(fromParam, "UTF-8")
        } catch (_: Exception) {
            fromParam
        }
        if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
            return decoded
        }
        // Fallback: take everything after url= (handles poorly encoded URLs with &)
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
            val decodedRest = try {
                java.net.URLDecoder.decode(rest, "UTF-8")
            } catch (_: Exception) {
                rest
            }
            if (decodedRest.startsWith("http://") || decodedRest.startsWith("https://")) {
                return decodedRest
            }
        }
        return ""
    }

    private fun extractQueryParam(dataString: String, key: String): String? {
        val regex = Regex("""[?&]$key=([^&]*)""", RegexOption.IGNORE_CASE)
        val match = regex.find(dataString) ?: return null
        return try {
            java.net.URLDecoder.decode(match.groupValues[1], "UTF-8")
        } catch (_: Exception) {
            match.groupValues[1]
        }
    }

    private fun failDeepLink(message: String) {
        Log.e(TAG, message)
        deepLinkInProgress = false
        lastDeepLinkError = message
        runOnUiThread {
            emitEvent(
                mapOf(
                    "type" to "deepLinkError",
                    "error" to message
                )
            )
        }
    }

    private fun emitEvent(payload: Map<String, Any?>) {
        try {
            eventSink?.success(payload)
        } catch (e: Exception) {
            Log.w(TAG, "emitEvent failed", e)
        }
    }

    private fun acceptJob(
        path: String,
        name: String,
        format: String = pendingJobFormat,
        isAutoPrint: Boolean = false
    ) {
        pendingJobPath = path
        pendingJobName = name
        pendingJobFormat = normalizeFormat(format)
        Lp46PrintService.lastJobPath = path
        Lp46PrintService.lastJobName = name
        deepLinkInProgress = false
        Log.i(TAG, "Print job ready ($pendingJobFormat, autoPrint=$isAutoPrint): $name → $path")
        emitEvent(
            mapOf(
                "type" to "printJob",
                "path" to path,
                "name" to name,
                "format" to pendingJobFormat
            )
        )

        if (isAutoPrint) {
            Log.i(TAG, "Executing silent background auto-print for $name")
            moveTaskToBack(true)
            Thread {
                try {
                    val prefs = getSharedPreferences("printfox_settings", MODE_PRIVATE)
                    val threshold = prefs.getInt("threshold", 160)
                    val isPdf = path.lowercase().endsWith(".pdf")
                    val result = printJobFile(
                        path = path,
                        format = pendingJobFormat,
                        transport = preferredTransport,
                        threshold = threshold,
                        pageIndex = 0,
                        isPdf = isPdf
                    )
                    Log.i(TAG, "Silent auto-print completed: $result")
                } catch (e: Exception) {
                    Log.e(TAG, "Silent auto-print failed", e)
                }
            }.start()
        }
    }

    override fun onDestroy() {
        // Keep shared USB receivers alive for PrintService / process lifetime.
        super.onDestroy()
    }
}
