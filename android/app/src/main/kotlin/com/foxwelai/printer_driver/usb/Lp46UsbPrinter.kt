package com.foxwelai.driverforcanon.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

/**
 * USB bulk-transfer driver for Canon LBP6030B (UFR II) and compatible USB printers.
 *
 * Canon imageCLASS LBP6030B is a host-based UFR II LT printer (VID 04A9 / PID 2795).
 * It does NOT understand ZPL/BPLZ. Raw ZPL writes will report USB success but print nothing.
 */
class Lp46UsbPrinter(private val context: Context) {
    companion object {
        private const val TAG = "Lp46UsbPrinter"
        const val ACTION_USB_PERMISSION = "com.foxwelai.driverforcanon.USB_PERMISSION"

        /** Canon Inc. */
        const val CANON_VENDOR_ID = 0x04A9
        /** LBP6030 / LBP6030B / LBP6018L */
        const val LBP6030_PRODUCT_ID = 0x2795

        /** Legacy SNBC OEM VIDs (label printers). */
        val SNBC_VENDOR_IDS = setOf(0x154F, 0x0AA7, 0x277D, 0x03F0)

        val KNOWN_VENDOR_IDS = SNBC_VENDOR_IDS + CANON_VENDOR_ID
    }

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var device: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var outEndpoint: UsbEndpoint? = null
    private var inEndpoint: UsbEndpoint? = null

