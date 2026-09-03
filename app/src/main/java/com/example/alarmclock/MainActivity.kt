package com.example.alarmclock

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.alarmclock.ui.theme.AlarmClockTheme
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    
    private val viewModel: AlarmViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(this, "Notification permission required for alarms", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Load persisted state
        val prefs = getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
        val savedTag = prefs.getString("linked_tag", null)
        if (savedTag != null) {
            viewModel.linkedTagId = hexStringToByteArray(savedTag)
        }
        viewModel.alarmHour = prefs.getInt("alarm_hour", 8)
        viewModel.alarmMinute = prefs.getInt("alarm_minute", 0)
        viewModel.isAlarmEnabled = prefs.getBoolean("alarm_enabled", false)
        
        // Show over lockscreen
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        
        dismissKeyguard()
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        enableEdgeToEdge()

        // Check if we were started by the AlarmReceiver
        if (intent?.getBooleanExtra("ALARM_RINGING", false) == true) {
            startRinging()
        }

        setContent {
            AlarmClockTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (viewModel.isRinging) {
                        AlarmRingingScreen()
                    } else {
                        AlarmSetupScreen(
                            hour = viewModel.alarmHour,
                            minute = viewModel.alarmMinute,
                            isEnabled = viewModel.isAlarmEnabled,
                            isLinked = viewModel.linkedTagId != null,
                            isScanning = viewModel.isScanningForLink,
                            onTimeClick = { showTimePicker() },
                            onToggleAlarm = { toggleAlarm(it) },
                            onScanTagClick = { viewModel.isScanningForLink = true },
                            onUnlinkTagClick = { unlinkTag() },
                            onOverlayPermissionClick = { requestOverlayPermission() },
                            isOverlayPermissionGranted = viewModel.canDrawOverlays
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupNfcForegroundDispatch()
        checkOverlayPermission()
    }

    private fun checkOverlayPermission() {
        viewModel.canDrawOverlays = Settings.canDrawOverlays(this)
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    override fun onPause() {
        super.onPause()
        NfcAdapter.getDefaultAdapter(this)?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d("NFC_SCAN", "onNewIntent received: ${intent.action}")
        
        setIntent(intent)
        
        if (intent.getBooleanExtra("ALARM_RINGING", false)) {
            dismissKeyguard()
            startRinging()
        }
        handleNfcIntent(intent)
    }

    private fun dismissKeyguard() {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            keyguardManager.requestDismissKeyguard(this, null)
        }
    }

    private fun handleNfcIntent(intent: Intent) {
        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        if (tag != null) {
            val tagId = tag.id
            Log.d("NFC_SCAN", "Tag detected: ${byteArrayToHexString(tagId)}")

            if (viewModel.isRinging) {
                Log.d("NFC_SCAN", "Alarm is ringing, checking tag...")
                if (viewModel.linkedTagId != null && viewModel.linkedTagId!!.contentEquals(tagId)) {
                    stopAlarm()
                } else {
                    Log.w("NFC_SCAN", "Wrong tag! Scanned: ${byteArrayToHexString(tagId)}")
                }
            } else if (viewModel.isScanningForLink) {
                viewModel.linkedTagId = tagId
                saveTagId(tagId)
                viewModel.isScanningForLink = false
                Log.d("NFC_SCAN", "Tag linked and saved!")
            }
        }
    }

    private fun saveTagId(tagId: ByteArray) {
        val prefs = getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("linked_tag", byteArrayToHexString(tagId)).apply()
    }

    private fun unlinkTag() {
        viewModel.linkedTagId = null
        viewModel.isAlarmEnabled = false
        cancelAlarm()
        val prefs = getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
        prefs.edit().remove("linked_tag").apply()
        saveAlarmState()
        Log.d("Alarm", "Tag unlinked")
    }

    private fun saveAlarmState() {
        val prefs = getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putInt("alarm_hour", viewModel.alarmHour)
            putInt("alarm_minute", viewModel.alarmMinute)
            putBoolean("alarm_enabled", viewModel.isAlarmEnabled)
            apply()
        }
    }

    private fun byteArrayToHexString(array: ByteArray): String {
        return array.joinToString("") { "%02x".format(it) }
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun showTimePicker() {
        TimePickerDialog(this, { _, h, m ->
            viewModel.alarmHour = h
            viewModel.alarmMinute = m
            saveAlarmState()
            if (viewModel.isAlarmEnabled) scheduleAlarm()
        }, viewModel.alarmHour, viewModel.alarmMinute, false).show()
    }

    private fun toggleAlarm(enabled: Boolean) {
        viewModel.isAlarmEnabled = enabled
        saveAlarmState()
        if (enabled) {
            scheduleAlarm()
        } else {
            cancelAlarm()
        }
    }

    private fun scheduleAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, viewModel.alarmHour)
            set(Calendar.MINUTE, viewModel.alarmMinute)
            set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) {
                add(Calendar.DATE, 1)
            }
        }

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            pendingIntent
        )
        Log.d("Alarm", "Alarm scheduled for ${calendar.time}")
    }

    private fun cancelAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        Log.d("Alarm", "Alarm cancelled")
    }

    private fun stopAlarm() {
        viewModel.isRinging = false
        
        val serviceIntent = Intent(this, AlarmService::class.java)
        stopService(serviceIntent)
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.cancel(1)

        viewModel.isAlarmEnabled = false
        saveAlarmState()
        Log.d("Alarm", "Alarm stopped via NFC")
    }

    private fun startRinging() {
        viewModel.isRinging = true
    }

    private fun setupNfcForegroundDispatch() {
        val adapter = NfcAdapter.getDefaultAdapter(this)
        if (adapter == null) {
            Log.e("NFC", "NFC is not available on this device")
            return
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        adapter.enableForegroundDispatch(this, pendingIntent, null, null)
    }
}

