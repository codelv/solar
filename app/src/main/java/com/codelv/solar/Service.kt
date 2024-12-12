package com.codelv.solar

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcelable
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.codelv.solar.MonitorService.Companion.ACTION_BATTERY_MONITOR_DATA_AVAILABLE
import com.codelv.solar.MonitorService.Companion.ACTION_GATT_CONNECTED
import com.codelv.solar.MonitorService.Companion.ACTION_GATT_DATA_AVAILABLE
import com.codelv.solar.MonitorService.Companion.ACTION_GATT_DISCONNECTED
import com.codelv.solar.MonitorService.Companion.ACTION_GATT_SERVICES_DISCOVERED
import com.codelv.solar.MonitorService.Companion.ACTION_SOLAR_CHARGER_DATA_AVAILABLE
import com.codelv.solar.MonitorService.Companion.BATTERY_MONITOR_CONF_CHARACTERISTIC_UUID
import com.codelv.solar.MonitorService.Companion.BATTERY_MONITOR_DATA_CHARACTERISTIC_UUID
import com.codelv.solar.MonitorService.Companion.BATTERY_MONITOR_DATA_DESCRIPTOR_UUID
import com.codelv.solar.MonitorService.Companion.BATTERY_MONITOR_DATA_SERVICE_UUID
import com.codelv.solar.MonitorService.Companion.BATTERY_MONITOR_DATA_TYPE
import com.codelv.solar.MonitorService.Companion.BATTERY_MONITOR_DATA_VALUE
import com.codelv.solar.MonitorService.Companion.DEVICE_INFO_SERVICE_UUID
import com.codelv.solar.MonitorService.Companion.DEVICE_MODEL_CHARACTERISTIC_UUID
import com.codelv.solar.MonitorService.Companion.SOLAR_CHARGER_DATA_CHARACTERISTIC_UUID
import com.codelv.solar.MonitorService.Companion.SOLAR_CHARGER_DATA_DESCRIPTOR_UUID
import com.codelv.solar.MonitorService.Companion.SOLAR_CHARGER_DATA_SERVICE_UUID
import com.codelv.solar.MonitorService.Companion.SOLAR_CHARGER_DATA_TYPE
import com.codelv.solar.MonitorService.Companion.SOLAR_CHARGER_DATA_VALUE
import kotlinx.android.parcel.Parcelize
import java.time.LocalTime
import java.util.Date
import java.util.UUID

const val TAG = "BluetoothLeService"

const val STATE_DISCONNECTED = 0
const val STATE_CONNECTED = 2
const val POLL_PERIOD: Long = 1000
const val MAX_READ_BUFFER_SIZE = 64000

infix fun Byte.shr(that: Int): Byte = this.toInt().shr(that).toByte()
infix fun UShort.shr(that: Int): UShort = this.toInt().shr(that).toUShort()

fun ByteArray.indexOfAny(vararg values: Byte): Int {
    var lowest: Int? = null;
    values.map { this.indexOf(it) }.forEach {
        if (it >= 0 && (lowest == null || it < lowest!!)) {
            lowest = it
        }
    }
    return if (lowest == null) -1 else lowest!!
}

enum class DeviceType {
    Unknown,
    SolarCharger,
    BatteryMonitor
}

@Parcelize
data class ChargerHistory(
    var index: Int, // Day offset from today. 0 - today, 1 = yesterday, etc..
    var totalEnergy: Double,
    var loadEnergy: Double,
    var peakPower: Double,
    var minVoltage: Double,
    var maxVoltage: Double,
) : Parcelable

@Parcelize
data class VoltageCurrent(
    var voltage: Double,
    var current: Double,
) : Parcelable

enum class BluetoothActionType {
    Read, // Callback on read
    Write, // Callback on write
    WriteRead, // Callback on next change response
}

enum class BluetoothActionStatus {
    Pending,
    Complete,
    Aborted
}


data class BluetoothAction(
    val type: BluetoothActionType,
    var characteristic: BluetoothGattCharacteristic? = null,
    var descriptor: BluetoothGattDescriptor? = null,
    // Data to write
    var value: ByteArray? = null,
    // Optional callback to invoke when the action is completed
    var callback: ((action: BluetoothAction, value: ByteArray?) -> Unit)? = null,
    var timeout: Int = 5000,
    var status: BluetoothActionStatus = BluetoothActionStatus.Pending
) {

}

enum class ChargerStatus(val code: Int) {
    Standby(0x00),
    Starting(0x01),
    MPPT(0x02),
    Equalize(0x03),
    Boost(0x04),
    Float(0x05),
    CurrentLimited(0x06);

    companion object {
        fun fromInt(value: Int) = ChargerStatus.values().firstOrNull { it.code == value }

    }
}

enum class SolarChargerDataType(val code: Int) {
    ChargeVoltage(0x01),
    ChargeCurrent(0x02),
    SolarVoltage(0x03),
    TodayChargeEnergy(0x04),
    TodayPeakPower(0x05),
    TodayMinBatteryVoltage(0x06),
    TodayMaxBatteryVoltage(0x07),
    ChargerTemp(0x09), // in C
    BatteryTemp(0x0A), // in C
    TotalChargeEnergy(0x0B),
    TodayDcLoadEnergy(0x0C),
    HistoryData(0x0D),
    ChargerStatus(0x0E);

    companion object {
        fun fromInt(value: Int) = SolarChargerDataType.values().firstOrNull { it.code == value }
    }
}

@Parcelize
data class BatteryMonitorHistoryRecord(
    var date: Date,
    var size: Int,
) : Parcelable


enum class BatteryMonitorDataType(val code: Int) {
    RecordedVoltage(0xa0),
    RecordedChargeCurrent(0xa1),
    RecordedDischargeCurrent(0xa2),
    RecordedData(0xaa),

