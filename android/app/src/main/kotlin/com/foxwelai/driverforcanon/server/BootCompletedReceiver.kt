package com.foxwelai.driverforcanon.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED
            || action == Intent.ACTION_LOCKED_BOOT_COMPLETED
            || action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            Log.i("BootCompletedReceiver", "Received system broadcast: $action — launching PrintFoxServerService")
            try {
                PrintFoxServerService.startServer(context)
            } catch (e: Exception) {
                Log.e("BootCompletedReceiver", "Failed to start service from broadcast=$action", e)
            }
        }
    }
}