@Composable
fun AlarmSetupScreen(
    hour: Int,
    minute: Int,
    isEnabled: Boolean,
    isLinked: Boolean,
    isScanning: Boolean,
    onTimeClick: () -> Unit,
    onToggleAlarm: (Boolean) -> Unit,
    onScanTagClick: () -> Unit,
    onUnlinkTagClick: () -> Unit,
    onOverlayPermissionClick: () -> Unit,
    isOverlayPermissionGranted: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Alarm Clock",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        if (!isOverlayPermissionGranted) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                onClick = onOverlayPermissionClick,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(
                    text = "Permission Required: Click here to allow 'Display over other apps' to ensure the alarm screen pops up.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = TextAlign.Center
                )
            }
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        Surface(
            onClick = onTimeClick,
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.padding(16.dp)
        ) {
            val formattedTime = remember(hour, minute) {
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                }
                SimpleDateFormat("hh:mm a", Locale.getDefault()).format(cal.time)
            }
            Text(
                text = formattedTime,
                fontSize = 64.sp,
                fontWeight = FontWeight.Thin,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                maxLines = 1,
                softWrap = false
            )
        }
        
        Spacer(modifier = Modifier.height(48.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isLinked) MaterialTheme.colorScheme.secondaryContainer 
                                 else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isScanning) "READY TO SCAN..." 
                           else if (isLinked) "NFC TAG LINKED" 
                           else "NFC TAG REQUIRED",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isLinked) MaterialTheme.colorScheme.onSecondaryContainer 
                            else MaterialTheme.colorScheme.onErrorContainer
                )
                
                Text(
                    text = if (isScanning) "Tap tag against back of phone" 
                           else if (isLinked) "Use this tag to deactivate alarm" 
                           else "Link a tag to enable the alarm",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (isLinked) {
                        OutlinedButton(
                            onClick = onUnlinkTagClick
                        ) {
                            Text("Unlink")
                        }
                    }
                    Button(
                        onClick = onScanTagClick,
                        enabled = !isScanning
                    ) {
                        Text(if (isLinked) "Change Tag" else "Link Tag")
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp)),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (isEnabled) "Alarm is ON" else "Alarm is OFF",
                    style = MaterialTheme.typography.titleLarge
                )
                Switch(
                    checked = isEnabled,
                    onCheckedChange = onToggleAlarm,
                    enabled = isLinked
                )
            }
        }
        
        Spacer(modifier = Modifier.weight(1.2f))
    }
}

@Composable
fun AlarmRingingScreen() {
    BackHandler(enabled = true) {
        // Do nothing
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.error)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "⏰",
                fontSize = 64.sp
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "ALARM RINGING",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Black,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Surface(
            color = Color.Black.copy(alpha = 0.3f),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "SCAN NFC TAG TO DEACTIVATE",
                modifier = Modifier.padding(20.dp),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
        }
        
        Spacer(modifier = Modifier.height(48.dp))
        
        Text(
            text = "Tap your linked tag against the back of your phone to turn off the alarm.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.9f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}
