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
import com.codelv.solar.MonitorService.Companion.BATTERY_MONITOR_DATA_CHARACTERISTIC_UUID
import com.codelv.solar.MonitorService.Companion.BATTERY_MONITOR_DATA_DESCRIPTOR_UUID
import com.codelv.solar.MonitorService.Companion.BATTERY_MONITOR_DATA_SERVICE_UUID
import com.codelv.solar.MonitorService.Companion.BATTERY_MONITOR_DATA_TYPE
import com.codelv.solar.MonitorService.Companion.BATTERY_MONITOR_DATA_VALUE
import com.codelv.solar.MonitorService.Companion.CHARGER_DATA_CHARACTERISTIC_UUID
import com.codelv.solar.MonitorService.Companion.CHARGER_DATA_DESCRIPTOR_UUID
import com.codelv.solar.MonitorService.Companion.CHARGER_DATA_SERVICE_UUID
import com.codelv.solar.MonitorService.Companion.DEVICE_INFO_SERVICE_UUID
import com.codelv.solar.MonitorService.Companion.DEVICE_MODEL_CHARACTERISTIC_UUID
import com.codelv.solar.MonitorService.Companion.SOLAR_CHARGER_DATA_TYPE
import com.codelv.solar.MonitorService.Companion.SOLAR_CHARGER_DATA_VALUE
import java.util.UUID

const val TAG = "BluetoothLeService"

const val STATE_DISCONNECTED = 0
const val STATE_CONNECTED = 2

infix fun Byte.shr(that: Int): Byte = this.toInt().shr(that).toByte()
infix fun UShort.shr(that: Int): UShort = this.toInt().shr(that).toUShort()


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
    var characteristic: BluetoothGattCharacteristic? = null,
    var descriptor: BluetoothGattDescriptor? = null,
    var value: ByteArray? = null
)

enum class SolarChargerDataType(val code: Int) {
    ChargeVoltage(0x01),
    ChargeCurrent(0x02),
    SolarVoltage(0x03),
    TodayChargeEnergy(0x04),
    TodayPeakPower(0x05),
    TodayMinBatteryVoltage(0x06),
    TodayMaxBatteryVoltage(0x07),
    ChargerTemp(0x09),
    BatteryTemp(0x0A);

    companion object {
        fun fromInt(value: Int) = SolarChargerDataType.values().firstOrNull { it.code == value }
    }
}

enum class BatteryMonitorDataType(val code: Int) {
    BatteryCapacity(0xb0),
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
    TotalDischargeEnergy(0xd3)
    ,
    TotalChargeEnergy(0xd4),
    RecordProgress(0xd5),
    RemainingTimeInMinutes(0xd6),
    Capacity(0xd8),
    TempData(0xd9),

    Config(0xe0),


    IsRecording(0xf1),
    Date(0xf2),
    Time(0xf3),
    Password(0xf6),
    IsTempInFahrenheit(0xf7);

    companion object {
        fun fromByte(value: Byte) = BatteryMonitorDataType.values().firstOrNull { it.code == value.toUByte().toInt() }
        fun fromInt(value: Int) = BatteryMonitorDataType.values().firstOrNull { it.code == value }
    }
}

class BatteryMonitorCommands{
    fun setBatteryCapacity(capacity: Double): ByteArray  {
        val v: UShort = if (capacity > 9999.9) 99999.toUShort() else (10*capacity).toInt().toUShort();
        val low = (v and 0xFF.toUShort()).toByte()
        val high = (v shr 0x08 and 0xFF.toUShort()).toByte()
        return byteArrayOf(0x9a.toByte(), 0xb0.toByte(), high, low, 0xb0.toByte())
    }
}

class SolarChargerCommands {
    companion object {
        val HOME_DATA = byteArrayOf(0x01, 0x03, 0x01, 0x01, 0x00, 0x13, 0x54, 0x3B)
    }
}

@OptIn(ExperimentalStdlibApi::class)
class MonitorConnection(val device: BluetoothDevice, val service: MonitorService) {
    var deviceType: MutableState<DeviceType> = mutableStateOf(DeviceType.Unknown)
    var connectionState: MutableState<Int> = mutableStateOf(STATE_DISCONNECTED)
    var bluetoothGatt: BluetoothGatt? = null
    var pendingActions: MutableList<BluetoothAction> = mutableListOf()
    var currentAction: BluetoothAction? = null
    var lastCharacteristicWrite: ByteArray? = null

