package com.webasto.heater.ui.theme

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.webasto.heater.databinding.FragmentFuelBinding

class FuelFragment : BaseHeaterFragment() {

    private var _binding: FragmentFuelBinding? = null
    private val binding get() = _binding!!

    // Переменные для хранения данных и расчетов
    private var pumpSize: Float = 0f
    private var fuelHz: Float = 0f
    private var totalFuelConsumed: Double = 0.0 // в литрах
    private var lastUpdateTime: Long = 0L
    private var calculationThread: Thread? = null
    private var isRunning = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFuelBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupListeners()
        initializeDisplay()
        startRealTimeCalculation()
    }

    private fun setupListeners() {
        binding.resetFuelBtn.setOnClickListener {
            resetFuelConsumption()
        }
    }

    private fun initializeDisplay() {
        binding.currentFuel.text = "0.000 л"
        binding.fuelPerHour.text = "0.000 л/ч"
        binding.fuelPumpRate.text = "-- Гц"
        binding.pumpSize.text = "-- мл/1000имп"
    }

    private fun startRealTimeCalculation() {
        calculationThread = Thread {
            while (isRunning) {
                try {
                    Thread.sleep(1000) // Обновляем каждую секунду
                    updateFuelCalculations()
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        calculationThread?.start()
    }

    override fun updateData(data: Map<String, Any>) {
        activity?.runOnUiThread {
            // Обновляем базовые данные
            updateTextView(binding.fuelPumpRate, data["fuel_hz"], "Гц")
            updateTextView(binding.pumpSize, data["pump_size"], "мл/1000имп")

            // Получаем текущие значения для расчетов
            data["pump_size"]?.let {
                pumpSize = it.toString().toFloatOrNull() ?: 0f
            }

            data["fuel_hz"]?.let {
                fuelHz = it.toString().toFloatOrNull() ?: 0f
            }
        }
    }

    private fun updateFuelCalculations() {
        val currentTime = System.currentTimeMillis()

        // Первое обновление - инициализация
        if (lastUpdateTime == 0L) {
            lastUpdateTime = currentTime
            return
        }

        // Вычисляем время прошедшее с последнего обновления в часах
        val timeDeltaHours = (currentTime - lastUpdateTime) / (1000.0 * 60 * 60)

        if (timeDeltaHours > 0 && pumpSize > 0 && fuelHz > 0) {
            activity?.runOnUiThread {
                // Расчет текущего расхода в л/ч
                val consumptionPerHour = calculateFuelConsumptionPerHour()
                binding.fuelPerHour.text = String.format("%.3f л/ч", consumptionPerHour)

                // Обновляем общий расход (интегрируем по времени)
                val fuelConsumed = consumptionPerHour * timeDeltaHours
                totalFuelConsumed += fuelConsumed
                binding.currentFuel.text = String.format("%.3f л", totalFuelConsumed)

                lastUpdateTime = currentTime
            }
        } else if (fuelHz == 0f) {
            // Если частота насоса 0, показываем нулевой расход
            activity?.runOnUiThread {
                binding.fuelPerHour.text = "0.000 л/ч"
            }
        }
    }

    private fun calculateFuelConsumptionPerHour(): Float {
        // Формула: (pump_size * fuel_hz * 3.6) / 1000
        // pump_size - мл за 1000 импульсов
        // fuel_hz - импульсов в секунду
        // 3.6 = (3600 секунд в часе / 1000 мл в литре)
        // Результат в литрах в час

        return (pumpSize * fuelHz * 3.6f) / 1000f
    }

    private fun resetFuelConsumption() {
        totalFuelConsumed = 0.0
        lastUpdateTime = System.currentTimeMillis()
        binding.currentFuel.text = "0.000 л"
        binding.fuelPerHour.text = "0.000 л/ч"
    }

    private fun updateTextView(textView: android.widget.TextView, value: Any?, unit: String) {
        textView.text = if (value != null) {
            "$value $unit"
        } else {
            "--"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        isRunning = false
        calculationThread?.interrupt()
        _binding = null
    }
}