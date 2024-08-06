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
import java.util.Date
import java.util.UUID

const val TAG = "BluetoothLeService"

const val STATE_DISCONNECTED = 0
const val STATE_CONNECTED = 2
const val POLL_PERIOD: Long = 1000

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
    // Data to write
    var value: ByteArray? = null,
    // Optional callback to invoke when the action is completed
    var callback: ((action: BluetoothAction) -> Unit)? = null,
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
    BatteryTemp(0x0A),
    TotalChargeEnergy(0x0B),
    TodayDcLoadEnergy(0x0C);


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
    TotalDischargeEnergy(0xd3),
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
        fun fromByte(value: Byte) =
            BatteryMonitorDataType.values().firstOrNull { it.code == value.toUByte().toInt() }

        fun fromInt(value: Int) = BatteryMonitorDataType.values().firstOrNull { it.code == value }
    }
}

class BatteryMonitorCommands {
    fun setBatteryCapacity(capacity: Double): ByteArray {
        val v: UShort =
            if (capacity > 9999.9) 99999.toUShort() else (10 * capacity).toInt().toUShort();
        val low = (v and 0xFF.toUShort()).toByte()
        val high = (v shr 0x08 and 0xFF.toUShort()).toByte()
        return byteArrayOf(0x9a.toByte(), 0xb0.toByte(), high, low, 0xb0.toByte())
    }

    companion object {
        // bb9aa90cee
        val HOME_DATA =
            byteArrayOf(0xBB.toByte(), 0x9A.toByte(), 0xA9.toByte(), 0x0C.toByte(), 0xEE.toByte())
    }
}

