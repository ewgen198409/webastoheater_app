package com.webasto.heater

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class UpdateChecker(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // URL для проверки обновлений приложения
    private val githubAppApiUrl = "https://api.github.com/repos/ewgen198409/webastoheater_app/releases"
    // URL для проверки обновлений прошивки ESP
    private val githubFirmwareApiUrl = "https://api.github.com/repos/ewgen198409/esp8266_Webasto/releases"

    data class UpdateInfo(
        val hasUpdate: Boolean,
        val latestVersion: String?,
        val currentVersion: String,
        val downloadUrl: String?,
        val releaseNotes: String?,
        val publishedAt: String?
    )

    interface UpdateCheckListener {
        fun onUpdateCheckStarted()
        fun onUpdateCheckCompleted(updateInfo: UpdateInfo)
        fun onUpdateCheckError(error: String)
    }

    data class FirmwareUpdateInfo(
        val hasUpdate: Boolean,
        val latestVersion: String?,
        val currentVersion: String?, // Может быть null, если не удалось получить с устройства
        val downloadUrl: String?,
        val releaseNotes: String?,
        val publishedAt: String?
    )

    interface FirmwareUpdateCheckListener {
        fun onFirmwareUpdateCheckStarted()
        fun onFirmwareUpdateCheckCompleted(firmwareUpdateInfo: FirmwareUpdateInfo)
        fun onFirmwareUpdateCheckError(error: String)
    }

    fun checkForUpdates(listener: UpdateCheckListener) {
        val currentVersion = getCurrentVersion()

        listener.onUpdateCheckStarted()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val updateInfo = performAppUpdateCheck(currentVersion)
                Handler(Looper.getMainLooper()).post {
                    listener.onUpdateCheckCompleted(updateInfo)
                }
            } catch (e: Exception) {
                Log.e("UpdateChecker", "Error checking for app updates", e)
                Handler(Looper.getMainLooper()).post {
                    listener.onUpdateCheckError("Ошибка проверки обновлений приложения: ${e.message}")
                }
            }
        }
    }

    private fun performAppUpdateCheck(currentVersion: String): UpdateInfo {
        val request = Request.Builder()
            .url(githubAppApiUrl)
            .addHeader("User-Agent", "WebastoHeater-App")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code}")
        }

        val responseBody = response.body?.string()
            ?: throw IOException("Empty response")

        val releases = JSONArray(responseBody as String)

        if (releases.length() == 0) {
            return UpdateInfo(
                hasUpdate = false,
                latestVersion = null,
                currentVersion = currentVersion,
                downloadUrl = null,
                releaseNotes = null,
                publishedAt = null
            )
        }

        // Получаем последний релиз (первый в массиве)
        val latestRelease = releases.getJSONObject(0)
        val latestVersion = extractVersionFromTag(latestRelease.getString("tag_name"))
        val hasUpdate = isNewerVersion(latestVersion, currentVersion)

        val downloadUrl = findApkDownloadUrl(latestRelease)
        val releaseNotes = latestRelease.optString("body", "")
        val publishedAt = latestRelease.optString("published_at", "")

        return UpdateInfo(
            hasUpdate = hasUpdate,
            latestVersion = latestVersion,
            currentVersion = currentVersion,
            downloadUrl = downloadUrl,
            releaseNotes = releaseNotes,
            publishedAt = publishedAt
        )
    }

    fun checkForFirmwareUpdates(currentFirmwareVersion: String?, listener: FirmwareUpdateCheckListener) {
        listener.onFirmwareUpdateCheckStarted()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val firmwareUpdateInfo = performFirmwareUpdateCheck(currentFirmwareVersion)
                Handler(Looper.getMainLooper()).post {
                    listener.onFirmwareUpdateCheckCompleted(firmwareUpdateInfo)
                }
            } catch (e: Exception) {
                Log.e("UpdateChecker", "Error checking for firmware updates", e)
                Handler(Looper.getMainLooper()).post {
                    listener.onFirmwareUpdateCheckError("Ошибка проверки обновлений прошивки: ${e.message}")
                }
            }
        }
    }

    private fun performFirmwareUpdateCheck(currentFirmwareVersion: String?): FirmwareUpdateInfo {
        val request = Request.Builder()
            .url(githubFirmwareApiUrl)
            .addHeader("User-Agent", "WebastoHeater-App")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code}")
        }

        val responseBody = response.body?.string()
            ?: throw IOException("Empty response")

        val releases = JSONArray(responseBody as String)

        if (releases.length() == 0) {
            return FirmwareUpdateInfo(
                hasUpdate = false,
                latestVersion = null,
                currentVersion = currentFirmwareVersion,
                downloadUrl = null,
                releaseNotes = null,
                publishedAt = null
            )
        }

        val latestRelease = releases.getJSONObject(0)
        val latestVersion = extractVersionFromTag(latestRelease.getString("tag_name"))
        val hasUpdate = if (currentFirmwareVersion != null) {
            isNewerVersion(latestVersion, currentFirmwareVersion)
        } else {
            true // Если текущая версия неизвестна, считаем, что есть обновление
        }

        val downloadUrl = findBinDownloadUrl(latestRelease)
        val releaseNotes = latestRelease.optString("body", "")
        val publishedAt = latestRelease.optString("published_at", "")

        return FirmwareUpdateInfo(
            hasUpdate = hasUpdate,
            latestVersion = latestVersion,
            currentVersion = currentFirmwareVersion,
            downloadUrl = downloadUrl,
            releaseNotes = releaseNotes,
            publishedAt = publishedAt
        )
    }

    private fun getCurrentVersion(): String {
        return try {
            val packageInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            Log.e("UpdateChecker", "Error getting current app version", e)
            "1.0.0"
        }
    }

    private fun extractVersionFromTag(tagName: String): String {
        // Убираем префикс 'v' если он есть
        return tagName.removePrefix("v").removePrefix("V")
    }

    fun isNewerVersion(latestVersion: String, currentVersion: String): Boolean {
        return try {
            val latestParts = latestVersion.split(".").map { it.toInt() }
            val currentParts = currentVersion.split(".").map { it.toInt() }

            for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
                val latestPart = latestParts.getOrElse(i) { 0 }
                val currentPart = currentParts.getOrElse(i) { 0 }

                if (latestPart > currentPart) {
                    return true
                } else if (latestPart < currentPart) {
                    return false
                }
            }
            false
        } catch (e: Exception) {
            Log.e("UpdateChecker", "Error comparing versions: $latestVersion vs $currentVersion", e)
            false
        }
    }

    private fun findApkDownloadUrl(release: JSONObject): String? {
        val assets = release.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val assetName = asset.getString("name")
            val downloadUrl = asset.getString("browser_download_url")

            // Ищем APK файл
            if (assetName.endsWith(".apk", ignoreCase = true)) {
                return downloadUrl
            }
        }
        return null
    }

    private fun findBinDownloadUrl(release: JSONObject): String? {
        val assets = release.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val assetName = asset.getString("name")
            val downloadUrl = asset.getString("browser_download_url")

            // Ищем .bin файл
            if (assetName.endsWith(".bin", ignoreCase = true)) {
                return downloadUrl
            }
        }
        return null
    }

    fun formatReleaseDate(dateString: String?): String {
        if (dateString.isNullOrEmpty()) return ""

        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            val outputFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            val date = inputFormat.parse(dateString) ?: return ""
            outputFormat.format(date)
        } catch (e: Exception) {
            Log.e("UpdateChecker", "Error formatting date", e)
            ""
        }
    }
}
