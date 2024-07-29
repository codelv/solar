package com.codelv.solar

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.ServiceConnection
import android.net.MacAddress
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.codelv.solar.ui.theme.SolarTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import java.io.IOException
import java.util.concurrent.Executor
import java.util.regex.Pattern

private const val SELECT_DEVICE_REQUEST_CODE = 0
private const val REQUEST_ENABLE_BT = 1


class MainActivity : ComponentActivity() {

    val companionDeviceManager: CompanionDeviceManager by lazy {
        getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
    }
    val bluetoothAdapter: BluetoothAdapter by lazy {
        val java = BluetoothManager::class.java
        getSystemService(java)!!.adapter
    }
    val executor: Executor = Executor { it.run() }
    var bluetoothDevice: MutableState<BluetoothDevice?> = mutableStateOf(null)
    var bluetoothService: MonitorService? = null

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        val gattServiceIntent = Intent(this, MonitorService::class.java)
        bindService(gattServiceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        super.onCreate(savedInstanceState)
        setContent {
            SolarTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainView()
                }
            }
        }
    }

    // Code to manage Service lifecycle.
    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(
            componentName: ComponentName,
            service: IBinder
        ) {
            bluetoothService = (service as MonitorService.LocalBinder).getService()
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            bluetoothService = null
        }
    }

    fun companionPairingRequest(launcher: ManagedActivityResultLauncher<IntentSenderRequest, ActivityResult>) {
        val deviceFilter: BluetoothLeDeviceFilter = BluetoothLeDeviceFilter.Builder()
            // Match only Bluetooth devices whose name matches the pattern.
            .setNamePattern(Pattern.compile("BT.+"))
            //.addServiceUuid(ParcelUuid(UUID()), null)
            .build()
        val pairingRequest: AssociationRequest = AssociationRequest.Builder()
            // Find only devices that match this request filter.
            .addDeviceFilter(deviceFilter)
            // Stop scanning as soon as one device matching the filter is found.
            .setSingleDevice(false)
            .build()

        // When the app tries to pair with a Bluetooth device, show the
        // corresponding dialog box to the user.
        companionDeviceManager.associate(pairingRequest,
            executor,
            object : CompanionDeviceManager.Callback() {
                // Called when a device is found. Launch the IntentSender so the user
                // can select the device they want to pair with.
                override fun onAssociationPending(intentSender: IntentSender) {
                    intentSender.let {
                        //startIntentSenderForResult(it, SELECT_DEVICE_REQUEST_CODE, null, 0, 0, 0)
                        val senderRequest = IntentSenderRequest.Builder(intentSender).build()
                        launcher.launch(senderRequest)
                    }
                }

                override fun onAssociationCreated(associationInfo: AssociationInfo) {
                    // AssociationInfo object is created and get association id and the
                    // macAddress.
                    var associationId: Int = associationInfo.id
                    var macAddress: MacAddress = associationInfo.deviceMacAddress!!
                    Log.i("BLE", "Companion association created ${associationId}")
                }

                override fun onFailure(errorMessage: CharSequence?) {
                    // Handle the failure.
                    Log.e("BLE", "Companion device failed ${errorMessage}")
                }

            })
    }

    fun connectBluetoothDevice(device: BluetoothDevice) {
        bluetoothAdapter.cancelDiscovery()
        //device.createBond();
        bluetoothDevice.value = device
        bluetoothService?.connect(device)
//        bluetoothGatt.value = device.connectGatt(this, false, object : BluetoothGattCallback() {
//            override fun onCharacteristicChanged(
//                gatt: BluetoothGatt,
//                characteristic: BluetoothGattCharacteristic,
//                value: ByteArray
//            ) {
//                super.onCharacteristicChanged(gatt, characteristic, value)
//                Log.d("BLE", "Characteristic changed ${characteristic} ${value}")
//            }
//
//            override fun onDescriptorRead(
//                gatt: BluetoothGatt,
//                descriptor: BluetoothGattDescriptor,
//                status: Int,
//                value: ByteArray
//            ) {
//                super.onDescriptorRead(gatt, descriptor, status, value)
//                Log.d("BLE", "Descriptor read  ${descriptor} ${status} ${value}")
//            }
//
//            override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
//                super.onReadRemoteRssi(gatt, rssi, status)
//                Log.d("BLE", "Read remote rssi  ${rssi} ${status}")
//
//            }
//
//            override fun onConnectionStateChange(
//                gatt: BluetoothGatt?,
//                status: Int,
//                newState: Int
//            ) {
//                super.onConnectionStateChange(gatt, status, newState)
//                if (newState == BluetoothProfile.STATE_CONNECTED) {
//                    Log.d("BLE", "Connected")
//                    gatt?.discoverServices()
//                    // successfully connected to the GATT Server
//                    broadcastUpdate(ACTION_GATT_CONNECTED)
//                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
//                    Log.d("BLE", "Disconnected")
//                }
//
//            }
//
//            override fun onServiceChanged(gatt: BluetoothGatt) {
//                super.onServiceChanged(gatt)
//                Log.d("BLE", "Service changed")
//
//            }
//
//            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
//                super.onServicesDiscovered(gatt, status)
//                Log.d("BLE", "Services discovered ${status}")
//            }
//        })
    }


    inner class BluetoothThread(context: Context, device: BluetoothDevice, handler: Handler) :
        Thread() {
        val MESSAGE_READ = 0
        val MESSAGE_WRITE = 1
        val MESSAGE_ERROR = 2
        val buf = ByteArray(1024)
        val handler: Handler = handler
        val device: BluetoothDevice = device
        val bluetoothSocket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            device.createRfcommSocketToServiceRecord(device.uuids.first().uuid)
        }

        override fun run() {
            bluetoothSocket?.let { socket ->
                // Connect to the remote device through the socket. This call blocks
                // until it succeeds or throws an exception.
                socket.connect()

                while (true) {
                    try {
                        val n = socket.inputStream.read(buf)
                        val msg = handler.obtainMessage(MESSAGE_READ, n, -1, buf)
                        msg.sendToTarget()
                        Log.d("Bluetooth", "Read ${msg}")
                    } catch (e: IOException) {
                        Log.d("Bluetooth", "Input stream closed", e)
                        break
                    }
                }
            }
        }

        // Call this from the main activity to send data to the remote device.
        fun write(bytes: ByteArray) {
            bluetoothSocket?.let { socket ->
                try {
                    Log.d("Bluetooth", "Write ${bytes}")
                    socket.outputStream.write(bytes)
                } catch (e: IOException) {
                    Log.e("Bluetooth", "Error occurred when sending data", e)

                    // Send a failure message back to the activity.
                    val errorMsg = handler.obtainMessage(MESSAGE_ERROR)
                    val bundle = Bundle().apply {
                        putString("error", "Couldn't send data to the other device")
                    }
                    errorMsg.data = bundle
                    handler.sendMessage(errorMsg)
                    return
                }

                // Share the sent message with the UI activity.
                val msg = handler.obtainMessage(MESSAGE_WRITE, -1, -1, buf)
                msg.sendToTarget()
            }
        }

        // Closes the client socket and causes the thread to finish.
        fun cancel() {
            try {
                bluetoothSocket?.close()
            } catch (e: IOException) {
                Log.e("Bluetooth", "Could not close the client socket", e)
            }
        }
    }


}

