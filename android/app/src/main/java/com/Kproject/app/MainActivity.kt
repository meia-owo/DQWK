package com.Kproject.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.getcapacitor.BridgeActivity
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

class MainActivity : BridgeActivity() {
    val REQUEST_MEDIA_PROJECTION = 1001
    val REQUEST_OVERLAY_PERMISSION = 1002
    val REQUEST_CAMERA_PERMISSION = 1003
    var pendingCall: PluginCall? = null

    // 現在の設定を保持
    var currentTargetKeywords = "！"
    var currentIsAutoBattleEnabled = true
    var currentTapOffsetX = 0f
    var currentTapOffsetY = 0f
    var currentScanInterval = 3000
    var currentEnableResultDetection = true
    var currentAutoBrightness = false
    var originalBrightness = -1f

    var batteryReceiver: BroadcastReceiver? = null
    private lateinit var sharedPrefs: SharedPreferences

    fun startBatteryMonitoring() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val batteryPct = if (level != -1 && scale != -1) (level * 100 / scale.toFloat()).toInt() else -1
                
                val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                
                // 温度取得 (0.1度単位なので10で割る)
                val temperature = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
                val tempCelsius = temperature / 10.0
                
                val data = JSObject()
                data.put("level", batteryPct)
                data.put("isCharging", isCharging)
                data.put("temperature", tempCelsius)
                
