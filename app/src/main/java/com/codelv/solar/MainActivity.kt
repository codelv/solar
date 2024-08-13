package com.codelv.solar

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_SCAN
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LineAxis
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.codelv.solar.MonitorService.Companion.ACTION_BATTERY_MONITOR_CONNECTED
import com.codelv.solar.MonitorService.Companion.ACTION_BATTERY_MONITOR_DATA_AVAILABLE
import com.codelv.solar.MonitorService.Companion.ACTION_SOLAR_CHARGER_CONNECTED
import com.codelv.solar.MonitorService.Companion.ACTION_SOLAR_CHARGER_DATA_AVAILABLE
import com.codelv.solar.ui.theme.SolarTheme
import com.codelv.solar.ui.theme.Typography
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottomAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStartAxis
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.AutoScrollCondition
import com.patrykandpatrick.vico.core.cartesian.Scroll
import com.patrykandpatrick.vico.core.cartesian.Zoom
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.util.Date
import kotlin.math.max

const val SNAPSHOT_PERIOD: Long = 1000

class MainActivity : ComponentActivity() {
    val dataStore by preferencesDataStore(name = "user_preferences")
    val state = AppViewModel()
    val handler = Handler(Looper.getMainLooper())
    var monitorService: MonitorService? = null;
    val bluetoothAdapter: BluetoothAdapter by lazy {
        val java = BluetoothManager::class.java
        getSystemService(java)!!.adapter
    }

    // Code to manage Service lifecycle.
    val serviceConnection: ServiceConnection = object : ServiceConnection {
        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun onServiceConnected(
            componentName: ComponentName,
            service: IBinder
        ) {
            monitorService = (service as MonitorService.LocalBinder).getService()
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            monitorService = null
        }
    }

    val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MonitorService.ACTION_SOLAR_CHARGER_DATA_AVAILABLE -> {
                    val type =
                        SolarChargerDataType.fromInt(
                            intent.getIntExtra(MonitorService.SOLAR_CHARGER_DATA_TYPE, 0)
                        )
                    when (type) {
                        SolarChargerDataType.SolarVoltage -> {
                            state.solarVoltage.value =
                                intent.getDoubleExtra(MonitorService.SOLAR_CHARGER_DATA_VALUE, 0.0)
                        }
                        SolarChargerDataType.HistoryData -> {
                            var data: ChargerHistory = intent.getParcelableExtra(MonitorService.SOLAR_CHARGER_DATA_VALUE)!!
                            if (data.index == 0) {
                                state.chargerMinVoltage.value = data.minVoltage
                                state.chargerMaxVoltage.value = data.maxVoltage
                                state.chargerTodayEnergy.value = data.totalEnergy
                                state.solarPeakPower.value = data.peakPower
                            }
                            state.pendingChanges.add(ModelChangeAction(
                                chargerHistory=data,
                            ))
                        }
                        SolarChargerDataType.TodayMinBatteryVoltage -> {
                            state.chargerMinVoltage.value =
                                intent.getDoubleExtra(MonitorService.SOLAR_CHARGER_DATA_VALUE, 0.0)
                        }

                        SolarChargerDataType.TodayMaxBatteryVoltage -> {
                            state.chargerMaxVoltage.value =
                                intent.getDoubleExtra(MonitorService.SOLAR_CHARGER_DATA_VALUE, 0.0)
                        }

                        SolarChargerDataType.ChargeVoltage -> {
                            state.chargerVoltage.value =
                                intent.getDoubleExtra(MonitorService.SOLAR_CHARGER_DATA_VALUE, 0.0)
                        }

                        SolarChargerDataType.ChargeCurrent -> {
                            state.chargerCurrent.value =
                                intent.getDoubleExtra(MonitorService.SOLAR_CHARGER_DATA_VALUE, 0.0)
                        }

                        SolarChargerDataType.TodayChargeEnergy -> {
                            state.chargerTodayEnergy.value =
                                intent.getDoubleExtra(MonitorService.SOLAR_CHARGER_DATA_VALUE, 0.0)
                        }

                        SolarChargerDataType.TotalChargeEnergy -> {
                            state.chargerTotalChargeEnergy.value =
                                intent.getDoubleExtra(MonitorService.SOLAR_CHARGER_DATA_VALUE, 0.0)
                        }

                        SolarChargerDataType.TodayPeakPower -> {
                            state.solarPeakPower.value =
                                intent.getDoubleExtra(MonitorService.SOLAR_CHARGER_DATA_VALUE, 0.0)
                        }

                        SolarChargerDataType.ChargerTemp -> {
                            state.chargerTemp.value =
                                intent.getIntExtra(MonitorService.SOLAR_CHARGER_DATA_VALUE, 0)
                        }
                        else -> {}
                    }
                }

