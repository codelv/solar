package com.codelv.solar

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow


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
}
