package com.example.alarmclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("AlarmReceiver", "Alarm received!")

        // 1. Start the AlarmService to play sound and show notification
        val serviceIntent = Intent(context, AlarmService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        // 2. Launch MainActivity immediately to take focus
        // This works if "Display over other apps" is granted
        val ringingIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra("ALARM_RINGING", true)
        }
        context.startActivity(ringingIntent)
    }
}
