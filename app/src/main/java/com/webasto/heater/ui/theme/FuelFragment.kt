package com.webasto.heater.ui.theme

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.webasto.heater.FuelCalculatorService
import com.webasto.heater.databinding.FragmentFuelBinding

class FuelFragment : BaseHeaterFragment() {

    private var _binding: FragmentFuelBinding? = null
    private val binding get() = _binding!!

    private var fuelService: FuelCalculatorService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as FuelCalculatorService.LocalBinder
            fuelService = binder.getService()
            isBound = true
            setupObservers()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            isBound = false
            fuelService = null
        }
    }

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
        startAndBindService()
    }

    private fun startAndBindService() {
        val intent = Intent(activity, FuelCalculatorService::class.java)
        // Запускаем сервис как Foreground Service
        ContextCompat.startForegroundService(requireContext(), intent)
        // Привязываемся к сервису, чтобы получать обновления
        activity?.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }
    
    private fun setupListeners() {
        binding.resetFuelBtn.setOnClickListener {
            fuelService?.resetFuelConsumption()
        }
    }

    private fun initializeDisplay() {
        binding.currentFuel.text = "0.000 л"
        binding.fuelPerHour.text = "0.000 л/ч"
        binding.fuelPumpRate.text = "-- Гц"
        binding.pumpSize.text = "-- мл/1000имп"
    }

    private fun setupObservers() {
        fuelService?.totalFuelConsumed?.observe(viewLifecycleOwner) { consumed ->
            binding.currentFuel.text = String.format("%.3f л", consumed)
        }
        fuelService?.consumptionPerHour?.observe(viewLifecycleOwner) { perHour ->
            binding.fuelPerHour.text = String.format("%.3f л/ч", perHour)
        }
    }

    override fun updateData(data: Map<String, Any>) {
        activity?.runOnUiThread {
            updateTextView(binding.fuelPumpRate, data["fuel_hz"], "Гц")
            updateTextView(binding.pumpSize, data["pump_size"], "мл/1000имп")

            val pumpSize = data["pump_size"]?.toString()?.toFloatOrNull() ?: 0f
            val fuelHz = data["fuel_hz"]?.toString()?.toFloatOrNull() ?: 0f
            
            if (isBound) {
                fuelService?.updateData(pumpSize, fuelHz)
            }
        }
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
        if (isBound) {
            activity?.unbindService(connection)
            isBound = false
        }
        _binding = null
    }
}