                MonitorService.ACTION_BATTERY_MONITOR_DATA_AVAILABLE -> {
                    val type =
                        BatteryMonitorDataType.fromInt(
                            intent.getIntExtra(MonitorService.BATTERY_MONITOR_DATA_TYPE, 0)
                        )
                    when (type) {
                        BatteryMonitorDataType.Voltage -> {
                            state.batteryVoltage.value = intent.getDoubleExtra(
                                MonitorService.BATTERY_MONITOR_DATA_VALUE,
                                0.0
                            )
                        }

                        BatteryMonitorDataType.Current -> {
                            state.batteryCurrent.value = intent.getDoubleExtra(
                                MonitorService.BATTERY_MONITOR_DATA_VALUE,
                                0.0
                            )
                        }

                        BatteryMonitorDataType.IsCharging -> {
                            state.batteryCharging.value = intent.getBooleanExtra(
                                MonitorService.BATTERY_MONITOR_DATA_VALUE,
                                false
                            )
                        }

                        BatteryMonitorDataType.RemainingAh -> {
                            state.batteryRemainingAh.value = intent.getDoubleExtra(
                                MonitorService.BATTERY_MONITOR_DATA_VALUE,
                                0.0
                            )
                        }

                        BatteryMonitorDataType.TotalChargeEnergy -> {
                            state.batteryTotalChargeEnergy.value = intent.getDoubleExtra(
                                MonitorService.BATTERY_MONITOR_DATA_VALUE,
                                0.0
                            )
                            state.batteryCharging.value = true;
                        }

                        BatteryMonitorDataType.TotalDischargeEnergy -> {
                            state.batteryTotalDischargeEnergy.value = intent.getDoubleExtra(
                                MonitorService.BATTERY_MONITOR_DATA_VALUE,
                                0.0
                            )
                            state.batteryCharging.value = false;
                        }

                        BatteryMonitorDataType.BatteryCapacity -> {
                            state.batteryCapacity.value = intent.getDoubleExtra(
                                MonitorService.BATTERY_MONITOR_DATA_VALUE,
                                0.0
                            )
                        }

                        BatteryMonitorDataType.RemainingTimeInMinutes -> {
                            state.batteryTimeRemaining.value =
                                intent.getIntExtra(MonitorService.BATTERY_MONITOR_DATA_VALUE, 0)
                        }

                        BatteryMonitorDataType.RecordProgressInMinutes -> {
                            state.batteryRecordMinutes.value = intent.getIntExtra(
                                MonitorService.BATTERY_MONITOR_DATA_VALUE, 0
                            )
                        }
                        BatteryMonitorDataType.RecordedDataStartDate -> {
                            state.lastBatteryRecordStartTime.value = intent.getParcelableExtra(
                                MonitorService.BATTERY_MONITOR_DATA_VALUE,
                            )
                        }
                        BatteryMonitorDataType.RecordedDataStartTime -> {
                            if (state.lastBatteryRecordStartTime.value != null) {
                                val date = state.lastBatteryRecordStartTime.value!!
                                val time = intent.getParcelableExtra(
                                    MonitorService.BATTERY_MONITOR_DATA_VALUE,
                                    LocalTime::class.java
                                )!!
                                state.lastBatteryRecordStartTime.value = Date(
                                    date.year,
                                    date.month,
                                    date.day,
                                    time.hour,
                                    time.minute,
                                    time.second
                                )
                            }
                        }
                        BatteryMonitorDataType.RecordedData -> {
                            val data: Array<VoltageCurrent>? = intent.getParcelableArrayExtra(
                                MonitorService.BATTERY_MONITOR_DATA_VALUE,
                                VoltageCurrent::class.java
                            )
                            state.pendingChanges.add(ModelChangeAction(
                                recordedData=data
                            ))
                        }
                        BatteryMonitorDataType.RecordedDataIndex -> {
                            val records: Array<BatteryMonitorHistoryRecord> = intent.getParcelableArrayExtra(
                                MonitorService.BATTERY_MONITOR_DATA_VALUE,
                                BatteryMonitorHistoryRecord::class.java
                            )!!
                            state.pendingChanges.add(ModelChangeAction(
                                historyRecords = records
                            ))
                        }

                        else -> {
                        }
                    }
                }
                MonitorService.ACTION_BATTERY_MONITOR_CONNECTED -> {
                    val device: BluetoothDevice = intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)!!
                    state.prefs.batteryMonitorAddress = device.address
                    state.unsaved.value = true
                }
                MonitorService.ACTION_SOLAR_CHARGER_CONNECTED -> {
                    val device: BluetoothDevice = intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)!!
                    state.prefs.solarChargerAddress = device.address
                    state.unsaved.value = true
                }
                else -> {}
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        val intent = Intent(this, MonitorService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        val intentFilter = IntentFilter()
        intentFilter.addAction(ACTION_SOLAR_CHARGER_CONNECTED)
        intentFilter.addAction(ACTION_SOLAR_CHARGER_DATA_AVAILABLE)
        intentFilter.addAction(ACTION_BATTERY_MONITOR_CONNECTED)
        intentFilter.addAction(ACTION_BATTERY_MONITOR_DATA_AVAILABLE)
        registerReceiver(dataReceiver, intentFilter, RECEIVER_EXPORTED)


        super.onCreate(savedInstanceState)
        setContent {
            SolarTheme {
                MainView(state)
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(dataReceiver)
        super.onDestroy()
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
fun MainView(state: AppViewModel) {
    val context = LocalContext.current
    val activity = context.getActivity<MainActivity>()!!
    LaunchedEffect(Unit){
        withContext(Dispatchers.Default) {
            state.load(activity.dataStore)
            while (true) {
                delay(SNAPSHOT_PERIOD)
                state.snapshot()
                state.syncPendingChanges()
                if (state.unsaved.value) {
                    state.save(activity.dataStore)
                }
            }
        }
    }

    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "devices") {
        composable("dashboard") {
            Log.i(TAG, "Navigate to dashboard")
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                bottomBar = { BottonNavBar(nav) }) { innerPadding ->
                DashboardScreen(nav, state)
            }
        }
        composable("charts") {
            Log.i(TAG, "Navigate to charts")
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                bottomBar = { BottonNavBar(nav) }) { innerPadding ->
                ChartsScreen(nav, state)
            }
        }
        composable("history") {
            Log.i(TAG, "Navigate to history")
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                bottomBar = { BottonNavBar(nav) }) { innerPadding ->
                HistoryScreen(nav, state)
            }
        }
        composable("devices") {
            Log.i(TAG, "Navigate to devices")
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                bottomBar = { BottonNavBar(nav) }) { innerPadding ->
                BluetoothDevicesScreen(nav, state)
            }
        }
    }
}

