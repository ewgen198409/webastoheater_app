package com.webasto.heater

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import android.net.Uri
import android.os.ParcelFileDescriptor // Добавляем импорт

class FirmwareUpdater(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
    private val firmwareDownloader: FirmwareDownloader
) : BluetoothDataListener { // Реализуем BluetoothDataListener
    companion object {
        private const val TAG = "FirmwareUpdater"
        private const val OTA_BUFFER_SIZE = 512 // Должен совпадать с размером буфера в прошивке
        private const val OTA_RESPONSE_TIMEOUT_SECONDS = 15L // Увеличил таймаут
    }

    interface FirmwareUpdateListener {
        fun onUpdateStarted()
        fun onDownloadProgress(progress: Int)
        fun funOnUploadProgress(progress: Int)
        fun onUpdateProgress(message: String, progress: Int) // Общий прогресс и сообщение
        fun onUpdateCompleted()
        fun onUpdateError(error: String)
    }

    private var updateListener: FirmwareUpdateListener? = null
    private val otaResponseQueue = ArrayBlockingQueue<String>(10) // Очередь для ответов OTA
    private var otaInProgress = false // Флаг для отслеживания процесса OTA

    // Методы BluetoothDataListener
    override fun onDataUpdated(data: Map<String, Any>) {
        // Не используется для OTA
    }

    override fun onConnectionStatusChanged(connected: Boolean) {
        if (!connected && otaInProgress) {
            // Если соединение потеряно во время OTA, сообщаем об ошибке
            updateListener?.onUpdateError("Соединение Bluetooth потеряно во время обновления прошивки.")
            otaInProgress = false
        }
    }

    override fun onError(error: String) {
        if (otaInProgress) {
            updateListener?.onUpdateError("Ошибка Bluetooth во время обновления прошивки: $error")
            otaInProgress = false
        }
    }

    override fun onMessageReceived(message: String) {
        Log.d(TAG, "Received Bluetooth message (OTA): $message")
        if (otaInProgress && message.startsWith("OTA_")) {
            otaResponseQueue.offer(message) // Добавляем OTA-ответ в очередь
        }
    }

    fun setUpdateListener(listener: FirmwareUpdateListener) {
        this.updateListener = listener
    }

    suspend fun startFirmwareUpdate(firmwareUrl: String?, currentFirmwareVersion: String?, localFileUri: Uri?) = withContext(Dispatchers.IO) {
        if (!bluetoothManager.isConnected()) {
            updateListener?.onUpdateError("Устройство не подключено по Bluetooth.")
            return@withContext
        }

        if (firmwareUrl == null && localFileUri == null) {
            updateListener?.onUpdateError("Не указан источник файла прошивки (URL или локальный URI).")
            return@withContext
        }

        otaInProgress = true
        bluetoothManager.setOtaDataListener(this@FirmwareUpdater) // Устанавливаем себя как OTA слушателя

        updateListener?.onUpdateStarted()
        updateListener?.onUpdateProgress("Начало обновления прошивки...", 0)

        var firmwareInputStream: FileInputStream? = null
        var localFileDescriptor: ParcelFileDescriptor? = null
        var totalFileSize: Long = 0

        try {
            if (firmwareUrl != null) {
                // 1. Загрузка файла прошивки по URL
                updateListener?.onUpdateProgress("Загрузка файла прошивки...", 5)
                val fileName = "firmware_${System.currentTimeMillis()}.bin"
                var downloadedFile: File? = null
                firmwareDownloader.downloadFirmware(firmwareUrl, fileName, object : FirmwareDownloader.DownloadListener {
                    override fun onDownloadStarted() { /* уже обрабатывается */ }
                    override fun onDownloadProgress(progress: Int) {
                        updateListener?.onDownloadProgress(progress)
                        updateListener?.onUpdateProgress("Загрузка файла: $progress%", (5 + progress * 45 / 100)) // 5% - 50%
                    }
                    override fun onDownloadCompleted(filePath: String) {
                        downloadedFile = File(filePath)
                        updateListener?.onUpdateProgress("Файл прошивки загружен.", 50)
                    }
                    override fun onDownloadError(error: String) {
                        throw IOException(error)
                    }
                })
                val file = downloadedFile ?: throw IOException("Firmware file not found after download.")
                firmwareInputStream = FileInputStream(file)
                totalFileSize = file.length()
            } else if (localFileUri != null) {
                // 1. Использование локального файла прошивки по URI
                updateListener?.onUpdateProgress("Чтение локального файла прошивки...", 5)
                val contentResolver = context.contentResolver
                localFileDescriptor = contentResolver.openFileDescriptor(localFileUri, "r")
                firmwareInputStream = FileInputStream(localFileDescriptor?.fileDescriptor)
                totalFileSize = localFileDescriptor?.statSize ?: 0L
                if (totalFileSize == 0L) {
                    throw IOException("Не удалось определить размер локального файла прошивки.")
                }
                updateListener?.onUpdateProgress("Локальный файл прошивки готов к отправке.", 50)
            } else {
                throw IOException("Не указан источник файла прошивки.")
            }

            // 2. Отправка команды START_OTA
            bluetoothManager.sendCommand("START_OTA")
            updateListener?.onUpdateProgress("Отправка команды START_OTA...", 55)
            waitForOtaResponse("OTA_READY", "Устройство не готово к OTA.")

            // 3. Отправка файла прошивки по частям
            updateListener?.onUpdateProgress("Загрузка прошивки на устройство...", 60)
            firmwareInputStream?.use { inputStream ->
                val buffer = ByteArray(OTA_BUFFER_SIZE)
                var bytesRead: Int
                var bytesSent: Long = 0

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    bluetoothManager.sendBytes(buffer.copyOfRange(0, bytesRead))
                    bytesSent += bytesRead

                    val progress = ((bytesSent * 100) / totalFileSize).toInt()
                    updateListener?.funOnUploadProgress(progress)
                    updateListener?.onUpdateProgress("Загрузка на устройство: $progress%", (60 + progress * 30 / 100)) // 60% - 90%

                    waitForOtaResponse("OTA_CHUNK_ACK", "Устройство не подтвердило получение чанка.")
                    delay(20) // Добавил небольшую задержку после каждого чанка
                }
            }

            // 4. Отправка команды END_OTA
            bluetoothManager.sendCommand("END_OTA")
            updateListener?.onUpdateProgress("Отправка команды END_OTA...", 90)
            val receiveCompleteResponse = waitForOtaResponse("OTA_RECEIVE_COMPLETE", "Устройство не подтвердило получение прошивки.")
            if (!receiveCompleteResponse.startsWith("OTA_RECEIVE_COMPLETE:")) {
                throw IOException("Некорректный ответ OTA_RECEIVE_COMPLETE: $receiveCompleteResponse")
            }

            // 5. Ожидание готовности к применению
            updateListener?.onUpdateProgress("Ожидание готовности к применению прошивки...", 92)
            waitForOtaResponse("OTA_READY_TO_APPLY", "Устройство не готово к применению прошивки.")

            // 6. Отправка команды APPLY_OTA
            bluetoothManager.sendCommand("APPLY_OTA")
            updateListener?.onUpdateProgress("Применение прошивки...", 95)
            waitForOtaResponse("OTA_APPLYING", "Устройство не начало применение прошивки.")

            // 7. Ожидание перезагрузки
            updateListener?.onUpdateProgress("Устройство перезагружается...", 98)
            waitForOtaResponse("OTA_REBOOTING", "Устройство не подтвердило перезагрузку.")

            updateListener?.onUpdateProgress("Обновление прошивки завершено!", 100)
            updateListener?.onUpdateCompleted()

        } catch (e: Exception) {
            Log.e(TAG, "Firmware update failed", e)
            updateListener?.onUpdateError("Ошибка обновления прошивки: ${e.message}")
        } finally {
            otaInProgress = false
            bluetoothManager.setOtaDataListener(null) // Сбрасываем OTA слушателя
            firmwareInputStream?.close()
            localFileDescriptor?.close()
            // Если файл был загружен, удаляем его
            if (firmwareUrl != null) {
                // Если firmwareInputStream был FileInputStream, то это был загруженный файл
                // Его путь можно получить из FirmwareDownloader или сохранить явно
                // В данном случае, мы знаем, что он был создан в кэше
                val cacheDir = context.cacheDir
                val downloadedFiles = cacheDir.listFiles { _, name -> name.startsWith("firmware_") && name.endsWith(".bin") }
                downloadedFiles?.forEach { it.delete() }
                Log.d(TAG, "Temporary firmware files in cache deleted.")
            }
        }
    }

    private suspend fun waitForOtaResponse(expectedPrefix: String, errorMessage: String): String {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < OTA_RESPONSE_TIMEOUT_SECONDS * 1000) {
            val response = withContext(Dispatchers.IO) {
                otaResponseQueue.poll(100, TimeUnit.MILLISECONDS) // Небольшой таймаут для опроса
            }

            if (response != null) {
                Log.d(TAG, "Checking OTA response: $response (expected: $expectedPrefix)")
                if (response.startsWith("OTA_ERROR") || response.startsWith("OTA_FAIL")) {
                    throw IOException("Устройство сообщило об ошибке OTA: $response")
                }
                if (response.startsWith(expectedPrefix)) {
                    return response
                }
                // Игнорируем другие OTA-сообщения, пока не найдем ожидаемое
            }
        }
        throw IOException("$errorMessage (таймаут)")
    }
}
