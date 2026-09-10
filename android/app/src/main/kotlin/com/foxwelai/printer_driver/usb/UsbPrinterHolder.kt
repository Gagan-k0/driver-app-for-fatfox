package com.foxwelai.driverforcanon.usb

import android.content.Context

/**
 * Single shared USB printer instance for MainActivity + PrintService.
 * Claiming the same USB device from two places was crashing website prints.
 */
object UsbPrinterHolder {
    @Volatile
    private var printer: Lp46UsbPrinter? = null

    fun get(context: Context): Lp46UsbPrinter {
        val existing = printer
        if (existing != null) return existing
        return synchronized(this) {
            printer ?: Lp46UsbPrinter(context.applicationContext).also {
                it.registerReceivers()
                printer = it
            }
        }
    }
}
