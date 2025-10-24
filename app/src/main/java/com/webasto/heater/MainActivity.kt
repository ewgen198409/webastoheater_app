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
import android.widget.ImageView
import android.view.LayoutInflater
import android.widget.ProgressBar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : AppCompatActivity(), BluetoothDataListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var viewPagerAdapter: ViewPagerAdapter
    private val currentData = mutableMapOf<String, Any>()
    private lateinit var sharedPreferences: SharedPreferences

    // Переменные для сервиса
    private var webastoService: WebastoService? = null
    private var isBound = false

    // Для обработки сканирования WiFi
    private var isScanningWifi = false
    private val wifiScanBuffer = mutableListOf<String>()

    // Новые поля для обновления прошивки
    private lateinit var firmwareDownloader: FirmwareDownloader
    private lateinit var firmwareUpdater: FirmwareUpdater
    private var originalBluetoothDataListener: BluetoothDataListener? = null // Для сохранения оригинального слушателя
    private var currentUpdateDialog: AlertDialog? = null // Для доступа к диалогу из ActivityResultLauncher

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

    private fun applyThemeOnCreate() {
        // Применяем тему только при первом создании
        val isDarkTheme = sharedPreferences.getBoolean("dark_theme", false)
        if (isDarkTheme) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
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
        initializeFirmwareComponents() // Инициализация компонентов прошивки
    }

    // Метод для получения текущего менеджера из фрагментов
    fun getCurrentManager(): BluetoothManager? {
        return bluetoothManager
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        Log.d("MainActivity", "onSaveInstanceState called")
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
        originalBluetoothDataListener = this // Сохраняем оригинальный слушатель
        bluetoothManager.setDataListener(this) // Устанавливаем основной слушатель
    }

    private fun initializeFirmwareComponents() {
        firmwareDownloader = FirmwareDownloader(this)
        firmwareUpdater = FirmwareUpdater(this, bluetoothManager, firmwareDownloader)
    }

    private fun initializeUI() {
        updateConnectionStatus("Отключено")

        // Обработчик клика на кнопку подключения Bluetooth
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

        // Обработчик клика на логотип для проверки обновлений
        val logoImageView = findViewById<ImageView>(R.id.webasto_logo)
        logoImageView.setOnClickListener {
            showUpdateDialog()
        }
    }

    // ActivityResultLauncher для выбора файла прошивки
    private val selectFirmwareFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            // Запускаем процесс обновления с локальным файлом
            startFirmwareUpdateProcess(it, currentData["firmware_version"] as? String, currentUpdateDialog)
        } ?: run {
            Toast.makeText(this, "Файл прошивки не выбран", Toast.LENGTH_SHORT).show()
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

        // Важно: здесь мы устанавливаем основной слушатель MainActivity
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

        val webastoIntent = Intent(this, WebastoService::class.java)
        stopService(webastoIntent)


    }

    // Реализация методов BluetoothDataListener
    override fun onDataUpdated(data: Map<String, Any>) {
        runOnUiThread {
            viewPagerAdapter.updateAllFragments(currentData.toMap()) // Pass a copy of currentData
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

        message.split('\n').forEach { line ->
            if (line.isBlank()) return@forEach // Пропускаем пустые строки

            when {
                line == "WIFI_SCAN_START" -> {
                    isScanningWifi = true
                    wifiScanBuffer.clear()
                    Log.d("BluetoothDebug", "WIFI_SCAN_START detected. Starting buffer.")
                }
                line == "WIFI_SCAN_END" -> {
                    isScanningWifi = false
                    currentData["WIFI_SCAN_RAW_RESULTS"] = wifiScanBuffer.joinToString(System.lineSeparator())
                    Log.d("BluetoothDebug", "WIFI_SCAN_END detected. Buffered results: ${currentData["WIFI_SCAN_RAW_RESULTS"]}")
                    runOnUiThread { viewPagerAdapter.updateAllFragments(currentData.toMap()) }
                    wifiScanBuffer.clear()
                }
                isScanningWifi -> {
                    wifiScanBuffer.add(line)
                    Log.d("BluetoothDebug", "Buffering WiFi scan line: $line")
                }
                line.startsWith("CURRENT_SETTINGS:") -> {
                    parseSettings(line.substring(17))
                }
                line.startsWith("SETTINGS_OK") -> {
                    Log.d("BluetoothDebug", "Device confirmed settings update")
                    runOnUiThread {
                        Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
                        viewPagerAdapter.updateAllFragments(emptyMap()) 
                    }
                }
                line.startsWith("SETTINGS_ERROR") -> {
                    Log.d("BluetoothDebug", "Device reported settings error")
                    runOnUiThread {
                        Toast.makeText(this, "Ошибка сохранения настроек", Toast.LENGTH_SHORT).show()
                    }
                }
                line.startsWith("FIRMWARE_VERSION:") -> {
                    val firmwareVersion = line.substringAfter("FIRMWARE_VERSION:").trim()
                    currentData["firmware_version"] = firmwareVersion
                    Log.d("BluetoothDebug", "Firmware version received: $firmwareVersion")
                    // Обновляем UI, если диалог обновления открыт
                    runOnUiThread { viewPagerAdapter.updateAllFragments(currentData.toMap()) }
                }
                line.contains("может быть изменена только через WebSocket") -> {
                    Log.d("BluetoothDebug", "Device WebSocket warning (ignored)")
                }
                line.startsWith("DEBUG:") -> {
                    Log.d("BluetoothDebug", "Device debug: $line")
                }
                line.startsWith("WIFI_STATUS:") -> {
                    currentData["WIFI_STATUS"] = line.substringAfter("WIFI_STATUS:")
                    // Парсим версию прошивки из WIFI_STATUS (fw=...)
                    val wifiData = line.substringAfter("WIFI_STATUS:")
                    val fwRegex = Regex("fw=([0-9]+(?:\\.[0-9]+)*)")
                    fwRegex.find(wifiData)?.groups?.get(1)?.value?.let { firmwareVersion ->
                        currentData["firmware_version"] = firmwareVersion
                        Log.d("BluetoothDebug", "Firmware version parsed from WIFI_STATUS: $firmwareVersion")
                    }
                    runOnUiThread { viewPagerAdapter.updateAllFragments(currentData.toMap()) }
                }
                else -> {
                    parseSerialData(line)
                }
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
            "message" to Regex("""I:\s*([^|]+)"""),
            "final_fuel" to Regex("""FinalFuel:\s*(\S+)"""),
            "burn" to Regex("""Burn:\s*(\S+)"""),
            "consumed_liters" to Regex("""TFC: (\S+)"""),
            "fuel_hour" to Regex("""FCH: (\S+)"""),
            "in_temp" to Regex("""InTemp: (\S+)"""),
            "current_state" to Regex("""St: (\S+)""")
            
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

    private fun showUpdateDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_update, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        // Элементы UI для обновления приложения
        val currentAppVersionText = dialogView.findViewById<TextView>(R.id.current_version_text)
        val appUpdateStatusText = dialogView.findViewById<TextView>(R.id.update_status_text)
        val appProgressBar = dialogView.findViewById<ProgressBar>(R.id.update_progress)
        val appDownloadButton = dialogView.findViewById<Button>(R.id.download_button)

        // Элементы UI для обновления прошивки
        val currentFirmwareVersionText = dialogView.findViewById<TextView>(R.id.current_firmware_version_text)
        val firmwareUpdateStatusText = dialogView.findViewById<TextView>(R.id.firmware_update_status_text)
        val firmwareProgressBar = dialogView.findViewById<ProgressBar>(R.id.firmware_update_progress)
        val firmwareProgressText = dialogView.findViewById<TextView>(R.id.firmware_update_progress_text)
        val downloadFirmwareButton = dialogView.findViewById<Button>(R.id.download_firmware_button)
        val selectLocalFirmwareButton = dialogView.findViewById<Button>(R.id.select_local_firmware_button)

        // Сохраняем ссылку на диалог
        currentUpdateDialog = dialog

        // Получаем текущую версию приложения
        val currentAppVersion = packageManager.getPackageInfo(packageName, 0).versionName
        currentAppVersionText.text = "Текущая версия: v$currentAppVersion"

        // Получаем текущую версию прошивки (если доступна)
        val currentFirmwareVersion = currentData["firmware_version"] as? String
        currentFirmwareVersionText.text = "Текущая прошивка: ${currentFirmwareVersion ?: "N/A"}"

        val updateChecker = UpdateChecker(this)

        // --- Логика для обновления приложения ---
        val appUpdateCheckListener = object : UpdateChecker.UpdateCheckListener {
            override fun onUpdateCheckStarted() {
                runOnUiThread {
                    appUpdateStatusText.text = "Проверка обновлений приложения..."
                    appProgressBar.visibility = View.VISIBLE
                    appDownloadButton.isEnabled = false
                }
            }

            override fun onUpdateCheckCompleted(updateInfo: UpdateChecker.UpdateInfo) {
                runOnUiThread {
                    appProgressBar.visibility = View.GONE

                    if (updateInfo.hasUpdate) {
                        val latestVersion = updateInfo.latestVersion ?: "Неизвестно"
                        val formattedDate = updateChecker.formatReleaseDate(updateInfo.publishedAt)

                        val statusMessage = buildString {
                            append("Доступно обновление v$latestVersion")
                            if (formattedDate.isNotEmpty()) {
                                append("\nОпубликовано: $formattedDate")
                            }
                            if (!updateInfo.releaseNotes.isNullOrEmpty()) {
                                append("\n\n${updateInfo.releaseNotes}")
                            }
                        }

                        appUpdateStatusText.text = statusMessage
                        appDownloadButton.isEnabled = true
                        appDownloadButton.setOnClickListener {
                            updateInfo.downloadUrl?.let { downloadUrl ->
                                val downloadManager = DownloadManager(this@MainActivity)
                                downloadManager.downloadApk(downloadUrl, latestVersion) { success, _ ->
                                    if (success) {
                                        dialog.dismiss()
                                    }
                                }
                            } ?: run {
                                Toast.makeText(this@MainActivity,
                                    "Ссылка на скачивание приложения недоступна",
                                    Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        appUpdateStatusText.text = "У вас установлена последняя версия приложения"
                        appDownloadButton.isEnabled = false
                    }
                }
            }

            override fun onUpdateCheckError(error: String) {
                runOnUiThread {
                    appProgressBar.visibility = View.GONE
                    appUpdateStatusText.text = "Ошибка проверки обновлений приложения: $error"
                    appDownloadButton.isEnabled = false
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                }
            }
        }

        // Запускаем проверку обновлений приложения
        updateChecker.checkForUpdates(appUpdateCheckListener)

        // --- Логика для обновления прошивки ---
        val firmwareUpdateCheckListener = object : UpdateChecker.FirmwareUpdateCheckListener {
            override fun onFirmwareUpdateCheckStarted() {
                runOnUiThread {
                    firmwareUpdateStatusText.text = "Проверка обновлений прошивки..."
                    firmwareProgressBar.visibility = View.VISIBLE
                    firmwareProgressText.visibility = View.VISIBLE
                    downloadFirmwareButton.isEnabled = false
                }
            }

            override fun onFirmwareUpdateCheckCompleted(firmwareUpdateInfo: UpdateChecker.FirmwareUpdateInfo) {
                runOnUiThread {
                    firmwareProgressBar.visibility = View.GONE
                    firmwareProgressText.visibility = View.GONE

                    if (firmwareUpdateInfo.hasUpdate) {
                        val latestVersion = firmwareUpdateInfo.latestVersion ?: "Неизвестно"
                        val formattedDate = updateChecker.formatReleaseDate(firmwareUpdateInfo.publishedAt)

                        val statusMessage = buildString {
                            append("Доступно обновление прошивки v$latestVersion")
                            if (formattedDate.isNotEmpty()) {
                                append("\nОпубликовано: $formattedDate")
                            }
                            if (!firmwareUpdateInfo.releaseNotes.isNullOrEmpty()) {
                                append("\n\n${firmwareUpdateInfo.releaseNotes}")
                            }
                        }

                        firmwareUpdateStatusText.text = statusMessage
                        downloadFirmwareButton.isEnabled = true
                        downloadFirmwareButton.setOnClickListener {
                            firmwareUpdateInfo.downloadUrl?.let { downloadUrl ->
                                startFirmwareUpdateProcess(downloadUrl, currentFirmwareVersion, dialog)
                            } ?: run {
                                Toast.makeText(this@MainActivity,
                                    "Ссылка на скачивание прошивки недоступна",
                                    Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        firmwareUpdateStatusText.text = "У вас установлена последняя версия прошивки"
                        downloadFirmwareButton.isEnabled = false
                    }
                }
            }

            override fun onFirmwareUpdateCheckError(error: String) {
                runOnUiThread {
                    firmwareProgressBar.visibility = View.GONE
                    firmwareProgressText.visibility = View.GONE
                    firmwareUpdateStatusText.text = "Ошибка проверки обновлений прошивки: $error"
                    downloadFirmwareButton.isEnabled = false
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                }
            }
        }


        // Обработчик для кнопки "Выбрать локальный файл прошивки"
        selectLocalFirmwareButton.setOnClickListener {
            selectFirmwareFileLauncher.launch("*/*") // Открываем диалог выбора файла
        }

        // Запускаем проверку прошивки при открытии диалога
        updateChecker.checkForFirmwareUpdates(currentFirmwareVersion, firmwareUpdateCheckListener)

        dialog.show()
    }

    private fun startFirmwareUpdateProcess(firmwareSource: Any, currentFirmwareVersion: String?, dialog: AlertDialog?) {
        // Сохраняем текущий слушатель BluetoothManager
        originalBluetoothDataListener = bluetoothManager.getDataListener()

        // Устанавливаем слушатель FirmwareUpdater
        firmwareUpdater.setUpdateListener(object : FirmwareUpdater.FirmwareUpdateListener {
            override fun onUpdateStarted() {
                runOnUiThread {
                    dialog?.findViewById<TextView>(R.id.firmware_update_status_text)?.text = "Начало обновления прошивки..."
                    dialog?.findViewById<ProgressBar>(R.id.firmware_update_progress)?.apply {
                        visibility = View.VISIBLE
                        progress = 0
                    }
                    dialog?.findViewById<TextView>(R.id.firmware_update_progress_text)?.apply {
                        visibility = View.VISIBLE
                        text = "0%"
                    }
                    dialog?.findViewById<Button>(R.id.download_firmware_button)?.isEnabled = false
                    dialog?.findViewById<Button>(R.id.select_local_firmware_button)?.isEnabled = false // Отключаем кнопку выбора локального файла
                    dialog?.setCancelable(false) // Запрещаем закрытие диалога во время обновления
                }
            }

            override fun onDownloadProgress(progress: Int) {
                runOnUiThread {
                    dialog?.findViewById<ProgressBar>(R.id.firmware_update_progress)?.progress = progress
                    dialog?.findViewById<TextView>(R.id.firmware_update_progress_text)?.text = "Загрузка: $progress%"
                }
            }

            override fun funOnUploadProgress(progress: Int) {
                runOnUiThread {
                    // Этот метод может быть использован для более детального прогресса загрузки на устройство
                    // Например, можно обновить текст или использовать другой прогресс-бар
                }
            }

            override fun onUpdateProgress(message: String, progress: Int) {
                runOnUiThread {
                    dialog?.findViewById<TextView>(R.id.firmware_update_status_text)?.text = message
                    dialog?.findViewById<ProgressBar>(R.id.firmware_update_progress)?.progress = progress
                    dialog?.findViewById<TextView>(R.id.firmware_update_progress_text)?.text = "$progress%"
                }
            }

            override fun onUpdateCompleted() {
                runOnUiThread {
                    dialog?.findViewById<TextView>(R.id.firmware_update_status_text)?.text = "Обновление прошивки завершено!"
                    dialog?.findViewById<ProgressBar>(R.id.firmware_update_progress)?.progress = 100
                    dialog?.findViewById<TextView>(R.id.firmware_update_progress_text)?.text = "100%"
                    Toast.makeText(this@MainActivity, "Прошивка успешно обновлена!", Toast.LENGTH_LONG).show()
                    dialog?.setCancelable(true)
                    dialog?.dismiss() // Закрываем диалог после успешного обновления
                    // Восстанавливаем оригинальный слушатель BluetoothManager
                    originalBluetoothDataListener?.let { bluetoothManager.setDataListener(it) }
                }
            }

            override fun onUpdateError(error: String) {
                runOnUiThread {
                    dialog?.findViewById<TextView>(R.id.firmware_update_status_text)?.text = "Ошибка: $error"
                    dialog?.findViewById<ProgressBar>(R.id.firmware_update_progress)?.progress = 0
                    dialog?.findViewById<TextView>(R.id.firmware_update_progress_text)?.text = "Ошибка"
                    Toast.makeText(this@MainActivity, "Ошибка обновления прошивки: $error", Toast.LENGTH_LONG).show()
                    dialog?.setCancelable(true)
                    dialog?.findViewById<Button>(R.id.download_firmware_button)?.isEnabled = true
                    dialog?.findViewById<Button>(R.id.select_local_firmware_button)?.isEnabled = true // Включаем кнопку выбора локального файла
                    // Восстанавливаем оригинальный слушатель BluetoothManager
                    originalBluetoothDataListener?.let { bluetoothManager.setDataListener(it) }
                }
            }
        })

        // Запускаем обновление прошивки в корутине
        CoroutineScope(Dispatchers.Main).launch {
            when (firmwareSource) {
                is String -> firmwareUpdater.startFirmwareUpdate(firmwareSource, currentFirmwareVersion, null)
                is Uri -> firmwareUpdater.startFirmwareUpdate(null, currentFirmwareVersion, firmwareSource)
                else -> Toast.makeText(this@MainActivity, "Неизвестный источник прошивки", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
