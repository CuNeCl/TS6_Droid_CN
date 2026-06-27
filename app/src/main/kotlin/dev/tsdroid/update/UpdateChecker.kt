package dev.tsdroid.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionName: String,
    val changelog: String,
    val downloadUrl: String,
    val apkSize: Long,
)

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val REPO = "YUAXI/TS6_Droid_CN"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"

    suspend fun checkForUpdate(currentVersionName: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL(API_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")

            if (conn.responseCode != 200) {
                conn.disconnect()
                return@withContext null
            }

            val json = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val obj = JSONObject(json)
            val tagName = obj.optString("tag_name", "")
            val versionName = tagName.removePrefix("v").removeSuffix("-Han")
            val body = obj.optString("body", "")

            if (!isNewerVersion(currentVersionName, versionName)) {
                return@withContext null
            }

            val assets = obj.getJSONArray("assets")
            if (assets.length() == 0) return@withContext null

            val apkAsset = assets.getJSONObject(0)
            val downloadUrl = apkAsset.optString("browser_download_url", "")
            val apkSize = apkAsset.optLong("size", 0L)

            if (downloadUrl.isBlank()) return@withContext null

            UpdateInfo(
                versionName = versionName,
                changelog = body,
                downloadUrl = downloadUrl,
                apkSize = apkSize,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            null
        }
    }

    private fun isNewerVersion(current: String, latest: String): Boolean {
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until maxLen) {
            val c = currentParts.getOrElse(i) { 0 }
            val l = latestParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    fun openDownload(context: Context, downloadUrl: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open download URL", e)
        }
    }
}