@Composable
fun BottonNavBar(nav: NavHostController) {
    NavigationBar {
        NavigationBarItem(
            selected = nav.currentDestination?.route == "dashboard",
            onClick = { nav.navigate("dashboard") },
            icon = { Icon(Icons.Outlined.Dashboard, null) },
            label = { Text("Live Data") }
        )
        NavigationBarItem(
            selected = nav.currentDestination?.route == "charts",
            onClick = { nav.navigate("charts") },
            icon = { Icon(Icons.Outlined.LineAxis, null) },
            label = { Text("Data Log") }
        )
        NavigationBarItem(
            selected = nav.currentDestination?.route == "history",
            onClick = { nav.navigate("history") },
            icon = { Icon(Icons.Outlined.History, null) },
            label = { Text("History") }
        )
        NavigationBarItem(
            selected = nav.currentDestination?.route == "devices",
            onClick = { nav.navigate("devices") },
            icon = { Icon(Icons.Outlined.Bluetooth, null) },
            label = { Text("Devices") }
        )

    }
}

@SuppressLint("InlinedApi")
@RequiresPermission(BLUETOOTH_SCAN)
@Composable
fun BluetoothScanEffect(
    scanSettings: ScanSettings,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    onScanFailed: (Int) -> Unit,
    onDeviceFound: (device: ScanResult) -> Unit,
) {
    // Copyright 2023 The Android Open Source Project
    // Licensed under the Apache License, Version 2.0 (the "License");
    val context = LocalContext.current
    val adapter = context.getSystemService<BluetoothManager>()?.adapter

    if (adapter == null) {
        onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
        return
    }

    val currentOnDeviceFound by rememberUpdatedState(onDeviceFound)

    DisposableEffect(lifecycleOwner, scanSettings) {
        val leScanCallback: ScanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                super.onScanResult(callbackType, result)
                currentOnDeviceFound(result)
            }

            override fun onScanFailed(errorCode: Int) {
                super.onScanFailed(errorCode)
                onScanFailed(errorCode)
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            // Start scanning once the app is in foreground and stop when in background
            if (event == Lifecycle.Event.ON_START) {
                adapter.bluetoothLeScanner.startScan(null, scanSettings, leScanCallback)
            } else if (event == Lifecycle.Event.ON_STOP) {
                adapter.bluetoothLeScanner.stopScan(leScanCallback)
            }
        }

        // Add the observer to the lifecycle
        lifecycleOwner.lifecycle.addObserver(observer)

        // When the effect leaves the Composition, remove the observer and stop scanning
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            adapter.bluetoothLeScanner.stopScan(leScanCallback)
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun BluetoothDevicesScreen(nav: NavHostController, state: AppViewModel) {
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
            FilledTonalButton(
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
            FilledTonalButton(
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
        return
    }

//    SystemBroadcastReceiver(systemActions = listOf(BluetoothDevice.ACTION_FOUND)) { intent ->
//        when (intent?.action) {
//            BluetoothDevice.ACTION_FOUND -> {
//                val device: BluetoothDevice =
//                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)!!
//                Log.i("Bluetooth", "Found ${device}")
//                if (!state.availableDevices.contains(device)) {
//                    state.availableDevices.add(device)
//                }
//            }
//
//            else -> {}
//        }
//    }
    val scanSettings: ScanSettings = ScanSettings.Builder()
        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
        .build()

    var scanning by remember { mutableStateOf(true) }
    if (scanning) {
        BluetoothScanEffect(scanSettings,
            onScanFailed = {
                scanning = false
            },
            onDeviceFound = { scanResult ->
                val device: BluetoothDevice = scanResult.device
                Log.i("Bluetooth", "Found ${device} rssi=${scanResult.rssi}")
                if (!state.availableDevices.contains(device)) {
                    state.availableDevices.add(device)
                }
                val lastBatteryMonitor = if (state.prefs.batteryMonitorAddress != null) activity.bluetoothAdapter.getRemoteDevice(state.prefs.batteryMonitorAddress) else null
                val lastSolarCharger = if (state.prefs.solarChargerAddress != null ) activity.bluetoothAdapter.getRemoteDevice(state.prefs.solarChargerAddress) else null
                if (!state.connectedDevices.contains(device) && (device == lastBatteryMonitor || device == lastSolarCharger)) {
                    state.connectedDevices.add(device)
                    activity.monitorService?.connect(device)
                }

            })
        LaunchedEffect(true) {
            delay(15000)
            scanning = false
        }
    }
    Column(Modifier.padding(16.dp)) {
        Text(
            "Available devices",
            fontWeight = FontWeight.Light,
            style = Typography.headlineLarge
        )
        if (state.connectedDevices.size == 0) {
            Text("No charger or battery monitor is connected.", style = Typography.bodyLarge)
        }
        if (scanning) {
            Text("Scanning for devices...")
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            if (state.availableDevices.size == 0) {
                Text("No devices found... ")
            }
            FilledTonalButton(onClick = { scanning = true }, modifier=Modifier.fillMaxWidth()) {
                Text("Start scanning", modifier = Modifier.padding(4.dp))
            }
        }
        state.availableDevices.forEach { device ->
            var name: String? = device.name
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
                    .clickable(onClick = {
                        if (state.connectedDevices.contains(device)) {
                            state.connectedDevices.remove(device)
                            activity.monitorService?.disconnect(device)
                        } else {
                            Log.d(TAG, "New bluetooth connection")
                            state.connectedDevices.add(device)
                            activity.monitorService?.connect(device)
                        }

                    })
                    .fillMaxWidth()
            ) {
                Text(if (name != null) name else "Unnamed device")
                if (state.connectedDevices.contains(device)) {
                    val conn = activity.monitorService?.connections?.find { it.device == device }
                    if (conn?.connectionState?.value == STATE_CONNECTED) {
                        Icon(imageVector = Icons.Filled.BluetoothConnected, contentDescription =null, tint = Color.Green )
                        Text("Connected", style = Typography.labelSmall)
                    } else {
                        Icon(imageVector = Icons.Filled.BluetoothSearching, contentDescription =null, tint = Color.DarkGray )
                        Text("Connecting...", style = Typography.labelSmall)
                        CircularProgressIndicator()
                    }
                }
            }
            HorizontalDivider()
        }
    }
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

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun DashboardScreen(nav: NavHostController, state: AppViewModel) {
    val context = LocalContext.current
    val activity = context.getActivity<MainActivity>()!!

    var refreshing by remember { mutableStateOf(false) }
    val refreshScope = rememberCoroutineScope()
    fun refresh() = refreshScope.launch {
        refreshing = true
        Log.d(TAG, "Refreshing")
        activity.handler.postDelayed({
            refreshing = false
        }, 5000)
        var n = activity.monitorService!!.connections.size;
        activity.monitorService?.sync{ action, value ->
            n -= 1;
            refreshing = n > 0
        }
    }

    val refreshState = rememberPullRefreshState(refreshing, ::refresh)

    Box(Modifier.pullRefresh(refreshState)) {
        Column(
            Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Column(Modifier.padding(8.dp)) {
                Text(
                    text = "Solar Panels",
                    style = Typography.headlineSmall,
                    fontWeight = FontWeight.Light
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        Modifier
                            .padding(8.dp)
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        Text("Voltage", style = Typography.labelSmall)
                        Text(
                            text = "${state.solarVoltage.value}V",
                            style = Typography.headlineSmall
                        )
                        Text("Current", style = Typography.labelSmall)
                        Text(
                            text = "${"%.2f".format(state.solarCurrent.value)}A",
                            style = Typography.headlineSmall
                        )
                    }
                    Column(
                        Modifier
                            .padding(8.dp)
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        Text("Today's energy output", style = Typography.labelSmall)
                        Text(
                            text = "${"%.0f".format(state.chargerTodayEnergy.value)}Wh",
                            style = Typography.displaySmall
                        )
                        Text("Today's peak power", style = Typography.labelSmall)
                        Text(
                            text = "${"%.0f".format(state.solarPeakPower.value)}W",
                            style = Typography.bodyLarge
                        )
                    }
                }
            }
            HorizontalDivider()
            Column(Modifier.padding(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Charger",
                        style = Typography.headlineSmall,
                        fontWeight = FontWeight.Light
                    )
                    if (activity.monitorService?.connections?.find { it.deviceType.value == DeviceType.SolarCharger && it.connectionState.value == STATE_CONNECTED } != null) {
                        Icon(Icons.Filled.BluetoothConnected, null, tint=Color.Green)
                    } else {
                        Icon(Icons.Filled.BluetoothDisabled, null, tint=Color.Red)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        Modifier
                            .padding(8.dp)
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        Text("Voltage", style = Typography.labelSmall)
                        Text(
                            text = "${"%.2f".format(state.chargerVoltage.value)}V",
                            style = Typography.headlineSmall
                        )
                        Text(
                            text = "Min ${"%.2f".format(state.chargerMinVoltage.value)}V Max ${"%.2f".format(state.chargerMaxVoltage.value)}V",
                            style = Typography.bodySmall
                        )
                        Text("Current", style = Typography.labelSmall)
                        Text(
                            text = "${"%.2f".format(state.chargerCurrent.value)}A",
                            style = Typography.headlineSmall
                        )
                    }
                    Column(
                        Modifier
                            .padding(8.dp)
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        Text("Power", style = Typography.labelSmall)
                        Text(
                            text = "${"%.0f".format(state.chargePower.value)}W",
                            style = Typography.displaySmall
                        )
                        Text("Total energy output", style = Typography.labelSmall)
                        Text(
                            text = "${"%.3f".format(state.chargerTotalChargeEnergy.value/1000)}kWh",
                            style = Typography.bodyLarge
                        )
                        Text("Temperature", style = Typography.labelSmall)
                        Text(
                            text = "${state.chargerTemp.value}°C | ${"%.0f".format(state.chargerTemp.value.toFloat() * 9.0 / 5.0 + 32)}°F",
                            style = Typography.bodyLarge
                        )
                    }
                }
            }
            HorizontalDivider()
            Column(Modifier.padding(8.dp)) {
                Text(
                    text = "Inverter",
                    style = Typography.headlineSmall,
                    fontWeight = FontWeight.Light
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        Modifier
                            .padding(8.dp)
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        Text("Current", style = Typography.labelSmall)
                        Text(
                            text = "${"%.2f".format(state.inverterCurrent.value)}A",
                            style = Typography.headlineSmall
                        )
                    }
                    Column(
                        Modifier
                            .padding(8.dp)
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        Text("Power draw", style = Typography.labelSmall)
                        Text(
                            text = "${"%.0f".format(state.inverterPower.value)}W",
                            style = Typography.headlineSmall
                        )
                    }
                    Column(
                        Modifier
                            .padding(8.dp)
                            .fillMaxWidth()
                            .weight(2f)
                    ) {
                        Text("Energy usage", style = Typography.labelSmall)
                        Text(
                            text = "${"%.0f".format(state.inverterEnergy.value)}Wh",
                            style = Typography.displaySmall
                        )
                    }
                }
            }
            HorizontalDivider()
            Column(Modifier.padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Battery",
                        style = Typography.headlineSmall,
                        fontWeight = FontWeight.Light
                    )
                    if (activity.monitorService?.connections?.find { it.deviceType.value == DeviceType.BatteryMonitor && it.connectionState.value == STATE_CONNECTED } != null) {
                        Icon(Icons.Filled.BluetoothConnected, null, tint=Color.Green)
                    } else {
                        Icon(Icons.Filled.BluetoothDisabled, null, tint=Color.Red)
                    }
                    Text(
                        if (state.batteryCharging.value) "Charging" else "Discharging",
                        Modifier.padding(8.dp),
                        style = Typography.labelSmall
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        Modifier
                            .padding(8.dp)
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        Text("Voltage", style = Typography.labelSmall)
                        Text(
                            text = "${"%.2f".format(state.batteryVoltage.value)}V",
                            style = Typography.headlineSmall
                        )
                    }
                    Column(
                        Modifier
                            .padding(8.dp)
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        Text("Current", style = Typography.labelSmall)
                        Text(
                            text = "${"%.2f".format(state.batteryCurrent.value)}A",
                            style = Typography.headlineSmall
                        )
                    }
                    Column(
                        Modifier
                            .padding(8.dp)
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        Text("Power", style = Typography.labelSmall)
                        Text(
                            text = "${"%.0f".format(state.batteryCurrent.value * state.batteryVoltage.value)}W",
                            style = Typography.displaySmall
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier
                            .padding(8.dp)
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        Text("Remaining capacity", style = Typography.labelSmall)
                        Text(
                            text = "${"%.3f".format(state.batteryRemainingAh.value)}Ah",
                            style = Typography.displaySmall
                        )
                        Text("Total capacity", style = Typography.labelSmall)
                        Text(
                            text = "${"%.0f".format(state.batteryCapacity.value)}Ah",
                            style = Typography.bodyMedium
                        )
                    }
                    Column(
                        Modifier
                            .padding(8.dp)
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        Text("Remaining time", style = Typography.labelSmall)
                        val hours = state.batteryTimeRemaining.value / 60
                        val minutes = state.batteryTimeRemaining.value % 60
                        Text(
                            text = "${hours}h ${minutes}min",
                            style = Typography.displaySmall
                        )
                        Text("Battery percent", style = Typography.labelSmall)
                        Text(
                            text = "${"%.2f".format(state.batteryPercentage.value)}%",
                            style = Typography.bodyMedium
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier
                            .padding(8.dp)
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        Text("Total charge energy", style = Typography.labelSmall)
                        Text(
                            text = "${"%.3f".format(state.batteryTotalChargeEnergy.value/1000)}kWh",
                            style = Typography.headlineSmall
                        )
                    }
                    Column(
                        Modifier
                            .padding(8.dp)
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        Text("Total discharge energy", style = Typography.labelSmall)
                        Text(
                            text = "${"%.3f".format(state.batteryTotalDischargeEnergy.value/1000)}kWh",
                            style = Typography.headlineSmall
                        )
                    }
                }
            }

        }
        PullRefreshIndicator(refreshing, refreshState, Modifier.align(Alignment.TopCenter))
    }
}