                val plugin = bridge.getPlugin("OverlayPlugin")?.instance as? OverlayPlugin
                plugin?.emitEvent("batteryStatusUpdate", data)
            }
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    fun getNetworkStatus(): JSObject {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val res = JSObject()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork
            val capabilities = cm.getNetworkCapabilities(network)
            if (capabilities != null) {
                when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                        res.put("type", "wifi")
                        var strength = 100
                        try {
                            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                            val wifiInfo = wifiManager.connectionInfo
                            if (wifiInfo != null) {
                                val level = android.net.wifi.WifiManager.calculateSignalLevel(wifiInfo.rssi, 100)
                                strength = level
                            }
                        } catch (e: Exception) {}
                        res.put("strength", strength)
                    }
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                        res.put("type", "cellular")
                        res.put("strength", 100)
                    }
                    else -> {
                        res.put("type", "other")
                        res.put("strength", 0)
                    }
                }
            } else {
                res.put("type", "none")
                res.put("strength", 0)
            }
        } else {
            val info = cm.activeNetworkInfo
            if (info != null && info.isConnected) {
                res.put("type", if (info.type == ConnectivityManager.TYPE_WIFI) "wifi" else "cellular")
                res.put("strength", 100)
            } else {
                res.put("type", "none")
                res.put("strength", 0)
            }
        }
        return res
    }

    fun loadSettings() {
        sharedPrefs = getSharedPreferences("OverlaySettings", Context.MODE_PRIVATE)
        currentTargetKeywords = sharedPrefs.getString("targetKeywords", "！") ?: "！"
        currentIsAutoBattleEnabled = sharedPrefs.getBoolean("isAutoBattleEnabled", true)
        currentTapOffsetX = sharedPrefs.getFloat("tapOffsetX", 0f)
        currentTapOffsetY = sharedPrefs.getFloat("tapOffsetY", 0f)
        currentScanInterval = sharedPrefs.getInt("scanInterval", 3000)
        currentEnableResultDetection = sharedPrefs.getBoolean("enableResultDetection", true)
        currentAutoBrightness = sharedPrefs.getBoolean("autoBrightness", false)
    }

    fun saveSettings() {
        sharedPrefs.edit().apply {
            putString("targetKeywords", currentTargetKeywords)
            putBoolean("isAutoBattleEnabled", currentIsAutoBattleEnabled)
            putFloat("tapOffsetX", currentTapOffsetX)
            putFloat("tapOffsetY", currentTapOffsetY)
            putInt("scanInterval", currentScanInterval)
            putBoolean("enableResultDetection", currentEnableResultDetection)
            putBoolean("autoBrightness", currentAutoBrightness)
            apply()
        }
    }

    private val statsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.Kproject.app.UPDATE_STATS") {
                val kills = intent.getIntExtra("kills", 0)
                val exp = intent.getIntExtra("exp", 0)
                val expGain = intent.getIntExtra("expGain", 0)
                val log = intent.getStringExtra("log")
                
                val data = JSObject()
                data.put("kills", kills)
                data.put("exp", exp)
                data.put("expGain", expGain)
                if (log != null) data.put("log", log)
                
                val plugin = bridge.getPlugin("OverlayPlugin")?.instance as? OverlayPlugin
                plugin?.emitEvent("statsUpdate", data)
            }
        }
    }

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.Kproject.app.UPDATE_LOG") {
                val message = intent.getStringExtra("message") ?: ""
                val type = intent.getStringExtra("type") ?: "info"
                val image = intent.getStringExtra("image")
                
                val data = JSObject()
                data.put("message", message)
                data.put("type", type)
                if (image != null) data.put("image", image)
                
                val plugin = bridge.getPlugin("OverlayPlugin")?.instance as? OverlayPlugin
                plugin?.emitEvent("logUpdate", data)
            }
        }
    }

    private val toastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.Kproject.app.SHOW_TOAST") {
                val message = intent.getStringExtra("message") ?: ""
                val type = intent.getStringExtra("type") ?: "info"
                
                val data = JSObject()
                data.put("message", message)
                data.put("type", type)
                
                val plugin = bridge.getPlugin("OverlayPlugin")?.instance as? OverlayPlugin
                plugin?.emitEvent("toastMessage", data)
            }
        }
    }

    private val scanAreaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.Kproject.app.UPDATE_SCAN_AREA") {
                val x = intent.getIntExtra("x", 0)
                val y = intent.getIntExtra("y", 0)
                val w = intent.getIntExtra("w", 0)
                val h = intent.getIntExtra("h", 0)
                
                val data = JSObject()
                data.put("x", x)
                data.put("y", y)
                data.put("w", w)
                data.put("h", h)
                
                val plugin = bridge.getPlugin("OverlayPlugin")?.instance as? OverlayPlugin
                plugin?.emitEvent("scanAreaUpdate", data)
            }
        }
    }

    private val calibrationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.Kproject.app.CALIBRATION_FINISHED") {
                val type = intent.getStringExtra("type") ?: ""
                val xPct = intent.getFloatExtra("xPct", 0.5f)
                val yPct = intent.getFloatExtra("yPct", 0.5f)
                val r = intent.getIntExtra("colorR", 0)
                val g = intent.getIntExtra("colorG", 0)
                val b = intent.getIntExtra("colorB", 0)
                
                val data = JSObject()
                data.put("type", type)
                data.put("xPct", xPct.toDouble())
                data.put("yPct", yPct.toDouble())
                val colorArr = org.json.JSONArray()
                colorArr.put(r)
                colorArr.put(g)
                colorArr.put(b)
                data.put("color", colorArr)
                
                val plugin = bridge.getPlugin("OverlayPlugin")?.instance as? OverlayPlugin
                plugin?.emitEvent("calibrationFinished", data)
            }
        }
    }

    private val toggleStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.Kproject.app.UPDATE_TOGGLE_STATE") {
                currentIsAutoBattleEnabled = intent.getBooleanExtra("isAutoBattleEnabled", true)
                saveSettings()
                
                val data = JSObject()
                data.put("isAutoBattleEnabled", currentIsAutoBattleEnabled)
                
                val plugin = bridge.getPlugin("OverlayPlugin")?.instance as? OverlayPlugin
                plugin?.emitEvent("toggleStateUpdate", data)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadSettings()
        registerPlugin(OverlayPlugin::class.java)
        startBatteryMonitoring()
        
        val filterStats = IntentFilter("com.Kproject.app.UPDATE_STATS")
        val filterLog = IntentFilter("com.Kproject.app.UPDATE_LOG")
        val filterToast = IntentFilter("com.Kproject.app.SHOW_TOAST")
        val filterScanArea = IntentFilter("com.Kproject.app.UPDATE_SCAN_AREA")
        val filterToggle = IntentFilter("com.Kproject.app.UPDATE_TOGGLE_STATE")
        val filterCalibration = IntentFilter("com.Kproject.app.CALIBRATION_FINISHED")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statsReceiver, filterStats, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(logReceiver, filterLog, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(toastReceiver, filterToast, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(scanAreaReceiver, filterScanArea, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(toggleStateReceiver, filterToggle, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(calibrationReceiver, filterCalibration, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statsReceiver, filterStats)
            registerReceiver(logReceiver, filterLog)
            registerReceiver(toastReceiver, filterToast)
            registerReceiver(scanAreaReceiver, filterScanArea)
            registerReceiver(toggleStateReceiver, filterToggle)
            registerReceiver(calibrationReceiver, filterCalibration)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        batteryReceiver?.let { unregisterReceiver(it) }
        unregisterReceiver(statsReceiver)
        unregisterReceiver(logReceiver)
        unregisterReceiver(toastReceiver)
        unregisterReceiver(scanAreaReceiver)
        unregisterReceiver(toggleStateReceiver)
        unregisterReceiver(calibrationReceiver)
    }

    fun isAccessibilityServiceEnabled(context: Context, service: Class<out AccessibilityService>): Boolean {
        val expectedComponentName = ComponentName(context, service)
        val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == expectedComponentName) return true
        }
        return false
    }

    fun showAccessibilityDialog() {
        runOnUiThread {
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Accessibility Service Required")
                .setMessage("Please enable the DQWEXP Accessibility Service to allow auto-tapping.")
                .setPositiveButton("Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent()
            val packageName = packageName
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }
    }

    @CapacitorPlugin(name = "OverlayPlugin")
    class OverlayPlugin : Plugin() {
        private val mainActivity: MainActivity
            get() = activity as MainActivity
        fun emitEvent(eventName: String, data: JSObject) {
            notifyListeners(eventName, data)
        }

        @PluginMethod
        fun startCalibration(call: PluginCall) {
            val type = call.getString("type") ?: "anchor"
            val intent = Intent("com.Kproject.app.START_CALIBRATION")
            intent.putExtra("type", type)
            context.sendBroadcast(intent)
            call.resolve()
        }

        @PluginMethod
        fun stopOverlay(call: PluginCall) {
            val intent = Intent(mainActivity, OverlayService::class.java)
            mainActivity.stopService(intent)
            call.resolve()
        }

        @PluginMethod
        fun takeScreenshot(call: PluginCall) {
            val base64 = OverlayService.instance?.takeScreenshot()
            if (base64 != null) {
                val res = JSObject()
                res.put("base64", base64)
                call.resolve(res)
            } else {
                call.reject("Failed to take screenshot or service not running")
            }
        }

        @PluginMethod
        fun getStatus(call: PluginCall) {
            val res = JSObject()
            res.put("overlayPermission", Settings.canDrawOverlays(mainActivity))
            res.put("accessibilityService", mainActivity.isAccessibilityServiceEnabled(mainActivity, AutoTapService::class.java))
            res.put("isServiceRunning", isServiceRunning(OverlayService::class.java))
            res.put("cameraPermission", ContextCompat.checkSelfPermission(mainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            
            val pm = mainActivity.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                res.put("batteryOptimizationExempt", pm.isIgnoringBatteryOptimizations(mainActivity.packageName))
            } else {
                res.put("batteryOptimizationExempt", true)
            }

            res.put("network", mainActivity.getNetworkStatus())
            
            call.resolve(res)
        }

        @PluginMethod
        fun requestPermission(call: PluginCall) {
            val type = call.getString("type")
            when (type) {
                "overlay" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(mainActivity)) {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + mainActivity.packageName)
                        )
                        mainActivity.startActivity(intent)
                    }
                }
                "accessibility" -> {
                    mainActivity.showAccessibilityDialog()
                }
                "batteryOptimization" -> {
                    mainActivity.requestBatteryOptimizationExemption()
                }
                "camera" -> {
                    if (ContextCompat.checkSelfPermission(mainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(mainActivity, arrayOf(Manifest.permission.CAMERA), mainActivity.REQUEST_CAMERA_PERMISSION)
                    }
                }
                "screenCapture" -> {
                    mainActivity.startMediaProjection(call)
                    return
                }
            }
            call.resolve()
        }

        fun isServiceRunning(serviceClass: Class<*>): Boolean {
            val manager = mainActivity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            for (service in manager.getRunningServices(Int.MAX_VALUE)) {
                if (serviceClass.name == service.service.className) {
                    return true
                }
            }
            return false
        }

        @PluginMethod
        fun startOverlay(call: PluginCall) {
            mainActivity.requestBatteryOptimizationExemption()
            
            if (!mainActivity.isAccessibilityServiceEnabled(mainActivity, AutoTapService::class.java)) {
                mainActivity.showAccessibilityDialog()
                call.reject("Accessibility service not enabled")
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(mainActivity)) {
                mainActivity.pendingCall = call
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + mainActivity.packageName)
                )
                mainActivity.startActivityForResult(intent, mainActivity.REQUEST_OVERLAY_PERMISSION)
            } else {
                mainActivity.startMediaProjection(call)
            }
        }

        @PluginMethod
        fun performTap(call: PluginCall) {
            val xPct = call.getDouble("x", 0.5) ?: 0.5
            val yPct = call.getDouble("y", 0.5) ?: 0.5
            
            val screenMetrics = android.util.DisplayMetrics()
            mainActivity.windowManager.defaultDisplay.getRealMetrics(screenMetrics)
            val tapX = (screenMetrics.widthPixels * xPct).toFloat()
            val tapY = (screenMetrics.heightPixels * yPct).toFloat()
            
            AutoTapService.instance?.performTap(tapX, tapY)
            call.resolve()
        }

        @PluginMethod
        fun updateSettings(call: PluginCall) {
            mainActivity.currentTargetKeywords = call.getString("targetKeywords", "！") ?: "！"
            mainActivity.currentIsAutoBattleEnabled = call.getBoolean("isAutoBattleEnabled", true) ?: true
            mainActivity.currentTapOffsetX = call.getDouble("tapOffsetX", 0.0)?.toFloat() ?: 0f
            mainActivity.currentTapOffsetY = call.getDouble("tapOffsetY", 0.0)?.toFloat() ?: 0f
            mainActivity.currentScanInterval = call.getInt("scanInterval", 3000) ?: 3000
            mainActivity.currentEnableResultDetection = call.getBoolean("enableResultDetection", true) ?: true
            mainActivity.currentAutoBrightness = call.getBoolean("autoBrightness", false) ?: false
            val targetPot = call.getBoolean("targetPot", true) ?: true
            val enablePotFilter = call.getBoolean("enablePotFilter", true) ?: true
            val targetHokora = call.getBoolean("targetHokora", true) ?: true

            mainActivity.saveSettings()

            val intent = Intent("com.Kproject.app.UPDATE_SETTINGS")
            intent.putExtra("targetKeywords", mainActivity.currentTargetKeywords)
            intent.putExtra("targetPot", targetPot)
            intent.putExtra("enablePotFilter", enablePotFilter)
            intent.putExtra("targetHokora", targetHokora)
            intent.putExtra("isAutoBattleEnabled", mainActivity.currentIsAutoBattleEnabled)
            intent.putExtra("tapOffsetX", mainActivity.currentTapOffsetX)
            intent.putExtra("tapOffsetY", mainActivity.currentTapOffsetY)
            intent.putExtra("scanInterval", mainActivity.currentScanInterval)
            intent.putExtra("enableResultDetection", mainActivity.currentEnableResultDetection)
            intent.putExtra("autoBrightness", mainActivity.currentAutoBrightness)
            
            // Calibration values
            call.getDouble("unlockBtnX")?.let { intent.putExtra("unlockBtnX", it.toFloat()) }
            call.getDouble("unlockBtnY")?.let { intent.putExtra("unlockBtnY", it.toFloat()) }
            call.getDouble("charCenterX")?.let { intent.putExtra("charCenterX", it.toFloat()) }
            call.getDouble("charCenterY")?.let { intent.putExtra("charCenterY", it.toFloat()) }
            call.getDouble("circleRadius")?.let { intent.putExtra("circleRadius", it.toFloat()) }
            
            // 追加の設定
            call.getInt("ocrRetryCount")?.let { intent.putExtra("ocrRetryCount", it) }
            call.getDouble("ocrWaitTime")?.let { intent.putExtra("ocrWaitTime", it.toFloat()) }
            call.getBoolean("enableAnchorSearch")?.let { intent.putExtra("enableAnchorSearch", it) }
            call.getInt("wideScanArea")?.let { intent.putExtra("wideScanArea", it) }
            call.getBoolean("enableRegexFilter")?.let { intent.putExtra("enableRegexFilter", it) }
            call.getBoolean("enableDictCorrection")?.let { intent.putExtra("enableDictCorrection", it) }
            call.getBoolean("pauseScanOnBattle")?.let { intent.putExtra("pauseScanOnBattle", it) }
            call.getBoolean("enablePartyScan")?.let { intent.putExtra("enablePartyScan", it) }
            
            call.getBoolean("isBerserkerMode")?.let { intent.putExtra("isBerserkerMode", it) }
            call.getDouble("berserkerIconX")?.let { intent.putExtra("berserkerIconX", it.toFloat()) }
            call.getDouble("berserkerIconY")?.let { intent.putExtra("berserkerIconY", it.toFloat()) }
            call.getDouble("berserkerItemX")?.let { intent.putExtra("berserkerItemX", it.toFloat()) }
            call.getDouble("berserkerItemY")?.let { intent.putExtra("berserkerItemY", it.toFloat()) }
            call.getInt("berserkerInterval")?.let { intent.putExtra("berserkerInterval", it.toLong()) }
            
            val colorArray = call.getArray("unlockBtnColor")
            if (colorArray != null && colorArray.length() == 3) {
                try {
                    val r = colorArray.getInt(0)
                    val g = colorArray.getInt(1)
                    val b = colorArray.getInt(2)
                    intent.putExtra("unlockBtnColor", intArrayOf(r, g, b))
                } catch (e: Exception) {
                    // Ignore
                }
            }

            listOf("normalEnemyColor", "strongEnemyColor", "eventPopColor", "potColor", "hokoraColor").forEach { key ->
                val arr = call.getArray(key)
                if (arr != null && arr.length() == 3) {
                    try {
                        intent.putExtra(key, intArrayOf(arr.getInt(0), arr.getInt(1), arr.getInt(2)))
                    } catch (e: Exception) {}
                }
            }
            
            call.getDouble("pullMargin")?.let { intent.putExtra("pullMargin", it.toFloat()) }
            call.getString("appStatus")?.let { intent.putExtra("appStatus", it) }

            context.sendBroadcast(intent)
            call.resolve()
        }
    }

    fun startMediaProjection(call: PluginCall) {
        pendingCall = call
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                pendingCall?.let { startMediaProjection(it) }
            } else {
                pendingCall?.reject("Overlay permission denied")
                pendingCall = null
            }
        } else if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                val intent = Intent(this, OverlayService::class.java)
                intent.putExtra("EXTRA_RESULT_CODE", resultCode)
                intent.putExtra("EXTRA_RESULT_DATA", data)
                // 起動時に現在の設定を渡す
                intent.putExtra("targetKeywords", currentTargetKeywords)
                intent.putExtra("isAutoBattleEnabled", currentIsAutoBattleEnabled)
                intent.putExtra("tapOffsetX", currentTapOffsetX)
                intent.putExtra("tapOffsetY", currentTapOffsetY)
                intent.putExtra("scanInterval", currentScanInterval)
                intent.putExtra("enableResultDetection", currentEnableResultDetection)

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    pendingCall?.resolve()
                } catch (e: Exception) {
                    pendingCall?.reject("Failed to start service: ${e.message}")
                }
            } else {
                pendingCall?.reject("Media projection permission denied or cancelled")
            }
            pendingCall = null
        }
    }
}
