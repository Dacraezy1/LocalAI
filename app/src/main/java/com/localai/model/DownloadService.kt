package com.localai.model

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.localai.R
import com.localai.ui.ModelManagerActivity
import kotlinx.coroutines.*
import okhttp3.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class DownloadService : Service() {

    companion object {
        const val ACTION_DOWNLOAD  = "com.localai.DOWNLOAD"
        const val ACTION_CANCEL    = "com.localai.CANCEL"
        const val EXTRA_URL        = "url"
        const val EXTRA_FILE_NAME  = "file_name"
        const val EXTRA_MODEL_ID   = "model_id"
        const val CHANNEL_ID       = "download_channel"
        const val NOTIF_ID         = 1001

        // Broadcast actions for UI updates
        const val BROADCAST_PROGRESS   = "com.localai.DOWNLOAD_PROGRESS"
        const val BROADCAST_DONE       = "com.localai.DOWNLOAD_DONE"
        const val BROADCAST_ERROR      = "com.localai.DOWNLOAD_ERROR"
        const val EXTRA_PROGRESS       = "progress"
        const val EXTRA_SPEED_KBPS     = "speed_kbps"
        const val EXTRA_ETA_SECS       = "eta_secs"
        const val EXTRA_DOWNLOADED_PATH= "downloaded_path"
        const val EXTRA_ERROR_MSG      = "error_msg"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentCall: Call? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)  // streaming download
        .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DOWNLOAD -> {
                val url      = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: return START_NOT_STICKY
                val modelId  = intent.getStringExtra(EXTRA_MODEL_ID) ?: ""
                startForeground(NOTIF_ID, buildNotification("Preparing download…", 0))
                scope.launch { download(url, fileName, modelId) }
            }
            ACTION_CANCEL -> {
                currentCall?.cancel()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun download(url: String, fileName: String, modelId: String) {
        val dir = getExternalFilesDir("models") ?: filesDir.resolve("models").also { it.mkdirs() }
        val outFile = File(dir, fileName)
        val tempFile = File(dir, "$fileName.tmp")

        // Support resuming
        val resumeFrom = if (tempFile.exists()) tempFile.length() else 0L
        val request = Request.Builder()
            .url(url)
            .apply { if (resumeFrom > 0) addHeader("Range", "bytes=$resumeFrom-") }
            .build()

        try {
            val response = withContext(Dispatchers.IO) {
                val call = client.newCall(request)
                currentCall = call
                call.execute()
            }

            if (!response.isSuccessful && response.code != 206) {
                broadcastError("HTTP ${response.code}")
                stopSelf()
                return
            }

            val body = response.body ?: run { broadcastError("Empty body"); stopSelf(); return }
            val totalBytes = (body.contentLength() + resumeFrom).let { if (it <= 0) -1L else it }

            withContext(Dispatchers.IO) {
                FileOutputStream(tempFile, resumeFrom > 0).use { out ->
                    val buffer = ByteArray(8 * 1024)
                    var downloaded = resumeFrom
                    var read: Int
                    var lastTime = System.currentTimeMillis()
                    var lastBytes = downloaded

                    body.byteStream().use { input ->
                        while (input.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                            downloaded += read

                            val now = System.currentTimeMillis()
                            if (now - lastTime >= 500) {
                                val elapsed = (now - lastTime) / 1000f
                                val speed = ((downloaded - lastBytes) / elapsed / 1024).toLong()
                                val progress = if (totalBytes > 0)
                                    ((downloaded * 100) / totalBytes).toInt() else -1
                                val eta = if (totalBytes > 0 && speed > 0)
                                    ((totalBytes - downloaded) / 1024 / speed).toInt() else -1

                                lastTime = now
                                lastBytes = downloaded

                                updateNotification(fileName, progress)
                                broadcastProgress(progress, speed, eta)
                            }
                        }
                    }
                }
            }

            tempFile.renameTo(outFile)
            broadcastDone(outFile.absolutePath, modelId)
            updateNotification(fileName, 100)

        } catch (e: IOException) {
            if (e.message?.contains("cancel") == true) {
                Log.i("DownloadService", "Download cancelled")
            } else {
                broadcastError(e.message ?: "Unknown error")
            }
        } finally {
            stopSelf()
        }
    }

    private fun broadcastProgress(progress: Int, speedKbps: Long, etaSecs: Int) {
        sendBroadcast(Intent(BROADCAST_PROGRESS).apply {
            putExtra(EXTRA_PROGRESS, progress)
            putExtra(EXTRA_SPEED_KBPS, speedKbps)
            putExtra(EXTRA_ETA_SECS, etaSecs)
        })
    }

    private fun broadcastDone(path: String, modelId: String) {
        sendBroadcast(Intent(BROADCAST_DONE).apply {
            putExtra(EXTRA_DOWNLOADED_PATH, path)
            putExtra(EXTRA_MODEL_ID, modelId)
        })
    }

    private fun broadcastError(msg: String) {
        sendBroadcast(Intent(BROADCAST_ERROR).apply {
            putExtra(EXTRA_ERROR_MSG, msg)
        })
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Model Downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "GGUF model download progress" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, progress: Int): Notification {
        val cancelIntent = PendingIntent.getService(
            this, 0,
            Intent(this, DownloadService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, ModelManagerActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LocalAI - Downloading model")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, progress < 0)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Cancel", cancelIntent)
            .build()
    }

    private fun updateNotification(fileName: String, progress: Int) {
        val text = if (progress >= 0) "$progress%" else "Downloading…"
        val notif = buildNotification(text, progress)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