    @RequiresPermission(BLUETOOTH_CONNECT)
    fun connect() {
        if (bluetoothGatt != null) {
            return
        }
        bluetoothGatt = device.connectGatt(service, false, bluetoothGattCallback)
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    fun close() {
        bluetoothGatt?.let { gatt ->
            gatt.close()
            bluetoothGatt = null
        }
    }

    val bluetoothGattCallback = object : BluetoothGattCallback() {
        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // successfully connected to the GATT Server
                service.broadcastUpdate(ACTION_GATT_CONNECTED)
                connectionState.value = STATE_CONNECTED
                // Attempts to discover services after successful connection.
                bluetoothGatt?.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // disconnected from the GATT Server
                service.broadcastUpdate(ACTION_GATT_DISCONNECTED)
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
                if (action.characteristic != null) {
                    val characterisitc = action.characteristic!!
                    if (action.dir == BluetoothActionDir.Read) {
                        bluetoothGatt?.readCharacteristic(characterisitc)
                    } else {
                        lastCharacteristicWrite = action.value!!;
                        characterisitc.setValue(action.value!!)
                        bluetoothGatt?.writeCharacteristic(characterisitc)
                    }
                } else if (action.descriptor != null) {
                    val desc = action.descriptor!!
                    if (action.dir == BluetoothActionDir.Read) {
                        bluetoothGatt?.readDescriptor(desc)
                    } else {
                        desc.setValue(action.value!!)
                        bluetoothGatt?.writeDescriptor(desc)
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
                service.broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED)
                for (service in gatt?.services!!) {
                    Log.d(TAG, "discovered service: ${service.uuid}")
                    for (c in service.characteristics) {
                        Log.d(TAG, "  characteristic: ${c.uuid} ")
                        for (desc in c.descriptors) {
                            Log.d(TAG, "    descriptor: ${desc.uuid} ")
                        }
                    }
                    if (service.uuid == UUID.fromString(DEVICE_INFO_SERVICE_UUID)) {
                        val c = service.getCharacteristic(UUID.fromString(
                            DEVICE_MODEL_CHARACTERISTIC_UUID
                        ))
                        if (c != null) {
                            queueAction(BluetoothAction(
                                BluetoothActionDir.Read,
                                characteristic = c
                            ))
                        }
                    } else if (deviceType.value == DeviceType.Unknown && service.uuid == UUID.fromString(
                            BATTERY_MONITOR_DATA_SERVICE_UUID
                        )) {
                        Log.d(TAG, "Found battery monitor data ID")
                        deviceType.value = DeviceType.BatteryMonitor
                        val c = service.getCharacteristic(
                            UUID.fromString(BATTERY_MONITOR_DATA_CHARACTERISTIC_UUID)
                        )
                        if (c != null) {
                            Log.d(TAG, "Found battery monitor data")
                            val d =
                                c.getDescriptor(UUID.fromString(BATTERY_MONITOR_DATA_DESCRIPTOR_UUID))
                            if (d != null) {
                                Log.d(TAG, "Found battery monitor data config")
                                queueAction(
                                    BluetoothAction(
                                        BluetoothActionDir.Write,
                                        descriptor = d,
                                        value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    )
                                )
                            }
                            gatt.setCharacteristicNotification(c, true)
                        }
                    } else if (deviceType.value == DeviceType.Unknown && service.uuid == UUID.fromString(
                            CHARGER_DATA_SERVICE_UUID
                        )) {
                        Log.d(TAG, "Found charger data ID")
                        deviceType.value = DeviceType.SolarCharger
                        val c = service.getCharacteristic(
                            UUID.fromString(CHARGER_DATA_CHARACTERISTIC_UUID)
                        )
                        if (c != null) {
                            Log.d(TAG, "Found charger data")
                            val d =
                                c.getDescriptor(UUID.fromString(CHARGER_DATA_DESCRIPTOR_UUID))
                            if (d != null) {
                                Log.d(TAG, "Found charger data config")
                                queueAction(
                                    BluetoothAction(
                                        BluetoothActionDir.Write,
                                        descriptor = d,
                                        value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    )
                                )
                            }
                            gatt.setCharacteristicNotification(c, true)
                            queueAction(
                                BluetoothAction(
                                    BluetoothActionDir.Write,
                                    characteristic = c,
                                    value = SolarChargerCommands.HOME_DATA
                                )
                            )
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
                if (characteristic.uuid == UUID.fromString(DEVICE_MODEL_CHARACTERISTIC_UUID)) {
                    val v = characteristic.value.decodeToString()
                    Log.d(TAG, "Device model is: ${v}")

                }
                service.broadcastUpdate(ACTION_GATT_DATA_AVAILABLE, characteristic)
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

        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            Log.d(TAG, "onCharacteristicChanged ${characteristic.uuid}")
            service.broadcastUpdate(ACTION_GATT_DATA_AVAILABLE, characteristic)
            when (characteristic.uuid) {
                UUID.fromString(BATTERY_MONITOR_DATA_CHARACTERISTIC_UUID) -> {
                    onBatteryMonitorData(characteristic.value)
                }
                UUID.fromString(CHARGER_DATA_CHARACTERISTIC_UUID) -> {
                    onSolarChargerData(characteristic.value)

                    // Solar charger needs data polled
                    Handler(Looper.getMainLooper()).postDelayed({
                        queueAction(
                            BluetoothAction(
                                BluetoothActionDir.Write,
                                characteristic = characteristic,
                                value = SolarChargerCommands.HOME_DATA
                            )
                        )
                    }, 1000)
                }
                else -> {

                }
            }
        }

    }

    fun onSolarChargerData(data: ByteArray) {
        Log.d(TAG, "onSolarChargerData ${data.size} bytes ${data.toHexString()}")
        // LiTime solar charger
        // Home data is 43 bytes
        // 0103260064010803e701072119000000000000023603af025c000000020000006300056a7200000000ad9a
        if (data.size == 43 && lastCharacteristicWrite.contentEquals(SolarChargerCommands.HOME_DATA)) {
            var intent = Intent(ACTION_SOLAR_CHARGER_DATA_AVAILABLE)
            intent.putExtra(SOLAR_CHARGER_DATA_TYPE, SolarChargerDataType.ChargeVoltage.code)
            intent.putExtra(SOLAR_CHARGER_DATA_VALUE, data.sliceArray(5..6).toHexString().toInt(16).toDouble() / 10)
            service.sendBroadcast(intent)

            intent = Intent(ACTION_SOLAR_CHARGER_DATA_AVAILABLE)
            intent.putExtra(SOLAR_CHARGER_DATA_TYPE, SolarChargerDataType.ChargeCurrent.code)
            Log.d(TAG, "Charge current ${data.sliceArray(7..8).toHexString()}")
            intent.putExtra(SOLAR_CHARGER_DATA_VALUE, data.sliceArray(7..8).toHexString().toInt(16).toDouble() / 100)
            service.sendBroadcast(intent)

            intent = Intent(ACTION_SOLAR_CHARGER_DATA_AVAILABLE)
            intent.putExtra(SOLAR_CHARGER_DATA_TYPE, SolarChargerDataType.SolarVoltage.code)
            intent.putExtra(SOLAR_CHARGER_DATA_VALUE, data.sliceArray(19..20).toHexString().toInt(16).toDouble() / 10)
            service.sendBroadcast(intent)

            intent = Intent(ACTION_SOLAR_CHARGER_DATA_AVAILABLE)
            intent.putExtra(SOLAR_CHARGER_DATA_TYPE, SolarChargerDataType.TodayPeakPower.code)
            intent.putExtra(SOLAR_CHARGER_DATA_VALUE, data.sliceArray(21..22).toHexString().toInt(16).toDouble())
            service.sendBroadcast(intent)

            intent = Intent(ACTION_SOLAR_CHARGER_DATA_AVAILABLE)
            intent.putExtra(SOLAR_CHARGER_DATA_TYPE, SolarChargerDataType.TodayChargeEnergy.code)
            intent.putExtra(SOLAR_CHARGER_DATA_VALUE, data.sliceArray(23..24).toHexString().toInt(16).toDouble())
            service.sendBroadcast(intent)

            intent = Intent(ACTION_SOLAR_CHARGER_DATA_AVAILABLE)
            intent.putExtra(SOLAR_CHARGER_DATA_TYPE, SolarChargerDataType.TodayMinBatteryVoltage.code)
            intent.putExtra(SOLAR_CHARGER_DATA_VALUE, data.sliceArray(0..1).toHexString().toInt(16).toDouble())
            service.sendBroadcast(intent)

            //intent = Intent(ACTION_SOLAR_CHARGER_DATA_AVAILABLE)
            //intent.putExtra(SOLAR_CHARGER_DATA_TYPE, SolarChargerDataType.TodayMaxBatteryVoltage.code)
            //intent.putExtra(SOLAR_CHARGER_DATA_VALUE, data.sliceArray(0..1).toHexString().toInt(16).toDouble())
            //service.sendBroadcast(intent)

            intent = Intent(ACTION_SOLAR_CHARGER_DATA_AVAILABLE)
            intent.putExtra(SOLAR_CHARGER_DATA_TYPE, SolarChargerDataType.ChargerTemp.code)
            intent.putExtra(SOLAR_CHARGER_DATA_VALUE, data[2].toHexString().toInt(16))
            service.sendBroadcast(intent)
        }

    }

    fun onBatteryMonitorData(data: ByteArray) {
        Log.d(TAG, "onBatteryMonitorData ${data.size} bytes ${data.toHexString()}")
        // The Junctek battery monitor uses some weird format where the data
        // is like bb2043c1052954d825ee.
        // Each field/data is separated by hex encoded codes (eg c1, d8)
        // Must convert to hex string (little endian) or toDouble uses incorrect base (16)
        if (data.first() == 0xbb.toByte()
            && data.last() == 0xee.toByte()
        ) {
            var entry = ByteArray(0)
            for (c in data.slice(1..data.size - 1)) {
                // They treat anything with a hex character as a code
                if ((c.toUByte() and 0xF0.toUByte() > 0xA0.toUByte()) || (c.toUByte() and 0x0F.toUByte() > 0xA.toUByte())) {
                    val type = BatteryMonitorDataType.fromByte(c)
                    Log.d(TAG, "Found packet type ${c.toHexString()} ${type?.name} with data ${entry.toHexString()}")
                    val intent = Intent(ACTION_BATTERY_MONITOR_DATA_AVAILABLE)

                    when (type) {
                        BatteryMonitorDataType.Voltage,
                        BatteryMonitorDataType.Current -> {
                            if (entry.size > 0) {
                                intent.putExtra(BATTERY_MONITOR_DATA_VALUE, entry.toHexString().toDouble() / 100)
                            }
                        }
                        BatteryMonitorDataType.RemainingAh, BatteryMonitorDataType.TotalChargeEnergy, BatteryMonitorDataType.TotalDischargeEnergy -> {
                            if (entry.size > 0) {
                                intent.putExtra(BATTERY_MONITOR_DATA_VALUE, entry.toHexString().toDouble() / 1000)
                            }
                        }
                        BatteryMonitorDataType.IsCharging, BatteryMonitorDataType.IsRecording ->     {
                            if (entry.size > 0) {
                                intent.putExtra(BATTERY_MONITOR_DATA_VALUE, entry[0].toHexString().toInt() == 1)
                            }
                        }
                        BatteryMonitorDataType.RemainingTimeInMinutes ->     {
                            if (entry.size > 0) {
                                intent.putExtra(BATTERY_MONITOR_DATA_VALUE, entry.toHexString().toInt())
                            }
                        }
                        BatteryMonitorDataType.BatteryCapacity ->     {
                            if (entry.size > 0) {
                                intent.putExtra(
                                    BATTERY_MONITOR_DATA_VALUE,
                                    entry.toHexString().toDouble()
                                )
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
                        service.sendBroadcast(intent)
                    }
                    entry = ByteArray(0)
                } else {
                    entry += c
                }
            }
        }
    }
}



class MonitorService : Service() {
    companion object {
        val DEVICE_INFO_SERVICE_UUID = "0000180A-0000-1000-8000-00805F9B34FB"
        val DEVICE_MODEL_CHARACTERISTIC_UUID = "00002A24-0000-1000-8000-00805F9B34FB"
        val BATTERY_MONITOR_DATA_SERVICE_UUID = "0000FFF0-0000-1000-8000-00805F9B34FB"
        val BATTERY_MONITOR_DATA_CHARACTERISTIC_UUID = "0000FFF1-0000-1000-8000-00805F9B34FB"
        val BATTERY_MONITOR_DATA_DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805F9B34FB"
        val CHARGER_DATA_SERVICE_UUID = "0000FFE0-0000-1000-8000-00805F9B34FB"
        val CHARGER_DATA_DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805F9B34FB"
        val CHARGER_DATA_CHARACTERISTIC_UUID = "0000FFE1-0000-1000-8000-00805F9B34FB"
        val ACTION_GATT_CONNECTED = "com.codelv.solar.ACTION_GATT_CONNECTED"
        val ACTION_GATT_DISCONNECTED = "com.codelv.solar.ACTION_GATT_DISCONNECTED"
        val ACTION_GATT_SERVICES_DISCOVERED = "com.codelv.solar.ACTION_GATT_SERVICES_DISCOVERED"
        val ACTION_GATT_DATA_AVAILABLE = "com.codelv.solar.ACTION_GATT_DATA_AVAILABLE"
        val BATTERY_MONITOR_DATA_TYPE = "com.codelv.solar.BATTERY_MONITOR_DATA_TYPE"
        val BATTERY_MONITOR_DATA_VALUE = "com.codelv.solar.BATTERY_MONITOR_DATA_VALUE"
        val ACTION_BATTERY_MONITOR_DATA_AVAILABLE =
            "com.codelv.solar.ACTION_BATTERY_MONITOR_DATA_AVAILABLE"
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
        if (this.connections.find {it.device == device } == null) {
            val conn = MonitorConnection(device, this)
            conn.connect()
            this.connections.add(conn)
        }
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    override fun onUnbind(intent: Intent?): Boolean {
        this.connections.forEach{it.close()}
        this.connections.clear()
        return super.onUnbind(intent)
    }

    fun broadcastUpdate(action: String, characteristic: BluetoothGattCharacteristic? = null) {
        val intent = Intent(action)
        sendBroadcast(intent)
    }


}