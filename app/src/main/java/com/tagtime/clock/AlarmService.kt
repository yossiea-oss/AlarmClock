package com.tagtime.clock

import android.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import android.util.Log

class AlarmService : Service() {
    private var ringtone: Ringtone? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isWatchdogPaused = false
    private var lockedAlarmVolume: Int? = null
    
    private val resumeWatchdogRunnable = Runnable {
        isWatchdogPaused = false
        Log.d("AlarmService", "Watchdog: Auto-resumed after timeout")
    }

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!isWatchdogPaused) {
                if (lockedAlarmVolume != null) {
                    val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, lockedAlarmVolume!!, 0)
                }

                Log.d("AlarmService", "Watchdog: Bringing activity to front")
                val ringingIntent = Intent(this@AlarmService, MainActivity::class.java).apply {
                    this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    putExtra("ALARM_RINGING", true)
                }
                startActivity(ringingIntent)
            }
            handler.postDelayed(this, 1000) // Every 1 second for higher enforcement
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("AlarmService", "AlarmService started with intent: ${intent?.action}")

        if (intent?.action == "PAUSE_WATCHDOG") {
            isWatchdogPaused = true
            handler.removeCallbacks(resumeWatchdogRunnable)
            handler.postDelayed(resumeWatchdogRunnable, 5000) // 5 seconds timeout
            return START_STICKY
        } else if (intent?.action == "RESUME_WATCHDOG") {
            isWatchdogPaused = false
            handler.removeCallbacks(resumeWatchdogRunnable)
            return START_STICKY
        }

        val channelId = "alarm_channel"
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            channelId,
            "Alarm Notifications",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Channel for alarm ringing notifications"
            setSound(null, null)
            // Explicitly set the volume stream to be controlled by this notification/service
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), null) 
        }
        notificationManager.createNotificationChannel(channel)

        val ringingIntent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra("ALARM_RINGING", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            ringingIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Use the notification itself to enforce volume control via its channel/priority
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Alarm Ringing")
            .setContentText("Scan your NFC tag or QR code to stop!")
            .setPriority(NotificationCompat.PRIORITY_MAX) // Highest priority ensures volume lock
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(false)
            .setOngoing(true)
            .build()

        val serviceType = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        } else {
            0
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, serviceType)
        } else {
            startForeground(1, notification)
        }

        startRinging()
        
        // Start the watchdog to prevent leaving the screen
        handler.post(watchdogRunnable)

        return START_STICKY
    }

    private fun startRinging() {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        if (lockedAlarmVolume == null) {
            lockedAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        }
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, lockedAlarmVolume!!, 0)

        if (ringtone?.isPlaying == true) return
        val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ringtone = RingtoneManager.getRingtone(applicationContext, notificationUri)
        ringtone?.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(watchdogRunnable)
        ringtone?.stop()
        lockedAlarmVolume = null
        Log.d("AlarmService", "AlarmService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
