package com.codelv.solar

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import java.util.LinkedList
import java.util.Queue
import java.util.UUID
import kotlin.experimental.and

const val TAG = "BluetoothLeService"

const val STATE_DISCONNECTED = 0
const val STATE_CONNECTED = 2

enum class DeviceType {
    Unknown,
    SolarCharger,
    BatteryMonitor
}

enum class BluetoothActionDir {
    Read,
    Write
}

data class BluetoothAction(
    val dir: BluetoothActionDir,
    var characterisitc: BluetoothGattCharacteristic? = null,
    var descriptor: BluetoothGattDescriptor? = null,
)

enum class BatteryMonitorDataType(val code: Int) {
    OverTempProtection(0xb1),
    VoltageAlign(0xb2),
    CurrentAlign(0xb3),
    TemperatureEnabled(0xb4),
    LanStatus(0xb7),


    Voltage(0xc0),
    Current(0xc1),
    SetItem2(0xc2),
    SetItem3(0xc3),
    LanAddr(0xc4),
    OverVoltageProtectionStatus(0xc5),

    Status(0xd0),
    IsCharging(0xd1),
    RemainingAh(0xd2),
    DischargePower(0xd3),
    ChargePower(0xd4),
    RecordProgress(0xd5),
    ChargeTime(0xd6),
    Capacity(0xd8),
    TempData(0xd9),


    DataRecord(0xf1),
    Date(0xf2),
    Time(0xf3);

    companion object {
        fun fromByte(value: Byte) = BatteryMonitorDataType.values().firstOrNull { it.code == value.toUByte().toInt() }
        fun fromInt(value: Int) = BatteryMonitorDataType.values().firstOrNull { it.code == value }
    }
}

infix fun Byte.shr(that: Int): Byte = this.toInt().shr(that).toByte()

