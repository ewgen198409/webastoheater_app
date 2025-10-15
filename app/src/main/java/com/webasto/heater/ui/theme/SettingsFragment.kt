package com.webasto.heater.ui.theme

import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import com.webasto.heater.BluetoothManager
import com.webasto.heater.BluetoothDataListener
import com.webasto.heater.MainActivity
import com.webasto.heater.databinding.FragmentSettingsBinding
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Button

class SettingsFragment : BaseHeaterFragment(), BluetoothDataListener {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var sharedPreferences: SharedPreferences

    // Хранилище для временных изменений настроек
    private val pendingSettings = mutableMapOf<String, Any>()
    // Флаг, что настройки были загружены
    private var settingsLoaded = false

    private var wifiStatus: Map<String, String>? = null
    private var wifiScanResults: List<Pair<String, Int>> = listOf() // Pair<SSID, RSSI>

    private var currentWifiStatusTextView: TextView? = null // Added this line
    private var currentWifiListView: ListView? = null // Added this line

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        setupListeners()
        setupSeekBars()
        setupThemeSwitch()
        initializeDisplay()
    }

    private fun setupThemeSwitch() {
        val isDarkTheme = sharedPreferences.getBoolean("dark_theme", false)
        binding.themeSwitch.isChecked = isDarkTheme

        binding.themeSwitch.setOnCheckedChangeListener { _, isChecked ->
            setTheme(isChecked)
            saveThemePreference(isChecked)
            Toast.makeText(requireContext(), "Тема будет применена при следующем запуске приложения", Toast.LENGTH_LONG).show()
        }
    }

    private fun setTheme(isDarkTheme: Boolean) {
        if (isDarkTheme) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
        saveThemePreference(isDarkTheme)
    }

    private fun saveThemePreference(isDarkTheme: Boolean) {
        sharedPreferences.edit().putBoolean("dark_theme", isDarkTheme).apply()
    }

    private fun setupListeners() {
        binding.saveSettingsBtn.setOnClickListener {
            saveSettings()
        }

        binding.loadSettingsBtn.setOnClickListener {
            loadSettings()
        }

        binding.resetSettingsBtn.setOnClickListener {
            sendCommand("RESET_SETTINGS")
        }

        binding.wifiSettingsBtn.setOnClickListener {
            showWifiSettingsDialog()
        }
    }

    private fun loadSettings() {
        binding.settingsStatus.text = "Загрузка настроек..."
        sendCommand("GET_SETTINGS")
    }

    private fun setupSeekBars() {
        // Размер насоса (10-100)
        binding.pumpSizeSeekbar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                val value = 10 + progress
                binding.pumpSizeValue.text = value.toString()
                if (fromUser) {
                    pendingSettings["pump_size"] = value
                    updateSettingsStatus("Есть несохраненные изменения")
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {}
        })

        // Целевая температура (150-250°C)
        binding.targetTempSeekbar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                val value = 150 + progress
                binding.targetTempValue.text = "${value}°C"
                if (fromUser) {
                    pendingSettings["heater_target"] = value
                    updateSettingsStatus("Есть несохраненные изменения")
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {}
        })

        // Минимальная температура (140-240°C)
        binding.minTempSeekbar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                val value = 140 + progress
                binding.minTempValue.text = "${value}°C"
                if (fromUser) {
                    pendingSettings["heater_min"] = value
                    updateSettingsStatus("Есть несохраненные изменения")
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {}
        })

        // Температура перегрева (200-300°C)
        binding.overheatTempSeekbar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                val value = 200 + progress
                binding.overheatTempValue.text = "${value}°C"
                if (fromUser) {
                    pendingSettings["heater_overheat"] = value
                    updateSettingsStatus("Есть несохраненные изменения")
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {}
        })

        // Температура предупреждения (180-280°C)
        binding.warningTempSeekbar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                val value = 180 + progress
                binding.warningTempValue.text = "${value}°C"
                if (fromUser) {
                    pendingSettings["heater_warning"] = value
                    updateSettingsStatus("Есть несохраненные изменения")
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {}
        })