val chartColors = listOf(
    Color(0xffb983ff), Color(0xff91b1fd), Color(0xff8fdaff)
)
val batteryPowerChartColors = listOf(Color(0xFF3716FF), Color(0xFFFF166A))
val currentsLegend = listOf(
    "Charger current",
    "Inverter current",
    "Battery current"
)
val voltageLegend = listOf(
    "Solar Panels",
    "Charger",
    "Battery"
)
fun secondsToStr(seconds: Int): String {
    if (seconds < 60) {
        return "${seconds} sec"
    }
    val mins = seconds/60;
    if (seconds < 60*60) {
        return "${mins} min${if (mins == 1) "" else "s"}"
    }
    val hrs = mins/60;
    return "${hrs} hour${if (hrs == 1) "" else "s"}"
}

@Composable
fun ChartsScreen(nav: NavHostController, state: AppViewModel) {
    val solarPowerModelProducer = remember { state.solarPowerModelProducer }
    val batteryPowerModelProducer = remember { state.batteryPowerModelProducer }
    val inverterPowerModelProducer = remember { state.inverterPowerModelProducer }
    val currentsModelProducer = remember { state.currentsModelProducer }
    val voltagesModelProducer = remember { state.voltagesModelProducer }
    var zoom by remember {mutableStateOf(state.chartXStep.value * 6) }

    LaunchedEffect(Unit){//state.lastUpdate.value) {
        Log.d(TAG, "New launch")
        withContext(Dispatchers.Default) {
            while (true) {
                state.mutex.withLock {
                    if (state.snapshots.isNotEmpty()) {
                        val t0 = state.snapshots.first().date.toInstant().epochSecond
                        val x = state.snapshots.map { it.date.toInstant().epochSecond - t0 }
                        solarPowerModelProducer.runTransaction {
                            lineSeries {
                                series(
                                    x,
                                    state.snapshots.map { it.chargerCurrent * it.chargerVoltage })
                            }
                        }
                        batteryPowerModelProducer.runTransaction {
                            lineSeries {
                                series(
                                    x,
                                    state.snapshots.map { it.batteryCurrent * it.batteryVoltage })
                            }
                        }
                        inverterPowerModelProducer.runTransaction {
                            lineSeries {
                                series(
                                    x,
                                    state.snapshots.map {
                                        max(
                                            0.0,
                                            // If charger or battery monitor is not connected don't show this
                                            if (it.chargerVoltage > 0 && it.batteryVoltage > 0)
                                                it.chargerCurrent * it.chargerVoltage - it.batteryCurrent * it.batteryVoltage
                                            else
                                                0.0
                                        )
                                    })
                            }
                        }
                        currentsModelProducer.runTransaction {
                            lineSeries {
                                series(
                                    x,
                                    state.snapshots.map { it.chargerCurrent })
                                series(
                                    x,
                                    state.snapshots.map { it.chargerCurrent - it.batteryCurrent })
                                series(
                                    x,
                                    state.snapshots.map { it.batteryCurrent })
                            }
                        }
                        voltagesModelProducer.runTransaction {
                            lineSeries {
                                series(
                                    x,
                                    state.snapshots.map { it.solarVoltage })
                                series(
                                    x,
                                    state.snapshots.map { it.chargerVoltage })
                                series(
                                    x,
                                    state.snapshots.map { it.batteryVoltage })
                            }
                        }
                    }
                }
                delay(1000)
            }
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "Solar Power",
            style = Typography.headlineSmall,
            fontWeight = FontWeight.Light
        )
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(
                    LineCartesianLayer.LineProvider.series(
                        rememberLine(
                            fill = remember { LineCartesianLayer.LineFill.single(fill(Color(0xffffc983))) },
                        )
                    )
                ),
                getXStep = { state.chartXStep.value },
                startAxis = rememberStartAxis(
                    horizontalLabelPosition = VerticalAxis.HorizontalLabelPosition.Inside,
                    titleComponent = rememberTextComponent(),
                    title="Power (Watts)"
                ),
                bottomAxis = rememberBottomAxis(
                    guideline = null,
                    title="Time (Seconds)",
                    titleComponent = rememberTextComponent(),
                ),
            ),
            modelProducer = solarPowerModelProducer,
            zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.x(zoom)),
            scrollState = rememberVicoScrollState(
                initialScroll = Scroll.Absolute.End,
                autoScroll = Scroll.Absolute.End,
                autoScrollCondition = if (state.chartAutoscroll.value) AutoScrollCondition.OnModelSizeIncreased else AutoScrollCondition.Never
            ),
            modifier = Modifier
                .height(200.dp)
                .fillMaxWidth()
        )

        Text(
            "Inverter Power",
            style = Typography.headlineSmall,
            fontWeight = FontWeight.Light
        )
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(
                    LineCartesianLayer.LineProvider.series(
                        rememberLine(
                            fill = remember { LineCartesianLayer.LineFill.single(fill(Color(0xfff283ff))) },
                        )
                    )
                ),
                getXStep = { state.chartXStep.value },
                startAxis = rememberStartAxis(
                    horizontalLabelPosition = VerticalAxis.HorizontalLabelPosition.Inside,
                    titleComponent = rememberTextComponent(),
                    title="Power (Watts)"
                ),
                bottomAxis = rememberBottomAxis(
                    guideline = null,
                    title="Time (Seconds)",
                    titleComponent = rememberTextComponent(),
                ),
            ),
            modelProducer = inverterPowerModelProducer,
            zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.x(zoom)),
            scrollState = rememberVicoScrollState(
                initialScroll = Scroll.Absolute.End,
                autoScroll = Scroll.Absolute.End,
                autoScrollCondition = if (state.chartAutoscroll.value) AutoScrollCondition.OnModelSizeIncreased else AutoScrollCondition.Never
            ),
            modifier = Modifier
                .height(200.dp)
                .fillMaxWidth()
        )

        Text(
            "Battery Power",
            style = Typography.headlineSmall,
            fontWeight = FontWeight.Light
        )
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(
                    LineCartesianLayer.LineProvider.series(
                        rememberLine(
                            fill =
                                remember(batteryPowerChartColors) {
                                    LineCartesianLayer.LineFill.double(fill(batteryPowerChartColors[0]), fill(batteryPowerChartColors[1]))
                                }
                        )
                    )
                ),
                getXStep = { state.chartXStep.value },
                startAxis = rememberStartAxis(
                    horizontalLabelPosition = VerticalAxis.HorizontalLabelPosition.Inside,
                    titleComponent = rememberTextComponent(),
                    title="Power (Watts)"
                ),
                bottomAxis = rememberBottomAxis(
                    guideline = null,
                    titleComponent = rememberTextComponent(),
                    title="Time (Seconds)"
                ),
                //marker = rememberMarker(),
            ),
            modelProducer = batteryPowerModelProducer,
            zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.x(zoom)),
            scrollState = rememberVicoScrollState(
                initialScroll = Scroll.Absolute.End,
                autoScroll = Scroll.Absolute.End,
                autoScrollCondition = if (state.chartAutoscroll.value) AutoScrollCondition.OnModelSizeIncreased else AutoScrollCondition.Never
            ),
            modifier = Modifier
                .height(200.dp)
                .fillMaxWidth()
        )