class SolarChargerCommands {
    companion object {
        val HOME_DATA = byteArrayOf(0x01, 0x03, 0x01, 0x01, 0x00, 0x13, 0x54, 0x3B)
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
    var lastCharacteristicWrite: ByteArray? = null

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
    fun sync(callback: ((action: BluetoothAction) -> Unit)? = null): Boolean {
        if (connectionState.value != STATE_CONNECTED || bluetoothGatt == null) {
            return false;
        }
        when (deviceType.value) {
            DeviceType.BatteryMonitor -> {
                val service =
                    bluetoothGatt?.getService(UUID.fromString(BATTERY_MONITOR_DATA_SERVICE_UUID))
                queueAction(
                    BluetoothAction(
                        BluetoothActionDir.Write,
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
                        BluetoothActionDir.Write,
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
    fun queueAction(action: BluetoothAction, timeout: Long? = null) {
        // Log.d(tag, "Queuing action")
        pendingActions.add(action)
        if (currentAction == null) {
            processNextAction()
        }
        if (timeout != null) {
            handler.postDelayed({abortAction(action)}, timeout)
        }
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    fun abortAction(action: BluetoothAction) {
        // Only one can occur at a time
        if (pendingActions.contains(action)) {
            Log.d(tag, "Queued action aborted")
            pendingActions.remove(action)
        } else if (currentAction == action) {
            Log.d(tag, "Pending action aborted")
            processNextAction()
        }
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    fun processNextAction() {
        // Only one can occur at a time
        if (currentAction != null && currentAction?.callback != null) {
            try {
                Log.d(tag, "Invoking action callback")
                currentAction?.callback?.invoke(currentAction!!)
            } catch (e: Exception) {
                Log.e(tag, "Callback exception: ${e}")
            }
        }
        if (pendingActions.size > 0) {
            var action = pendingActions.removeAt(0)
            currentAction = action;
            // Log.d(tag, "Processing action")
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
            } else {
                Log.w(tag, "Action has no characteristic or descriptor")
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
            val dt = Date().toInstant().toEpochMilli() - lastUpdated.value.toInstant().toEpochMilli();
            if (dt > 3*POLL_PERIOD) {
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
            processNextAction();
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
                                BluetoothActionDir.Read,
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
                                    BluetoothActionDir.Write,
                                    descriptor = d,
                                    value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                )
                            )
                        }
                        gatt.setCharacteristicNotification(c, true)
                        service.broadcastUpdate(device, MonitorService.ACTION_BATTERY_MONITOR_CONNECTED)
                        handler.postDelayed({sync()}, POLL_PERIOD/2)
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
                                    BluetoothActionDir.Write,
                                    descriptor = d,
                                    value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                )
                            )
                        }
                        gatt.setCharacteristicNotification(c, true)
                        service.broadcastUpdate(device, MonitorService.ACTION_SOLAR_CHARGER_CONNECTED)
                        handler.postDelayed({sync()}, POLL_PERIOD/2)
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
            processNextAction();
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            Log.d(tag, "onCharacteristicWrite uuid=${characteristic?.uuid} status=${status}")
            super.onCharacteristicWrite(gatt, characteristic, status)
            processNextAction();
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            Log.d(tag, "onCharacteristicChanged ${characteristic.uuid}")
            service.broadcastUpdate(device, ACTION_GATT_DATA_AVAILABLE, characteristic)
            when (characteristic.uuid) {
                UUID.fromString(BATTERY_MONITOR_DATA_CHARACTERISTIC_UUID) -> {
                    onBatteryMonitorData(characteristic.value)
                }

                UUID.fromString(SOLAR_CHARGER_DATA_CHARACTERISTIC_UUID) -> {
                    if (onSolarChargerData(characteristic.value)) {
                        // Sending both commands at the same time seems to mess up the response
                        // it appears to start overwriting so only queue the next command
                        //  after a sucessfull response
                        handler.postDelayed({
                            when (characteristic.value.size) {
                                43 -> {
                                    queueAction(
                                        BluetoothAction(
                                            BluetoothActionDir.Write,
                                            characteristic = characteristic,
                                            value = SolarChargerCommands.CHART_DATA,
                                        )
                                    )
                                }

                                15 -> {
                                    queueAction(
                                        BluetoothAction(
                                            BluetoothActionDir.Write,
                                            characteristic = characteristic,
                                            value = SolarChargerCommands.HOME_DATA,
                                        )
                                    )
                                }

                                else -> {}
                            }
                        }, POLL_PERIOD/2)
                    }
                }

                else -> {

                }
            }
        }

    }

    fun onSolarChargerData(data: ByteArray): Boolean {
        Log.d(tag, "onSolarChargerData ${data.size} bytes ${data.toHexString()}")
        // LiTime solar charger
        // Home data is 43 bytes
        // 0103260064010803e701072119000000000000023603af025c000000020000006300056a7200000000ad9a
        if (data.size == 43 && data.sliceArray(0..2).contentEquals(byteArrayOf(0x01, 0x03, 0x26))) { // && lastCharacteristicWrite.contentEquals(SolarChargerCommands.HOME_DATA)) {
            sendUpdate(
                SolarChargerDataType.ChargeVoltage,
                data.sliceArray(5..6).toHexString().toInt(16).toDouble() / 10
            )

            sendUpdate(
                SolarChargerDataType.ChargeCurrent,
                data.sliceArray(7..8).toHexString().toInt(16).toDouble() / 100
            )
            sendUpdate(SolarChargerDataType.ChargerTemp, data[11].toHexString().toInt(16))
            sendUpdate(SolarChargerDataType.BatteryTemp, data[12].toHexString().toInt(16))

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
            lastUpdated.value = Date()
            return true
        } else if (data.size == 15 && data.sliceArray(0..2).contentEquals(byteArrayOf(0x01, 0x03, 0x0a))) {
            // 01,03,0a,19,89,00,00,05,32,01,0f,01,00,f7,64
            sendUpdate(
                SolarChargerDataType.TodayChargeEnergy,
                data.sliceArray(3..4).toHexString().toInt(16).toDouble()
            )
            sendUpdate(
                SolarChargerDataType.TodayDcLoadEnergy,
                data.sliceArray(5..6).toHexString().toInt(16).toDouble()
            )
            sendUpdate(
                SolarChargerDataType.TodayPeakPower,
                data.sliceArray(7..8).toHexString().toInt(16).toDouble()
            )
            sendUpdate(
                SolarChargerDataType.TodayMaxBatteryVoltage,
                data.sliceArray(9..10).toHexString().toInt(16).toDouble() / 10
            )
            sendUpdate(
                SolarChargerDataType.TodayMinBatteryVoltage,
                data.sliceArray(11..12).toHexString().toInt(16).toDouble() / 10
            )
            lastUpdated.value = Date()
            return true
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

    fun discard(size: Int) {
        if (readBuffer.size >= size + 1) {
            readBuffer = byteArrayOf()
        } else {
            readBuffer = readBuffer.sliceArray(size + 1..readBuffer.size)
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
            val start = readBuffer.indexOf(0xbb.toByte())
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
            for (c in packet.slice(1..packet.size - 1)) {
                // They treat anything with a hex character as a code
                if ((c.toUByte() and 0xF0.toUByte() > 0xA0.toUByte()) || (c.toUByte() and 0x0F.toUByte() > 0xA.toUByte())) {
                    val type = BatteryMonitorDataType.fromByte(c)
//                    Log.i(
//                        tag,
//                        "Found packet type ${c.toHexString()} ${type?.name} with data ${entry.toHexString()}"
//                    )
                    val intent = Intent(ACTION_BATTERY_MONITOR_DATA_AVAILABLE)

                    when (type) {
                        BatteryMonitorDataType.Voltage,
                        BatteryMonitorDataType.Current,
                        BatteryMonitorDataType.TotalChargeEnergy,
                        BatteryMonitorDataType.TotalDischargeEnergy-> {
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

                        BatteryMonitorDataType.RemainingTimeInMinutes -> {
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
            lastUpdated.value = Date()
        }
        if (readBuffer.size > 512) {
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

    fun broadcastUpdate(device: BluetoothDevice, action: String, characteristic: BluetoothGattCharacteristic? = null) {
        val intent = Intent(action)
        val name: String? = device.name
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device)
        if (characteristic != null) {
            intent.putExtra(EXTRA_CHARACTERISTIC, characteristic)
        }
        sendBroadcast(intent)
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    fun sync(callback: ((action: BluetoothAction) -> Unit)? = null) {
        this.connections.forEach { it.sync(callback) }
    }

}