// Макс. ШИМ вентилятора (0-255)
        binding.fanPwmSeekbar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                val percentage = (progress * 100) / 255
                binding.fanPwmValue.text = "$progress ($percentage%)"
                if (fromUser) {
                    pendingSettings["max_pwm_fan"] = progress
                    updateSettingsStatus("Есть несохраненные изменения")
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {}
        })

// Яркость свечи (0-255)
        binding.glowBrightnessSeekbar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                val percentage = (progress * 100) / 255
                binding.glowBrightnessValue.text = "$progress ($percentage%)"
                if (fromUser) {
                    pendingSettings["glow_brightness"] = progress
                    updateSettingsStatus("Есть несохраненные изменения")
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {}
        })

        // Время розжига свечи (0-60000 мс)
        binding.glowFadeInSeekbar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                val value = progress * 100
                binding.glowFadeInValue.text = "${value} мс"
                if (fromUser) {
                    pendingSettings["glow_fade_in_duration"] = value
                    updateSettingsStatus("Есть несохраненные изменения")
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {}
        })

        // Время затухания свечи (0-60000 мс)
        binding.glowFadeOutSeekbar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                val value = progress * 100
                binding.glowFadeOutValue.text = "${value} мс"
                if (fromUser) {
                    pendingSettings["glow_fade_out_duration"] = value
                    updateSettingsStatus("Есть несохраненные изменения")
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {}
        })
    }

    private fun saveSettings() {
        if (pendingSettings.isEmpty()) {
            binding.settingsStatus.text = "Нет изменений для сохранения"
            return
        }

        binding.settingsStatus.text = "Сохранение настроек..."

        // Отправляем все измененные настройки
        for ((key, value) in pendingSettings) {
            sendCommand("SET:$key=$value")
        }

        pendingSettings.clear()
        binding.settingsStatus.text = "Настройки отправлены на сохранение"
    }

    private fun updateSettingsStatus(status: String) {
        binding.settingsStatus.text = status
    }

    private fun initializeDisplay() {
        // Устанавливаем начальные значения для всех полей
        binding.pumpSizeValue.text = "10"
        binding.targetTempValue.text = "150°C"
        binding.minTempValue.text = "140°C"
        binding.overheatTempValue.text = "200°C"
        binding.warningTempValue.text = "180°C"
        binding.fanPwmValue.text = "200"
        binding.glowBrightnessValue.text = "200"
        binding.glowFadeInValue.text = "0 мс"
        binding.glowFadeOutValue.text = "0 мс"

        binding.settingsStatus.text = "Настройки не загружены"
    }

    override fun updateData(data: Map<String, Any>) {
        // НЕ обновляем настройки автоматически при получении данных
        // Настройки будут обновляться только при явном вызове loadSettings()
        // или при подключении через специальный флаг
        data["WIFI_STATUS"]?.let {
            if (it is String) {
                parseWifiStatus(it)
                currentWifiStatusTextView?.let { tv ->
                    updateWifiStatusDisplay(tv)
                }
            }
        }
        data["WIFI_SCAN_RAW_RESULTS"]?.let { 
            if (it is String) {
                Log.d("SettingsFragment", "Received raw WiFi scan results: $it")
                parseWifiScanResults(it)
                currentWifiListView?.let { lv ->
                    val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, wifiScanResults.map { "${it.first} (${it.second} dBm)" })
                    lv.adapter = adapter
                }
            }
        }
    }

    // Новый метод для явной загрузки настроек (вызывается из MainActivity при подключении)
    fun loadSettingsFromData(data: Map<String, Any>) {
        activity?.runOnUiThread {
            // Обновляем все слайдеры на основе полученных данных
            updateSeekBarFromData(data, "pump_size", binding.pumpSizeSeekbar, 10, 100)
            updateSeekBarFromData(data, "heater_target", binding.targetTempSeekbar, 150, 250)
            updateSeekBarFromData(data, "heater_min", binding.minTempSeekbar, 140, 240)
            updateSeekBarFromData(data, "heater_overheat", binding.overheatTempSeekbar, 200, 300)
            updateSeekBarFromData(data, "heater_warning", binding.warningTempSeekbar, 180, 280)
            updateSeekBarFromData(data, "max_pwm_fan", binding.fanPwmSeekbar, 0, 255)
            updateSeekBarFromData(data, "glow_brightness", binding.glowBrightnessSeekbar, 0, 255)
            updateSeekBarFromData(data, "glow_fade_in_duration", binding.glowFadeInSeekbar, 0, 60000, 100)
            updateSeekBarFromData(data, "glow_fade_out_duration", binding.glowFadeOutSeekbar, 0, 60000, 100)

            binding.settingsStatus.text = "Настройки загружены"
            pendingSettings.clear() // Очищаем временные настройки при загрузке новых
            settingsLoaded = true
        }
    }

    private fun updateSeekBarFromData(data: Map<String, Any>, key: String, seekBar: android.widget.SeekBar, min: Int, max: Int, step: Int = 1) {
        data[key]?.let { value ->
            val numericValue = value.toString().toIntOrNull() ?: min
            val progress = (numericValue - min) / step
            seekBar.progress = progress.coerceIn(0, (max - min) / step)
        }
    }

    private fun showWifiSettingsDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(com.webasto.heater.R.layout.dialog_wifi_settings, null)
        val tvWifiStatus = dialogView.findViewById<TextView>(com.webasto.heater.R.id.tv_wifi_status)
        val btnScanWifi = dialogView.findViewById<Button>(com.webasto.heater.R.id.btn_scan_wifi)
        val lvWifiNetworks = dialogView.findViewById<ListView>(com.webasto.heater.R.id.lv_wifi_networks)
        val btnResetWifi = dialogView.findViewById<Button>(com.webasto.heater.R.id.btn_reset_wifi)
        val btnRebootEsp = dialogView.findViewById<Button>(com.webasto.heater.R.id.btn_reboot_esp)

        currentWifiStatusTextView = tvWifiStatus // Assigned here
        currentWifiListView = lvWifiNetworks // Assigned here

        val alertDialog = AlertDialog.Builder(requireContext())
            .setTitle("Настройки WiFi")
            .setView(dialogView)
            .setNegativeButton("Закрыть", null)
            .create()

        alertDialog.setOnDismissListener { // Added this listener
            currentWifiStatusTextView = null
            currentWifiListView = null
        }

        updateWifiStatusDisplay(tvWifiStatus)
        // Initially populate the list if scan results are already available
        if (wifiScanResults.isNotEmpty()) {
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, wifiScanResults.map { "${it.first} (${it.second} dBm)" })
            lvWifiNetworks.adapter = adapter
        }

        btnScanWifi.setOnClickListener {
            sendCommand("SCAN_WIFI")
            Toast.makeText(requireContext(), "Сканирование WiFi сетей...", Toast.LENGTH_SHORT).show()
        }

        lvWifiNetworks.setOnItemClickListener { parent, view, position, id ->
            val selectedNetwork = wifiScanResults[position]
            showConnectWifiDialog(selectedNetwork.first)
        }

        btnResetWifi.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Сброс настроек WiFi")
                .setMessage("Вы уверены, что хотите сбросить настройки WiFi на устройстве? Это приведет к перезагрузке устройства.")
                .setPositiveButton("Да") { _, _ ->
                    sendCommand("RESET_WIFI")
                    Toast.makeText(requireContext(), "Сброс настроек WiFi...", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        btnRebootEsp.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Перезагрузка устройства")
                .setMessage("Вы уверены, что хотите перезагрузить устройство?")
                .setPositiveButton("Да") { _, _ ->
                    sendCommand("REBOOT_ESP")
                    Toast.makeText(requireContext(), "Перезагрузка устройства...", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
        alertDialog.show()
        sendCommand("GET_WIFI_STATUS") // Запрашиваем статус при открытии диалога
    }

    private fun updateWifiStatusDisplay(tvWifiStatus: TextView) {
        wifiStatus?.let { status ->
            val mode = status["mode"] ?: "N/A"
            val ssid = status["ssid"] ?: "N/A"
            val ip = status["ip"] ?: "N/A"
            val connectionStatus = status["status"] ?: "N/A"
            tvWifiStatus.text = "Режим: $mode\nSSID: $ssid\nIP: $ip\nСтатус: $connectionStatus"
        } ?: run {
            tvWifiStatus.text = "Статус WiFi: Неизвестно"
        }
    }

    private fun parseWifiStatus(statusString: String) {
        val statusMap = mutableMapOf<String, String>()
        statusString.split(',').forEach { part ->
            val keyValue = part.split('=', limit = 2)
            if (keyValue.size == 2) {
                statusMap[keyValue[0].trim()] = keyValue[1].trim()
            }
        }
        wifiStatus = statusMap
    }

    private fun parseWifiScanResults(rawScanResults: String) {
        Log.d("SettingsFragment", "Parsing raw WiFi scan results: $rawScanResults")
        val results = mutableListOf<Pair<String, Int>>()
        rawScanResults.split(System.lineSeparator()).forEach { line ->
            val ssidMatch = Regex("""SSID: ([^,]+), RSSI: (-?\d+)""").find(line)
            ssidMatch?.let {
                val ssid = it.groupValues[1]
                val rssi = it.groupValues[2].toInt()
                results.add(Pair(ssid, rssi))
            }
        }
        wifiScanResults = results
        Log.d("SettingsFragment", "Parsed WiFi scan results: $wifiScanResults")
    }

    private fun showConnectWifiDialog(ssid: String) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(com.webasto.heater.R.layout.dialog_connect_wifi, null)
        val etPassword = dialogView.findViewById<EditText>(com.webasto.heater.R.id.et_wifi_password)

        AlertDialog.Builder(requireContext())
            .setTitle("Подключиться к WiFi: $ssid")
            .setView(dialogView)
            .setPositiveButton("Подключить") { _, _ ->
                val password = etPassword.text.toString()
                sendCommand("CONNECT_WIFI:$ssid,${password}")
                Toast.makeText(requireContext(), "Попытка подключения к $ssid...", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    override fun onDataUpdated(data: Map<String, Any>) {
        activity?.runOnUiThread {
            Log.d("SettingsFragment", "onDataUpdated received: $data")
            // Handle WIFI_STATUS updates
            data["WIFI_STATUS"]?.let {
                if (it is String) {
                    parseWifiStatus(it)
                    currentWifiStatusTextView?.let { tv -> // Updated this block
                        updateWifiStatusDisplay(tv)
                    }
                }
            }
            // Handle WIFI_SCAN_RAW_RESULTS
            data["WIFI_SCAN_RAW_RESULTS"]?.let {
                if (it is String) {
                    Log.d("SettingsFragment", "Received raw WiFi scan results from onDataUpdated: $it")
                    parseWifiScanResults(it)
                    currentWifiListView?.let { lv ->
                        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, wifiScanResults.map { "${it.first} (${it.second} dBm)" })
                        lv.adapter = adapter
                        Log.d("SettingsFragment", "Updated ListView with new scan results.")
                    }
                }
            }
        }
    }

    override fun onConnectionStatusChanged(connected: Boolean) {
        // Not directly used in settings fragment for now, but good to have.
    }

    override fun onError(error: String) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), "Ошибка WiFi: $error", Toast.LENGTH_LONG).show()
        }
    }

    override fun onMessageReceived(message: String) {
        // This is handled in MainActivity, then parsed data is sent via updateData.
        // The raw WIFI_SCAN messages are now buffered in MainActivity.
        if (message.startsWith("WIFI_STATUS:")) {
            parseWifiStatus(message.substringAfter("WIFI_STATUS:"))
            currentWifiStatusTextView?.let { tv -> 
                updateWifiStatusDisplay(tv)
            }
        }
        // Removed WIFI_SCAN_START/END handling from here, as it's now in MainActivity
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    } 
}