//        Text(
//            "Currents",
//            style = Typography.headlineSmall,
//            fontWeight = FontWeight.Light
//        )
//        CartesianChartHost(
//            chart = rememberCartesianChart(
//                rememberLineCartesianLayer(
//                    LineCartesianLayer.LineProvider.series(
//                        chartColors.map { color ->
//                            rememberLine(
//                                fill = remember { LineCartesianLayer.LineFill.single(fill(color)) },
//                            )
//                        }
//                    )
//                ),
//                getXStep = { state.chartXStep.value },
//                startAxis = rememberStartAxis(
//                    horizontalLabelPosition = VerticalAxis.HorizontalLabelPosition.Inside,
//                    titleComponent = rememberTextComponent(),
//                    title="Current (A)"
//                ),
//                bottomAxis = rememberBottomAxis(
//                    guideline = null,
//                    titleComponent = rememberTextComponent(),
//                    title="Time (Seconds)"
//                ),
//                legend = rememberVerticalLegend<CartesianMeasureContext, CartesianDrawContext>(
//                    items =
//                    chartColors.mapIndexed { index, chartColor ->
//                        rememberLegendItem(
//                            icon = rememberShapeComponent(chartColor, Shape.Pill),
//                            labelComponent = rememberTextComponent(vicoTheme.textColor),
//                            label = currentsLegend[index],
//                        )
//                    },
//                    iconSize = 8.dp,
//                    iconPadding = 8.dp,
//                    spacing = 4.dp,
//                    padding = Dimensions.of(top = 8.dp),
//                )
//                //marker = rememberMarker(),
//            ),
//            modelProducer = currentsModelProducer,
//            zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.x(zoom)),
//            scrollState = rememberVicoScrollState(
//                initialScroll = Scroll.Absolute.End,
//                autoScroll = Scroll.Absolute.End,
//                autoScrollCondition = if (autoscroll) AutoScrollCondition.OnModelSizeIncreased else AutoScrollCondition.Never,
//            ),
//            modifier = Modifier
//                .height(300.dp)
//                .fillMaxWidth()
//        )
//
//        Text(
//            "Voltages",
//            style = Typography.headlineSmall,
//            fontWeight = FontWeight.Light
//        )
//        CartesianChartHost(
//            chart = rememberCartesianChart(
//                rememberLineCartesianLayer(
//                    LineCartesianLayer.LineProvider.series(
//                        chartColors.map { color ->
//                            rememberLine(
//                                fill = remember { LineCartesianLayer.LineFill.single(fill(color)) },
//                            )
//                        }
//                    )
//                ),
//                getXStep = { state.chartXStep.value },
//                startAxis = rememberStartAxis(
//                    horizontalLabelPosition = VerticalAxis.HorizontalLabelPosition.Inside,
//                    titleComponent = rememberTextComponent(),
//                    title="Voltage (V)"
//                ),
//                bottomAxis = rememberBottomAxis(
//                    guideline = null,
//                    titleComponent = rememberTextComponent(),
//                    title="Time (Seconds)"
//                ),
//                legend = rememberVerticalLegend<CartesianMeasureContext, CartesianDrawContext>(
//                    items =
//                    chartColors.mapIndexed { index, chartColor ->
//                        rememberLegendItem(
//                            icon = rememberShapeComponent(chartColor, Shape.Pill),
//                            labelComponent = rememberTextComponent(vicoTheme.textColor),
//                            label = voltageLegend[index],
//                        )
//                    },
//                    iconSize = 8.dp,
//                    iconPadding = 8.dp,
//                    spacing = 4.dp,
//                    padding = Dimensions.of(top = 8.dp),
//                )
//                //marker = rememberMarker(),
//            ),
//            modelProducer = voltagesModelProducer,
//            zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.x(zoom)),
//            scrollState = rememberVicoScrollState(
//                initialScroll = Scroll.Absolute.End,
//                autoScroll = Scroll.Absolute.End,
//                autoScrollCondition = if (autoscroll) AutoScrollCondition.OnModelSizeIncreased else AutoScrollCondition.Never
//            ),
//            modifier = Modifier
//                .height(300.dp)
//                .fillMaxWidth()
//        )
        ChartControls(
            autoscroll = state.chartAutoscroll.value,
            onAutoscrollChanged = {state.chartAutoscroll.value = it},
            xstep = state.chartXStep.value,
            xstepToString = {secondsToStr(it.toInt() * 6)},
            onXStepChanged = {state.chartXStep.value = it; zoom = it*6}
        )
    }
}