@OptIn(ExperimentalStdlibApi::class)
class MonitorService : Service() {
    companion object {
        val DEVICE_INFO_UUID = "0000180A-0000-1000-8000-00805F9B34FB"
        val DEVICE_MODEL_UUID = "00002A24-0000-1000-8000-00805F9B34FB"
        val DEVICE_DATA_UUID = "0000FFF0-0000-1000-8000-00805F9B34FB"
        val UART_DATA_UUID = "0000FFF1-0000-1000-8000-00805F9B34FB"
        val UART_DATA_DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805F9B34FB"
        val ACTION_GATT_CONNECTED = "com.codelv.solar.ACTION_GATT_CONNECTED"
        val ACTION_GATT_DISCONNECTED = "com.codelv.solar.ACTION_GATT_DISCONNECTED"
        val ACTION_GATT_SERVICES_DISCOVERED = "com.codelv.solar.ACTION_GATT_SERVICES_DISCOVERED"
        val ACTION_GATT_DATA_AVAILABLE = "com.codelv.solar.ACTION_GATT_DATA_AVAILABLE"
        val BATTERY_MONITOR_DATA_TYPE = "com.codelv.solar.BATTERY_MONITOR_DATA_TYPE"
        val BATTERY_MONITOR_DATA_VALUE = "com.codelv.solar.BATTERY_MONITOR_DATA_VALUE"
        val ACTION_BATTERY_MONITOR_DATA_AVAILABLE =
            "com.codelv.solar.ACTION_BATTERY_MONITOR_DATA_AVAILABLE"
    }

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        // Return this instance of LocalService so clients can call public methods.
        fun getService(): MonitorService = this@MonitorService
    }

    var deviceType: MutableState<DeviceType> = mutableStateOf(DeviceType.Unknown)
    var connectionState: MutableState<Int> = mutableStateOf(STATE_DISCONNECTED)
    var bluetoothDevice: BluetoothDevice? = null
    var bluetoothGatt: BluetoothGatt? = null
    var pendingActions: MutableList<BluetoothAction> = mutableListOf()
    var currentAction: BluetoothAction? = null

    @RequiresPermission(BLUETOOTH_CONNECT)
    fun connect(device: BluetoothDevice) {
        bluetoothDevice = device
        bluetoothGatt = device.connectGatt(this, false, bluetoothGattCallback)
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        close()
        return super.onUnbind(intent)
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    private fun close() {
        bluetoothGatt?.let { gatt ->
            gatt.close()
            bluetoothGatt = null
        }
        bluetoothDevice = null
    }

    fun broadcastUpdate(action: String, characteristic: BluetoothGattCharacteristic? = null) {
        val intent = Intent(action)
        sendBroadcast(intent)
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    fun setCharacteristicNotification(
        characteristic: BluetoothGattCharacteristic,
        enabled: Boolean
    ) {
        bluetoothGatt?.let { gatt ->
            gatt.setCharacteristicNotification(characteristic, enabled)

            // This is specific to Heart Rate Measurement.
//            if (BluetoothLeService.UUID_HEART_RATE_MEASUREMENT == characteristic.uuid) {
//                val descriptor = characteristic.getDescriptor(UUID.fromString(SampleGattAttributes.CLIENT_CHARACTERISTIC_CONFIG))
//                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
//                gatt.writeDescriptor(descriptor)
//            }
        } ?: run {
            Log.w(TAG, "BluetoothGatt not initialized")
        }
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    fun readCharacteristic(characteristic: BluetoothGattCharacteristic) {
        bluetoothGatt?.let { gatt ->
            gatt.readCharacteristic(characteristic)
        } ?: run {
            Log.w(TAG, "BluetoothGatt not initialized")
            return
        }
    }

    val bluetoothGattCallback = object : BluetoothGattCallback() {
        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // successfully connected to the GATT Server
                broadcastUpdate(ACTION_GATT_CONNECTED)
                connectionState.value = STATE_CONNECTED
                // Attempts to discover services after successful connection.
                bluetoothGatt?.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // disconnected from the GATT Server
                broadcastUpdate(ACTION_GATT_DISCONNECTED)
                connectionState.value = STATE_DISCONNECTED
            }
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        fun queueAction(action: BluetoothAction) {
            Log.d(TAG, "Queuing action")
            pendingActions.add(action)
            if (currentAction == null) {
                processNextAction()
            }
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        fun processNextAction() {
            // Only one can occur at a time
            if (pendingActions.size > 0) {
                var action = pendingActions.removeAt(0)
                currentAction = action;
                Log.d(TAG, "Processing action")
                if (action.characterisitc != null) {
                    if (action.dir == BluetoothActionDir.Read) {
                        bluetoothGatt?.readCharacteristic(action.characterisitc!!)
                    } else {
                        bluetoothGatt?.writeCharacteristic(action.characterisitc!!)
                    }
                } else if (action.descriptor != null) {
                    if (action.dir == BluetoothActionDir.Read) {
                        bluetoothGatt?.readDescriptor(action.descriptor!!)
                    } else {
                        bluetoothGatt?.writeDescriptor(action.descriptor!!)
                    }
                }
            } else {
                currentAction = null;
                Log.d(TAG, "No more actions")
            }
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)
            Log.d(TAG, "onDescriptorWrite desc=${descriptor?.uuid} status=${status}")
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
                TAG,
                "onDescriptorRead desc=${descriptor.uuid} status=${status} value=${value.toHexString()}"
            )
            processNextAction();
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED)
                for (service in gatt?.services!!) {
                    Log.d(TAG, "discovered service: ${service.uuid}")
                    for (c in service.characteristics) {
                        Log.d(TAG, "  characteristic: ${c.uuid} ")
                        for (desc in c.descriptors) {
                            Log.d(TAG, "    descriptor: ${desc.uuid} ")
                        }
                    }
                    if (service.uuid == UUID.fromString(DEVICE_INFO_UUID)) {
                        val c = service.getCharacteristic(UUID.fromString(DEVICE_MODEL_UUID))
                        if (c != null) {
                            queueAction(BluetoothAction(
                                BluetoothActionDir.Read,
                                characterisitc = c
                            ))
                        }
                    } else if (service.uuid == UUID.fromString(DEVICE_DATA_UUID)) {
                        Log.d(TAG, "Found data ID")
                        val c = service.getCharacteristic(UUID.fromString(UART_DATA_UUID))
                        if (c != null) {
                            Log.d(TAG, "Found uart data")
                            val d = c.getDescriptor(UUID.fromString(UART_DATA_DESCRIPTOR_UUID))
                            if (d != null) {
                                Log.d(TAG, "Found uart data config")
                                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                queueAction(BluetoothAction(
                                    BluetoothActionDir.Write,
                                    descriptor = d
                                ))
                            }
                            gatt.setCharacteristicNotification(c, true)
                        }
                    }
                }
            } else {
                Log.w(TAG, "onServicesDiscovered received: $status")
            }
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.d(TAG, "onCharacteristicRead uuid=${characteristic.uuid} status=${status}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (characteristic.uuid == UUID.fromString(DEVICE_MODEL_UUID)) {
                    val v = characteristic.value.decodeToString()
                    Log.d(TAG, "Device model is: ${v}")
                    if (v == "BK-BLE-1.0") {
                        deviceType.value = DeviceType.SolarCharger
                        Log.d(TAG, "Conntected device is a solar charger")
                    } else if (v == "CH9141") {
                        deviceType.value = DeviceType.BatteryMonitor
                        Log.d(TAG, "Conntected device is a battery monitor")
                    } else {
                        deviceType.value = DeviceType.Unknown
                    }
                }
                broadcastUpdate(ACTION_GATT_DATA_AVAILABLE, characteristic)
            }
            processNextAction();
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            Log.d(TAG, "onCharacteristicWrite uuid=${characteristic?.uuid} status=${status}")
            super.onCharacteristicWrite(gatt, characteristic, status)
            processNextAction();
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            Log.d(TAG, "onCharacteristicChanged ${characteristic.uuid}")
            broadcastUpdate(ACTION_GATT_DATA_AVAILABLE, characteristic)
            if (characteristic.uuid == UUID.fromString(UART_DATA_UUID)) {
                when (deviceType.value) {
                    DeviceType.BatteryMonitor -> {
                        onBatteryMonitorData(characteristic.value)
                    }

                    DeviceType.SolarCharger -> {
                        onSolarChargerData(characteristic.value)
                    }

                    else -> {

                    }
                }
            }
        }

    }

    fun onSolarChargerData(data: ByteArray) {

    }

    fun onBatteryMonitorData(data: ByteArray) {
        Log.d(TAG, "onBatteryMonitorData ${data.size} bytes ${data.toHexString()}")
        if (data.size < 4) {
            return
        }
        // The Junctek battery monitor uses some weird format where the data
        // is like bb2043c1052954d825ee. When separated
        if (data.first() == 0xbb.toByte()
            && data.last() == 0xee.toByte()
        ) {
            var entry = ByteArray(0)
            for (c in data.slice(1..data.size - 1)) {
                // They treat anything with a hex character as a code
                if ((c and 0xF) >= 0xA || ((c shr 0x8) and 0xF) >= 0xA) {
                    val type = BatteryMonitorDataType.fromByte(c)
                    Log.d(TAG, "Found packet type ${c.toHexString()} ${type?.name} with data ${entry.toHexString()}")
                    val intent = Intent(ACTION_BATTERY_MONITOR_DATA_AVAILABLE)

                    when (type) {
                        BatteryMonitorDataType.Voltage,
                        BatteryMonitorDataType.Current -> {
                            if (entry.size > 1) {
                                // Must convert to hex string or toDouble uses incorrect base (16)
                                val v1 = entry[0].toHexString().toDouble()
                                val v2 = entry[1].toHexString().toDouble() / 100
                                intent.putExtra(BATTERY_MONITOR_DATA_VALUE, v1 + v2)
                            }
                        }
                        else -> {
                            if (entry.size > 0) {
                                intent.putExtra(BATTERY_MONITOR_DATA_VALUE, entry)
                            }
                        }

                    }
                    if (type != null && intent.hasExtra(BATTERY_MONITOR_DATA_VALUE)) {
                        intent.putExtra(BATTERY_MONITOR_DATA_TYPE, type.code)
                        sendBroadcast(intent)
                    }
                    entry = ByteArray(0)
                } else {
                    entry += c
                }
            }
        }
    }
}