package com.example.alarmclock

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

class AlarmViewModel : ViewModel() {
    var alarmHour by mutableIntStateOf(8)
    var alarmMinute by mutableIntStateOf(0)
    var isAlarmEnabled by mutableStateOf(false)
    var linkedTagId by mutableStateOf<ByteArray?>(null)
    var isRinging by mutableStateOf(false)
    var isScanningForLink by mutableStateOf(false)
    var canDrawOverlays by mutableStateOf(true)
}