@Composable
fun ChartControls(
    autoscroll: Boolean,
    onAutoscrollChanged: (value: Boolean)->Unit,
    xstep: Double,
    xstepToString: (value: Double) -> String,
    onXStepChanged: (value: Double)-> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        var autoscrollExpanded by remember {mutableStateOf(false) }
        FilledTonalButton(onClick = {autoscrollExpanded = !autoscrollExpanded}) {
            Text("Autoscroll: ${if (autoscroll) "On" else "Off"}")
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = autoscrollExpanded, onDismissRequest = { autoscrollExpanded = false }) {
            DropdownMenuItem(onClick = {onAutoscrollChanged(false)}, text={Text("Off")})
            DropdownMenuItem(onClick = {onAutoscrollChanged(true)}, text={Text("On")})
        }

        var timePeriodsExpanded by remember {mutableStateOf(false) }
        var timePeriods = remember{listOf(10, 50, 100, 300, 600, 1200)}
        FilledTonalButton(onClick = {timePeriodsExpanded = !timePeriodsExpanded}) {
            Text("Time Span: ${xstepToString(xstep)}")
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = timePeriodsExpanded, onDismissRequest = { timePeriodsExpanded = false }) {
            timePeriods.forEach {
                DropdownMenuItem(onClick = { onXStepChanged(it.toDouble()); timePeriodsExpanded = false }, text={Text(
                    xstepToString(it.toDouble())
                )})
            }
        }
    }
}