inline fun <reified Activity : ComponentActivity> Context.getActivity(): Activity? {
    return when (this) {
        is Activity -> this
        else -> {
            var context = this
            while (context is ContextWrapper) {
                context = context.baseContext
                if (context is Activity) return context
            }
            null
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainView(appViewModel: AppViewModel = viewModel()) {
    val state by appViewModel.state.collectAsState()
    val context = LocalContext.current
    val activity = context.getActivity<MainActivity>()!!

    val bluetoothPermissionState = rememberMultiplePermissionsState(
        listOf(
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_SCAN,
        )
    )
    if (!bluetoothPermissionState.allPermissionsGranted) {
        Column(Modifier.padding(16.dp)) {
            val textToShow = if (bluetoothPermissionState.shouldShowRationale) {
                // If the user has denied the permission but the rationale can be shown,
                // then gently explain why the app requires this permission
                "Bluetooth permission is required to connect to the charger and/or battery monitor." +
                        "Please grant the permission."
            } else {
                // If it's the first time the user lands on this feature, or the user
                // doesn't want to be asked again for this permission, explain that the
                // permission is required
                "App will not work without bluetooth. Use the button below to request permission. "
            }
            Text(textToShow)
            Button(
                onClick = { bluetoothPermissionState.launchMultiplePermissionRequest() },
                Modifier.fillMaxWidth()
            ) {
                Text("Request bluetooth permission", Modifier.padding(6.dp))
            }
        }
        return
    }
    var isBluetoothEnabled by remember { mutableStateOf(activity.bluetoothAdapter?.isEnabled) }

    var enableBluetoothLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.StartActivityForResult()) { result ->
            isBluetoothEnabled = result.resultCode == Activity.RESULT_OK
        }

    if (!isBluetoothEnabled!!) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Bluetooth is not on",
            )
            Button(
                onClick = {
                    Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).apply {
                        enableBluetoothLauncher.launch(this)
                    }
                },
                Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
            ) {
                Text("Turn on bluetooth", Modifier.padding(16.dp))
            }
        }
        //activity.startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
        return
    }
    val bluetoothPairingLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val deviceToPair: BluetoothDevice? =
                result.data?.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE)
            deviceToPair?.let { device ->
                device.createBond()
                // Maintain continuous interaction with a paired device.
                activity.bluetoothDevice.value = device
                Log.i("BLE", "Device connected")
            }
        } else {
            Log.i("BLE", "Pairing request cancelled")
        }
    }

    if (activity.bluetoothDevice.value == null) {
        BluetoothScanView(state = state)
        return
    }
    if (activity.bluetoothService?.connectionState?.value == STATE_CONNECTED) {
        BatteryMonitorView(state = state)
        return
    }
    Text("Connecting...")
}

