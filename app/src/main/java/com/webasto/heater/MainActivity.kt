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

class MainActivity : AppCompatActivity(), BluetoothDataListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var viewPagerAdapter: ViewPagerAdapter
    private val currentData = mutableMapOf<String, Any>()
    private lateinit var sharedPreferences: SharedPreferences

    // Внутренний класс для хранения элементов диалога
    private data class DialogElements(
        val firmwareProgressBar: ProgressBar?,
        val firmwareStatusText: TextView?,
        val firmwareDownloadButton: Button?
    )

    // Переменные для сервиса
    private var webastoService: WebastoService? = null
    private var isBound = false

    // Для обработки сканирования WiFi
    private var isScanningWifi = false
    private val wifiScanBuffer = mutableListOf<String>()

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
                line.contains("может быть изменена только через WebSocket") -> {
                    Log.d("BluetoothDebug", "Device WebSocket warning (ignored)")
                }
                line.startsWith("DEBUG:") -> {
                    Log.d("BluetoothDebug", "Device debug: $line")
                }
                line.startsWith("WIFI_STATUS:") -> {
                    val wifiStatus = line.substringAfter("WIFI_STATUS:")
                    currentData["WIFI_STATUS"] = wifiStatus

                    // Парсим версию firmware из строки WIFI_STATUS
                    parseFirmwareVersion(wifiStatus)

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

    private fun parseFirmwareVersion(wifiStatus: String) {
        try {
            // Ищем fw= в строке WIFI_STATUS
            val fwPattern = Regex("""fw=(\S+)""")
            val match = fwPattern.find(wifiStatus)

            if (match != null) {
                val firmwareVersion = match.groupValues[1]
                currentData["firmware_version"] = firmwareVersion
                Log.d("BluetoothDebug", "Parsed firmware version: $firmwareVersion")
            } else {
                Log.d("BluetoothDebug", "No firmware version found in WIFI_STATUS")
            }
        } catch (e: Exception) {
            Log.e("BluetoothDebug", "Error parsing firmware version: ${e.message}")
        }
    }

    private fun showUpdateDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_update, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        val currentVersionText = dialogView.findViewById<TextView>(R.id.current_version_text)
        val updateStatusText = dialogView.findViewById<TextView>(R.id.update_status_text)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.update_progress)
        val downloadButton = dialogView.findViewById<Button>(R.id.download_button)

        // Добавляем элементы для firmware
        val firmwareVersionText = dialogView.findViewById<TextView>(R.id.firmware_version_text)
        val firmwareStatusText = dialogView.findViewById<TextView>(R.id.firmware_status_text)
        val firmwareProgressBar = dialogView.findViewById<ProgressBar>(R.id.firmware_progress)
        val firmwareDownloadButton = dialogView.findViewById<Button>(R.id.firmware_download_button)

        // Получаем текущую версию приложения
        val currentVersion = packageManager.getPackageInfo(packageName, 0).versionName
        currentVersionText.text = "Текущая версия: v$currentVersion"

        // Создаем UpdateChecker для приложения
        val updateChecker = UpdateChecker(this)

        // Создаем FirmwareUpdateChecker для firmware
        val firmwareUpdateChecker = FirmwareUpdateChecker(this)

        // Обработчик проверки обновлений приложения
        val updateCheckListener = object : UpdateChecker.UpdateCheckListener {
            override fun onUpdateCheckStarted() {
                runOnUiThread {
                    updateStatusText.text = "Проверка обновлений приложения..."
                    progressBar.visibility = View.VISIBLE
                    downloadButton.isEnabled = false
                }
            }

            override fun onUpdateCheckCompleted(updateInfo: UpdateChecker.UpdateInfo) {
                runOnUiThread {
                    progressBar.visibility = View.GONE

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

                        updateStatusText.text = statusMessage
                        downloadButton.isEnabled = true
                        downloadButton.setOnClickListener {
                            updateInfo.downloadUrl?.let { downloadUrl ->
                                val downloadManager = DownloadManager(this@MainActivity)
                                downloadManager.downloadApk(downloadUrl, latestVersion) { success, _ ->
                                    if (success) {
                                        dialog.dismiss()
                                    }
                                }
                            } ?: run {
                                Toast.makeText(this@MainActivity,
                                    "Ссылка на скачивание недоступна",
                                    Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        updateStatusText.text = "У вас установлена последняя версия приложения"
                        downloadButton.isEnabled = false
                    }
                }
            }

            override fun onUpdateCheckError(error: String) {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    updateStatusText.text = "Ошибка проверки обновлений приложения: $error"
                    downloadButton.isEnabled = false
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                }
            }
        }

        // Обработчик проверки обновлений firmware
        val firmwareUpdateCheckListener = object : FirmwareUpdateChecker.FirmwareUpdateCheckListener {
            override fun onFirmwareUpdateCheckStarted() {
                runOnUiThread {
                    firmwareStatusText.text = "Проверка обновлений firmware..."
                    firmwareProgressBar.visibility = View.VISIBLE
                    firmwareDownloadButton.isEnabled = false
                }
            }

            override fun onFirmwareUpdateCheckCompleted(updateInfo: FirmwareUpdateChecker.FirmwareUpdateInfo) {
                runOnUiThread {
                    firmwareProgressBar.visibility = View.GONE

                    if (updateInfo.hasUpdate) {
                        val latestVersion = updateInfo.latestVersion ?: "Неизвестно"
                        val formattedDate = firmwareUpdateChecker.formatReleaseDate(updateInfo.publishedAt)

                        val statusMessage = buildString {
                            append("Доступно обновление firmware v$latestVersion")
                            if (formattedDate.isNotEmpty()) {
                                append("\nОпубликовано: $formattedDate")
                            }
                            if (!updateInfo.releaseNotes.isNullOrEmpty()) {
                                append("\n\n${updateInfo.releaseNotes}")
                            }
                        }

                        firmwareStatusText.text = statusMessage
                        firmwareDownloadButton.isEnabled = true
                        firmwareDownloadButton.setOnClickListener {
                            updateInfo.downloadUrl?.let { downloadUrl ->
                                performOTAUpdate(downloadUrl, dialog)
                            } ?: run {
                                Toast.makeText(this@MainActivity,
                                    "Ссылка на скачивание firmware недоступна",
                                    Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        firmwareStatusText.text = "У вас установлена последняя версия firmware"
                        firmwareDownloadButton.isEnabled = false
                    }
                }
            }

            override fun onFirmwareUpdateCheckError(error: String) {
                runOnUiThread {
                    firmwareProgressBar.visibility = View.GONE
                    firmwareStatusText.text = "Ошибка проверки обновлений firmware: $error"
                    firmwareDownloadButton.isEnabled = false
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                }
            }
        }

        // Проверяем наличие подключения к устройству для firmware обновления
        if (bluetoothManager.isConnected()) {
            // Показываем текущую версию firmware если доступна
            currentData["firmware_version"]?.let { firmwareVersion ->
                firmwareVersionText.text = "Текущая версия firmware: v$firmwareVersion"
                firmwareVersionText.visibility = View.VISIBLE
            } ?: run {
                firmwareVersionText.text = "Текущая версия firmware: неизвестна"
                firmwareVersionText.visibility = View.VISIBLE
            }

            // Запускаем проверку обновлений firmware
            firmwareUpdateChecker.checkForFirmwareUpdates(firmwareUpdateCheckListener)
        } else {
            firmwareVersionText.text = "Текущая версия firmware: устройство не подключено"
            firmwareVersionText.visibility = View.VISIBLE
            firmwareStatusText.text = "Для проверки обновлений firmware подключите устройство"
            firmwareProgressBar.visibility = View.GONE
            firmwareDownloadButton.isEnabled = false
        }

        // Запускаем проверку обновлений приложения
        updateChecker.checkForUpdates(updateCheckListener)

        dialog.show()
    }

    private fun performOTAUpdate(downloadUrl: String, dialog: AlertDialog) {
        val downloadManager = DownloadManager(this)

        // Показываем прогресс обновления
        val firmwareProgressBar = dialog.findViewById<ProgressBar>(R.id.firmware_progress)
        val firmwareStatusText = dialog.findViewById<TextView>(R.id.firmware_status_text)
        val firmwareDownloadButton = dialog.findViewById<Button>(R.id.firmware_download_button)

        if (firmwareProgressBar != null && firmwareStatusText != null && firmwareDownloadButton != null) {
            firmwareProgressBar.visibility = View.VISIBLE
            firmwareStatusText.text = "Скачивание firmware файла..."
            firmwareDownloadButton.isEnabled = false
        }

        // Шаг 1: Скачиваем firmware файл
        downloadManager.downloadFirmware(downloadUrl) { firmwareFile, error ->
            runOnUiThread {
                if (firmwareFile != null) {
                    firmwareStatusText?.text = "Передача firmware на устройство..."

                    // Шаг 2: Передаем файл на устройство через Bluetooth
                    bluetoothManager.sendOTAData(
                        firmwareData = firmwareFile.readBytes(),
                        onProgress = { sent: Int, total: Int ->
                            runOnUiThread {
                                val progress = (sent * 100) / total
                                firmwareStatusText?.text = "Передача: $progress% ($sent/$total байт)"
                                firmwareProgressBar?.progress = progress
                            }
                        },
                        onComplete = { success: Boolean, message: String ->
                            runOnUiThread {
                                if (success) {
                                    firmwareStatusText?.text = "OTA обновление завершено успешно!"
                                    firmwareProgressBar?.progress = 100
                                    Toast.makeText(this@MainActivity, "Firmware обновлен успешно", Toast.LENGTH_LONG).show()

                                    // Закрываем диалог через 2 секунды
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        dialog.dismiss()
                                    }, 2000)
                                } else {
                                    firmwareStatusText?.text = "Ошибка обновления: $message"
                                    firmwareDownloadButton?.isEnabled = true
                                    Toast.makeText(this@MainActivity, "Ошибка обновления: $message", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    )
                } else {
                    firmwareStatusText?.text = "Ошибка скачивания firmware: $error"
                    firmwareDownloadButton?.isEnabled = true
                    Toast.makeText(this@MainActivity, "Ошибка скачивания: $error", Toast.LENGTH_LONG).show()
                }
            }
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
        }
    }
}