@OptIn(ExperimentalMaterialApi::class, ExperimentalStdlibApi::class)
@Composable
fun HistoryScreen(nav: NavHostController, state: AppViewModel) {
    val context = LocalContext.current
    val activity = context.getActivity<MainActivity>()!!
    val batteryModelProducer = remember { state.batteryRecordedDataModelProducer }
    val chargerHistoryModelProducer = remember { state.chargerHistoryModelProducer }

    var loading by remember {mutableStateOf(false)}
    var zoom by remember {mutableStateOf(state.historyXStep.value * 6) }
    val batteryMonitor = activity.monitorService?.connections?.find{
        it.deviceType.value == DeviceType.BatteryMonitor}
    val solarCharger = activity.monitorService?.connections?.find{
        it.deviceType.value == DeviceType.SolarCharger}


    LaunchedEffect(Unit) {
        Log.d(TAG, "New launch")
        withContext(Dispatchers.Default) {
            while (true) {
                state.mutex.withLock {
                    if (state.batteryRecordedData.isNotEmpty()) {
                        batteryModelProducer.runTransaction {
                            lineSeries {
                                series(
                                    state.batteryRecordedData.mapIndexed { index, voltageCurrent -> index },
                                    state.batteryRecordedData.map { it.voltage * it.current })
                            }
                        }
                    }
                    if (state.chargerHistoryData.isNotEmpty()) {
                        chargerHistoryModelProducer.runTransaction {
                            lineSeries {
                                series(
                                    state.chargerHistoryData.map { it.key },
                                    state.chargerHistoryData.map{ it.value.totalEnergy }
                                )
                            }
                        }
                    }
                }
                delay(1000)
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .verticalScroll(rememberScrollState())
    ) {
        if (loading) {
            Text("Loading...")
            LinearProgressIndicator()
        }
        if (batteryMonitor != null && batteryMonitor!!.connectionState.value == STATE_CONNECTED) {
            if (state.batteryRecordMinutes.value > 0) {
                LaunchedEffect(Unit) {
                    loading = true
                    batteryMonitor!!.writeCommand(
                        BatteryMonitorCommands.RECORD_HISTORY,
                        5000
                    ) { action, value -> loading = false }
                }
                var recordingSelectExpanded by remember { mutableStateOf(false) }
                if (state.batteryHistoryRecords.size > 0) {
                    FilledTonalButton(
                        onClick = { recordingSelectExpanded = !recordingSelectExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Recordings")
                        Icon(Icons.Filled.ArrowDropDown, null)
                    }
                    DropdownMenu(
                        expanded = recordingSelectExpanded,
                        onDismissRequest = { recordingSelectExpanded = false }) {
                        state.batteryHistoryRecords.forEach { record ->
                            DropdownMenuItem(onClick = {
                                recordingSelectExpanded = false
                                loading = true
                                state.pendingChanges.add(ModelChangeAction(clearRecordedData = true))
                                fun loadRecording(i: Int) {
                                    batteryMonitor!!.writeAllCommands(
                                        BatteryMonitorCommands.loadRecording(5, i),
                                        3000
                                    ) { action, value ->
                                        if (i + 5 < record.size) {
                                            activity.handler.postDelayed({
                                                loadRecording(i + 5)
                                            }, 500)
                                        } else {
                                            loading = false
                                        }
                                    }
                                }
                                loadRecording(0)
                            }, text = {
                                Text("${record.date} (${record.size})")
                            })
                        }
                    }
                }
            } else {
                Text("No recordings or data not yet loaded")
            }
        } else {
            Text("Battery monitor is not connected")
        }

        Text(
            "Battery Power",
            style = Typography.headlineSmall,
            fontWeight = FontWeight.Light
        )
        if (state.batteryRecordedData.isNotEmpty()) {
            CartesianChartHost(
                chart = rememberCartesianChart(
                    rememberLineCartesianLayer(
                        LineCartesianLayer.LineProvider.series(
                            rememberLine(
                                fill =
                                remember(batteryPowerChartColors) {
                                    LineCartesianLayer.LineFill.double(
                                        fill(batteryPowerChartColors[0]),
                                        fill(batteryPowerChartColors[1])
                                    )
                                }
                            )
                        )
                    ),
                    getXStep = { state.historyXStep.value },
                    startAxis = rememberStartAxis(
                        horizontalLabelPosition = VerticalAxis.HorizontalLabelPosition.Inside,
                        titleComponent = rememberTextComponent(),
                        title = "Power (Watts)"
                    ),
                    bottomAxis = rememberBottomAxis(
                        guideline = null,
                        titleComponent = rememberTextComponent(),
                        title = "Time (Seconds)"
                    ),
                    //marker = rememberMarker(),
                ),
                modelProducer = batteryModelProducer,
                zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.x(zoom)),
                scrollState = rememberVicoScrollState(
                    initialScroll = Scroll.Absolute.End,
                    autoScroll = Scroll.Absolute.End,
                    autoScrollCondition = if (state.historyAutoscroll.value) AutoScrollCondition.OnModelSizeIncreased else AutoScrollCondition.Never
                ),
                modifier = Modifier
                    .height(200.dp)
                    .fillMaxWidth()
            )

            ChartControls(
                autoscroll = state.historyAutoscroll.value,
                onAutoscrollChanged = { state.historyAutoscroll.value = it },
                xstep = state.historyXStep.value,
                xstepToString = { secondsToStr(it.toInt() * 6) },
                onXStepChanged = { zoom = it * 6; state.historyXStep.value = it; }
            )
        } else {
            Text("No history data is available")
        }

        Text(
            "Charger History",
            style = Typography.headlineSmall,
            fontWeight = FontWeight.Light
        )
        if (solarCharger != null && solarCharger!!.connectionState.value == STATE_CONNECTED) {
            LaunchedEffect(Unit) {
                solarCharger!!.writeAllCommands(
                    listOf(
                        SolarChargerCommands.loadHistory(1),
                        SolarChargerCommands.loadHistory(2),
                        SolarChargerCommands.loadHistory(3),
                        SolarChargerCommands.loadHistory(4),
                        SolarChargerCommands.loadHistory(5),
                    ),
                    3000
                ) {  action, value -> loading = false }
            }
        } else {
            Text("No history data is available")
        }

        if (state.chargerHistoryData.isNotEmpty()) {
            CartesianChartHost(
                chart = rememberCartesianChart(
                    rememberLineCartesianLayer(
                        LineCartesianLayer.LineProvider.series(
                            rememberLine(
                                fill = remember {
                                    LineCartesianLayer.LineFill.single(
                                        fill(
                                            Color(
                                                0xfff283ff
                                            )
                                        )
                                    )
                                },
                            )
                        )
                    ),
                    getXStep = { state.chartXStep.value },
                    startAxis = rememberStartAxis(
                        horizontalLabelPosition = VerticalAxis.HorizontalLabelPosition.Inside,
                        titleComponent = rememberTextComponent(),
                        title = "Power (Watts)"
                    ),
                    bottomAxis = rememberBottomAxis(
                        guideline = null,
                        title = "Time (Seconds)",
                        titleComponent = rememberTextComponent(),
                    ),
                ),
                modelProducer = chargerHistoryModelProducer,
                zoomState = rememberVicoZoomState(zoomEnabled = false),
                scrollState = rememberVicoScrollState(),
                modifier = Modifier
                    .height(200.dp)
                    .fillMaxWidth()
            )

        }
    }

}