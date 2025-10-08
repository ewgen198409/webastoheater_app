package com.webasto.heater

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.app.PendingIntent
import androidx.core.content.ContextCompat

class WebastoService : Service() {
    companion object {
        private const val TAG = "WebastoService"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "webasto_service_channel"

        const val ACTION_TURN_ON_HEATER = "com.webasto.heater.ACTION_TURN_ON_HEATER"
        const val ACTION_TURN_OFF_HEATER = "com.webasto.heater.ACTION_TURN_OFF_HEATER"
    }

    private val binder = WebastoBinder()
    private lateinit var bluetoothManager: BluetoothManager
    private var isRunning = false
    private var isHeaterOn = false // Добавляем состояние нагревателя
    private val notificationActionReceiver = NotificationActionReceiver()

    inner class WebastoBinder : Binder() {
        fun getService(): WebastoService = this@WebastoService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val filter = IntentFilter().apply {
            addAction(ACTION_TURN_ON_HEATER)
            addAction(ACTION_TURN_OFF_HEATER)
        }
        ContextCompat.registerReceiver(this, notificationActionReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        Log.d(TAG, "WebastoService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "WebastoService started")

        when (intent?.action) {
            ACTION_TURN_ON_HEATER -> {
                sendCommand("ENTER") // Используем "ENTER" как в ControlFragment
                isHeaterOn = true
                Log.d(TAG, "Heater ON command received from notification")
                updateNotification("Нагрев включен")
            }
            ACTION_TURN_OFF_HEATER -> {
                sendCommand("ENTER") // Используем "ENTER" как в ControlFragment
                isHeaterOn = false
                Log.d(TAG, "Heater OFF command received from notification")
                updateNotification("Нагрев выключен")
            }
            else -> {
                val notification = createNotification()
                startForeground(NOTIFICATION_ID, notification)
            }
        }

        isRunning = true

        // Bluetooth подключение будет установлено через Binder
        Log.d(TAG, "Bluetooth connection type selected, waiting for device selection")

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(notificationActionReceiver)
        disconnect()
        isRunning = false
        Log.d(TAG, "WebastoService destroyed")
    }

    fun connectBluetooth(deviceAddress: String) {
        bluetoothManager = BluetoothManager(this)
        bluetoothManager.setDataListener(object : BluetoothDataListener {
            override fun onDataUpdated(data: Map<String, Any>) {
                isHeaterOn = determineHeaterStateByFanSpeed(data) // Обновляем состояние нагревателя
                sendDataBroadcast(data)
                updateNotification(if (isHeaterOn) "Нагрев включен" else "Нагрев выключен") // Обновляем уведомление с новым статусом
            }

            override fun onConnectionStatusChanged(connected: Boolean) {
                if (!connected) {
                    isHeaterOn = false // Сбрасываем состояние нагревателя при отключении
                }
                updateNotification(if (connected) "Подключено (Bluetooth)" else "Отключено")
            }

            override fun onError(error: String) {
                Log.e(TAG, "Bluetooth error: $error")
                updateNotification("Ошибка: $error")
            }

            override fun onMessageReceived(message: String) {
                // Обработка сообщений Bluetooth
                Log.d(TAG, "Bluetooth message: $message")
            }
        })

        // Здесь нужно реализовать подключение к Bluetooth по адресу
        Log.d(TAG, "Bluetooth connection requested for device: $deviceAddress")
    }

    // Методы для определения состояния нагревателя, перенесенные из ControlFragment
    private fun determineHeaterStateByFanSpeed(data: Map<String, Any>): Boolean {
        data["fan_speed"]?.let { fanSpeedValue ->
            try {
                val fanSpeed = when (fanSpeedValue) {
                    is String -> fanSpeedValue.toFloatOrNull()
                    is Number -> fanSpeedValue.toFloat()
                    else -> null
                }

                if (fanSpeed != null) {
                    return fanSpeed > 0
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing fan speed: ${e.message}")
            }
        }
        return determineHeaterStateFallback(data)
    }

    private fun determineHeaterStateFallback(data: Map<String, Any>): Boolean {
        data["burn"]?.let { burnValue ->
            return when (burnValue) {
                is String -> burnValue == "1"
                is Number -> burnValue.toInt() == 1
                else -> false
            }
        }

        data["current_state"]?.let { stateValue ->
            val state = stateValue.toString()
            return state != "N/A" && state.isNotEmpty() && state != "unavailable" &&
                    state != "0" && state != "OFF"
        }

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
                Log.e(TAG, "Error parsing exhaust temp: ${e.message}")
            }
        }
        return false
    }

    fun sendCommand(command: String) {
        if (::bluetoothManager.isInitialized) {
            bluetoothManager.sendCommand(command)
            Log.d(TAG, "Bluetooth command sent: $command")
        } else {
            Log.e(TAG, "BluetoothManager not initialized for Bluetooth")
        }
    }

    fun disconnect() {
        if (::bluetoothManager.isInitialized) {
            bluetoothManager.disconnect()
            Log.d(TAG, "Bluetooth disconnected")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Webasto Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
val actionIntent = if (isHeaterOn) {
    Intent(this, WebastoService::class.java).apply { action = ACTION_TURN_OFF_HEATER }
} else {
    Intent(this, WebastoService::class.java).apply { action = ACTION_TURN_ON_HEATER }
}

        val pendingIntent: PendingIntent =
            PendingIntent.getService(this, 0, actionIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val actionText = if (isHeaterOn) "Выключить нагрев" else "Включить нагрев"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Webasto Monitor")
            .setContentText("Мониторинг работы Webasto")
            .setSmallIcon(R.drawable.fire)
            .addAction(R.drawable.fire, actionText, pendingIntent) // Одна кнопка, меняющая текст и действие
            .build()
    }

    private fun updateNotification(status: String) {
val actionIntent = if (isHeaterOn) {
    Intent(this, WebastoService::class.java).apply { action = ACTION_TURN_OFF_HEATER }
} else {
    Intent(this, WebastoService::class.java).apply { action = ACTION_TURN_ON_HEATER }
}

        val pendingIntent: PendingIntent =
            PendingIntent.getService(this, 0, actionIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val actionText = if (isHeaterOn) "Выключить нагрев" else "Включить нагрев"

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Webasto Monitor")
            .setContentText("Статус: $status")
            .setSmallIcon(R.drawable.fire)
            .addAction(R.drawable.fire, actionText, pendingIntent) // Одна кнопка, меняющая текст и действие
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private inner class NotificationActionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                when (it.action) {
                    ACTION_TURN_ON_HEATER -> {
                        sendCommand("ENTER")
                        isHeaterOn = true
                        Log.d(TAG, "Heater ON command received from notification receiver")
                        updateNotification("Нагрев включен")
                    }
                    ACTION_TURN_OFF_HEATER -> {
                        sendCommand("ENTER")
                        isHeaterOn = false
                        Log.d(TAG, "Heater OFF command received from notification receiver")
                        updateNotification("Нагрев выключен")
                    }
                }
            }
        }
    }

    private fun sendDataBroadcast(data: Map<String, Any>) {
        val intent = Intent("WEBASO_DATA_UPDATE")
        intent.putExtra("data", HashMap(data))
        sendBroadcast(intent)
        Log.d(TAG, "Data broadcast sent: ${data.size} items")
    }

    fun isServiceRunning(): Boolean = isRunning
}
