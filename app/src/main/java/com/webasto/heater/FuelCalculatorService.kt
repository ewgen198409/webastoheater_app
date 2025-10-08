package com.webasto.heater

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.webasto.heater.ui.theme.FuelFragment

class FuelCalculatorService : Service() {

    private val binder = LocalBinder()

    // LiveData для передачи данных в UI
    private val _totalFuelConsumed = MutableLiveData(0.0)
    val totalFuelConsumed: LiveData<Double> = _totalFuelConsumed
    private val _consumptionPerHour = MutableLiveData<Float>(0.0f)
    val consumptionPerHour: LiveData<Float> = _consumptionPerHour
    val currentConsumptionPerHour: LiveData<Float> = _consumptionPerHour

    // Переменные для расчетов
    private var pumpSize: Float = 0f
    private var fuelHz: Float = 0f
    private var lastUpdateTime: Long = 0L
    private var calculationThread: Thread? = null
    private var isRunning = true

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "FuelCalculatorChannel"
    }

    inner class LocalBinder : Binder() {
        fun getService(): FuelCalculatorService = this@FuelCalculatorService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        startRealTimeCalculation()
        return START_STICKY
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Расчет расхода топлива")
            .setContentText("Общий расход: ${totalFuelConsumed.value} л. \nТекущий расход: ${currentConsumptionPerHour.value} л/ч")
            .setSmallIcon(R.drawable.gas) // Замените на вашу иконку
            .setContentIntent(pendingIntent)
            .setSilent(true) // Делаем уведомление беззвучным
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Fuel Calculator Service Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null) // Делаем уведомление беззвучным
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
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

    fun updateData(newPumpSize: Float, newFuelHz: Float) {
        this.pumpSize = newPumpSize
        this.fuelHz = newFuelHz
    }

    fun resetFuelConsumption() {
        _totalFuelConsumed.postValue(0.0)
        lastUpdateTime = System.currentTimeMillis()
        _consumptionPerHour.postValue(0.0f)
    }

    private fun updateFuelCalculations() {
        val currentTime = System.currentTimeMillis()
        if (lastUpdateTime == 0L) {
            lastUpdateTime = currentTime
            return
        }

        val timeDeltaHours = (currentTime - lastUpdateTime) / (1000.0 * 60 * 60)

        if (timeDeltaHours > 0 && pumpSize > 0 && fuelHz > 0) {
            val currentConsumption = calculateFuelConsumptionPerHour()
            _consumptionPerHour.postValue(currentConsumption)

            val fuelConsumed = currentConsumption * timeDeltaHours
            _totalFuelConsumed.postValue((_totalFuelConsumed.value ?: 0.0) + fuelConsumed)

            lastUpdateTime = currentTime
        } else if (fuelHz == 0f) {
            _consumptionPerHour.postValue(0.0f)
        }
    }

    private fun calculateFuelConsumptionPerHour(): Float {
        return (pumpSize * fuelHz * 3.6f) / 1000f
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        calculationThread?.interrupt()
    }
}