    BatteryCapacity(0xb0),
    OverTempProtection(0xb1),
    VoltageAlign(0xb2),
    CurrentAlign(0xb3),
    TemperatureEnabled(0xb4),
    LanStatus(0xb7),
    LiveDataStart(0xbb),


    Voltage(0xc0),
    Current(0xc1),
    SetItem2(0xc2),
    SetItem3(0xc3),
    LanAddr(0xc4),
    OverVoltageProtectionStatus(0xc5),

    Status(0xd0),
    IsCharging(0xd1),
    RemainingAh(0xd2),
    TotalDischargeEnergy(0xd3),
    TotalChargeEnergy(0xd4),

    // Appears to be max of 999999
    RecordProgressInMinutes(0xd5),
    RemainingTimeInMinutes(0xd6),
    Power(0xd8),
    TempData(0xd9), // in C

    Config(0xe0),
    VersionInfo(0xe2),
    LowTempProtectionLevel(0xe3),
    DataEnd(0xee),

    IsRecording(0xf1),
    RecordedDataStartDate(0xf2),
    RecordedDataStartTime(0xf3),
    RecordedDataIndex(0xf4),
    Password(0xf6),
    IsTempInFahrenheit(0xf7);

    companion object {
        fun fromByte(value: Byte) =
            BatteryMonitorDataType.values().firstOrNull { it.code == value.toUByte().toInt() }

        fun fromInt(value: Int) = BatteryMonitorDataType.values().firstOrNull { it.code == value }
    }
}

class BatteryMonitorCommands {
    companion object {
        fun setBatteryCapacity(capacity: Double): ByteArray {
            val v: UShort =
                if (capacity > 9999.9) 99999.toUShort() else (10 * capacity).toInt().toUShort();
            val low = (v and 0xFF.toUShort()).toByte()
            val high = (v shr 0x08 and 0xFF.toUShort()).toByte()
            return byteArrayOf(0x9a.toByte(), 0xb0.toByte(), high, low, 0xb0.toByte())
        }

        // Must be sent as two commands. A difference of 1 will return 60 value pairs (one for each second)
        // Will be a data packet starting with 0xAA and ending with 0xEE. Values between
        // will be 0xA0, 0xA1, or 0xA2.
        fun loadRecording(size: Int, index: Int): List<ByteArray> {
            return listOf(
                byteArrayOf(
                    0xBB.toByte(),
                    size.toByte(),
                    0xB9.toByte(),
                    0x0C.toByte(),
                    0xEE.toByte()
                ),
                byteArrayOf(
                    0xBB.toByte(),
                    index.toByte(),
                    0xB8.toByte(),
                    0x0C.toByte(),
                    0xEE.toByte()
                )
            )
        }

        // bb9aa90cee
        val HOME_DATA =
            byteArrayOf(0xBB.toByte(), 0x9A.toByte(), 0xA9.toByte(), 0x0C.toByte(), 0xEE.toByte())
        val RECORD_HISTORY =
            byteArrayOf(0xBB.toByte(), 0x31.toByte(), 0xf4.toByte(), 0x0C.toByte(), 0xEE.toByte())
        val LAN_ON = byteArrayOf(0x9A.toByte(), 0xF1.toByte(), 0x01.toByte(), 0xF1.toByte())
        val LAN_OFF = byteArrayOf(0x9A.toByte(), 0xF1.toByte(), 0x00.toByte(), 0xF1.toByte())
        val CLEAR_DATA = byteArrayOf(0x00.toByte(), 0xf8.toByte(), 0x9a.toByte(), 0xf8.toByte())
    }
}

class SolarChargerCommands {
    companion object {
        fun crc(cmd: ByteArray): ByteArray {
            var v = 0xFFFF
            for (c in cmd) {
                v = v.xor(c.toUByte().toInt())
                (0..7).forEach {
                    val x = v.shr(1)
                    v = if (v.and(1) == 1) x.xor(0xA001) else x
                }
            }
            val high = (v shr 8) and 0xFF
            val low = v and 0xFF
            return byteArrayOf(low.toByte(), high.toByte())
        }

        // Load history of day today-now
        fun loadHistory(days: Int): ByteArray {
            var cmd = byteArrayOf(0x01.toByte(), 0x03.toByte(), 0x04.toByte())
            cmd += days.toByte()
            cmd += 0x00.toByte()
            cmd += 0x05.toByte()
            cmd += crc(cmd)
            return cmd
        }

        val HOME_DATA = byteArrayOf(0x01, 0x03, 0x01, 0x01, 0x00, 0x13, 0x54, 0x3B)

        // This is the same as loadHistory(0)
        val CHART_DATA =
            byteArrayOf(0x01, 0x03, 0x04, 0x00, 0x00, 0x05, 0x84.toByte(), 0xF9.toByte())
    }
}

@OptIn(ExperimentalStdlibApi::class)
class MonitorConnection(val device: BluetoothDevice, val service: MonitorService) {
    var deviceType: MutableState<DeviceType> = mutableStateOf(DeviceType.Unknown)
    var tag: String? = null
    var connectionState: MutableState<Int> = mutableStateOf(STATE_DISCONNECTED)
    var lastUpdated: MutableState<Date> = mutableStateOf(Date())
    var bluetoothGatt: BluetoothGatt? = null
    var pendingActions: MutableList<BluetoothAction> = mutableListOf()
    var currentAction: BluetoothAction? = null
    var lastCharacteristicWrite: MutableState<ByteArray> = mutableStateOf(byteArrayOf())
    var lastCharacteristicRead: MutableState<ByteArray> = mutableStateOf(byteArrayOf())

