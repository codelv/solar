package com.codelv.solar

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor


class AppState {
    var solarVoltage: Double = 0.0
    var solarCurrent: Double = 0.0
    val solarPower: Double
        get() = this.solarVoltage * this.solarCurrent
    var chargeVoltage: Double = 0.0
    var chargeCurrent: Double = 0.0
    val chargePower: Double
        get() = this.chargeVoltage * this.chargeCurrent
    var batteryVoltage: Double = 0.0
    var batteryCurrent: Double = 0.0
    val batteryPower: Double
        get() = this.batteryVoltage * this.batteryCurrent
    var batteryStateOfCharge: Double = 0.0
    var batteryCapacity: Double = 0.0

}

class AppViewModel : ViewModel() {
    val mutableState = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = mutableState.asStateFlow()
    var availableDevices: MutableList<BluetoothDevice> = mutableStateListOf()
    val connectedDevices: MutableList<BluetoothDevice> = mutableStateListOf()
}
