package com.webasto.heater.ui.theme

import android.animation.ValueAnimator
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.webasto.heater.R
import com.webasto.heater.databinding.FragmentControlBinding

class ControlFragment : BaseHeaterFragment() {

    private var _binding: FragmentControlBinding? = null
    private val binding get() = _binding!!
    private var glowPlugAnimator: ValueAnimator? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentControlBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupListeners()
        saveOriginalTitles()
    }

    private fun saveOriginalTitles() {
        binding.inTempText.tag = "Температура впуска: "
        binding.exhaustTemp.tag = "Температура выхлопа: "
        binding.fanSpeed.tag = "Скорость вентилятора: "
        binding.fuelRate.tag = "Частота насоса: "
        binding.statusText.tag = "Состояние: "
        binding.cycleTime.tag = "Время цикла: "
        binding.ignitFail.tag = "Попытка запуска: "
        binding.currentStateText.tag = "Текущий режим: "
        binding.webastoFail.tag = "Ошибки Webasto: "
        // glow_plug больше не используется, поэтому мы его удалили
    }

    private fun setupListeners() {
        // Кнопка включения/выключения нагревателя
        binding.burnButton.setOnClickListener {
            sendCommand("ENTER")
        }

        binding.fuelPumpBtn.setOnClickListener {
            sendCommand("FP")
        }
        binding.clearFailBtn.setOnClickListener {
            sendCommand("CF")
        }
        binding.upModeBtn.setOnClickListener {
            sendCommand("UP")
        }
        binding.downModeBtn.setOnClickListener {
            sendCommand("DOWN")
        }
    }

    private fun updateBurnButton(isHeaterOn: Boolean) {
        activity?.runOnUiThread {
            if (isHeaterOn) {
                binding.burnButton.text = "Выключить нагрев"
                // Применяем 3D-стиль для включенного состояния
                binding.burnButton.setBackgroundResource(R.drawable.button_3d_effect_off)
            } else {
                binding.burnButton.text = "Включить нагрев"
                // Применяем 3D-стиль для выключенного состояния
                binding.burnButton.setBackgroundResource(R.drawable.button_3d_effect_on)
            }
        }
    }

    override fun updateData(data: Map<String, Any>) {
        activity?.runOnUiThread {
            // Основные сенсоры
            updateTextViewWithTitle(binding.inTempText, data["in_temp"], "°C")
            updateTextViewWithTitle(binding.exhaustTemp, data["exhaust_temp"], "°C")
            updateTextViewWithTitle(binding.fanSpeed, data["fan_speed"], "%")
            updateTextViewWithTitle(binding.fuelRate, data["fuel_hz"], "Гц")
            updateTextViewWithTitle(binding.statusText, data["message"], "")

            // Cycle time и попытка запуска (ignit_fail)
            updateTextViewWithTitle(binding.cycleTime, data["cycle_time"], "")
            updateTextViewWithTitle(binding.ignitFail, data["ignit_fail"], "")

            // Текущий режим (FULL, MID, LOW)
            updateStateDisplay(binding.currentStateText, data["current_state"])

            // Бинарные сенсоры с цветами
            updateWebastoFailWithColor(binding.webastoFail, data["webasto_fail"])
            updateGlowPlugWithColor(binding.glowPlugStatus, data["glow_left"]) // Используем glow_plug_status

            // Определяем состояние нагрева по скорости вентилятора
            val isHeaterOn = determineHeaterStateByFanSpeed(data)
            updateBurnButton(isHeaterOn)
        }
    }


    private fun updateWebastoFailWithColor(textView: android.widget.TextView, value: Any?) {
        val title = textView.tag?.toString() ?: ""
        val hasError = when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value != "0" && value.isNotEmpty() && value != "unavailable"
            else -> false
        }

        textView.text = "$title${if (hasError) "Есть" else "Нет"}"
        textView.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (hasError) android.R.color.holo_red_dark else android.R.color.holo_green_dark
            )
        )
    }

    private fun updateGlowPlugWithColor(textView: android.widget.TextView, value: Any?) {
        val isActive = when (value) {
            is String -> value >= "1" || value.toIntOrNull() != 0 || value.equals("true", true)
            is Number -> value.toInt() != 0
            is Boolean -> value
            else -> false
        }

        if (isActive) {
            textView.text = "Активна"
            if (glowPlugAnimator == null) {
                glowPlugAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1000
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { animation ->
                        val shader = LinearGradient(
                            0f, 0f, textView.width.toFloat(), 0f,
                            ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark),
                            ContextCompat.getColor(requireContext(), android.R.color.white),
                            Shader.TileMode.CLAMP
                        )
                        val matrix = Matrix()
                        matrix.setTranslate(animation.animatedValue as Float * textView.width, 0f)
                        shader.setLocalMatrix(matrix)
                        textView.paint.shader = shader
                        textView.invalidate()
                    }
                    start()
                }
            }
        } else {
            textView.text = "Неактивна"
            glowPlugAnimator?.cancel()
            glowPlugAnimator = null
            textView.paint.shader = null
        }
    }


    private fun determineHeaterStateByFanSpeed(data: Map<String, Any>): Boolean {
        data["fan_speed"]?.let { fanSpeedValue ->
            try {
                val fanSpeed = when (fanSpeedValue) {
                    is String -> fanSpeedValue.toFloatOrNull()
                    is Number -> fanSpeedValue.toFloat()
                    else -> null
                }

                if (fanSpeed != null) {
                    // Если скорость вентилятора больше 0, считаем что нагрев включен
                    return fanSpeed > 0
                }
            } catch (e: Exception) {
                // Игнорируем ошибки парсинга
            }
        }

        // Если не удалось определить по скорости вентилятора, используем запасные методы
        return determineHeaterStateFallback(data)
    }

    private fun determineHeaterStateFallback(data: Map<String, Any>): Boolean {
        // 1. Проверяем поле burn
        data["burn"]?.let { burnValue ->
            return when (burnValue) {
                is String -> burnValue == "1"
                is Number -> burnValue.toInt() == 1
                else -> false
            }
        }

        // 2. Проверяем current_state
        data["current_state"]?.let { stateValue ->
            val state = stateValue.toString()
            return state != "N/A" && state.isNotEmpty() && state != "unavailable" &&
                    state != "0" && state != "OFF"
        }

        // 3. Проверяем температуру выхлопа
        data["exhaust_temp"]?.let { tempValue ->
            try {
                val temp = when (tempValue) {
                    is String -> tempValue.toFloatOrNull()
                    is Number -> tempValue.toFloat()
                    else -> null
                }
                if (temp != null && temp > 50.0) {
                    return true
                }
            } catch (e: Exception) {
                // Игнорируем ошибки парсинга
            }
        }

        return false
    }

    private fun updateTextViewWithTitle(textView: android.widget.TextView, value: Any?, unit: String) {
        val title = textView.tag?.toString() ?: ""
        val displayValue = when {
            value != null -> "$value$unit"
            unit.isNotEmpty() -> "unavailable $unit"
            else -> "unavailable"
        }
        textView.text = "$title$displayValue"
    }

    private fun updateStateDisplay(textView: android.widget.TextView, value: Any?) {
        val title = textView.tag?.toString() ?: ""
        val stateValue = when (value) {
            is String -> value
            is Number -> value.toString()
            else -> "unavailable"
        }

        // Преобразуем числовые значения в текстовые (как в webasto.py)
        val displayState = when (stateValue) {
            "0", "FULL" -> "FULL"
            "1", "MID" -> "MID"
            "2", "LOW" -> "LOW"
            else -> stateValue
        }

        textView.text = "$title$displayState"
    }

    private fun updateBinarySensorWithTitle(
        textView: android.widget.TextView,
        value: Any?,
        trueText: String,
        falseText: String
    ) {
        val title = textView.tag?.toString() ?: ""
        val isActive = when (value) {
            is Boolean -> value
            is Number -> value.toInt() == 1
            is String -> value == "1" || value.equals("true", true)
            else -> false
        }

        textView.text = "$title${if (isActive) trueText else falseText}"
        textView.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (isActive) android.R.color.holo_green_dark else android.R.color.holo_red_dark
            )
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        glowPlugAnimator?.cancel()
        glowPlugAnimator = null
        _binding = null
    }
}