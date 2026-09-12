package com.foxwelai.driverforcanon.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Dual Bluetooth Classic (SPP) hub — one socket per print role.
 *
 * Yes: an Android device can keep **two** SPP connections open at once
 * (receipt printer + label printer), as long as they are different MAC addresses.
 */
class BluetoothPrinterHub private constructor(private val context: Context) {
    companion object {
        private const val TAG = "BtPrinterHub"
        /** Well-known SPP UUID */
        private val SPP_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        const val ROLE_RECEIPT = "receipt"
        const val ROLE_LABEL = "label"

        @Volatile
        private var instance: BluetoothPrinterHub? = null

        fun get(context: Context): BluetoothPrinterHub {
            return instance ?: synchronized(this) {
                instance ?: BluetoothPrinterHub(context.applicationContext).also { instance = it }
            }
        }
    }

    private val adapter: BluetoothAdapter? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val mgr = context.getSystemService(BluetoothManager::class.java)
            mgr?.adapter
        } else {
            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter()
        }
    }

    private data class Conn(
        val address: String,
        val name: String,
        val socket: BluetoothSocket
    )

    private val connections = ConcurrentHashMap<String, Conn>()

    // --- Auto-Reconnect Watchdog ---
    private data class ReconnectTarget(
        val role: String,
        val address: String,
        /** Backoff for NEXT failed reconnect. Start at 2s → max 60s (exponential). */
        @Volatile var backoffMs: Long = 2000L,
        /** Absolute epoch ms before which we skip attempting reconnect. */
        @Volatile var nextRetryAtMs: Long = 0L
    )
    private val reconnectTargets = ConcurrentHashMap<String, ReconnectTarget>()
    private val watchdogRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private var watchdogThread: Thread? = null

    private fun ensureWatchdog() {
        if (watchdogRunning.getAndSet(true)) return
        watchdogThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            Log.i(TAG, "Bluetooth watchdog started (20s NUL keep-alive + auto-reconnect)")
            try {
                while (watchdogRunning.get()) {
                    try { Thread.sleep(20_000L) } catch (_: InterruptedException) { break }
                    if (!watchdogRunning.get()) break

                    val adapter = adapter
                    val hasPerm = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            androidx.core.content.ContextCompat.checkSelfPermission(
                                context, android.Manifest.permission.BLUETOOTH_CONNECT
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        } else true
                    } catch (_: Throwable) { true }
                    if (adapter == null || !adapter.isEnabled || !hasPerm) {
                        continue  // Skip this cycle if BT is off / missing permission
                    }

                    // ------------------------------------------------------------------
                    // STEP A: NUL keep-alive ping — 1-byte ESC/POS 0x00 every 20s
                    // Printers ignore NUL bytes but mark SPP socket as active →
                    // prevents idle timeout close on budget thermal models.
                    // ------------------------------------------------------------------
                    val iterator = connections.entries.iterator()
                    while (iterator.hasNext()) {
                        val e = iterator.next()
                        val role = e.key
                        val conn = e.value
                        if (conn.socket.isConnected) {
                            try {
                                conn.socket.outputStream.write(byteArrayOf(0x00))
                                conn.socket.outputStream.flush()
                            } catch (_: IOException) {
                                Log.i(TAG, "Watchdog: NUL keep-alive failed for $role (${conn.name}). Socket dead. Will reconnect this cycle.")
                                try { conn.socket.close() } catch (_: Exception) {}
                                iterator.remove()
                            }
                        } else {
                            try { conn.socket.close() } catch (_: Exception) {}
                            iterator.remove()
                        }
                    }

                    // ------------------------------------------------------------------
                    // STEP B: Auto-reconnect with exponential backoff
                    // ------------------------------------------------------------------
                    val nowMs = System.currentTimeMillis()
                    for ((role, target) in reconnectTargets) {
                        if (isRoleConnected(role)) {
                            if (target.backoffMs != 2000L || target.nextRetryAtMs != 0L) {
                                target.backoffMs = 2000L
                                target.nextRetryAtMs = 0L
                            }
                            continue
                        }
                        if (target.nextRetryAtMs > nowMs) {
                            Log.d(TAG, "Watchdog: $role backoff active — retry in ${(target.nextRetryAtMs - nowMs)/1000}s")
                            continue
                        }
                        Log.i(TAG, "Watchdog auto-reconnecting $role → ${target.address} (backoff=${target.backoffMs/1000}s)")
                        val res = connect(role, target.address)
                        if (res["ok"] == true) {
                            target.backoffMs = 2000L
                            target.nextRetryAtMs = 0L
                            Log.i(TAG, "Watchdog reconnected $role successfully")
                        } else {
                            target.backoffMs = (target.backoffMs * 2L).coerceAtMost(60_000L)
                            target.nextRetryAtMs = System.currentTimeMillis() + target.backoffMs
                            Log.w(TAG, "Watchdog reconnect failed for $role. Next attempt in ${target.backoffMs/1000}s: ${res["error"]}")
                        }
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                watchdogRunning.set(false)
                Log.i(TAG, "Bluetooth watchdog stopped")
            }
        }.apply { start() }
    }

    private fun registerForAutoReconnect(role: String, address: String) {
        val existing = reconnectTargets[role]
        if (existing != null && existing.address.equals(address, true)) {
            existing.backoffMs = 2000L
            existing.nextRetryAtMs = 0L
            return
        }
        reconnectTargets[role] = ReconnectTarget(role = role, address = address)
        ensureWatchdog()
    }

    fun stopWatchdog() {
        watchdogRunning.set(false)
        watchdogThread?.interrupt()
        reconnectTargets.clear()
    }
    // -------------------------------

    fun isBluetoothAvailable(): Boolean = adapter != null

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun listBondedPrinters(): List<Map<String, Any?>> {
        val a = adapter ?: return emptyList()
        if (!a.isEnabled) return emptyList()
        return try {
            a.bondedDevices.orEmpty().map { d ->
                mapOf(
                    "name" to (d.name ?: "Bluetooth printer"),
                    "address" to d.address,
                    "bonded" to true,
                    "type" to d.type,
                    "connectedRoles" to rolesForAddress(d.address)
                )
            }.sortedBy { it["name"]?.toString()?.lowercase() }
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth permission missing", e)
            emptyList()
        }
    }

    private fun rolesForAddress(address: String): List<String> =
        connections.entries.filter { it.value.address.equals(address, true) }.map { it.key }

    fun status(): Map<String, Any?> = mapOf(
        "available" to isBluetoothAvailable(),
        "enabled" to isBluetoothEnabled(),
        "receipt" to roleStatus(ROLE_RECEIPT),
        "label" to roleStatus(ROLE_LABEL)
    )

    private fun roleStatus(role: String): Map<String, Any?> {
        val c = connections[role]
        return mapOf(
            "connected" to (c != null && c.socket.isConnected),
            "address" to c?.address,
            "name" to c?.name
        )
    }

    fun isRoleConnected(role: String): Boolean {
        val c = connections[role] ?: return false
        return c.socket.isConnected
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun connect(role: String, address: String): Map<String, Any?> {
        val normalized = role.lowercase()
        if (normalized != ROLE_RECEIPT && normalized != ROLE_LABEL) {
            return mapOf("ok" to false, "error" to "Unknown role=$role (use receipt|label)")
        }
        val a = adapter ?: return mapOf("ok" to false, "error" to "Bluetooth not available")
        if (!a.isEnabled) return mapOf("ok" to false, "error" to "Bluetooth is off")
        if (address.isBlank()) return mapOf("ok" to false, "error" to "Missing MAC address")

        // 1. If this exact role is already connected to this MAC address, reuse active socket
        val existing = connections[normalized]
        if (existing != null && existing.address.equals(address, true) && existing.socket.isConnected) {
            Log.i(TAG, "Already connected role=$normalized to ${existing.name} ($address)")
            return mapOf(
                "ok" to true,
                "role" to normalized,
                "address" to address,
                "name" to existing.name,
                "alreadyConnected" to true
            )
        }

        // 2. Same MAC assigned to both roles: share active socket instead of erroring
        val other = if (normalized == ROLE_RECEIPT) ROLE_LABEL else ROLE_RECEIPT
        val otherConn = connections[other]
        if (otherConn != null && otherConn.address.equals(address, true) && otherConn.socket.isConnected) {
            connections[normalized] = Conn(address, otherConn.name, otherConn.socket)
            Log.i(TAG, "Reusing active Bluetooth socket for $normalized → ${otherConn.name} ($address)")
            return mapOf(
                "ok" to true,
                "role" to normalized,
                "address" to address,
                "name" to otherConn.name,
                "shared" to true
            )
        }

        disconnect(normalized)

        return try {
            val device = a.getRemoteDevice(address)
            val socket = createSocket(device)
            socket.connect()
            val name = try {
                device.name ?: address
            } catch (_: SecurityException) {
                address
            }
            connections[normalized] = Conn(address, name, socket)
            Log.i(TAG, "Connected $normalized → $name ($address)")
            registerForAutoReconnect(normalized, address)
            mapOf(
                "ok" to true,
                "role" to normalized,
                "address" to address,
                "name" to name
            )
        } catch (e: Exception) {
            Log.e(TAG, "Connect failed role=$normalized addr=$address", e)
            val msg = when {
                e.message?.contains("read failed", ignoreCase = true) == true ||
                e.message?.contains("socket might closed", ignoreCase = true) == true ||
                e.message?.contains("timeout", ignoreCase = true) == true ->
                    "Printer powered off or Bluetooth connection timed out. Ensure printer is ON."
                else -> e.message ?: "Bluetooth connect failed"
            }
            mapOf("ok" to false, "error" to msg)
        }
    }

    @SuppressLint("MissingPermission")
    private fun createSocket(device: BluetoothDevice): BluetoothSocket {
        return try {
            device.createRfcommSocketToServiceRecord(SPP_UUID)
        } catch (_: Exception) {
            try {
                device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            } catch (_: Exception) {
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                m.invoke(device, 1) as BluetoothSocket
            }
        }
    }

    @Synchronized
    fun disconnect(role: String? = null): Map<String, Any?> {
        val roles = if (role.isNullOrBlank()) {
            listOf(ROLE_RECEIPT, ROLE_LABEL)
        } else {
            listOf(role.lowercase())
        }
        for (r in roles) {
            reconnectTargets.remove(r)
            connections.remove(r)?.let { c ->
                try {
                    c.socket.close()
                } catch (_: Exception) {
                }
                Log.i(TAG, "Disconnected $r")
            }
        }
        return mapOf("ok" to true)
    }

    @Synchronized
    fun write(role: String, bytes: ByteArray): Map<String, Any?> {
        val normalized = role.lowercase()
        val c = connections[normalized]
            ?: return mapOf("ok" to false, "error" to "Bluetooth $normalized printer not connected")
        if (!c.socket.isConnected) {
            connections.remove(normalized)
            return mapOf("ok" to false, "error" to "Bluetooth $normalized socket closed — reconnect")
        }
        return try {
            val out = c.socket.outputStream
            // Chunk large ESC/POS jobs for flaky BT stacks
            var offset = 0
            val chunk = 1024
            while (offset < bytes.size) {
                val end = (offset + chunk).coerceAtMost(bytes.size)
                out.write(bytes, offset, end - offset)
                out.flush()
                offset = end
            }
            mapOf(
                "ok" to true,
                "bytesWritten" to bytes.size,
                "transport" to "bluetooth",
                "role" to normalized,
                "address" to c.address,
                "name" to c.name
            )
        } catch (e: IOException) {
            Log.e(TAG, "Write failed $normalized", e)
            try {
                c.socket.close()
            } catch (_: Exception) {
            }
            connections.remove(normalized)
            mapOf("ok" to false, "error" to (e.message ?: "Bluetooth write failed"))
        }
    }
}
