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

class WebastoService : Service() {
    companion object {
        private const val TAG = "WebastoService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "webasto_service_channel"
    }

    private val binder = WebastoBinder()
    private lateinit var bluetoothManager: BluetoothManager
    private var isRunning = false

    inner class WebastoBinder : Binder() {
        fun getService(): WebastoService = this@WebastoService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "WebastoService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "WebastoService started")

        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

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
        disconnect()
        isRunning = false
        Log.d(TAG, "WebastoService destroyed")
    }

    fun connectBluetooth(deviceAddress: String) {
        bluetoothManager = BluetoothManager(this)
        bluetoothManager.setDataListener(object : BluetoothDataListener {
            override fun onDataUpdated(data: Map<String, Any>) {
                sendDataBroadcast(data)
            }

            override fun onConnectionStatusChanged(connected: Boolean) {
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Webasto Monitor")
            .setContentText("Мониторинг работы Webasto")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(status: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Webasto Monitor")
            .setContentText("Статус: $status")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun sendDataBroadcast(data: Map<String, Any>) {
        val intent = Intent("WEBASO_DATA_UPDATE")
        intent.putExtra("data", HashMap(data))
        sendBroadcast(intent)
        Log.d(TAG, "Data broadcast sent: ${data.size} items")
    }

    fun isServiceRunning(): Boolean = isRunning
}