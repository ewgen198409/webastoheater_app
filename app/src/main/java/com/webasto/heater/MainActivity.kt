package com.webasto.heater

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.webasto.heater.databinding.ActivityMainBinding
import com.google.android.material.tabs.TabLayoutMediator
import org.json.JSONObject
import android.widget.Button
import android.widget.TextView

class MainActivity : AppCompatActivity(), BluetoothDataListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var viewPagerAdapter: ViewPagerAdapter
    private val currentData = mutableMapOf<String, Any>()
    private lateinit var sharedPreferences: SharedPreferences

    // Переменные для сервиса
    private var webastoService: WebastoService? = null
    private var isBound = false

    // Соединение с сервисом
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as WebastoService.WebastoBinder
            webastoService = binder.getService()
            isBound = true
            Log.d("Service", "WebastoService connected")
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            webastoService = null
            Log.d("Service", "WebastoService disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Загружаем настройки темы ДО setContentView
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        applyThemeOnCreate()

        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Получаем версию приложения и устанавливаем в TextView
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        findViewById<TextView>(R.id.app_version).text = "v$versionName"

        Log.d("MainActivity", "Activity created - isChangingConfigurations: $isChangingConfigurations")

        initializeUI()
        setupTabs()
        initializeBluetooth()
    }

    private fun applyThemeOnCreate() {
        // Применяем тему только при первом создании
        val isDarkTheme = sharedPreferences.getBoolean("dark_theme", false)
        if (isDarkTheme) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Обрабатываем изменение конфигурации без пересоздания активности
        Log.d("MainActivity", "Configuration changed but activity not recreated")
    }

    private fun initializeBluetooth() {
        bluetoothManager = BluetoothManager(this)
        if (!bluetoothManager.initialize()) {
            Toast.makeText(this, "Bluetooth не поддерживается", Toast.LENGTH_SHORT).show()
        }
    }

    private fun initializeUI() {
        updateConnectionStatus("Отключено")
        binding.connectButton.setOnClickListener {
            if (bluetoothManager.isConnected()) {
                bluetoothManager.disconnect()
                updateConnectionStatus("Отключено")
                updateConnectButton(false)
                binding.connectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            } else {
                connectViaBluetooth()
            }
        }
    }

    private fun connectViaBluetooth() {
        if (!bluetoothManager.isBluetoothEnabled()) {
            Toast.makeText(this, "Включите Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }

        checkBluetoothPermissions()
    }

    private fun checkBluetoothPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest, 1)
        } else {
            showBluetoothDeviceDialog()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            showBluetoothDeviceDialog()
        } else {
            Toast.makeText(this, "Для работы Bluetooth нужны разрешения", Toast.LENGTH_LONG).show()
        }
    }

    private fun showBluetoothDeviceDialog() {
        val pairedDevices = bluetoothManager.getPairedDevices()
        if (pairedDevices.isEmpty()) {
            Toast.makeText(this, "Нет сопряженных устройств", Toast.LENGTH_SHORT).show()
            return
        }
    
        // Проверка разрешения BLUETOOTH_CONNECT
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            val deviceNames = pairedDevices.map { it.name ?: "Unknown Device" }.toTypedArray()
        
            AlertDialog.Builder(this)
                .setTitle("Выберите Bluetooth устройство")
                .setItems(deviceNames) { _, which ->
                    val selectedDevice = pairedDevices.elementAt(which)
                    connectToBluetoothDevice(selectedDevice)
                }
                .setNegativeButton("Отмена", null)
                .show()
        } else {
            Toast.makeText(this, "Нет разрешения на доступ к Bluetooth-устройствам", Toast.LENGTH_LONG).show()
            // Можно запросить разрешение или просто уведомить пользователя
        }
    }

    private fun connectToBluetoothDevice(device: BluetoothDevice) {
        if (bluetoothManager.isConnected()) {
            bluetoothManager.disconnect()
        }

        bluetoothManager.setDataListener(this)

        // Запускаем в отдельном потоке, чтобы не блокировать UI
        Thread {
            val success = bluetoothManager.connectToDevice(device)
            runOnUiThread {
                if (success) {
                    // Запускаем сервис для работы в фоне
                    startWebastoService()

                    updateConnectionStatus("Подключено Bluetooth (${bluetoothManager.getConnectedDeviceName()})")
                    binding.connectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                } else {
                    updateConnectionStatus("Ошибка подключения Bluetooth")
                    binding.connectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                }
                binding.connectButton.isEnabled = true
            }
        }.start()
    }

    private fun updateConnectionStatus(status: String) {
        binding.connectionStatus.text = status
    }

    private fun setupTabs() {
        viewPagerAdapter = ViewPagerAdapter(this)
        binding.viewPager.adapter = viewPagerAdapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Управление"
                1 -> "Настройки"
                2 -> "Топливо"
                else -> "Таб"
            }
        }.attach()
    }

    // Методы для работы с сервисом
    private fun startWebastoService() {
        val intent = Intent(this, WebastoService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopWebastoService() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }

        val intent = Intent(this, WebastoService::class.java)
        stopService(intent)
    }

    // Реализация методов BluetoothDataListener
    override fun onDataUpdated(data: Map<String, Any>) {
        runOnUiThread {
            viewPagerAdapter.updateAllFragments(data)
        }
    }

    override fun onConnectionStatusChanged(connected: Boolean) {
        runOnUiThread {
            if (connected) {
                val deviceInfo = " (${bluetoothManager.getConnectedDeviceName()})"
                binding.connectionStatus.text = "Подключено Bluetooth$deviceInfo"
                binding.connectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            } else {
                binding.connectionStatus.text = "Отключено"
                binding.connectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            }
            binding.connectButton.isEnabled = true
            updateConnectButton(connected)
        }
    }



    override fun onError(error: String) {
        runOnUiThread {
            Toast.makeText(this, "Ошибка: $error", Toast.LENGTH_LONG).show()
            binding.connectionStatus.text = "Ошибка подключения"
            binding.connectButton.isEnabled = true
        }
    }

    override fun onMessageReceived(message: String) {
        Log.d("BluetoothDebug", "Raw message: $message")

        when {
            message.startsWith("CURRENT_SETTINGS:") -> {
                parseSettings(message.substring(17))
            }
            message.startsWith("SETTINGS_OK") -> {
                Log.d("BluetoothDebug", "Device confirmed settings update")
                runOnUiThread {
                    Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
                    // Можно обновить статус в SettingsFragment через адаптер
                    viewPagerAdapter.updateAllFragments(emptyMap()) // или передать специальный флаг
                }
            }
            message.startsWith("SETTINGS_ERROR") -> {
                Log.d("BluetoothDebug", "Device reported settings error")
                runOnUiThread {
                    Toast.makeText(this, "Ошибка сохранения настроек", Toast.LENGTH_SHORT).show()
                }
            }
            message.contains("может быть изменена только через WebSocket") -> {
                // Это сообщение от устройства - игнорируем его, так как команды все равно работают
                Log.d("BluetoothDebug", "Device WebSocket warning (ignored)")
            }
            message.startsWith("DEBUG:") -> {
                // Игнорируем debug сообщения
                Log.d("BluetoothDebug", "Device debug: $message")
            }
            else -> {
                // Парсим обычные данные Webasto
                parseSerialData(message)
            }
        }
    }

    private fun parseSerialData(message: String) {
        Log.d("BluetoothDebug", "Parsing serial data: $message")

        // Парсинг данных в формате Serial (полностью как в webasto.py)
        val patterns = mapOf(
            "webasto_fail" to Regex("""F:\s*(\S+)"""),
            "ignit_fail" to Regex("""IgnF#:\s*(\S+)"""),
            "exhaust_temp" to Regex("""ETmp:\s*(\S+)"""),
            "fan_speed" to Regex("""Fan%:\s*(\S+)"""),
            "fuel_hz" to Regex("""FHZ\s*(\S+)"""),
            "fuel_need" to Regex("""FN:\s*(\S+)"""),
            "glow_left" to Regex("""Gl:\s*(\S+)"""),
            "cycle_time" to Regex("""CyTim:\s*(\S+)"""),
            "message" to Regex("""I:\s*(\S+)"""),
            "final_fuel" to Regex("""FinalFuel:\s*(\S+\s+\S+)"""),
            "burn" to Regex("""Burn:\s*(\S+)""")
        )

        var dataFound = false

        patterns.forEach { (key, pattern) ->
            pattern.find(message)?.groups?.get(1)?.value?.let { value ->
                if (key == "cycle_time") {
                    // Преобразуем секунды в минуты и добавляем в скобках
                    val seconds = value.toFloatOrNull()
                    if (seconds != null) {
                        val minutes = seconds / 60f
                        val formatted = String.format("%s (%.0f мин)", value, minutes)
                        currentData[key] = formatted
                        Log.d("BluetoothDebug", "Serial data: $key = $formatted")
                    } else {
                        currentData[key] = value
                        Log.d("BluetoothDebug", "Serial data: $key = $value")
                    }
                } else {
                    currentData[key] = value
                    Log.d("BluetoothDebug", "Serial data: $key = $value")
                }
                dataFound = true
            }
        }

        // Дополнительный парсинг для сообщений без префикса I:
        if (!currentData.containsKey("message") && message.contains("|")) {
            val messageParts = message.split("|")
            if (messageParts.isNotEmpty()) {
                val cleanMessage = messageParts[0].trim()
                if (cleanMessage.isNotEmpty()) {
                    currentData["message"] = cleanMessage
                    Log.d("BluetoothDebug", "Message without prefix: $cleanMessage")
                    dataFound = true
                }
            }
        }
        
        
        // Обработка состояния (St:) как в webasto.py
        if (message.contains("St:")) {
            try {
                val statePart = message.split("St: ")[1].split(" ")[0]
                val stateMapping = mapOf("0" to "FULL", "1" to "MID", "2" to "LOW")
                val displayState = stateMapping[statePart] ?: statePart
                currentData["current_state"] = displayState

                val isHeaterOn = displayState != "N/A" && displayState.isNotEmpty() && displayState != "unavailable"
                currentData["heater_on"] = isHeaterOn

                Log.d("BluetoothDebug", "State: $displayState, Heater ON: $isHeaterOn")
                dataFound = true
            } catch (e: Exception) {
                Log.e("BluetoothDebug", "Error parsing state: ${e.message}")
            }
        }

        // Обновляем только ControlFragment и FuelFragment, но НЕ SettingsFragment
        if (dataFound) {
            Log.d("BluetoothDebug", "All parsed data: $currentData")
            runOnUiThread {
                // Обновляем все фрагменты кроме SettingsFragment
                viewPagerAdapter.updateAllFragments(currentData.toMap())
            }
        }
    }

    private fun updateConnectButton(isConnected: Boolean) {
        val button = findViewById<Button>(R.id.connect_button)
        if (isConnected) {
            button.text = "Отключить Bluetooth"
            button.setBackgroundResource(R.drawable.button_3d_effect_on)

        } else {
            button.text = "Подключить Bluetooth"
            button.setBackgroundResource(R.drawable.button_3d_effect_off)
          }
    }

    private fun parseSettings(data: String) {
        Log.d("BluetoothDebug", "Parsing settings: $data")
        try {
            val params = data.split(',')
            for (param in params) {
                if (param.contains('=')) {
                    val keyValue = param.split('=', limit = 2)
                    val key = keyValue[0].trim()
                    val value = keyValue[1].trim()

                    try {
                        currentData[key] = if (value.contains('.')) {
                            value.toFloat()
                        } else {
                            value.toInt()
                        }
                    } catch (e: NumberFormatException) {
                        currentData[key] = value
                    }
                    Log.d("BluetoothDebug", "Setting: $key = ${currentData[key]}")
                }
            }

            // Обновляем UI после загрузки настроек - только SettingsFragment получает настройки
            runOnUiThread {
                viewPagerAdapter.loadSettingsToFragment(currentData.toMap())
                Toast.makeText(this, "Настройки загружены", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("BluetoothDebug", "Error parsing settings: ${e.message}")
        }
    }

    // Метод для получения текущего менеджера из фрагментов
    fun getCurrentManager(): Any? {
        return bluetoothManager
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        Log.d("MainActivity", "onSaveInstanceState called")
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        Log.d("MainActivity", "onRestoreInstanceState called")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("MainActivity", "Activity destroyed - isChangingConfigurations: $isChangingConfigurations")

        if (!isChangingConfigurations) {
            // Реальное закрытие приложения
            stopWebastoService()
            bluetoothManager.disconnect()
            Log.d("MainActivity", "Real destroy - services stopped")
        } else {
            // Смена конфигурации - сохраняем соединение
            Log.d("MainActivity", "Config change - services preserved")
        }
    }
}