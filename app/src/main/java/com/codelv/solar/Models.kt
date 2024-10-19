package com.codelv.solar

import android.bluetooth.BluetoothDevice
import android.os.Parcelable
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import kotlinx.android.parcel.Parcelize
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.Date
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.max

@Parcelize
data class Snapshot(
    val date: Date,
    val solarVoltage: Double,
    val chargerVoltage: Double,
    val chargerCurrent: Double,
    val chargerEnergy: Double,
    val batteryVoltage: Double,
    val batteryCurrent: Double,
    val batteryRemainingAh: Double,
) : Parcelable

data class UserPreferences(
    var batteryMonitorAddress: String? = null,
    var solarChargerAddress: String? = null,
) {
    companion object {
        suspend fun load(dataStore: DataStore<Preferences>): UserPreferences {
            return dataStore.data
                .catch { exception ->
                    // dataStore.data throws an IOException when an error is encountered when reading data
                    if (exception is IOException) {
                        Log.d("UserPreferences", "Preferences empty")
                        emit(emptyPreferences())
                    } else {
                        throw exception
                    }
                }.map { state ->
                    UserPreferences(
                        batteryMonitorAddress = state[stringPreferencesKey(UserPreferences::batteryMonitorAddress.name)],
                        solarChargerAddress = state[stringPreferencesKey(UserPreferences::solarChargerAddress.name)]
                    )
                }.first()
        }
    }

    suspend fun save(dataStore: DataStore<Preferences>) {
        dataStore.edit { state ->
            if (batteryMonitorAddress == null) {
                state.remove(stringPreferencesKey(::batteryMonitorAddress.name))
            } else {
                state[stringPreferencesKey(::batteryMonitorAddress.name)] = batteryMonitorAddress!!
            }
            if (solarChargerAddress == null) {
                state.remove(stringPreferencesKey(::solarChargerAddress.name))
            } else {
                state[stringPreferencesKey(::solarChargerAddress.name)] = solarChargerAddress!!
            }
        }
    }
}

data class ModelChangeAction(
    val clearRecordedData: Boolean = false,
    val clearChargerHistory: Boolean = false,
    val recordedData: Array<VoltageCurrent>? = null,
    val historyRecords: Array<BatteryMonitorHistoryRecord>? = null,
    val chargerHistory: ChargerHistory? = null,

    )

class AppViewModel : ViewModel() {
    var prefs = UserPreferences()
    var solarVoltage = mutableStateOf(0.0)
    var chargerStatus = mutableStateOf<ChargerStatus?>(null)
    var chargerVoltage = mutableStateOf(0.0)
    var chargerMinVoltage = mutableStateOf(0.0)
    var chargerMaxVoltage = mutableStateOf(0.0)
    var chargerCurrent = mutableStateOf(0.0)
    var chargerTodayEnergy = mutableStateOf(0.0)
    var chargerTotalChargeEnergy = mutableStateOf(0.0)

    var solarPeakPower = mutableStateOf(0.0)
    var batteryRemainingAh = mutableStateOf(0.0)
    var batteryCharging = mutableStateOf(false)
    var batteryCurrent = mutableStateOf(0.0)
    var batteryVoltage = mutableStateOf(0.0)
    var batteryTotalChargeEnergy = mutableStateOf(0.0)
    var batteryTotalDischargeEnergy = mutableStateOf(0.0)
    var batteryCapacity = mutableStateOf(0.0)
    var batteryTimeRemaining = mutableStateOf(0)
    var chargerTemp = mutableStateOf<Double?>(null)
    var batteryTemp = mutableStateOf<Double?>(null)
    var monitorTemp = mutableStateOf<Double?>(null)

    var availableDevices = mutableStateListOf<BluetoothDevice>()
    var connectedDevices = mutableStateListOf<BluetoothDevice>()
    var chartAutoscroll = mutableStateOf(true)
    var chartXStep = mutableStateOf(10.0)
    var historyAutoscroll = mutableStateOf(true)
    var historyXStep = mutableStateOf(10.0)


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

    // RecordedData cannot be updated without a lock
    // not sure how to do that from the broadcast receiver
    var pendingChanges = ConcurrentLinkedQueue<ModelChangeAction>()
    var lastBatteryRecordStartTime: MutableState<Date?> = mutableStateOf(null)
    var batteryRecordedData = mutableStateListOf<VoltageCurrent>()
    var batteryRecordMinutes = mutableStateOf(0)
    var batteryHistoryRecords = mutableStateListOf<BatteryMonitorHistoryRecord>()
    var batteryHistoryNumberOfDays = mutableStateOf(7)

    var chargerHistoryData = mutableStateMapOf<Int, ChargerHistory>()

    var lastUpdate = mutableStateOf(Date())
    var snapshots = mutableStateListOf<Snapshot>()
    val mutex = Mutex()
    var unsaved = mutableStateOf(false)

    val solarPowerModelProducer = CartesianChartModelProducer()
    val chargerHistoryModelProducer = CartesianChartModelProducer()

    val batteryRecordedDataModelProducer = CartesianChartModelProducer()
    val batteryPowerModelProducer = CartesianChartModelProducer()
    val inverterPowerModelProducer = CartesianChartModelProducer()
    val currentsModelProducer = CartesianChartModelProducer()
    val voltagesModelProducer = CartesianChartModelProducer()

    suspend fun syncPendingChanges() {
        mutex.withLock {
            while (pendingChanges.isNotEmpty()) {
                val action = pendingChanges.poll()
                if (action != null) {
                    if (action.clearRecordedData) {
                        batteryRecordedData.clear()
                    }
                    if (action.clearChargerHistory) {
                        chargerHistoryData.clear()
                    }
                    if (action.recordedData != null) {
                        batteryRecordedData.addAll(action.recordedData!!)
                        Log.w(TAG, "Updated recorded data ${batteryRecordedData.size}!")
                    }
                    if (action.historyRecords != null) {
                        batteryHistoryRecords.clear()
                        batteryHistoryRecords.addAll(action.historyRecords!!)
                        Log.w(TAG, "Updated history records  ${batteryHistoryRecords.size}!")
                    }
                    if (action.chargerHistory != null) {
                        val entry = action.chargerHistory!!
                        chargerHistoryData.put(entry.index, entry)
                        Log.w(TAG, "Updated charger history  ${chargerHistoryData.size}!")
                    }
                }
            }
        }
    }

    suspend fun snapshot() {
        val t = Date()
        val sign = if (batteryCharging.value) 1 else -1
        mutex.withLock {
            snapshots.add(
                Snapshot(
                    date = t,
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

    suspend fun load(dataStore: DataStore<Preferences>) {
        Log.d("State", "loading preferences")
        prefs = UserPreferences.load(dataStore)
    }

    suspend fun save(dataStore: DataStore<Preferences>) {
        Log.d("State", "saving preferences")
        prefs.save(dataStore)
        unsaved.value = false
    }
}