@Composable
fun SystemBroadcastReceiver(
    systemActions: List<String>,
    onSystemEvent: (intent: Intent?) -> Unit
) {
    // from carlos at https://stackoverflow.com/a/76746193
    val context = LocalContext.current
    val currentOnSystemEvent by rememberUpdatedState(onSystemEvent)
    DisposableEffect(context, systemActions) {
        val intentFilter = IntentFilter()
        systemActions.forEach { action -> intentFilter.addAction(action) }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                currentOnSystemEvent(intent)
            }
        }
        context.registerReceiver(receiver, intentFilter, RECEIVER_EXPORTED)
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }
}

@Composable
fun BluetoothScanView(state: AppState) {
    val context = LocalContext.current
    val activity = context.getActivity<MainActivity>()!!
    var bluetoothDevices = remember { mutableStateListOf<BluetoothDevice>() }
    Column(Modifier.padding(16.dp)) {
        Text("No charger or battery monitor is connected.")
        Button(
            onClick = {
                Log.i("Bluetooth", "Start scan")
                activity.bluetoothAdapter.startDiscovery()
                //activity.companionPairingRequest(bluetoothPairingLauncher)
            },
            Modifier.fillMaxWidth()
        ) {
            Text("Search for devices", Modifier.padding(6.dp))
        }
        Text("Available devices (tap to connect)")

        SystemBroadcastReceiver(systemActions = listOf(BluetoothDevice.ACTION_FOUND)) { intent ->
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)!!
                    Log.i("Bluetooth", "Found ${device}")
                    if (!bluetoothDevices.contains(device)) {
                        bluetoothDevices.add(device)
                    }
                }
//                BluetoothDevice.ACTION_UUID -> {
//                    val device: BluetoothDevice =
//                        intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)!!
//                    val uuids: Array<ParcelUuid>? = device.uuids;
//                    if (uuids != null) {
//                        for (uuid in uuids) {
//                            Log.i("Bluetooth", "Found ${device} service ${uuid}")
//                            if (uuid.toString().startsWith("0000FFE0-0000-1000-8000")) {
//                                if (!bluetoothDevices.contains(device!!)) {
//                                    bluetoothDevices.add(device!!)
//                                }
//                                break;
//                            }
//                        }
//                    } else {
//                        Log.w("Bluetooth", "No uuids fetched")
//                    }
//                }
                else -> {}
            }
        }
        bluetoothDevices.forEach({ device ->
            var name: String? = device.name
            Row(
                Modifier
                    .padding(16.dp)
                    .clickable(onClick = { activity.connectBluetoothDevice(device) })
                    .fillMaxWidth()
            ) {
                Text(if (name != null) name else "Unamed device")
            }
            HorizontalDivider()
        })
    }
}

