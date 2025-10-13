package com.webasto.heater.ui.theme

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.webasto.heater.databinding.FragmentFuelBinding

class FuelFragment : BaseHeaterFragment() {

    private var _binding: FragmentFuelBinding? = null
    private val binding get() = _binding!!

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

    }



    private fun setupListeners() {
        binding.resetFuelBtn.setOnClickListener {
            sendCommand("RESET_FUEL_CONSUMPTION")
        }
    }

    override fun updateData(data: Map<String, Any>) {
        activity?.runOnUiThread {
            updateTextViewWithTitle(binding.consumedLitersText, data["consumed_liters"], " л")
            updateTextViewWithTitle(binding.fuelHourText, data["fuel_hour"], " л/ч")
            updateTextViewWithTitle(binding.fuelPumpRate, data["fuel_hz"], " Гц")
            updateTextViewWithTitle(binding.pumpSize, data["pump_size"], " мл/1000имп")
        }
    }

    private fun updateTextViewWithTitle(textView: android.widget.TextView, value: Any?, unit: String) {
        val title = textView.tag?.toString() ?: ""
        val displayValue = when {
            value != null -> "$value$unit"
            unit.isNotEmpty() -> "unavailable$unit"
            else -> "unavailable"
        }
        textView.text = "$title$displayValue"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}