    var isTempInF: Boolean? = null

    var readBuffer: ByteArray = byteArrayOf()

    var handler = Handler(Looper.getMainLooper())

    @RequiresPermission(BLUETOOTH_CONNECT)
    fun connect() {
        if (bluetoothGatt != null) {
            return
        }
        tag = "Bt.${device.name}"
        bluetoothGatt = device.connectGatt(service, true, bluetoothGattCallback)
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    fun sync(callback: ((action: BluetoothAction, value: ByteArray?) -> Unit)? = null): Boolean {
        if (connectionState.value != STATE_CONNECTED || bluetoothGatt == null) {
            return false;
        }
        when (deviceType.value) {
            DeviceType.BatteryMonitor -> {
                val service =
                    bluetoothGatt?.getService(UUID.fromString(BATTERY_MONITOR_DATA_SERVICE_UUID))
                queueAction(
                    BluetoothAction(
                        BluetoothActionType.WriteRead,
                        characteristic = service?.getCharacteristic(
                            UUID.fromString(
                                BATTERY_MONITOR_CONF_CHARACTERISTIC_UUID
                            )
                        ),
                        value = BatteryMonitorCommands.HOME_DATA,
                        callback = callback
                    )
                )

            }

            DeviceType.SolarCharger -> {
                val service =
                    bluetoothGatt?.getService(UUID.fromString(SOLAR_CHARGER_DATA_SERVICE_UUID))
                queueAction(
                    BluetoothAction(
                        BluetoothActionType.WriteRead,
                        characteristic = service?.getCharacteristic(
                            UUID.fromString(
                                SOLAR_CHARGER_DATA_CHARACTERISTIC_UUID
                            )
                        ),
                        value = SolarChargerCommands.HOME_DATA,
                        callback = callback
                    )
                )
            }

            else -> {
                return false
            }
        }
        return true
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    fun writeCommand(
        value: ByteArray,
        timeout: Int,
        read: Boolean = true,
        callback: ((action: BluetoothAction, value: ByteArray?) -> Unit)? = null,
    ): Boolean {
        if (bluetoothGatt != null && connectionState.value == STATE_CONNECTED) {
            var characteristic: BluetoothGattCharacteristic? = null
            when (deviceType.value) {
                DeviceType.BatteryMonitor -> {
                    val service = bluetoothGatt!!.getService(
                        UUID.fromString(
                            BATTERY_MONITOR_DATA_SERVICE_UUID
                        )
                    )
                    if (service != null) {
                        characteristic = service.getCharacteristic(
                            UUID.fromString(
                                BATTERY_MONITOR_CONF_CHARACTERISTIC_UUID
                            )
                        )
                    }
                }

                DeviceType.SolarCharger -> {
                    val service = bluetoothGatt!!.getService(
                        UUID.fromString(
                            SOLAR_CHARGER_DATA_SERVICE_UUID
                        )
                    )
                    if (service != null) {
                        characteristic = service.getCharacteristic(
                            UUID.fromString(
                                SOLAR_CHARGER_DATA_CHARACTERISTIC_UUID
                            )
                        )
                    }
                }

                else -> {}
            }
            if (characteristic != null) {
                queueAction(
                    BluetoothAction(
                        if (read) BluetoothActionType.WriteRead else BluetoothActionType.Write,
                        characteristic = characteristic!!,
                        value = value,
                        callback = callback,
                        timeout = timeout,
                    )
                )
                return true
            }
        }
        Log.i(tag, "Write error. Device, service, or characteristic not found.")
        return false
    }

    /*
     * Sends all commands not continuing until the response for the previous one is written or read back
     * depending on the read flag.
     * The callback will be invoked once for each value. The timeout is per command.
     */
    @RequiresPermission(BLUETOOTH_CONNECT)
    fun writeAllCommands(
        values: List<ByteArray>,
        timeout: Int,
        read: Boolean = true,
        callback: ((action: BluetoothAction, value: ByteArray?) -> Unit)? = null
    ): Boolean {
        if (values.size > 1) {
            return writeCommand(values.first(), timeout, read) { action, value ->
                callback?.invoke(action, value)
                action.status = BluetoothActionStatus.Complete
                writeAllCommands(values.subList(1, values.size), timeout, read, callback)
            }
        } else if (values.size == 1) {
            return writeCommand(values.first(), timeout, read, callback)
        }
        return true
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    fun queueAction(action: BluetoothAction) {
        // Log.d(tag, "Queuing action")
        pendingActions.add(action)
        if (currentAction == null) {
            processNextAction()
        }
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    fun abortAction(action: BluetoothAction) {
        if (pendingActions.contains(action)) {
            Log.d(tag, "Queued action aborted")
            pendingActions.remove(action)
        }
        if (action.status == BluetoothActionStatus.Pending) {
            finishAction(action, null, true)
        }
        if (action == currentAction) {
            currentAction = null
            processNextAction()
        }
    }

    fun finishAction(action: BluetoothAction, value: ByteArray? = null, aborted: Boolean) {
        if (action?.callback != null && action?.status == BluetoothActionStatus.Pending) {
            try {
                Log.d(tag, "Invoking action callback")
                action.callback?.invoke(action!!, value)
                action.status =
                    if (aborted) BluetoothActionStatus.Aborted else BluetoothActionStatus.Complete
            } catch (e: Exception) {
                Log.e(tag, "Callback exception: ${e}")
            }
        }
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    fun processNextAction(value: ByteArray? = null) {
        // Only one can occur at a time
        if (currentAction != null) {
            finishAction(currentAction!!, value, false)
        }
        if (pendingActions.size > 0) {
            var action = pendingActions.removeAt(0)
            currentAction = action;
            // Log.d(tag, "Processing action")
            if (action.characteristic != null) {
                val characteristic = action.characteristic!!
                when (action.type) {
                    BluetoothActionType.Read -> {
                        bluetoothGatt?.readCharacteristic(characteristic)
                    }

                    BluetoothActionType.Write,
                    BluetoothActionType.WriteRead -> {
                        characteristic.setValue(action.value!!)
                        Log.i(
                            tag,
                            "Write value=${action.value!!.toHexString()} (${action.value!!.size} byte(s)) to characteristic=${characteristic.uuid}"
                        )
                        bluetoothGatt?.writeCharacteristic(characteristic)
                    }

                    else -> {}
                }
            } else if (action.descriptor != null) {
                val desc = action.descriptor!!
                when (action.type) {
                    BluetoothActionType.Read -> {
                        bluetoothGatt?.readDescriptor(desc)
                    }

                    BluetoothActionType.Write,
                    BluetoothActionType.WriteRead -> {
                        desc.setValue(action.value!!)
                        Log.i(
                            tag,
                            "Write value=${action.value!!.toHexString()} (${action.value!!.size} byte(s)) to descriptor=${desc.uuid}"
                        )
                        bluetoothGatt?.writeDescriptor(desc)
                    }

                    else -> {}
                }
            } else {
                Log.w(tag, "Action has no characteristic or descriptor")
            }

            if (action.timeout > 0) {
                handler.postDelayed({ abortAction(action) }, action.timeout.toLong())
            }

        } else {
            currentAction = null;
            // Log.d(tag, "No more actions")
        }
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    fun close() {
        bluetoothGatt?.let { gatt ->
            gatt.close()
            bluetoothGatt = null
        }
    }

    val syncTask = object : Runnable {
        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun run() {
            if (connectionState.value != STATE_CONNECTED) {
                Log.w(tag, "Sync task stopped")
                return // Stop task on disconnect
            }
            val dt =
                Date().toInstant().toEpochMilli() - lastUpdated.value.toInstant().toEpochMilli();
            if (dt > 3 * POLL_PERIOD) {
                Log.w(tag, "Out of sync by ${dt}ms. Flushing all pending actions")
                lastUpdated.value = Date() // Don't try again on next poll
                pendingActions.clear();
                sync()
            }
            handler.postDelayed(this, POLL_PERIOD)
        }
    }

    val bluetoothGattCallback = object : BluetoothGattCallback() {
        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // successfully connected to the GATT Server
                lastUpdated.value = Date()
                connectionState.value = STATE_CONNECTED
                service.broadcastUpdate(device, ACTION_GATT_CONNECTED)
                // Attempts to discover services after successful connection.
                bluetoothGatt?.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // disconnected from the GATT Server
                connectionState.value = STATE_DISCONNECTED
                service.broadcastUpdate(device, ACTION_GATT_DISCONNECTED)
            }
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)
            Log.d(tag, "onDescriptorWrite desc=${descriptor?.uuid} status=${status}")
            processNextAction();
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun onDescriptorRead(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
            value: ByteArray
        ) {
            super.onDescriptorRead(gatt, descriptor, status, value)
            Log.d(
                tag,
                "onDescriptorRead desc=${descriptor.uuid} status=${status} value=${value.toHexString()}"
            )
            processNextAction(value);
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(tag, "onServicesDiscovered received: $status")
                return
            }
            lastUpdated.value = Date()
            val intent = Intent(ACTION_GATT_SERVICES_DISCOVERED)
            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device)
            service.sendBroadcast(intent)
            for (svc in gatt?.services!!) {
                Log.d(tag, "discovered service: ${svc.uuid}")
                for (c in svc.characteristics) {
                    Log.d(tag, "  characteristic: ${c.uuid} ")
                    for (desc in c.descriptors) {
                        Log.d(tag, "    descriptor: ${desc.uuid} ")
                    }
                }
                if (svc.uuid == UUID.fromString(DEVICE_INFO_SERVICE_UUID)) {
                    val c = svc.getCharacteristic(
                        UUID.fromString(
                            DEVICE_MODEL_CHARACTERISTIC_UUID
                        )
                    )
                    if (c != null) {
                        queueAction(
                            BluetoothAction(
                                BluetoothActionType.Read,
                                characteristic = c
                            )
                        )
                    }
                } else if (deviceType.value == DeviceType.Unknown && svc.uuid == UUID.fromString(
                        BATTERY_MONITOR_DATA_SERVICE_UUID
                    )
                ) {
                    Log.d(tag, "Found battery monitor data ID")
                    deviceType.value = DeviceType.BatteryMonitor
                    var c = svc.getCharacteristic(
                        UUID.fromString(BATTERY_MONITOR_DATA_CHARACTERISTIC_UUID)
                    )
                    if (c != null) {
                        Log.d(tag, "Found battery monitor data")
                        val d =
                            c.getDescriptor(UUID.fromString(BATTERY_MONITOR_DATA_DESCRIPTOR_UUID))
                        if (d != null) {
                            Log.d(tag, "Found battery monitor data config")
                            queueAction(
                                BluetoothAction(
                                    BluetoothActionType.Write,
                                    descriptor = d,
                                    value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                )
                            )
                        }
                        gatt.setCharacteristicNotification(c, true)
                        service.broadcastUpdate(
                            device,
                            MonitorService.ACTION_BATTERY_MONITOR_CONNECTED
                        )
                        handler.postDelayed({ sync() }, POLL_PERIOD / 2)
                        handler.postDelayed(syncTask, POLL_PERIOD)

                    }
                } else if (deviceType.value == DeviceType.Unknown && svc.uuid == UUID.fromString(
                        SOLAR_CHARGER_DATA_SERVICE_UUID
                    )
                ) {
                    Log.d(tag, "Found charger data ID")
                    deviceType.value = DeviceType.SolarCharger
                    val c = svc.getCharacteristic(
                        UUID.fromString(SOLAR_CHARGER_DATA_CHARACTERISTIC_UUID)
                    )
                    if (c != null) {
                        Log.d(tag, "Found charger data")
                        val d =
                            c.getDescriptor(UUID.fromString(SOLAR_CHARGER_DATA_DESCRIPTOR_UUID))
                        if (d != null) {
                            Log.d(tag, "Found charger data config")
                            queueAction(
                                BluetoothAction(
                                    BluetoothActionType.Write,
                                    descriptor = d,
                                    value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                )
                            )
                        }
                        gatt.setCharacteristicNotification(c, true)
                        service.broadcastUpdate(
                            device,
                            MonitorService.ACTION_SOLAR_CHARGER_CONNECTED
                        )
                        handler.postDelayed({ sync() }, POLL_PERIOD / 2)
                        handler.postDelayed(syncTask, POLL_PERIOD)
                    }
                }
            }
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.d(tag, "onCharacteristicRead uuid=${characteristic.uuid} status=${status}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (characteristic.uuid == UUID.fromString(DEVICE_MODEL_CHARACTERISTIC_UUID)) {
                    val v = characteristic.value.decodeToString()
                    Log.d(tag, "Device model is: ${v}")

                }
                service.broadcastUpdate(device, ACTION_GATT_DATA_AVAILABLE, characteristic)
            }
            lastCharacteristicRead.value = characteristic.value;
            if (currentAction != null) {
                val action = currentAction!!
                if (action.type == BluetoothActionType.Read && action.characteristic == characteristic) {
                    processNextAction(characteristic.value)
                } else {
                    Log.w(tag, "Read occurred for incorrect action")
                }
            }
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            Log.d(tag, "onCharacteristicWrite uuid=${characteristic?.uuid} status=${status}")
            super.onCharacteristicWrite(gatt, characteristic, status)
            if (characteristic != null) {
                lastCharacteristicWrite.value = characteristic!!.value;
                if (currentAction != null) {
                    val action = currentAction!!
                    if ((action.type == BluetoothActionType.Write || action.type == BluetoothActionType.WriteRead)
                        && action.characteristic == characteristic
                    ) {
                        if (action.type == BluetoothActionType.WriteRead) {
                            // Don't process next action until there is a change response or timeout
                        } else {
                            processNextAction(characteristic!!.value);
                        }
                    } else {
                        Log.w(tag, "Write occurred for incorrect action")
                    }
                }
            }
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            Log.d(tag, "onCharacteristicChanged ${characteristic.uuid}")
            lastCharacteristicRead.value = characteristic.value
            service.broadcastUpdate(device, ACTION_GATT_DATA_AVAILABLE, characteristic)

            when (characteristic.uuid) {
                UUID.fromString(BATTERY_MONITOR_DATA_CHARACTERISTIC_UUID) -> {
                    onBatteryMonitorData(characteristic.value)
                }

                UUID.fromString(SOLAR_CHARGER_DATA_CHARACTERISTIC_UUID) -> {
                    if (onSolarChargerData(characteristic.value)) {
                        // Sending both commands at the same time seems to mess up the response
                        // it appears to start overwriting so only queue the next command
                        //  after a successfull response
                        handler.postDelayed({
                            when (characteristic.value.size) {
                                43 -> {
                                    queueAction(
                                        BluetoothAction(
                                            BluetoothActionType.WriteRead,
                                            characteristic = characteristic,
                                            value = SolarChargerCommands.CHART_DATA,
                                        )
                                    )
                                }

                                15 -> {
                                    queueAction(
                                        BluetoothAction(
                                            BluetoothActionType.WriteRead,
                                            characteristic = characteristic,
                                            value = SolarChargerCommands.HOME_DATA,
                                        )
                                    )
                                }

                                else -> {}
                            }
                        }, POLL_PERIOD / 2)
                    }
                }

                else -> {

                }
            }

            if (currentAction != null) {
                var action = currentAction!!;
                if (action.type == BluetoothActionType.WriteRead && action.characteristic == characteristic) {
                    processNextAction(characteristic.value);
                } else {
                    Log.w(tag, "Change occurred for incorrect action")
                }
            }
        }


    }

    fun onSolarChargerData(data: ByteArray): Boolean {
        Log.d(tag, "onSolarChargerData ${data.size} bytes ${data.toHexString()}")
        // LiTime solar charger
        // Home data is 43 bytes
        // 0103260064010803e701072119000000000000023603af025c000000020000006300056a7200000000ad9a
        // 0103260064011f089602762115000000000000027f045815e400000004000000b2000bb88600000000367f
        if (data.size == 43 && data.sliceArray(0..2)
                .contentEquals(byteArrayOf(0x01, 0x03, 0x26))
        ) { // && lastCharacteristicWrite.contentEquals(SolarChargerCommands.HOME_DATA)) {
            sendUpdate(
                SolarChargerDataType.ChargeVoltage,
                data.sliceArray(5..6).toHexString().toInt(16).toDouble() / 10
            )

            sendUpdate(
                SolarChargerDataType.ChargeCurrent,
                data.sliceArray(7..8).toHexString().toInt(16).toDouble() / 100
                )
            sendUpdate(SolarChargerDataType.ChargerTemp, data[11].toHexString().toInt(16).toDouble())
            // Fix negative temps
            var t = data[12].toHexString().toInt(16);
            sendUpdate(SolarChargerDataType.BatteryTemp, (if (t < 128) t else (128-t)).toDouble())

            sendUpdate(
                SolarChargerDataType.SolarVoltage,
                data.sliceArray(19..20).toHexString().toInt(16).toDouble() / 10
            )

            sendUpdate(
                SolarChargerDataType.TodayPeakPower,
                data.sliceArray(21..22).toHexString().toInt(16).toDouble()
            )

            sendUpdate(
                SolarChargerDataType.TodayChargeEnergy,
                data.sliceArray(23..24).toHexString().toInt(16).toDouble()
            )
            sendUpdate(
                SolarChargerDataType.TotalChargeEnergy,
                data.sliceArray(33..36).toHexString().toInt(16).toDouble()
            )
            sendUpdate(SolarChargerDataType.ChargerStatus, data[28].toHexString().toInt(16))


            lastUpdated.value = Date()
            return true
        } else if (data.size == 15 && data.sliceArray(0..2)
                .contentEquals(byteArrayOf(0x01, 0x03, 0x0a))
        ) {
            // 01,03,0a,19,89,00,00,05,32,01,0f,01,00,f7,64
            if (lastCharacteristicWrite.value.size == 8 && lastCharacteristicWrite.value.toHexString()
                    .startsWith("010304")
            ) {
                val entry = ChargerHistory(
                    index = lastCharacteristicWrite.value[3].toUByte().toInt(),
                    totalEnergy = data.sliceArray(3..4).toHexString().toInt(16).toDouble(),
                    loadEnergy = data.sliceArray(5..6).toHexString().toInt(16).toDouble(),
                    peakPower = data.sliceArray(7..8).toHexString().toInt(16).toDouble(),
                    maxVoltage = data.sliceArray(9..10).toHexString().toInt(16).toDouble() / 10,
                    minVoltage = data.sliceArray(11..12).toHexString().toInt(16).toDouble() / 10
                )
                sendUpdate(
                    SolarChargerDataType.HistoryData,
                    entry
                )
                lastUpdated.value = Date()
                if (entry.index == 0) {
                    return true
                }
            }

        }
        return false
    }

    // Kotlin generics are useless...
    fun sendUpdate(dataType: SolarChargerDataType, value: Int) {
        val intent = Intent(ACTION_SOLAR_CHARGER_DATA_AVAILABLE)
        intent.putExtra(SOLAR_CHARGER_DATA_TYPE, dataType.code)
        intent.putExtra(SOLAR_CHARGER_DATA_VALUE, value)
        service.sendBroadcast(intent)
    }

    fun sendUpdate(dataType: SolarChargerDataType, value: Double) {
        val intent = Intent(ACTION_SOLAR_CHARGER_DATA_AVAILABLE)
        intent.putExtra(SOLAR_CHARGER_DATA_TYPE, dataType.code)
        intent.putExtra(SOLAR_CHARGER_DATA_VALUE, value)
        service.sendBroadcast(intent)
    }

    fun sendUpdate(dataType: SolarChargerDataType, value: ChargerHistory) {
        val intent = Intent(ACTION_SOLAR_CHARGER_DATA_AVAILABLE)
        intent.putExtra(SOLAR_CHARGER_DATA_TYPE, dataType.code)
        intent.putExtra(SOLAR_CHARGER_DATA_VALUE, value)
        service.sendBroadcast(intent)
    }

    fun discard(size: Int) {
        if (readBuffer.size >= size) {
            readBuffer = byteArrayOf()
        } else {
            readBuffer = readBuffer.sliceArray(size..readBuffer.size)
        }
    }

    fun onBatteryMonitorData(data: ByteArray) {
        Log.d(tag, "onBatteryMonitorData ${data.size} bytes ${data.toHexString()}")
        if (data.size == 0) {
            return
        }
        readBuffer += data

        // The Junctek battery monitor uses some weird format where the data
        // is like bb2043c1052954d825ee.
        // Each field/data is separated by hex encoded codes (eg c1, d8)
        // Must convert to hex string (little endian) or toDouble uses incorrect base (16)
        // Might also come in chunks after a query is sent like
        // bb1516d61186d710eebb0600c1015930d840eebb
        // 925430d5331782d203477174d4124134f385eebb
        // 0615c1016328d863eebb925431d5331784d20347
        // 7178d4124135f393eebb00f62655c00615c101d1
        // 925431d51516d61186d7016328d800d999d03317
        // 84d203299635d303477178d400c200c301c401f7
        // 00c500c700c600c800c95000b00255b10100b201
        // 00b30100b40100f500b501b600b700e400e500e6
        // 00e720e820e91152f000f100f800f9240801f212
        // 4135f300b801b94140e00964e10137e20255e345
        // eebb01d199d058eebb0629c1016699d899ee
        while (readBuffer.size > 0) {
            val start = readBuffer.indexOfAny(
                0xbb.toByte(),
                0xaa.toByte()
            )
            val end = readBuffer.indexOf(0xee.toByte())

            // Got end of packet but missing start
            if (end >= 0 && end <= start) {
                discard(end) // Discard what was used
                break;
            } else if (end < 0 || start < 0) {
                break; // Need to read more
            }
            assert(start >= 0 && end > start)
            val packet = readBuffer.sliceArray(start..end)
            discard(end)// Discard what was used
            // Log.d(tag, "Read buffer size remaining ${readBuffer.size}")
            var entry = ByteArray(0)
            // Log.d(tag, "onBatteryMonitorData found packet ${packet.size} bytes ${packet.toHexString()}")
            var lastVoltage = 0.0
            val history: MutableList<VoltageCurrent> = mutableListOf()
            val records: MutableList<BatteryMonitorHistoryRecord> = mutableListOf()


            for (c in packet.slice(1..packet.size - 1)) {
                // They treat anything with a hex character as a code
                if ((c.toUByte() and 0xF0.toUByte() >= 0xA0.toUByte()) || (c.toUByte() and 0x0F.toUByte() >= 0xA.toUByte())) {
                    val type = BatteryMonitorDataType.fromByte(c)
                    Log.i(
                        tag,
                        "Found packet type ${c.toHexString()} ${type?.name} with data ${entry.toHexString()}"
                    )
                    val intent = Intent(ACTION_BATTERY_MONITOR_DATA_AVAILABLE)

                    when (type) {
                        BatteryMonitorDataType.Voltage,
                        BatteryMonitorDataType.Current,
                        BatteryMonitorDataType.Power,
                        BatteryMonitorDataType.TotalChargeEnergy,
                        BatteryMonitorDataType.TotalDischargeEnergy -> {
                            if (entry.size > 0) {
                                intent.putExtra(
                                    BATTERY_MONITOR_DATA_VALUE,
                                    entry.toHexString().toDouble() / 100
                                )
                            }
                        }

                        BatteryMonitorDataType.RemainingAh -> {
                            if (entry.size > 0) {
                                intent.putExtra(
                                    BATTERY_MONITOR_DATA_VALUE,
                                    entry.toHexString().toDouble() / 1000
                                )
                            }
                        }

                        BatteryMonitorDataType.IsCharging, BatteryMonitorDataType.IsRecording -> {
                            if (entry.size > 0) {
                                intent.putExtra(
                                    BATTERY_MONITOR_DATA_VALUE,
                                    entry[0].toHexString().toInt() == 1
                                )
                            }
                        }

                        BatteryMonitorDataType.RemainingTimeInMinutes, BatteryMonitorDataType.RecordProgressInMinutes -> {
                            if (entry.size > 0) {
                                intent.putExtra(
                                    BATTERY_MONITOR_DATA_VALUE,
                                    entry.toHexString().toInt()
                                )
                            }
                        }

                        BatteryMonitorDataType.BatteryCapacity -> {
                            if (entry.size > 0) {
                                intent.putExtra(
                                    BATTERY_MONITOR_DATA_VALUE,
                                    entry.toHexString().toDouble() / 10
                                )
                            }
                        }

                        BatteryMonitorDataType.RecordedDataStartTime -> {
                            if (entry.size == 3) {
                                val h = entry[0].toHexString().toInt()
                                val m = entry[1].toHexString().toInt()
                                val s = entry[2].toHexString().toInt()
                                try {
                                    intent.putExtra(
                                        BATTERY_MONITOR_DATA_VALUE,
                                        LocalTime.of(h, m, s)
                                    )
                                } catch (e: Exception) {
                                    Log.e(tag, "Invalid time data ${e}")
                                }
                            }
                        }

                        BatteryMonitorDataType.RecordedDataStartDate -> {
                            if (entry.size == 3) {
                                val y = 2000 + entry[0].toHexString().toInt()
                                val m = entry[1].toHexString().toInt()
                                val d = entry[2].toHexString().toInt()
                                try {
                                    intent.putExtra(
                                        BATTERY_MONITOR_DATA_VALUE,
                                        Date(y, m, d)
                                    )
                                } catch (e: Exception) {
                                    Log.e(tag, "Invalid date data ${e}")
                                }
                            }

                        }
                        // Do not send broadcast for each of these
                        BatteryMonitorDataType.RecordedVoltage -> {
                            if (entry.size > 0) {
                                lastVoltage = entry.toHexString().toDouble() / 100
                            }
                        }
                        BatteryMonitorDataType.IsTempInFahrenheit -> {
                            if (entry.size > 0) {
                                isTempInF = entry.toHexString().toInt() == 1;
                            }
                        }
                        BatteryMonitorDataType.TempData -> {
                            if (entry.size > 0 && isTempInF != null) {
                                val v = entry.toHexString().toInt();

                                // When in C the temp is offset by 100 when in F its offset by 5
                                val t = if (isTempInF!!)
                                    ((v.toDouble() - 32 - 5) * 5 / 9)
                                else v.toDouble() - 100;
                                intent.putExtra(BATTERY_MONITOR_DATA_VALUE, t)

                            }
                        }

                        BatteryMonitorDataType.RecordedDischargeCurrent,
                        BatteryMonitorDataType.RecordedChargeCurrent -> {
                            if (entry.size > 0) {
                                val sign =
                                    if (type == BatteryMonitorDataType.RecordedDischargeCurrent) -1 else 1
                                history.add(
                                    VoltageCurrent(
                                        lastVoltage,
                                        sign * entry.toHexString().toDouble() / 100
                                    )
                                )
                            }
                        }

                        BatteryMonitorDataType.RecordedDataIndex -> {
                            if (entry.size == 10) {
                                try {
                                    val record = BatteryMonitorHistoryRecord(
                                        date = Date(
                                            2000 + entry[0].toHexString().toInt(),
                                            entry[1].toHexString().toInt(),
                                            entry[2].toHexString().toInt(),
                                            entry[3].toHexString().toInt(),
                                            entry[4].toHexString().toInt(),
                                            entry[5].toHexString().toInt(),
                                        ),
                                        size = entry.sliceArray(6..9).toHexString().toInt()
                                    )
                                    // Keep unique
                                    if (records.find { it.date == record.date || it.size == record.size } == null) {
                                        records.add(record)
                                    }
                                } catch (e: Exception) {
                                    Log.e(tag, "Error loading history record: ${e}")
                                }

                            }
                        }

                        else -> {
                            if (entry.size > 0) {
                                intent.putExtra(BATTERY_MONITOR_DATA_VALUE, entry)
                            }
                        }

                    }
                    if (intent.hasExtra(BATTERY_MONITOR_DATA_VALUE)) {
                        intent.putExtra(BATTERY_MONITOR_DATA_TYPE, c.toUByte().toInt())
                        service.sendBroadcast(intent)
                    }
                    entry = ByteArray(0)
                } else {
                    entry += c
                }
            }
            if (packet[0] == 0xaa.toByte()) {
                Log.i(tag, "Got history packet ${history.size}")
                if (history.size > 0) {
                    val intent = Intent(ACTION_BATTERY_MONITOR_DATA_AVAILABLE)
                    intent.putExtra(
                        BATTERY_MONITOR_DATA_TYPE,
                        BatteryMonitorDataType.RecordedData.code
                    )
                    intent.putExtra(BATTERY_MONITOR_DATA_VALUE, history.toTypedArray())
                    service.sendBroadcast(intent)
                } else if (records.size > 0) {
                    val intent = Intent(ACTION_BATTERY_MONITOR_DATA_AVAILABLE)
                    intent.putExtra(
                        BATTERY_MONITOR_DATA_TYPE,
                        BatteryMonitorDataType.RecordedDataIndex.code
                    )
                    intent.putExtra(BATTERY_MONITOR_DATA_VALUE, records.toTypedArray())
                    service.sendBroadcast(intent)
                }
            }

            lastUpdated.value = Date()
        }
        if (readBuffer.size >= MAX_READ_BUFFER_SIZE) {
            // If not getting the expected data for some reason, reset
            Log.w(tag, "Read buffer too large, discarding.")
            readBuffer = byteArrayOf()
        }
    }
}


class MonitorService : Service() {
    companion object {
        val DEVICE_INFO_SERVICE_UUID = "0000180A-0000-1000-8000-00805F9B34FB"
        val DEVICE_MODEL_CHARACTERISTIC_UUID = "00002A24-0000-1000-8000-00805F9B34FB"
        val BATTERY_MONITOR_DATA_SERVICE_UUID = "0000FFF0-0000-1000-8000-00805F9B34FB"
        val BATTERY_MONITOR_DATA_CHARACTERISTIC_UUID = "0000FFF1-0000-1000-8000-00805F9B34FB"
        val BATTERY_MONITOR_CONF_CHARACTERISTIC_UUID = "0000FFF2-0000-1000-8000-00805F9B34FB"
        val BATTERY_MONITOR_DATA_DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805F9B34FB"
        val SOLAR_CHARGER_DATA_SERVICE_UUID = "0000FFE0-0000-1000-8000-00805F9B34FB"
        val SOLAR_CHARGER_DATA_DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805F9B34FB"
        val SOLAR_CHARGER_DATA_CHARACTERISTIC_UUID = "0000FFE1-0000-1000-8000-00805F9B34FB"
        val ACTION_GATT_CONNECTED = "com.codelv.solar.ACTION_GATT_CONNECTED"
        val ACTION_GATT_DISCONNECTED = "com.codelv.solar.ACTION_GATT_DISCONNECTED"
        val ACTION_GATT_SERVICES_DISCOVERED = "com.codelv.solar.ACTION_GATT_SERVICES_DISCOVERED"
        val ACTION_GATT_DATA_AVAILABLE = "com.codelv.solar.ACTION_GATT_DATA_AVAILABLE"
        val EXTRA_CHARACTERISTIC = "com.codelv.solar.EXTRA_CHARACTERISTIC"

        val BATTERY_MONITOR_DATA_TYPE = "com.codelv.solar.BATTERY_MONITOR_DATA_TYPE"
        val BATTERY_MONITOR_DATA_VALUE = "com.codelv.solar.BATTERY_MONITOR_DATA_VALUE"
        val ACTION_BATTERY_MONITOR_CONNECTED = "com.codelv.solar.ACTION_BATTERY_MONITOR_CONNECTED"
        val ACTION_BATTERY_MONITOR_DATA_AVAILABLE =
            "com.codelv.solar.ACTION_BATTERY_MONITOR_DATA_AVAILABLE"
        val ACTION_SOLAR_CHARGER_CONNECTED = "com.codelv.solar.ACTION_SOLAR_CHARGER_CONNECTED"
        val ACTION_SOLAR_CHARGER_DATA_AVAILABLE =
            "com.codelv.solar.ACTION_SOLAR_CHARGER_DATA_AVAILABLE"
        val SOLAR_CHARGER_DATA_TYPE = "com.codelv.solar.SOLAR_CHARGER_DATA_TYPE"
        val SOLAR_CHARGER_DATA_VALUE = "com.codelv.solar.SOLAR_CHARGER_DATA_VALUE"
    }

    val connections: MutableList<MonitorConnection> = mutableListOf()
    val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService() = this@MonitorService
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    fun connect(device: BluetoothDevice) {
        if (this.connections.find { it.device == device } == null) {
            val conn = MonitorConnection(device, this)
            conn.connect()
            this.connections.add(conn)
        }
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    fun disconnect(device: BluetoothDevice) {
        val conn = this.connections.find { it.device == device }
        if (conn != null) {
            this.connections.remove(conn)
            conn.close()
        }
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    override fun onUnbind(intent: Intent?): Boolean {
        this.connections.forEach { it.close() }
        this.connections.clear()
        return super.onUnbind(intent)
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    fun broadcastUpdate(
        device: BluetoothDevice,
        action: String,
        characteristic: BluetoothGattCharacteristic? = null
    ) {
        val intent = Intent(action)
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device)
        if (characteristic != null) {
            intent.putExtra(EXTRA_CHARACTERISTIC, characteristic)
        }
        sendBroadcast(intent)
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    fun sync(callback: ((action: BluetoothAction, value: ByteArray?) -> Unit)? = null) {
        this.connections.forEach { it.sync(callback) }
    }

}