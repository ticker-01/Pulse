package com.example.pulse.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pulse.screen.PrimaryPurple
import com.example.pulse.screen.SurfaceDark
import com.example.pulse.screen.TextGray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val hasUpdate: Boolean,
    val versionName: String,
    val message: String,
    val downloadUrl: String,
    val versionCode: Int
)

object UpdateChecker {
    private const val VERSION_JSON_URL = "https://raw.githubusercontent.com/ticker-01/Pulse-Releases/main/app_version.json"

    suspend fun checkForUpdates(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }

            val url = URL(VERSION_JSON_URL)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                requestMethod = "GET"
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val latestCode = json.getInt("latest_version_code")

                if (latestCode > currentVersionCode) {
                    return@withContext UpdateInfo(
                        hasUpdate = true,
                        versionName = json.getString("latest_version_name"),
                        message = json.getString("update_message"),
                        downloadUrl = json.getString("download_url"),
                        versionCode = latestCode
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }

    fun openDownloadLink(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

@Composable
fun UpdateAvailableDialog(
    updateInfo: UpdateInfo,
    onDismiss: () -> Unit,
    onConfirmUpdate: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = onConfirmUpdate,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Скачать", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Позже", color = TextGray)
            }
        },
        modifier = Modifier.border(
            width = 1.dp,
            color = PrimaryPurple.copy(alpha = 0.35f),
            shape = RoundedCornerShape(24.dp)
        ),
        shape = RoundedCornerShape(24.dp),
        containerColor = SurfaceDark,
        title = {
            Text(
                text = "Доступно обновление ${updateInfo.versionName} ",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = updateInfo.message,
                color = TextGray,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }
    )
}