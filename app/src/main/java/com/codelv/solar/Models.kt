package com.codelv.solar

import android.bluetooth.BluetoothDevice
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Date
import kotlin.math.abs
import kotlin.math.max

data class Snapshot(
    val time: Date,
    val solarVoltage: Double,
    val chargerVoltage: Double,
    val chargerCurrent: Double,
    val chargerEnergy: Double,
    val batteryVoltage: Double,
    val batteryCurrent: Double,
    val batteryRemainingAh: Double,
)

data class UserPreferences(
    val batteryMonitorAddres: String? = null,
    val solarChargerAddress: String? = null,
)



class AppViewModel : ViewModel() {
    var prefs = UserPreferences()
    var solarVoltage = mutableStateOf(0.0)
    var chargerVoltage = mutableStateOf(0.0)
    var chargerMinVoltage = mutableStateOf(0.0)
    var chargerMaxVoltage = mutableStateOf(0.0)
    var chargerCurrent = mutableStateOf(0.0)
    var chargerTodayEnergy = mutableStateOf(0.0)
    var chargerTotalChargeEnergy = mutableStateOf(0.0)
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
    var chartAutoscroll = mutableStateOf(true)
    var chartXStep = mutableStateOf(10.0)

    var inverterCurrent = derivedStateOf {
        // If charger is not connected don't show this
        if (chargerVoltage.value > 0 && batteryVoltage.value > 0)
            abs(chargerCurrent.value + (if (batteryCharging.value) -1 else 1) * batteryCurrent.value)
        else
            0.0
    }

    var inverterPower = derivedStateOf {
        if (chargerVoltage.value > 0 && batteryVoltage.value > 0)
            inverterCurrent.value * max(chargerVoltage.value, batteryVoltage.value)
        else
            0.0
    }

    var inverterEnergy = derivedStateOf {
        max(0.0, chargerTodayEnergy.value - batteryTotalChargeEnergy.value)
    }
    var chargePower = derivedStateOf {
        chargerVoltage.value * chargerCurrent.value
    }
    var solarCurrent = derivedStateOf {
        if (solarVoltage.value > 0) chargePower.value / solarVoltage.value else 0.0
    }
    var batteryPercentage = derivedStateOf {
        if (batteryCapacity.value > 0) batteryRemainingAh.value / batteryCapacity.value * 100 else 0.0
    }

    var lastUpdate = mutableStateOf(Date())
    var history = mutableStateListOf<Snapshot>()
    val mutex = Mutex()

    val solarPowerModelProducer = CartesianChartModelProducer()
    val batteryPowerModelProducer = CartesianChartModelProducer()
    val inverterPowerModelProducer = CartesianChartModelProducer()
    val currentsModelProducer = CartesianChartModelProducer()
    val voltagesModelProducer = CartesianChartModelProducer()


    suspend fun snapshot() {
        val t = Date()
        val sign = if (batteryCharging.value) 1 else -1
        mutex.withLock {
            history.add(
                Snapshot(
                    time = t,
                    chargerCurrent = chargerCurrent.value,
                    chargerVoltage = chargerVoltage.value,
                    chargerEnergy = chargerTodayEnergy.value,
                    solarVoltage = solarVoltage.value,
                    batteryCurrent = sign * batteryCurrent.value,
                    batteryVoltage = batteryVoltage.value,
                    batteryRemainingAh = batteryRemainingAh.value
                )
            )
        }
        lastUpdate.value = t
//        if (history.size > 60 * 60 * 24) {
//            history.removeAt(0)
//        }
    }

}