    var onPermissionResult: ((Boolean) -> Unit)? = null
    var onDeviceChanged: (() -> Unit)? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val usbDevice: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            if (granted && usbDevice != null) {
                openDevice(usbDevice)
            }
            onPermissionResult?.invoke(granted && isConnected())
            onDeviceChanged?.invoke()
        }
    }

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val usbDevice: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            if (usbDevice != null && usbDevice.deviceId == device?.deviceId) {
                close()
                onDeviceChanged?.invoke()
            }
        }
    }

    private var receiversRegistered = false

    fun registerReceivers() {
        if (receiversRegistered) return
        val permissionFilter = IntentFilter(ACTION_USB_PERMISSION)
        val detachFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permissionReceiver, permissionFilter, Context.RECEIVER_NOT_EXPORTED)
            context.registerReceiver(detachReceiver, detachFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(permissionReceiver, permissionFilter)
            context.registerReceiver(detachReceiver, detachFilter)
        }
        receiversRegistered = true
    }

    fun unregisterReceivers() {
        if (!receiversRegistered) return
        try {
            context.unregisterReceiver(permissionReceiver)
            context.unregisterReceiver(detachReceiver)
        } catch (_: Exception) {
        }
        receiversRegistered = false
    }

    fun listPrinters(): List<Map<String, Any>> {
        return usbManager.deviceList.values
            .filter { isLikelyPrinter(it) }
            .map { deviceInfo(it) }
    }

    fun isLikelyPrinter(device: UsbDevice): Boolean {
        if (device.vendorId == CANON_VENDOR_ID) return true
        if (KNOWN_VENDOR_IDS.contains(device.vendorId)) return true
        // Printer class or vendor-specific with bulk endpoints
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_PRINTER) return true
        }
        val name = "${device.manufacturerName.orEmpty()} ${device.productName.orEmpty()}".lowercase()
        return name.contains("canon") ||
            name.contains("lbp6030") ||
            name.contains("lbp") ||
            name.contains("imageclass") ||
            name.contains("lp46") ||
            name.contains("dlite") ||
            name.contains("snbc") ||
            name.contains("tvse") ||
            name.contains("tvs")
    }

    fun isCanonDevice(device: UsbDevice? = this.device ?: findPrinter()): Boolean {
        val d = device ?: return false
        if (d.vendorId == CANON_VENDOR_ID) return true
        val name = "${d.manufacturerName.orEmpty()} ${d.productName.orEmpty()}".lowercase()
        return name.contains("canon") || name.contains("lbp6030")
    }

    /** Prefer Canon LBP6030 when multiple USB printers are attached. */
    fun findPrinter(): UsbDevice? {
        val all = usbManager.deviceList.values.filter { isLikelyPrinter(it) }
        return all.firstOrNull {
            it.vendorId == CANON_VENDOR_ID && it.productId == LBP6030_PRODUCT_ID
        } ?: all.firstOrNull { it.vendorId == CANON_VENDOR_ID }
            ?: all.firstOrNull()
    }

    fun requestPermission(device: UsbDevice? = findPrinter()): Boolean {
        val target = device ?: return false
        registerReceivers()
        if (usbManager.hasPermission(target)) {
            openDevice(target)
            onPermissionResult?.invoke(true)
            onDeviceChanged?.invoke()
            return true
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val pi = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags
        )
        usbManager.requestPermission(target, pi)
        return false
    }

    fun openDevice(usbDevice: UsbDevice): Boolean {
        close()
        if (!usbManager.hasPermission(usbDevice)) {
            Log.w(TAG, "No permission for ${usbDevice.deviceName}")
            return false
        }
        val conn = usbManager.openDevice(usbDevice) ?: return false

        data class Candidate(
            val intf: UsbInterface,
            val outEp: UsbEndpoint,
            val inEp: UsbEndpoint?,
            val score: Int
        )

        val candidates = mutableListOf<Candidate>()
        for (i in 0 until usbDevice.interfaceCount) {
            val intf = usbDevice.getInterface(i)
            var localOut: UsbEndpoint? = null
            var localIn: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_OUT) localOut = ep
                if (ep.direction == UsbConstants.USB_DIR_IN) localIn = ep
            }
            if (localOut == null) continue
            var score = 0
            if (intf.interfaceClass == UsbConstants.USB_CLASS_PRINTER) score += 100
            // USB Printer Class: protocol 2 = bidirectional
            if (intf.interfaceProtocol == 2) score += 50
            if (localIn != null) score += 20
            score += intf.endpointCount
            candidates += Candidate(intf, localOut, localIn, score)
            Log.i(
                TAG,
                "USB iface#$i class=${intf.interfaceClass} sub=${intf.interfaceSubclass} " +
                    "proto=${intf.interfaceProtocol} alt=${intf.alternateSetting} " +
                    "out=${localOut.address} in=${localIn?.address} score=$score"
            )
        }

        val best = candidates.maxByOrNull { it.score }
        if (best == null) {
            conn.close()
            Log.e(TAG, "No bulk OUT endpoint found")
            return false
        }

        if (!conn.claimInterface(best.intf, true)) {
            conn.close()
            Log.e(TAG, "Failed to claim interface")
            return false
        }

        this.device = usbDevice
        this.connection = conn
        this.usbInterface = best.intf
        this.outEndpoint = best.outEp
        this.inEndpoint = best.inEp
        Log.i(
            TAG,
            "Opened ${usbDevice.productName} VID=${usbDevice.vendorId} PID=${usbDevice.productId} " +
                "iface=${best.intf.id} proto=${best.intf.interfaceProtocol}"
        )

        // Wake / clear stalls before first job.
        softReset()
        val id = getDeviceId()
        Log.i(TAG, "IEEE1284 ID: ${id ?: "(none)"}")
        return true
    }

    /** USB Printer Class SOFT_RESET (clears bulk pipes / stalls). */
    fun softReset(): Boolean {
        val conn = connection ?: return false
        val intf = usbInterface ?: return false
        // bmRequestType: class | interface | host-to-device = 0x21
        // Some 1.0 devices expect 0x23; try both.
        for (type in intArrayOf(0x21, 0x23)) {
            val r = conn.controlTransfer(type, 2, 0, intf.id, null, 0, 2000)
            Log.i(TAG, "SOFT_RESET type=0x${type.toString(16)} → $r")
            if (r >= 0) return true
        }
        return false
    }

    /** USB Printer Class GET_PORT_STATUS. */
    fun getPortStatus(): Int? {
        val conn = connection ?: return null
        val intf = usbInterface ?: return null
        val buf = ByteArray(1)
        val r = conn.controlTransfer(0xA1, 1, 0, intf.id, buf, 1, 2000)
        return if (r == 1) buf[0].toInt() and 0xFF else null
    }

    /** USB Printer Class GET_DEVICE_ID (IEEE 1284). */
    fun getDeviceId(): String? {
        val conn = connection ?: return null
        val intf = usbInterface ?: return null
        val buf = ByteArray(1024)
        val r = conn.controlTransfer(0xA1, 0, 0, intf.id, buf, buf.size, 3000)
        if (r < 2) return null
        val len = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
        val end = minOf(r, len).coerceAtLeast(2)
        return try {
            String(buf, 2, end - 2, Charsets.ISO_8859_1).trim()
        } catch (_: Exception) {
            null
        }
    }

    fun readIn(maxLen: Int = 2048, timeoutMs: Int = 500): ByteArray? {
        val conn = connection ?: return null
        val ep = inEndpoint ?: return null
        val buf = ByteArray(maxLen.coerceAtMost(ep.maxPacketSize.coerceAtLeast(64) * 16))
        val n = conn.bulkTransfer(ep, buf, buf.size, timeoutMs)
        return if (n > 0) buf.copyOf(n) else null
    }

    /**
     * CAPT-style command (little-endian opcode + length). Used only as a probe —
     * LBP6030 is UFR II LT, but a reply would be very informative.
     */
    fun captProbe(): Map<String, Any?> {
        // CAPT_IDENT = 0xA1A1, length 4
        val cmd = byteArrayOf(0xA1.toByte(), 0xA1.toByte(), 0x04, 0x00)
        val w = write(cmd, timeoutMs = 3000)
        val reply = readIn(4096, 1500)
        return mapOf(
            "writeOk" to (w["ok"] == true),
            "bytesWritten" to w["bytesWritten"],
            "replyLen" to (reply?.size ?: 0),
            "replyHex" to reply?.joinToString("") { String.format("%02X", it) }?.take(128)
        )
    }

    fun diagnose(): Map<String, Any?> {
        softReset()
        val port = getPortStatus()
        val id = getDeviceId()
        val probe = if (isCanonDevice()) captProbe() else emptyMap()
        return mapOf(
            "connected" to isConnected(),
            "ieee1284" to id,
            "portStatus" to port,
            "paperEmpty" to port?.let { (it and 0x20) != 0 },
            "selected" to port?.let { (it and 0x10) != 0 },
            "notError" to port?.let { (it and 0x08) != 0 },
            "captProbe" to probe,
            "device" to device?.let { deviceInfo(it) }
        )
    }

    fun connect(): Map<String, Any> {
        registerReceivers()
        val printer = findPrinter()
            ?: return mapOf("ok" to false, "error" to "No USB bill printer found. Use a USB-OTG cable.")
        if (!usbManager.hasPermission(printer)) {
            requestPermission(printer)
            return mapOf(
                "ok" to false,
                "pendingPermission" to true,
                "device" to deviceInfo(printer)
            )
        }
        val opened = openDevice(printer)
        return if (opened) {
            mapOf("ok" to true, "device" to deviceInfo(printer))
        } else {
            mapOf("ok" to false, "error" to "Failed to open USB device")
        }
    }

    fun isConnected(): Boolean = connection != null && outEndpoint != null && device != null

    fun status(): Map<String, Any?> {
        val connected = isConnected()
        val found = findPrinter()
        val active = device
        val port = if (connected) getPortStatus() else null
        val ieee = if (connected) getDeviceId() else null
        val deviceMap = when {
            connected && active != null -> deviceInfo(active)
            found != null -> deviceInfo(found)
            else -> null
        }
        return mapOf(
            "connected" to connected,
            "hasDevice" to (found != null || active != null),
            "device" to deviceMap,
            "devices" to listPrinters(),
            "ieee1284" to ieee,
            "portStatus" to port
        )
    }

    fun write(data: ByteArray, timeoutMs: Int = 15000): Map<String, Any> {
        val conn = connection
        val out = outEndpoint
        if (conn == null || out == null) {
            return mapOf("ok" to false, "error" to "Printer not connected")
        }

        // Canon host-based printers prefer modest URB sizes.
        var offset = 0
        val chunkSize = out.maxPacketSize.coerceAtLeast(64).coerceAtMost(4096)
        while (offset < data.size) {
            val len = minOf(chunkSize, data.size - offset)
            val chunk = data.copyOfRange(offset, offset + len)
            val written = conn.bulkTransfer(out, chunk, chunk.size, timeoutMs)
            if (written < 0) {
                return mapOf(
                    "ok" to false,
                    "error" to "USB write failed at offset $offset / ${data.size}",
                    "bytesWritten" to offset
                )
            }
            offset += written
            // Give Canon firmware time to ACK between URBs on large UFR jobs.
            if (isCanonDevice() && offset < data.size) {
                try {
                    Thread.sleep(1)
                } catch (_: InterruptedException) {
                }
            }
        }
        return mapOf("ok" to true, "bytesWritten" to offset)
    }

    fun writeText(text: String, timeoutMs: Int = 15000): Map<String, Any> {
        return write(text.toByteArray(Charsets.UTF_8), timeoutMs)
    }

    fun close() {
        try {
            usbInterface?.let { connection?.releaseInterface(it) }
        } catch (_: Exception) {
        }
        try {
            connection?.close()
        } catch (_: Exception) {
        }
        connection = null
        usbInterface = null
        outEndpoint = null
        inEndpoint = null
        device = null
    }

    private fun deviceInfo(d: UsbDevice): Map<String, Any> {
        return mapOf(
            "deviceName" to d.deviceName,
            "deviceId" to d.deviceId,
            "vendorId" to d.vendorId,
            "productId" to d.productId,
            "vendorIdHex" to String.format("%04X", d.vendorId),
            "productIdHex" to String.format("%04X", d.productId),
            "manufacturer" to (d.manufacturerName ?: ""),
            "product" to (d.productName ?: "USB Bill Printer"),
            "hasPermission" to usbManager.hasPermission(d),
            "isCanon" to (d.vendorId == CANON_VENDOR_ID),
            "protocol" to if (d.vendorId == CANON_VENDOR_ID) "UFR_II" else "ZPL"
        )
    }
}
