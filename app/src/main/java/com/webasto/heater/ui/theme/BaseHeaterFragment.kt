package com.webasto.heater.ui.theme

import androidx.fragment.app.Fragment
import com.webasto.heater.MainActivity
import com.webasto.heater.BluetoothManager

abstract class BaseHeaterFragment : Fragment() {

    protected lateinit var bluetoothManager: BluetoothManager

    override fun onResume() {
        super.onResume()
        val activity = requireActivity() as? MainActivity
        activity?.getCurrentManager()?.let { manager ->
            bluetoothManager = manager
        }
    }

    protected fun sendCommand(command: String) {
        if (this::bluetoothManager.isInitialized) {
            bluetoothManager.sendCommand(command)
        }
    }

    abstract fun updateData(data: Map<String, Any>)
}