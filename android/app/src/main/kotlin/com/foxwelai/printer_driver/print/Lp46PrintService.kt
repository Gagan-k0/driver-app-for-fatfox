package com.foxwelai.driverforcanon.print

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.provider.Settings
import android.util.Log
import com.foxwelai.driverforcanon.MainActivity
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Receives Chrome / system print jobs and hands them to the Flutter app.
 *
 * IMPORTANT: PrintJob.getDocument() / getInfo() MUST run on the main thread.
 * Copying the PDF bytes can happen on a background thread after dup()'ing the FD.
 */
class Lp46PrintService : PrintService() {
    companion object {
        private const val TAG = "Lp46PrintService"
        const val EXTRA_PRINT_JOB_PATH = "print_job_path"
        const val EXTRA_PRINT_JOB_NAME = "print_job_name"
        const val ACTION_NEW_PRINT_JOB = "com.foxwelai.driverforcanon.NEW_PRINT_JOB"
        private const val CHANNEL_ID = "lp46_print_jobs"
        private const val NOTIFICATION_ID = 4601

        @Volatile
        var lastJobPath: String? = null

        @Volatile
        var lastJobName: String? = null

        fun isEnabled(context: Context): Boolean {
            return statusDetails(context)["enabled"] == true
        }

        /**
         * Android 7+ stores only DISABLED services. Do NOT call
         * PrintManager.isPrintServiceEnabled() — missing on many OEM builds.
         */
        fun statusDetails(context: Context): Map<String, Any?> {
            val cn = ComponentName(context, Lp46PrintService::class.java)
            val disabledRaw = Settings.Secure.getString(
                context.contentResolver,
                "disabled_print_services"
            )
            val enabledRaw = Settings.Secure.getString(
                context.contentResolver,
                "enabled_print_services"
            )
            val explicitlyDisabled = containsComponent(disabledRaw.orEmpty(), cn)
            val enabled = when {
                explicitlyDisabled -> false
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> true
                else -> containsComponent(enabledRaw.orEmpty(), cn)
            }
            return mapOf(
                "enabled" to enabled,
                "component" to cn.flattenToString(),
                "explicitlyDisabled" to explicitlyDisabled,
                "disabled_print_services" to (disabledRaw ?: ""),
                "enabled_print_services" to (enabledRaw ?: ""),
                "sdk" to Build.VERSION.SDK_INT
            )
        }

        private fun containsComponent(flatList: String, cn: ComponentName): Boolean {
            if (flatList.isBlank()) return false
            val full = cn.flattenToString()
            val pkgClass = "${cn.packageName}/${cn.className}"
            val relative = "${cn.packageName}/.${cn.className.removePrefix("${cn.packageName}.")}"
            return flatList.split(':', ',', ';').any { raw ->
                val part = raw.trim()
                if (part.isEmpty()) return@any false
                part.equals(full, ignoreCase = true) ||
                    part.equals(pkgClass, ignoreCase = true) ||
                    part.equals(relative, ignoreCase = true) ||
                    part.endsWith("/${cn.className}", ignoreCase = true) ||
                    part.endsWith(".Lp46PrintService", ignoreCase = true)
            }
        }

        fun openPrintSettings(context: Context) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_PRINT_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        return object : PrinterDiscoverySession() {
            override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
                addPrinters(listOf(buildPrinterInfo(generatePrinterId("lp46_dlite"))))
            }

            override fun onStopPrinterDiscovery() {}

            override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {
                addPrinters(printerIds.map { buildPrinterInfo(it) })
            }

            override fun onStartPrinterStateTracking(printerId: PrinterId) {
                addPrinters(listOf(buildPrinterInfo(printerId)))
            }

            override fun onStopPrinterStateTracking(printerId: PrinterId) {}

            override fun onDestroy() {}
        }
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        try {
            printJob.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "cancel failed", e)
        }
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        // This callback is already on the main thread. Touch PrintJob APIs here only.
        try {
            printJob.start()
            val doc = printJob.document
                ?: throw IllegalStateException("No print document")
            val data = doc.data
                ?: throw IllegalStateException("No print data")
            val name = printJob.info?.label ?: "Website print"

            // Dup FD so we can close the original and copy off the main thread.
            val dup = try {
                ParcelFileDescriptor.dup(data.fileDescriptor)
            } catch (e: Exception) {
                // Fallback: copy on main thread (label PDFs are usually small).
                Log.w(TAG, "dup failed, copying on main thread", e)
                val outFile = copyPdfOnCurrentThread(data)
                finishHandoff(printJob, outFile, name)
                return
            }

            try {
                data.close()
            } catch (_: Exception) {
            }

            executor.execute {
                try {
                    val outFile = copyFromPfd(dup)
                    mainHandler.post {
                        finishHandoff(printJob, outFile, name)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Background copy failed", e)
                    try {
                        dup.close()
                    } catch (_: Exception) {
                    }
                    mainHandler.post {
                        try {
                            printJob.fail(e.message ?: "Print failed")
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onPrintJobQueued failed", e)
            try {
                printJob.fail(e.message ?: "Print failed")
            } catch (_: Exception) {
            }
        }
    }

    private fun finishHandoff(printJob: PrintJob, outFile: File, name: String) {
        try {
            if (!outFile.exists() || outFile.length() == 0L) {
                printJob.fail("Empty print document from browser")
                return
            }
            lastJobPath = outFile.absolutePath
            lastJobName = name
            openAppWithJob(outFile.absolutePath, name)
            showJobNotification(outFile.absolutePath, name)
            printJob.complete()
        } catch (e: Exception) {
            Log.e(TAG, "finishHandoff failed", e)
            try {
                printJob.fail(e.message ?: "Print failed")
            } catch (_: Exception) {
            }
        }
    }

    private fun copyPdfOnCurrentThread(data: ParcelFileDescriptor): File {
        val cache = File(cacheDir, "print_jobs").apply { mkdirs() }
        val outFile = File(cache, "job_${System.currentTimeMillis()}.pdf")
        data.use { pfd ->
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
        }
        return outFile
    }

    private fun copyFromPfd(pfd: ParcelFileDescriptor): File {
        val cache = File(cacheDir, "print_jobs").apply { mkdirs() }
        val outFile = File(cache, "job_${System.currentTimeMillis()}.pdf")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
            FileOutputStream(outFile).use { output -> input.copyTo(output) }
        }
        return outFile
    }

    private fun openAppWithJob(path: String, name: String) {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                action = ACTION_NEW_PRINT_JOB
                putExtra(EXTRA_PRINT_JOB_PATH, path)
                putExtra(EXTRA_PRINT_JOB_NAME, name)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start activity — use notification", e)
        }
    }

    private fun showJobNotification(path: String, name: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Print jobs",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Website / system print jobs"
                }
            )
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = ACTION_NEW_PRINT_JOB
            putExtra(EXTRA_PRINT_JOB_PATH, path)
            putExtra(EXTRA_PRINT_JOB_NAME, name)
        }
        val pending = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Print ready: FatFox Driver")
            .setContentText(name)
            .setStyle(
                Notification.BigTextStyle()
                    .bigText("Tap to open and print \"$name\".")
            )
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun buildPrinterInfo(printerId: PrinterId): PrinterInfo {
        val caps = PrinterCapabilitiesInfo.Builder(printerId)
            // A5 landscape: 210×148 mm = 8268×5827 mils (1/1000 inch)
            .addMediaSize(
                PrintAttributes.MediaSize("A5_LANDSCAPE", "A5 Landscape (Bill)", 8268, 5827),
                true
            )
            .addMediaSize(
                PrintAttributes.MediaSize("A5_PORTRAIT", "A5 Portrait", 5827, 8268),
                false
            )
            .addMediaSize(PrintAttributes.MediaSize.ISO_A5, false)
            .addMediaSize(PrintAttributes.MediaSize.ISO_A4, false)
            // 4×6 shipping label
            .addMediaSize(
                PrintAttributes.MediaSize("LABEL_4X6", "4×6 Label", 4000, 6000),
                false
            )
            .addMediaSize(
                PrintAttributes.MediaSize("LABEL_4X3", "4×3 Label", 4000, 3000),
                false
            )
            .addResolution(
                PrintAttributes.Resolution("203dpi", "203 DPI", 203, 203),
                true
            )
            .setColorModes(
                PrintAttributes.COLOR_MODE_MONOCHROME,
                PrintAttributes.COLOR_MODE_MONOCHROME
            )
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()

        return PrinterInfo.Builder(printerId, "FatFox Driver (USB/Wi‑Fi)", PrinterInfo.STATUS_IDLE)
            .setCapabilities(caps)
            .setDescription("A5 bills + label sheets · USB / Wi‑Fi GDI · Blaze SD‑30NW")
            .build()
    }
}
