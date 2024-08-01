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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.max


class AppViewModel : ViewModel() {
    var solarVoltage = mutableStateOf(0.0)
    var chargeVoltage = mutableStateOf(0.0)
    var chargeCurrent = mutableStateOf(0.0)
    var chargeEnergy = mutableStateOf(0.0)
    var chargerTemp = mutableStateOf(0)
    var solarPeakPower = mutableStateOf(0.0)
    var batteryRemainingAh = mutableStateOf(0.0)
    var batteryCharging = mutableStateOf(false)
    var batteryCurrent = mutableStateOf(0.0)
    var batteryVoltage = mutableStateOf(0.0)
    var batteryTotalChargeEnergy = mutableStateOf(0.0)
    var batteryTotalDischargeEnergy = mutableStateOf(0.0)
    var batteryCapacity = mutableStateOf(0.0)
    var batteryTimeRemaining = mutableStateOf(0)
    var availableDevices = mutableStateListOf<BluetoothDevice>()
    var connectedDevices = mutableStateListOf<BluetoothDevice>()

    var inverterCurrent = derivedStateOf{
        abs(chargeCurrent.value + (if (batteryCharging.value) -1 else 1) * batteryCurrent.value)
    }

    var inverterPower = derivedStateOf {
        inverterCurrent.value * max(chargeVoltage.value, batteryVoltage.value)
    }
    var inverterEnergy = derivedStateOf {
        max(0.0, chargeEnergy.value - batteryTotalChargeEnergy.value)
    }
    var chargePower = derivedStateOf {
        chargeVoltage.value * chargeCurrent.value
    }
    var solarCurrent = derivedStateOf {
        if (solarVoltage.value > 0) chargePower.value / solarVoltage.value else 0.0
    }
    var batteryPercentage = derivedStateOf {
        if (batteryCapacity.value > 0) batteryRemainingAh.value / batteryCapacity.value * 100 else 0.0
    }

    var solarPanelsModelProducer = CartesianChartModelProducer()


}
