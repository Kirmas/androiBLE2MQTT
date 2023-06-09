@file:Suppress("DEPRECATION")
package com.example.androidbluetoothtomqtt

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.androidbluetoothtomqtt.ui.theme.AndroidBluetoothToMqttTheme
import com.vmadalin.easypermissions.EasyPermissions
import com.vmadalin.easypermissions.annotations.AfterPermissionGranted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File

@SuppressLint("MutableCollectionMutableState")
class MainActivity : ComponentActivity() {
    enum class MenuItems {
        Settings,
        Devices
    }

    private val TAG = "MainActivity"
    private val CONFIG_FILE_NAME = "config.yaml"
    private val DEVICE_FILE_NAME = "device.yaml"
    private var bConfigLoaded: Boolean = false
    private var mqttServer by mutableStateOf<String>("tcp://localhost:1883")
    private var mqttTopic by mutableStateOf<String>("bluetooth2mqtt")
    private var mqttUser by mutableStateOf<String>("")
    private var mqttPassword by mutableStateOf<CharArray>(CharArray(0) { ' ' })
    private var selectedMenu by mutableStateOf<MenuItems>(MenuItems.Devices)
    private var availableBTDevice by mutableStateOf<ArrayList<BTDeviceInformation>>(arrayListOf())
    private var selectedBTDevice by mutableStateOf<MutableMap<String, String>>(mutableMapOf())
    private lateinit var localBroadcastManager: LocalBroadcastManager
    private lateinit var broadcastReceiver: BroadcastReceiver

