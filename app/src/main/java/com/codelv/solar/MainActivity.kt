package com.codelv.solar

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
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
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.codelv.solar.ui.theme.SolarTheme
import com.codelv.solar.ui.theme.Typography
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlin.math.abs
import kotlin.math.max

private const val SELECT_DEVICE_REQUEST_CODE = 0
private const val REQUEST_ENABLE_BT = 1


class MainActivity : ComponentActivity() {
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

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        val intent = Intent(this, MonitorService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        super.onCreate(savedInstanceState)
        setContent {
            SolarTheme {
                MainView()
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
fun MainView(state: AppViewModel = viewModel()) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "devices") {
        composable("devices") {
            Log.i(TAG, "Navigate to devices creen")
            Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                BluetoothDevicesScreen(nav, state)
            }
        }
        composable("dashboard") {
            Log.i(TAG, "Navigate to dashboard")
            Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                DashboardScreen(nav, state)
            }
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
                    if (!state.availableDevices.contains(device)) {
                        state.availableDevices.add(device)
                    }
                }
                else -> {}
            }
        }
        state.availableDevices.forEach({ device ->
            var name: String? = device.name
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
                    .clickable(onClick = {
                        if (!state.connectedDevices.contains(device)) {
                            Log.d(TAG, "New bluetooth connection")
                            state.connectedDevices.add(device)
                            activity.monitorService?.connect(device)
                        }

                    })
                    .fillMaxWidth()
            ) {
                Text(if (name != null) name else "Unamed device")
                if (state.connectedDevices.contains(device)) {
                    Text("Connected", style= Typography.labelSmall)
                }
            }
            HorizontalDivider()
        })
        Button(onClick = {
            activity.bluetoothAdapter?.cancelDiscovery()
            nav.navigate("dashboard")
        }, modifier = Modifier.fillMaxWidth()) {
            Text(text = "Go to dashboard", Modifier.padding(4.dp))
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

@Composable
fun DashboardScreen(nav: NavHostController, state: AppViewModel) {
    val context = LocalContext.current
    val activity = context.getActivity<MainActivity>()!!

    var solarVoltage by remember { mutableStateOf(0.0) }
    var chargeVoltage by remember { mutableStateOf(0.0) }
    var chargeCurrent by remember { mutableStateOf(0.0) }
    var chargeEnergy by remember { mutableStateOf(0.0) }
    var chargerTemp by remember { mutableStateOf(0) }
    var solarPeakPower by remember { mutableStateOf(0.0) }

    var batteryRemainingAh by remember { mutableStateOf(0.0) }
    var batteryCharging by remember{ mutableStateOf(false) }
    var batteryCurrent by remember{ mutableStateOf(0.0) }
    var batteryVoltage by remember{ mutableStateOf(0.0) }
    var batteryTotalChargeEnergy by remember{ mutableStateOf(0.0) }
    var batteryTotalDischargeEnergy by remember{ mutableStateOf(0.0) }
    var batteryCapacity by remember{ mutableStateOf(0.0) }
    var batteryTimeRemaining by remember{ mutableStateOf(0) }
    var inverterCurrent = abs(chargeCurrent + (if (batteryCharging) -1 else 1) * batteryCurrent);
    var inverterPower = inverterCurrent * max(chargeVoltage, batteryVoltage);
    var inverterEnergy = max(0.0, chargeEnergy - batteryTotalChargeEnergy);
    var chargePower = chargeVoltage * chargeCurrent;
    var solarCurrent = if (solarVoltage > 0) chargePower / solarVoltage else 0.0;
    var batteryPercentage = if (batteryCapacity > 0) batteryRemainingAh / batteryCapacity * 100 else 0.0;

    SystemBroadcastReceiver(systemActions = listOf(MonitorService.ACTION_BATTERY_MONITOR_DATA_AVAILABLE, MonitorService.ACTION_SOLAR_CHARGER_DATA_AVAILABLE)) { intent ->
        when (intent?.action) {
            MonitorService.ACTION_SOLAR_CHARGER_DATA_AVAILABLE -> {
                val type =
                    SolarChargerDataType.fromInt(
                        intent.getIntExtra(MonitorService.SOLAR_CHARGER_DATA_TYPE, 0)
                    )
                when (type) {
                    SolarChargerDataType.SolarVoltage -> {
                        solarVoltage = intent.getDoubleExtra(MonitorService.SOLAR_CHARGER_DATA_VALUE, 0.0)
                    }
                    SolarChargerDataType.ChargeVoltage -> {
                        chargeVoltage = intent.getDoubleExtra(MonitorService.SOLAR_CHARGER_DATA_VALUE, 0.0)
                    }
                    SolarChargerDataType.ChargeCurrent -> {
                        chargeCurrent = intent.getDoubleExtra(MonitorService.SOLAR_CHARGER_DATA_VALUE, 0.0)
                    }
                    SolarChargerDataType.TodayChargeEnergy -> {
                        chargeEnergy = intent.getDoubleExtra(MonitorService.SOLAR_CHARGER_DATA_VALUE, 0.0)
                    }
                    SolarChargerDataType.TodayPeakPower -> {
                        solarPeakPower = intent.getDoubleExtra(MonitorService.SOLAR_CHARGER_DATA_VALUE, 0.0)

                    }
                    SolarChargerDataType.ChargerTemp -> {
                        chargerTemp = intent.getIntExtra(MonitorService.SOLAR_CHARGER_DATA_VALUE, 0)
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
                        batteryVoltage = intent.getDoubleExtra(MonitorService.BATTERY_MONITOR_DATA_VALUE, 0.0)
                    }
                    BatteryMonitorDataType.Current -> {
                        batteryCurrent = intent.getDoubleExtra(MonitorService.BATTERY_MONITOR_DATA_VALUE, 0.0)
                    }
                    BatteryMonitorDataType.IsCharging -> {
                        batteryCharging = intent.getBooleanExtra(MonitorService.BATTERY_MONITOR_DATA_VALUE, false)
                    }
                    BatteryMonitorDataType.RemainingAh -> {
                        batteryRemainingAh = intent.getDoubleExtra(MonitorService.BATTERY_MONITOR_DATA_VALUE, 0.0)
                    }
                    BatteryMonitorDataType.TotalChargeEnergy -> {
                        batteryTotalChargeEnergy = intent.getDoubleExtra(MonitorService.BATTERY_MONITOR_DATA_VALUE, 0.0)
                        batteryCharging = true;
                    }
                    BatteryMonitorDataType.TotalDischargeEnergy -> {
                        batteryTotalDischargeEnergy = intent.getDoubleExtra(MonitorService.BATTERY_MONITOR_DATA_VALUE, 0.0)
                        batteryCharging = false;
                    }
                    BatteryMonitorDataType.BatteryCapacity -> {
                        batteryCapacity = intent.getDoubleExtra(MonitorService.BATTERY_MONITOR_DATA_VALUE, 0.0)
                    }
                    BatteryMonitorDataType.RemainingTimeInMinutes -> {
                        batteryTimeRemaining = intent.getIntExtra(MonitorService.BATTERY_MONITOR_DATA_VALUE, 0)
                    }
                    else -> {
                    }
                }
            }

            else -> {}
        }
    }
    Column(
        Modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Column(Modifier.padding(8.dp)){
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
                        .weight(1f)) {
                    Text("Voltage", style = Typography.labelSmall)
                    Text(
                        text = "${solarVoltage}V",
                        style = Typography.headlineSmall
                    )
                    Text("Current", style = Typography.labelSmall)
                    Text(
                        text = "${"%.2f".format(solarCurrent)}A",
                        style = Typography.headlineSmall
                    )
                }
                Column(
                    Modifier
                        .padding(8.dp)
                        .fillMaxWidth()
                        .weight(1f)) {
                    Text("Energy output", style = Typography.labelSmall)
                    Text(
                        text = "${"%.0f".format(chargeEnergy)}Wh",
                        style = Typography.displaySmall
                    )
                    Text("Peak power", style = Typography.labelSmall)
                    Text(
                        text = "${"%.0f".format(solarPeakPower)}W",
                        style = Typography.bodyLarge
                    )
                }
            }
        }
        HorizontalDivider()
        Column(Modifier.padding(8.dp)){
            Text(
                text = "Charger",
                style = Typography.headlineSmall,
                fontWeight = FontWeight.Light
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.padding(8.dp).fillMaxWidth()
                    .weight(1f)) {
                    Text("Voltage", style = Typography.labelSmall)
                    Text(
                        text = "${"%.2f".format(chargeVoltage)}A",
                        style = Typography.headlineSmall
                    )
                    Text("Current", style = Typography.labelSmall)
                    Text(
                        text = "${"%.2f".format(chargeCurrent)}A",
                        style = Typography.headlineSmall
                    )
                }
                Column(Modifier.padding(8.dp).fillMaxWidth()
                    .weight(1f)) {
                    Text("Power", style = Typography.labelSmall)
                    Text(
                        text = "${"%.0f".format(chargePower)}W",
                        style = Typography.displaySmall
                    )
                    Text("Temperature", style = Typography.labelSmall)
                    Text(
                        text = "${chargerTemp}°C | ${"%.0f".format(chargerTemp.toFloat()*9.0/5.0+32)}°F",
                        style = Typography.bodyLarge
                    )
                }
            }
        }
        HorizontalDivider()
        Column(Modifier.padding(8.dp)){
            Text(
                text = "Inverter",
                style = Typography.headlineSmall,
                fontWeight = FontWeight.Light
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.padding(8.dp).fillMaxWidth()
                    .weight(1f)) {
                    Text("Current", style = Typography.labelSmall)
                    Text(
                        text = "${"%.2f".format(inverterCurrent)}A",
                        style = Typography.headlineSmall
                    )
                }
                Column(Modifier.padding(8.dp).fillMaxWidth()
                    .weight(1f)) {
                    Text("Power draw", style = Typography.labelSmall)
                    Text(
                        text = "${"%.0f".format(inverterPower)}W",
                        style = Typography.headlineSmall
                    )
                }
                Column(Modifier.padding(8.dp).fillMaxWidth()
                    .weight(2f)) {
                    Text("Energy usage", style = Typography.labelSmall)
                    Text(
                        text = "${"%.0f".format(inverterEnergy)}Wh",
                        style = Typography.displaySmall
                    )
                }
            }
        }
        HorizontalDivider()
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically){
                Text(
                    text = "Battery",
                    style = Typography.headlineSmall,
                    fontWeight = FontWeight.Light
                )
                Text(if (batteryCharging || chargeCurrent > batteryCurrent) "Charging" else "Discarging",Modifier.padding(8.dp), style = Typography.labelSmall)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.padding(8.dp).fillMaxWidth()
                    .weight(1f)) {
                    Text("Voltage", style = Typography.labelSmall)
                    Text(
                        text = "${"%.2f".format(batteryVoltage)}V",
                        style = Typography.headlineSmall
                    )
                }
                Column(Modifier.padding(8.dp).fillMaxWidth()
                    .weight(1f)) {
                    Text("Current", style = Typography.labelSmall)
                    Text(
                        text = "${"%.2f".format(batteryCurrent)}A",
                        style = Typography.headlineSmall
                    )
                }
                Column(Modifier.padding(8.dp).fillMaxWidth()
                    .weight(1f)) {
                    Text("Power", style = Typography.labelSmall)
                    Text(
                        text = "${"%.0f".format(batteryCurrent * batteryVoltage)}W",
                        style = Typography.displaySmall
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp).fillMaxWidth()
                    .weight(1f)) {
                    Text("Remaining capacity", style = Typography.labelSmall)
                    Text(
                        text = "${"%.3f".format(batteryRemainingAh)}Ah",
                        style = Typography.displaySmall
                    )
                    Text("Total capacity", style = Typography.labelSmall)
                    Text(
                        text = "${"%.0f".format(batteryCapacity)}Ah",
                        style = Typography.bodyMedium
                    )
                }
                Column(Modifier.padding(8.dp).fillMaxWidth()
                    .weight(1f)) {
                    Text("Remaining time", style = Typography.labelSmall)
                    val hours = batteryTimeRemaining / 60
                    val minutes = batteryTimeRemaining % 60
                    Text(
                        text = "${hours}h ${minutes}min",
                        style = Typography.displaySmall
                    )
                    Text("Battery percent", style = Typography.labelSmall)
                    Text(
                        text = "${"%.2f".format(batteryPercentage)}%",
                        style = Typography.bodyMedium
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp).fillMaxWidth()
                    .weight(1f)) {
                    Text("Total charge energy", style = Typography.labelSmall)
                    Text(
                        text = "${"%.2f".format(batteryTotalChargeEnergy)}Wh",
                        style = Typography.headlineSmall
                    )
                }
                Column(Modifier.padding(8.dp).fillMaxWidth()
                    .weight(1f)) {
                    Text("Total discharge energy", style = Typography.labelSmall)
                    Text(
                        text = "${"%.2f".format(batteryTotalDischargeEnergy)}Wh",
                        style = Typography.headlineSmall
                    )
                }
            }
        }
        Row{
            Button(onClick = { nav.navigate("devices") }) {
                Text("Find other devices")
            }
            Button(onClick = { activity.monitorService?.sync() }) {
                Text("Force reload")
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