@Composable
fun BatteryMonitorView(state: AppState) {
    val context = LocalContext.current
    val activity = context.getActivity<MainActivity>()!!
    var batteryCurrent by remember{ mutableStateOf(0.0) }
    var batteryVoltage by remember{ mutableStateOf(0.0) }

    SystemBroadcastReceiver(systemActions = listOf(MonitorService.ACTION_BATTERY_MONITOR_DATA_AVAILABLE)) { intent ->
        when (intent?.action) {
            MonitorService.ACTION_BATTERY_MONITOR_DATA_AVAILABLE -> {
                val type =
                    BatteryMonitorDataType.fromInt(
                        intent.getIntExtra(MonitorService.BATTERY_MONITOR_DATA_TYPE, 0)
                    )
                when (type) {
                    BatteryMonitorDataType.Voltage -> {
                        // TOOD
                        batteryVoltage = intent.getDoubleExtra(MonitorService.BATTERY_MONITOR_DATA_VALUE, 0.0)
                    }

                    BatteryMonitorDataType.Current -> {
                        // TOOD
                        batteryCurrent = intent.getDoubleExtra(MonitorService.BATTERY_MONITOR_DATA_VALUE, 0.0)
                    }

                    else -> {
                    }
                }
            }

            else -> {}
        }
    }
    Column(Modifier.padding(16.dp)) {
        if (activity.bluetoothService?.deviceType?.value == DeviceType.BatteryMonitor) {
            Text("Battery monitor")
        } else if (activity.bluetoothService?.deviceType?.value == DeviceType.SolarCharger) {
            Text("Solar charger")
        }
        Row {
            Text(
                text = "Solar"
            )
            Row(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "${state.solarVoltage}V",
                    Modifier.padding(4.dp)
                )
                Text(
                    text = "${state.solarCurrent}A",
                    Modifier.padding(4.dp)
                )

                Text(
                    text = "${state.solarPower}W",
                    Modifier.padding(4.dp)
                )
            }
        }
        Row {
            Text(
                text = "Charger"
            )
            Row(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "${state.chargeVoltage}V",
                    Modifier.padding(4.dp)
                )
                Text(
                    text = "${state.chargeCurrent}A",
                    Modifier.padding(4.dp)
                )

                Text(
                    text = "${state.chargePower}W",
                    Modifier.padding(4.dp)
                )
            }
        }
        Row {
            Text(
                text = "Battery"
            )
            Row(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "${"%.2f".format(batteryVoltage)}V",
                    Modifier.padding(4.dp)
                )
                Text(
                    text = "${"%.2f".format(batteryCurrent)}A",
                    Modifier.padding(4.dp)
                )

                Text(
                    text = "${"%.2f".format(batteryVoltage*batteryCurrent)}W",
                    Modifier.padding(4.dp)
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainViewPreview() {
    SolarTheme {
        MainView()
    }
}