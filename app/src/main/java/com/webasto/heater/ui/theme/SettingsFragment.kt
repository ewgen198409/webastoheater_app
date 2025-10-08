package com.webasto.heater.ui.theme

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import com.webasto.heater.databinding.FragmentSettingsBinding

class SettingsFragment : BaseHeaterFragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var sharedPreferences: SharedPreferences

    // Хранилище для временных изменений настроек
    private val pendingSettings = mutableMapOf<String, Any>()
    // Флаг, что настройки были загружены
    private var settingsLoaded = false

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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}