    private val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.INTERNET,
            Manifest.permission.FOREGROUND_SERVICE
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.INTERNET,
        )
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AndroidBluetoothToMqttTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Greeting()
                }
            }
        }

        localBroadcastManager = LocalBroadcastManager.getInstance(this)
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if(intent.hasExtra("AvailableBTDevice")) {
                    availableBTDevice = intent.getParcelableArrayListExtra("AvailableBTDevice")!!
                }
            }
        }

        if (BluetoothAdapter.getDefaultAdapter() == null) //якщо блютуз вимкнений треба буде потім попросити його увімкнути на сервіс це не вплине так як по ідеї користувач може дергати бт туди сюди(нафіга це робити на стат утройстві?)
        {
            Log.e(TAG, "Bluetooth is not supported on this device")
            return
        }
        loadDeviceFromYaml()
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        if (EasyPermissions.hasPermissions(this, *permissions)) {
            loadConfigFromYaml()
            startBluetoothToMQTTService()
        } else {
            EasyPermissions.requestPermissions(
                this,
                "Для роботи програми потрібен доступ до блютуз та інтернет",
                PERMISSION_REQUEST_CODE,
                *permissions
            )
        }
    }

    @AfterPermissionGranted(PERMISSION_REQUEST_CODE)
    private fun permissionGranted() {
        loadConfigFromYaml()
        startBluetoothToMQTTService()
    }

    private fun startBluetoothToMQTTService() {
        if(!bConfigLoaded) {
            Log.i(TAG, "Config not loaded")
            return
        }

        if(!isServiceRunning()) {
            startForegroundService(Intent(this, ServiceBluetoothToMQTT::class.java))
            if(selectedMenu == MenuItems.Devices) {
                CoroutineScope(Dispatchers.Main).launch {
                    delay(1000)
                    startDataReceiver()
                }
            }
        } else {
            Log.i(TAG, "Service already running")
        }
    }

    private fun sendDataToService(name: String, value: Boolean) {
        val intent = Intent("com.example.mainActivity")
        intent.putExtra(name, value)
        localBroadcastManager.sendBroadcast(intent)
    }

    private fun startDataReceiver() {
        val intentFilter = IntentFilter("com.example.bluetoothToMQTT")
        localBroadcastManager.registerReceiver(broadcastReceiver, intentFilter)
        sendDataToService("SendData", true)
    }



    private fun stopDataReceiver() {
        sendDataToService("SendData", false)
        localBroadcastManager.unregisterReceiver(broadcastReceiver)
    }

    private fun restartBluetoothToMQTTService() {
        stopService(Intent(this, ServiceBluetoothToMQTT::class.java))
        startBluetoothToMQTTService()
    }

    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val services = manager.getRunningServices(Integer.MAX_VALUE)
        return services.any { it.service.className == ServiceBluetoothToMQTT::class.java.name }
    }

    override fun onDestroy() {
        super.onDestroy()

        if (selectedMenu == MenuItems.Devices) {
            stopDataReceiver()
        }
    }

    private fun loadConfigFromYaml() {
        val configFile = File(filesDir, CONFIG_FILE_NAME)
        if (configFile.exists()) {
            val yaml = Yaml()
            val configData = configFile.readText()
            val configMap = yaml.load<Map<String, String>>(configData)
            mqttServer = configMap["mqtt_server"].orEmpty()
            mqttTopic = configMap["mqtt_topic"].orEmpty()
            mqttUser = configMap["mqtt_user"].orEmpty()
            mqttPassword = configMap["mqtt_password"].orEmpty().toCharArray()
            bConfigLoaded = true
            Log.i(TAG, "Config loaded")
        } else {
            Log.i(TAG, "Config not exists")
        }
    }

    private fun saveConfigToYaml() {
        val configData = mapOf(
            "mqtt_server" to mqttServer,
            "mqtt_topic" to mqttTopic,
            "mqtt_user" to mqttUser,
            "mqtt_password" to String(mqttPassword)
        )
        val dumperOptions = DumperOptions()
        dumperOptions.defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        dumperOptions.isPrettyFlow = true
        val yaml = Yaml(dumperOptions)
        val configFile = File(filesDir, CONFIG_FILE_NAME)
        configFile.writeText(yaml.dump(configData))
        bConfigLoaded = true
        Log.i(TAG, "Config saved")
    }

    private fun loadDeviceFromYaml() {
        val deviceFile = File(filesDir, DEVICE_FILE_NAME)
        if (deviceFile.exists()) {
            val yaml = Yaml()
            val configData = deviceFile.readText()
            selectedBTDevice = yaml.load<MutableMap<String, String>>(configData)
            Log.i(TAG, "Device file loaded")
        } else {
            Log.i(TAG, "Device file not exists")
        }
    }

    private fun saveDeviceToYaml() {
        val dumperOptions = DumperOptions()
        dumperOptions.defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        dumperOptions.isPrettyFlow = true
        val yaml = Yaml(dumperOptions)
        val configFile = File(filesDir, DEVICE_FILE_NAME)
        configFile.writeText(yaml.dump(selectedBTDevice))
        Log.i(TAG, "Device file saved")
    }

    @Composable
    fun Greeting() {
        val menuExpanded = remember { mutableStateOf(false) }
        MaterialTheme {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentSize(Alignment.TopStart)
                    ) {
                    Row {
                        IconButton(
                            onClick = { menuExpanded.value = !menuExpanded.value }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Menu",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = menuExpanded.value,
                        onDismissRequest = { menuExpanded.value = false },
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(text = "Налаштування")
                            },
                            onClick = { handleSettingsMenuItemClick(MenuItems.Settings) })
                        DropdownMenuItem(
                            text = {
                                Text(text = "Устройства")
                            },
                            onClick = { handleSettingsMenuItemClick(MenuItems.Devices) })
                    }
                }

                when (selectedMenu) {
                    MenuItems.Settings -> {
                        ShowSettings()
                    }
                    MenuItems.Devices -> {
                        ShowDevices()
                    }
                }
            }
        }
    }

    @Composable
    @OptIn(ExperimentalMaterial3Api::class)
    private fun ShowSettings() {
        OutlinedTextField(
            value = mqttServer,
            onValueChange = { mqttServer = it },
            label = { Text("MQTT Server") },
        )
        OutlinedTextField(
            value = mqttTopic,
            onValueChange = { mqttTopic = it },
            label = { Text("MQTT Topic") }
        )
        OutlinedTextField(
            value = mqttUser,
            onValueChange = { mqttUser = it },
            label = { Text("MQTT User") }
        )
        OutlinedTextField(
            value = mqttPassword.joinToString(separator = ""),
            onValueChange = { newValue ->
                mqttPassword = newValue.toCharArray()
            },
            label = { Text("MQTT Password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        Button(
            onClick = {
                saveConfigToYaml()
                restartBluetoothToMQTTService()
            }
        ) {
            Text(text = "Save and Restart Service")
        }
    }

    @Composable
    private fun ShowDevices() {

        Column {
            availableBTDevice.forEach { device ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Checkbox(
                        checked = selectedBTDevice.containsKey(device.address),
                        onCheckedChange = { isChecked ->
                            if (isChecked) {
                                selectedBTDevice[device.address] = device.name
                                saveDeviceToYaml()
                            } else {
                                selectedBTDevice.remove(device.address)
                                saveDeviceToYaml()
                            }
                            sendDataToService("SelectedDevice", true)}
                    )
                    Column(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .weight(1f)
                    ) {
                        Text(text = device.name)
                        Text(text = device.address)
                        Text(text = "Distance: ${device.distance}")
                    }
                }
            }
        }
    }


    @Preview(showBackground = true)
    @Composable
    fun GreetingPreview() {
        AndroidBluetoothToMqttTheme {
            Greeting()
        }
    }

    private fun handleSettingsMenuItemClick(menuItem: MenuItems) {
        if (selectedMenu == menuItem) {
            return
        }
        if (selectedMenu == MenuItems.Devices) {
            stopDataReceiver()
        }
        else if (menuItem == MenuItems.Devices) {
            startDataReceiver()
        }

        selectedMenu = menuItem
    }
}
