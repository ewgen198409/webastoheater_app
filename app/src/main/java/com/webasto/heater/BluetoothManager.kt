package com.webasto.heater

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

// Создаем отдельный интерфейс для Bluetooth
interface BluetoothDataListener {
    fun onDataUpdated(data: Map<String, Any>)
    fun onConnectionStatusChanged(connected: Boolean)
    fun onError(error: String)
    fun onMessageReceived(message: String)
}

class BluetoothManager(private val context: Context) {
    companion object {
        private const val TAG = "BluetoothManager"
        // UUID для SPP (Serial Port Profile) - стандартный для последовательного соединения
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var connectedDevice: BluetoothDevice? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var dataListener: BluetoothDataListener? = null
    private var readThread: Thread? = null
    private var isConnected = false

    @SuppressLint("MissingPermission")
    fun initialize(): Boolean {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        if (bluetoothAdapter == null) {
            // Fallback для старых устройств
            @Suppress("DEPRECATION")
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        }
        return bluetoothAdapter != null
    }

    @SuppressLint("MissingPermission")
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): Set<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices ?: emptySet()
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice): Boolean {
        return try {
            Log.d(TAG, "Connecting to device: ${device.name} - ${device.address}")

            bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            bluetoothSocket?.connect()
            connectedDevice = device

            inputStream = bluetoothSocket?.inputStream
            outputStream = bluetoothSocket?.outputStream

            isConnected = true
            startReading()

            sendInitialCommands()

            dataListener?.onConnectionStatusChanged(true)
            Log.d(TAG, "Bluetooth connected successfully")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Bluetooth connection failed: ${e.message}")
            dataListener?.onError("Bluetooth connection failed: ${e.message}")
            disconnect()
            false
        }
    }

    fun disconnect() {
        isConnected = false
        readThread?.interrupt()

        try {
            inputStream?.close()
            outputStream?.close()
            bluetoothSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing Bluetooth connection: ${e.message}")
        }

        dataListener?.onConnectionStatusChanged(false)
        Log.d(TAG, "Bluetooth disconnected")
    }

    fun sendCommand(command: String) {
        if (!isConnected) {
            Log.e(TAG, "Not connected, cannot send command")
            dataListener?.onError("Not connected to Bluetooth device")
            return
        }

        try {
            val data = "$command\n".toByteArray()
            outputStream?.write(data)
            outputStream?.flush()
            Log.d(TAG, "Bluetooth command sent: $command")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send Bluetooth command: ${e.message}")
            dataListener?.onError("Bluetooth send failed: ${e.message}")
            disconnect()
        }
    }

    fun sendBinaryData(data: ByteArray, onProgress: ((Int, Int) -> Unit)? = null): Boolean {
        if (!isConnected) {
            Log.e(TAG, "Not connected, cannot send binary data")
            dataListener?.onError("Not connected to Bluetooth device")
            return false
        }

        return try {
            val chunkSize = 512 // Размер чанка как в ESP8266
            var sent = 0
            val total = data.size

            Log.d(TAG, "Starting binary data transfer: $total bytes")

            for (i in 0 until total step chunkSize) {
                val end = minOf(i + chunkSize, total)
                val chunk = data.copyOfRange(i, end)

                outputStream?.write(chunk)
                outputStream?.flush()

                sent += chunk.size
                onProgress?.invoke(sent, total)

                Log.d(TAG, "Sent chunk: $sent/$total bytes")

                // Увеличиваем паузу между чанками для надежности
                Thread.sleep(20)
            }

            Log.d(TAG, "Binary data transfer completed: $sent bytes")

            // Дополнительная пауза после завершения передачи
            Thread.sleep(1000)

            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send binary data: ${e.message}")
            dataListener?.onError("Bluetooth binary send failed: ${e.message}")
            false
        } catch (e: InterruptedException) {
            Log.e(TAG, "Binary data transfer interrupted: ${e.message}")
            dataListener?.onError("Bluetooth binary send interrupted")
            false
        }
    }

    fun sendOTAData(firmwareData: ByteArray, onProgress: (Int, Int) -> Unit, onComplete: (Boolean, String) -> Unit) {
        Thread {
            try {
                // Шаг 1: Начать OTA обновление
                Log.d(TAG, "Step 1: Sending START_OTA")
                sendCommand("START_OTA")
                Thread.sleep(8000) // Ждем ответа от устройства

                // Шаг 2: Отправить бинарные данные firmware
                Log.d(TAG, "Step 2: Starting binary data transfer")
                val success = sendBinaryData(firmwareData, onProgress)

                if (success) {
                    // Ждем завершения передачи последнего чанка
                    Log.d(TAG, "Binary transfer completed, waiting before END_OTA")
                    Thread.sleep(20000) // Увеличено

                    // Шаг 3: Отправляем команду END_OTA несколько раз для надежности
                    Log.d(TAG, "Step 3: Sending END_OTA command (attempt 1)")
                    sendCommand("END_OTA")
                    Thread.sleep(2000) // Увеличено

                    // Ждем подтверждения от устройства
                    Log.d(TAG, "Waiting for device response...")
                    Thread.sleep(12000) // Увеличено

                    // Проверяем статус файловой системы
                    Log.d(TAG, "Step 4: Checking filesystem info")
                    sendCommand("GET_FS_INFO")
                    Thread.sleep(5000) // Увеличено

                    onComplete(true, "OTA update completed successfully")
                } else {
                    sendCommand("CANCEL_OTA")
                    onComplete(false, "Failed to send firmware data")
                }
            } catch (e: Exception) {
                Log.e(TAG, "OTA update failed: ${e.message}")
                sendCommand("CANCEL_OTA")
                onComplete(false, "OTA update error: ${e.message}")
            }
        }.start()
    }

    private fun startReading() {
        readThread = Thread {
            val buffer = ByteArray(1024)
            var bytes: Int
            var remainingData = ""

            try {
                while (isConnected && !Thread.currentThread().isInterrupted) {
                    bytes = inputStream?.read(buffer) ?: -1
                    if (bytes > 0) {
                        val rawData = String(buffer, 0, bytes)
                        remainingData += rawData

                        // Обрабатываем построчно как в webasto.py
                        val lines = remainingData.split("\n")
                        remainingData = lines.last() // Сохраняем неполную строку для следующей итерации

                        for (line in lines.dropLast(1)) {
                            val trimmedLine = line.trim()
                            if (trimmedLine.isNotEmpty()) {
                                Log.d(TAG, "Bluetooth line received: $trimmedLine")
                                dataListener?.onMessageReceived(trimmedLine)
                            }
                        }
                    }
                    Thread.sleep(50) // Уменьшил задержку для более быстрого响应
                }
            } catch (e: IOException) {
                Log.e(TAG, "Bluetooth read error: ${e.message}")
                if (isConnected) {
                    dataListener?.onError("Bluetooth read error: ${e.message}")
                    disconnect()
                }
            } catch (e: InterruptedException) {
                // Thread was interrupted, normal shutdown
            }
        }
        readThread?.start()
    }

    fun setDataListener(listener: BluetoothDataListener) {
        this.dataListener = listener
    }

    fun isConnected(): Boolean {
        return isConnected && bluetoothSocket?.isConnected == true
    }

    fun sendInitialCommands() {
        Thread {
            Thread.sleep(1000)
            sendCommand("GET_SETTINGS")
            Log.d(TAG, "Sent initial GET_SETTINGS command")
        }.start()
    }

    @SuppressLint("MissingPermission")
    fun getConnectedDeviceName(): String {
        return connectedDevice?.name ?: "Unknown"
    }
}
