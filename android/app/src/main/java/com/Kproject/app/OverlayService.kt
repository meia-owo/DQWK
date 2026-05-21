package com.Kproject.app

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class OverlayService : Service() {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "KAI_Channel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "OverlayService"

        const val ACTION_STOP_SERVICE = "com.Kproject.app.ACTION_STOP_SERVICE"
        const val ACTION_TOGGLE_AUTO = "com.Kproject.app.ACTION_TOGGLE_AUTO"
        
        val EXCLUDE_REGEX = Regex("[^a-z0-9!|壺つぼツボ回復ほこら祠攻撃経験値かくとくp\\+P]")
        var instance: OverlayService? = null
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var params: WindowManager.LayoutParams

    private var scanAreaView: View? = null
    private var scanAreaParams: WindowManager.LayoutParams? = null
    private var isScanAreaVisible = false

    private var tapPointView: ImageView? = null
    private var tapPointParams: WindowManager.LayoutParams? = null
    private var isTapPointVisible = false
    private var isProcessing = false
    private var retryCounter = 0
    private var isAutoBattleActive = false

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var lastCapturedBase64: String? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    
    // ボトルネック対策6: スレッドプールの導入 (毎回のThread{}.start()によるGC負荷を回避)
    private val ocrExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private lateinit var tessBaseAPI: TessBaseAPI
    private var isOcrInitialized = false

    private var isAutoBattleEnabled = true
    private var isCollapsed = false
    private var totalKills = 0
    private var totalExp = 0
    private var targetKeywords = listOf("！")
    private var normalEnemyColor: IntArray? = null
    private var strongEnemyColor: IntArray? = null
    private var eventPopColor: IntArray? = null
    private var potColor: IntArray? = null
    private var hokoraColor: IntArray? = null
    private var targetPot = true
    private var enablePotFilter = true
    private var targetHokora = true
    private var tapOffsetX = 0f
    private var tapOffsetY = 0f
    private var unlockBtnPos = Pair(0.5f, 0.92f)
    private var charCenterPos = Pair(0.5f, 0.6f)
    private var resultBtnPos: Pair<Float, Float>? = null
    private var circleRadius = 0.25f
    private var scanInterval = 3000L
    private var enableResultDetection = true
    private var ocrRetryCount = 3
    private var ocrWaitTime = 1.5f
    private var enableAnchorSearch = true
    private var wideScanArea = 75
    private var enableRegexFilter = true
    private var enableDictCorrection = true
    private var pauseScanOnBattle = true
    private var enablePartyScan = true
    private var autoBrightness = false
    
    // New variables for parity
    private var isBerserkerMode = false
    private var berserkerIconX = 0.1f
    private var berserkerIconY = 0.8f
    private var berserkerItemX = 0.5f
    private var berserkerItemY = 0.5f
    private var berserkerInterval = 300000L
    private var lastBerserkerTime = 0L
    
    private var unlockBtnColor: IntArray? = null
    private var pullMargin = 1.2f
    private var appStatus = "WAIT"

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var dimOverlayView: View? = null

    private var isDebugMode = false
    private var isScreenOn = true

    private lateinit var notificationManager: NotificationManager

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.Kproject.app.UPDATE_SETTINGS" -> {
                    val keywordsStr = intent.getStringExtra("targetKeywords") ?: "！"
                    targetKeywords = keywordsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    
                    if (intent.hasExtra("normalEnemyColor")) normalEnemyColor = intent.getIntArrayExtra("normalEnemyColor")
                    if (intent.hasExtra("strongEnemyColor")) strongEnemyColor = intent.getIntArrayExtra("strongEnemyColor")
                    if (intent.hasExtra("eventPopColor")) eventPopColor = intent.getIntArrayExtra("eventPopColor")
                    if (intent.hasExtra("potColor")) potColor = intent.getIntArrayExtra("potColor")
                    if (intent.hasExtra("hokoraColor")) hokoraColor = intent.getIntArrayExtra("hokoraColor")

                    targetPot = intent.getBooleanExtra("targetPot", true)
                    enablePotFilter = intent.getBooleanExtra("enablePotFilter", true)
                    targetHokora = intent.getBooleanExtra("targetHokora", true)
                    
                    unlockBtnPos = Pair(
                        intent.getFloatExtra("unlockBtnX", 0.5f),
                        intent.getFloatExtra("unlockBtnY", 0.92f)
                    )
                    charCenterPos = Pair(
                        intent.getFloatExtra("charCenterX", 0.5f),
                        intent.getFloatExtra("charCenterY", 0.6f)
                    )
                    
                    val resX = intent.getFloatExtra("resultBtnX", -1f)
                    val resY = intent.getFloatExtra("resultBtnY", -1f)
                    if (resX >= 0f && resY >= 0f) {
                        resultBtnPos = Pair(resX, resY)
                    }

                    circleRadius = intent.getFloatExtra("circleRadius", 0.25f)

                    isAutoBattleEnabled = intent.getBooleanExtra("isAutoBattleEnabled", true)
                    tapOffsetX = intent.getFloatExtra("tapOffsetX", 0f)
                    tapOffsetY = intent.getFloatExtra("tapOffsetY", 0f)
                    scanInterval = intent.getIntExtra("scanInterval", 3000).toLong()
                    enableResultDetection = intent.getBooleanExtra("enableResultDetection", true)
                    isDebugMode = intent.getBooleanExtra("isDebugMode", false)
                    
                    ocrRetryCount = intent.getIntExtra("ocrRetryCount", 3)
                    ocrWaitTime = intent.getFloatExtra("ocrWaitTime", 1.5f)
                    enableAnchorSearch = intent.getBooleanExtra("enableAnchorSearch", true)
                    wideScanArea = intent.getIntExtra("wideScanArea", 75)
                    enableRegexFilter = intent.getBooleanExtra("enableRegexFilter", true)
                    enableDictCorrection = intent.getBooleanExtra("enableDictCorrection", true)
                    pauseScanOnBattle = intent.getBooleanExtra("pauseScanOnBattle", true)
                    enablePartyScan = intent.getBooleanExtra("enablePartyScan", true)
                    autoBrightness = intent.getBooleanExtra("autoBrightness", false)
                    
                    isBerserkerMode = intent.getBooleanExtra("isBerserkerMode", false)
                    berserkerIconX = intent.getFloatExtra("berserkerIconX", 0.1f)
                    berserkerIconY = intent.getFloatExtra("berserkerIconY", 0.8f)
                    berserkerItemX = intent.getFloatExtra("berserkerItemX", 0.5f)
                    berserkerItemY = intent.getFloatExtra("berserkerItemY", 0.5f)
                    berserkerInterval = intent.getLongExtra("berserkerInterval", 300000L)
                    
                    if (intent.hasExtra("unlockBtnColor")) {
                        unlockBtnColor = intent.getIntArrayExtra("unlockBtnColor")
                    }
                    
                    pullMargin = intent.getFloatExtra("pullMargin", 1.2f)
                    appStatus = intent.getStringExtra("appStatus") ?: "WAIT"
                    
                    updateDimmingOverlay()
                    
                    // Update UI
                    overlayView.findViewById<TextView>(R.id.tv_target_word).text = "TARGET: ${targetKeywords.firstOrNull() ?: "！"}"
                    val btnAutoBattle = overlayView.findViewById<TextView>(R.id.btn_auto_battle)
                    btnAutoBattle.text = if (isAutoBattleEnabled) "Auto Battle: ON" else "Auto Battle: OFF"
                    btnAutoBattle.setBackgroundResource(if (isAutoBattleEnabled) R.drawable.bg_button_active else R.drawable.bg_button_inactive)
                    btnAutoBattle.setTextColor(if (isAutoBattleEnabled) Color.parseColor("#10B981") else Color.parseColor("#9CA3AF"))
                    
                    overlayView.findViewById<LinearLayout>(R.id.layout_debug).visibility = if (isDebugMode) View.VISIBLE else View.GONE
                    updateNotification()
                }
                "com.Kproject.app.START_CALIBRATION" -> {
                    val calType = intent.getStringExtra("type") ?: "anchor"
                    startCalibrationUI(calType)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    Log.d(TAG, "Screen OFF: Pausing OCR")
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    Log.d(TAG, "Screen ON: Resuming OCR")
                }
            }
        }
    }

    private val timeUpdater = object : Runnable {
        override fun run() {
            updateTime()
            handler.postDelayed(this, 1000)
        }
    }

    private val berserkerUpdater = object : Runnable {
        override fun run() {
            if (isBerserkerMode && appStatus == "WALK_MODE" && isAutoBattleEnabled && isScreenOn) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastBerserkerTime >= berserkerInterval) {
                    lastBerserkerTime = currentTime
                    broadcastLog("Berserker Mode: Using item", "info")
                    
                    val iconX = (screenWidth * berserkerIconX)
                    val iconY = (screenHeight * berserkerIconY)
                    val itemX = (screenWidth * berserkerItemX)
                    val itemY = (screenHeight * berserkerItemY)
                    
                    // Tap icon
                    AutoTapService.instance?.performTap(iconX, iconY)
                    
                    // Wait and tap item
                    handler.postDelayed({
                        AutoTapService.instance?.performTap(itemX, itemY)
                        
                        // Wait and tap again to confirm (optional, depending on game UI)
                        handler.postDelayed({
                            AutoTapService.instance?.performTap(itemX, itemY)
                        }, 500)
                    }, 1000)
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    private val ocrUpdater = object : Runnable {
        override fun run() {
            if (isAutoBattleEnabled && isOcrInitialized && enableResultDetection && isScreenOn && !isProcessing) {
                if (pauseScanOnBattle && isAutoBattleActive) {
                    handler.postDelayed(this, scanInterval)
                    return
                }
                try {
                    captureAndRecognize()
                } catch (e: Exception) {
                    Log.e(TAG, "OCR Error: ${e.message}")
                    isProcessing = false
                }
            }
            // インターバルが極端に短い場合の安全策
            val safeInterval = scanInterval.coerceAtLeast(500L)
            handler.postDelayed(this, safeInterval)
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection stopped")
            handler.post {
                isAutoBattleEnabled = false
                overlayView.findViewById<TextView>(R.id.btn_auto_battle).text = "Auto Battle: OFF"
                overlayView.findViewById<TextView>(R.id.btn_auto_battle).setBackgroundResource(R.drawable.bg_button_inactive)
                overlayView.findViewById<TextView>(R.id.btn_auto_battle).setTextColor(Color.parseColor("#9CA3AF"))
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenMetrics()
        setupVirtualDisplay()
    }

    private fun updateScreenMetrics() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    override fun onBind(intent: Intent?): IBinder? { return null }

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        val prefs = getSharedPreferences("OverlaySettings", Context.MODE_PRIVATE)
        isAutoBattleEnabled = prefs.getBoolean("isAutoBattleEnabled", true)
        
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KAI:OverlayWakeLock")
        wakeLock?.acquire()

        val filter = IntentFilter().apply {
            addAction("com.Kproject.app.UPDATE_SETTINGS")
            addAction("com.Kproject.app.START_CALIBRATION")
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(settingsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(settingsReceiver, filter)
        }

        startForegroundServiceWithNotification()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlayView = inflater.inflate(R.layout.overlay_layout, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 150
        }

        windowManager.addView(overlayView, params)
        setupUI()
        setupDragWithUpdate(overlayView, params, R.id.drag_handle)

        updateScreenMetrics()

        Thread { initTesseract() }.start()
        handler.post(timeUpdater)
        handler.post(berserkerUpdater)
        handler.postDelayed(ocrUpdater, 3000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            when (intent.action) {
                ACTION_STOP_SERVICE -> {
                    stopSelf()
                    return START_NOT_STICKY
                }
                ACTION_TOGGLE_AUTO -> {
                    isAutoBattleEnabled = !isAutoBattleEnabled
                    updateNotification()
                    broadcastLog("Auto Battle toggled via notification: $isAutoBattleEnabled", "info")
                    // UI update if needed
                    handler.post {
                        val btnAutoBattle = overlayView.findViewById<TextView>(R.id.btn_auto_battle)
                        btnAutoBattle.text = if (isAutoBattleEnabled) "Auto Battle: ON" else "Auto Battle: OFF"
                        btnAutoBattle.setBackgroundResource(if (isAutoBattleEnabled) R.drawable.bg_button_active else R.drawable.bg_button_inactive)
                        btnAutoBattle.setTextColor(if (isAutoBattleEnabled) Color.parseColor("#10B981") else Color.parseColor("#9CA3AF"))
                    }
                    return START_NOT_STICKY
                }
            }

            // 初期設定の受け取り
            if (intent.hasExtra("targetKeywords")) {
                val keywordsStr = intent.getStringExtra("targetKeywords") ?: "！"
                targetKeywords = keywordsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                
                if (intent.hasExtra("normalEnemyColor")) normalEnemyColor = intent.getIntArrayExtra("normalEnemyColor")
                if (intent.hasExtra("strongEnemyColor")) strongEnemyColor = intent.getIntArrayExtra("strongEnemyColor")
                if (intent.hasExtra("eventPopColor")) eventPopColor = intent.getIntArrayExtra("eventPopColor")
                if (intent.hasExtra("potColor")) potColor = intent.getIntArrayExtra("potColor")
                if (intent.hasExtra("hokoraColor")) hokoraColor = intent.getIntArrayExtra("hokoraColor")

                isAutoBattleEnabled = intent.getBooleanExtra("isAutoBattleEnabled", true)
                tapOffsetX = intent.getFloatExtra("tapOffsetX", 0f)
                tapOffsetY = intent.getFloatExtra("tapOffsetY", 0f)
                scanInterval = intent.getIntExtra("scanInterval", 3000).toLong()
                enableResultDetection = intent.getBooleanExtra("enableResultDetection", true)
                
                ocrRetryCount = intent.getIntExtra("ocrRetryCount", 3)
                ocrWaitTime = intent.getFloatExtra("ocrWaitTime", 1.5f)
                enableAnchorSearch = intent.getBooleanExtra("enableAnchorSearch", true)
                wideScanArea = intent.getIntExtra("wideScanArea", 75)
                enableRegexFilter = intent.getBooleanExtra("enableRegexFilter", true)
                enableDictCorrection = intent.getBooleanExtra("enableDictCorrection", true)
                pauseScanOnBattle = intent.getBooleanExtra("pauseScanOnBattle", true)
                targetPot = intent.getBooleanExtra("targetPot", true)
                enablePotFilter = intent.getBooleanExtra("enablePotFilter", true)
                targetHokora = intent.getBooleanExtra("targetHokora", true)
                enablePartyScan = intent.getBooleanExtra("enablePartyScan", true)
                autoBrightness = intent.getBooleanExtra("autoBrightness", false)

                isBerserkerMode = intent.getBooleanExtra("isBerserkerMode", false)
                berserkerIconX = intent.getFloatExtra("berserkerIconX", 0.1f)
                berserkerIconY = intent.getFloatExtra("berserkerIconY", 0.8f)
                berserkerItemX = intent.getFloatExtra("berserkerItemX", 0.5f)
                berserkerItemY = intent.getFloatExtra("berserkerItemY", 0.5f)
                berserkerInterval = intent.getLongExtra("berserkerInterval", 300000L)
                
                if (intent.hasExtra("unlockBtnColor")) {
                    unlockBtnColor = intent.getIntArrayExtra("unlockBtnColor")
                }
                
                pullMargin = intent.getFloatExtra("pullMargin", 1.2f)
                appStatus = intent.getStringExtra("appStatus") ?: "WAIT"

                // Calibration values
                if (intent.hasExtra("unlockBtnX")) {
                    unlockBtnPos = intent.getFloatExtra("unlockBtnX", 0.5f) to intent.getFloatExtra("unlockBtnY", 0.5f)
                }
                if (intent.hasExtra("charCenterX")) {
                    charCenterPos = intent.getFloatExtra("charCenterX", 0.5f) to intent.getFloatExtra("charCenterY", 0.5f)
                }
                if (intent.hasExtra("resultBtnX")) {
                    val rx = intent.getFloatExtra("resultBtnX", -1f)
                    val ry = intent.getFloatExtra("resultBtnY", -1f)
                    if (rx >= 0f && ry >= 0f) {
                        resultBtnPos = Pair(rx, ry)
                    } else {
                        resultBtnPos = null
                    }
                }
                if (intent.hasExtra("circleRadius")) {
                    circleRadius = intent.getFloatExtra("circleRadius", 0.5f)
                }
                
                // Update UI immediately
                overlayView.findViewById<TextView>(R.id.tv_target_word).text = "TARGET: ${targetKeywords.firstOrNull() ?: "！"}"
                val btnAutoBattle = overlayView.findViewById<TextView>(R.id.btn_auto_battle)
                btnAutoBattle.text = if (isAutoBattleEnabled) "Auto Battle: ON" else "Auto Battle: OFF"
                btnAutoBattle.setBackgroundResource(if (isAutoBattleEnabled) R.drawable.bg_button_active else R.drawable.bg_button_inactive)
                btnAutoBattle.setTextColor(if (isAutoBattleEnabled) Color.parseColor("#10B981") else Color.parseColor("#9CA3AF"))
            }

            val resultCode = intent.getIntExtra("EXTRA_RESULT_CODE", 0)
            val resultData = intent.getParcelableExtra<Intent>("EXTRA_RESULT_DATA")
            if (resultCode != 0 && resultData != null) {
                mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
                mediaProjection?.registerCallback(projectionCallback, handler)
                setupVirtualDisplay()
            }
        }
        return START_NOT_STICKY
    }

    private fun setupVirtualDisplay() {
        virtualDisplay?.release()
        imageReader?.close()
        
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture", screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null
        )
    }

    private fun captureAndRecognize() {
        if (imageReader == null || isProcessing) return
        isProcessing = true
        
        val image: Image? = try {
            imageReader?.acquireLatestImage()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire image: ${e.message}")
            null
        }

        if (image != null) {
            Thread {
                try {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val captureWidth = image.width
                    val captureHeight = image.height
                    val rowPadding = rowStride - pixelStride * captureWidth

                    val fullBitmap = Bitmap.createBitmap(captureWidth + rowPadding / pixelStride, captureHeight, Bitmap.Config.ARGB_8888)
                    fullBitmap.copyPixelsFromBuffer(buffer)
                    image.close()

                    val cropRect = if (isScanAreaVisible && scanAreaParams != null) {
                        Rect(scanAreaParams!!.x, scanAreaParams!!.y, scanAreaParams!!.x + 400, scanAreaParams!!.y + 200)
                    } else {
                        val areaRatio = wideScanArea / 100f
                        val w = (screenWidth * areaRatio).toInt()
                        val h = (screenHeight * areaRatio).toInt()
                        val cx = (screenWidth * charCenterPos.first).toInt()
                        val cy = (screenHeight * charCenterPos.second).toInt()
                        Rect(cx - w/2, cy - h/2, cx + w/2, cy + h/2)
                    }

                    val startX = cropRect.left.coerceIn(0, fullBitmap.width - 1)
                    val startY = cropRect.top.coerceIn(0, fullBitmap.height - 1)
                    val width = cropRect.width().coerceIn(1, fullBitmap.width - startX)
                    val height = cropRect.height().coerceIn(1, fullBitmap.height - startY)

                    val croppedBitmap = Bitmap.createBitmap(fullBitmap, startX, startY, width, height)
                    
                    // ボトルネック対策3: OCR解像度最適化 (高解像クロップ画像を縮小してOCRと二値化処理を劇的に高速化)
                    val maxOcrWidth = 400
                    val ocrRatio = if (width > maxOcrWidth) maxOcrWidth.toFloat() / width else 1f
                    val ocrBitmap = if (width > maxOcrWidth) {
                        val targetOcrHeight = (height * ocrRatio).toInt().coerceAtLeast(1)
                        Bitmap.createScaledBitmap(croppedBitmap, maxOcrWidth, targetOcrHeight, true)
                    } else {
                        croppedBitmap
                    }
                    
                    val processedBitmap = preprocessBitmap(ocrBitmap)

                    if (isDebugMode) {
                        broadcastOcrPreview(processedBitmap)
                    }

                    // Unlock Button Color Check
                    if (unlockBtnColor != null) {
                        val btnX = (screenWidth * unlockBtnPos.first).toInt().coerceIn(0, fullBitmap.width - 1)
                        val btnY = (screenHeight * unlockBtnPos.second).toInt().coerceIn(0, fullBitmap.height - 1)
                        val pixel = fullBitmap.getPixel(btnX, btnY)
                        val r = Color.red(pixel)
                        val g = Color.green(pixel)
                        val b = Color.blue(pixel)
                        val targetR = unlockBtnColor!![0]
                        val targetG = unlockBtnColor!![1]
                        val targetB = unlockBtnColor!![2]
                        
                        val dist = Math.sqrt(
                            Math.pow((r - targetR).toDouble(), 2.0) +
                            Math.pow((g - targetG).toDouble(), 2.0) +
                            Math.pow((b - targetB).toDouble(), 2.0)
                        )
                        
                        if (dist <= 50) {
                            AutoTapService.instance?.performTap(btnX.toFloat(), btnY.toFloat())
                            broadcastLog("Unlock button detected and tapped", "success")
                        }
                    }

                    tessBaseAPI.setImage(processedBitmap)
                    var recognizedText = tessBaseAPI.utF8Text.lowercase(Locale.US)
                    
                    if (enableDictCorrection) {
                        recognizedText = recognizedText.replace("1", "!").replace("l", "!").replace("i", "!")
                    }
                    
                    if (enableRegexFilter) {
                        // ボトルネック対策4: 正規表現のコンパイル排除 (コンパニオンオブジェクトに定義した静的正規表現を再利用)
                        recognizedText = recognizedText.replace(EXCLUDE_REGEX, "")
                    }
                    
                    handler.post {
                        if (isDebugMode) {
                            val previewBitmap = processedBitmap.copy(Bitmap.Config.ARGB_8888, false)
                            overlayView.findViewById<ImageView>(R.id.iv_debug_preview).setImageBitmap(previewBitmap)
                        }

                    // 複数キーワード対応
                    val baseTriggerWords = listOf("!", "|", "i!", "攻撃")
                    val potKeywords = listOf("壺", "つぼ", "ツボ", "回復")
                    val hokoraKeywords = listOf("ほこら", "祠")

                    // Anchor Search
                    val hasAnchor = if (enableAnchorSearch) {
                        recognizedText.contains("lv") || recognizedText.contains("レベル")
                    } else {
                        true
                    }

                    val isTargetMatchByText = hasAnchor && (targetKeywords.any { keyword -> 
                        recognizedText.contains(keyword.lowercase(Locale.US))
                    } || baseTriggerWords.any { recognizedText.contains(it) })
                    
                    val isNormalEnemyTargeted = targetKeywords.any { it.contains("通常の敵") }
                    val isStrongEnemyTargeted = targetKeywords.any { it.contains("強敵") }
                    val isEventPopTargeted = targetKeywords.any { it.contains("イベントポップ") }
                    
                    var targetTapPoint: Pair<Float, Float>? = null

                    val normalColorCenter = if (isNormalEnemyTargeted) getTargetColorCenter(ocrBitmap, normalEnemyColor) else null
                    val strongColorCenter = if (isStrongEnemyTargeted) getTargetColorCenter(ocrBitmap, strongEnemyColor) else null
                    val eventColorCenter = if (isEventPopTargeted) getTargetColorCenter(ocrBitmap, eventPopColor) else null
                    
                    val isNormalColorMatched = normalColorCenter != null
                    val isStrongColorMatched = strongColorCenter != null
                    val isEventPopColorMatched = eventColorCenter != null
                    
                    val isTargetMatch = isTargetMatchByText || isNormalColorMatched || isStrongColorMatched || isEventPopColorMatched

                    if (isNormalColorMatched && normalColorCenter != null) targetTapPoint = normalColorCenter
                    else if (isStrongColorMatched && strongColorCenter != null) targetTapPoint = strongColorCenter
                    else if (isEventPopColorMatched && eventColorCenter != null) targetTapPoint = eventColorCenter

                    val isPotKeywordMatched = targetPot && potKeywords.any { recognizedText.contains(it) }
                    val potColorCenter = if (targetPot) getTargetColorCenter(ocrBitmap, potColor) else null
                    val isPotCustomColorMatched = targetPot && potColorCenter != null
                    var isPotColorMatched = true
                    if (isPotKeywordMatched && enablePotFilter && potColor == null) {
                        // 縮小済みのocrBitmapを使用することで色判定も極めて高速
                        isPotColorMatched = hasPotColors(ocrBitmap)
                        if (!isPotColorMatched) {
                            Log.d(TAG, "Pot keyword matched but color check failed: $recognizedText")
                        }
                    }
                    val isPotMatch = (isPotKeywordMatched && isPotColorMatched) || isPotCustomColorMatched
                    if (isPotCustomColorMatched && targetTapPoint == null && potColorCenter != null) targetTapPoint = potColorCenter
                    
                    val isHokoraKeywordMatched = targetHokora && hokoraKeywords.any { recognizedText.contains(it) }
                    val hokoraColorCenter = if (targetHokora) getTargetColorCenter(ocrBitmap, hokoraColor) else null
                    val isHokoraColorMatched = targetHokora && hokoraColorCenter != null
                    val isHokoraMatch = isHokoraKeywordMatched || isHokoraColorMatched
                    if (isHokoraColorMatched && targetTapPoint == null && hokoraColorCenter != null) targetTapPoint = hokoraColorCenter

                    // ボトルネック・モックデータ対策: 実質のEXPを取得するロジック
                    val expRegex = Regex("exp\\+?(\\d+)|(\\d+)経験値")
                    val expMatch = expRegex.find(recognizedText)
                    
                    if (expMatch != null) {
                        val expValStr = expMatch.groupValues[1].takeIf { it.isNotEmpty() } ?: expMatch.groupValues[2]
                        val expVal = expValStr.toIntOrNull()
                        if (expVal != null && expVal > 0) {
                            totalExp += expVal
                            totalKills += 1
                            handler.post {
                                overlayView.findViewById<TextView>(R.id.tv_kills).text = totalKills.toString()
                                overlayView.findViewById<TextView>(R.id.tv_exp).text = totalExp.toString()
                                updateNotification()
                                broadcastLog("Battle ends. EXP: $expVal", "success")
                                broadcastStats("Battle ends", expVal)
                            }
                            // 結果画面なので画面中央付近か設定位置をタップして次へ進める
                            val cx = (screenWidth * (resultBtnPos?.first ?: charCenterPos.first))
                            val cy = (screenHeight * (resultBtnPos?.second ?: charCenterPos.second))
                            AutoTapService.instance?.performTap(cx, cy)
                            
                            isProcessing = false
                            if (ocrBitmap != croppedBitmap) ocrBitmap.recycle()
                            fullBitmap.recycle()
                            croppedBitmap.recycle()
                            processedBitmap.recycle()
                            return@post
                        }
                    }

                    if (isTargetMatch || isPotMatch || isHokoraMatch) {
                        retryCounter = 0
                        isAutoBattleActive = true
                        
                        if (isPotMatch) {
                            broadcastLog("Pot detected: $recognizedText", "success")
                        } else if (isHokoraMatch) {
                            broadcastLog("Hokora detected: $recognizedText", "success")
                        } else {
                            broadcastLog("Target detected: $recognizedText", "success")
                        }

                        // ここでのモックデータ加算を削除。EXPとKill数は結果画面(上記)で集計する
                        // totalKills += 1
                        // totalExp += Random.nextInt(10, 50)
                        
                        val jitterX = Random.nextInt(-10, 10).toFloat()
                        val jitterY = Random.nextInt(-10, 10).toFloat()

                        if (targetTapPoint != null) {
                            val tapX = startX + (targetTapPoint.first / ocrRatio) + jitterX
                            val tapY = startY + (targetTapPoint.second / ocrRatio) + jitterY
                            AutoTapService.instance?.performTap(tapX, tapY)
                            broadcastLog("Tapped exactly on target color position", "info")
                        } else if (pullMargin > 1.0f && !isPotMatch && !isHokoraMatch) {
                            val cx = (screenWidth * charCenterPos.first)
                            val cy = (screenHeight * charCenterPos.second)
                            val r = screenWidth * circleRadius
                            val maxPullRadius = r * pullMargin
                            
                            // Simulate finding an enemy at a random distance within maxPullRadius
                            val enemyDist = r + (maxPullRadius - r) * Math.random().toFloat()
                            val angle = Math.random().toFloat() * 2 * Math.PI
                            val enemyX = cx + (enemyDist * Math.cos(angle)).toFloat()
                            val enemyY = cy + (enemyDist * Math.sin(angle)).toFloat()
                            
                            // 細かいタップ操作 (Fine tap operations) instead of swipe
                            val steps = 5
                            for (i in 0..steps) {
                                val t = i.toFloat() / steps
                                val tapX = enemyX + (cx - enemyX) * t
                                val tapY = enemyY + (cy - enemyY) * t
                                handler.postDelayed({
                                    AutoTapService.instance?.performTap(tapX, tapY)
                                }, (i * 150).toLong())
                            }
                            
                            broadcastLog("Pulled enemy from margin (Taps)", "info")
                        } else {
                            val tapX = if (isTapPointVisible && tapPointParams != null) {
                                tapPointParams!!.x.toFloat() + 20 + jitterX
                            } else {
                                (screenWidth * charCenterPos.first) + (screenWidth * (tapOffsetX / 100f)) + jitterX
                            }
                            val tapY = if (isTapPointVisible && tapPointParams != null) {
                                tapPointParams!!.y.toFloat() + 20 + jitterY
                            } else {
                                (screenHeight * charCenterPos.second) + (screenHeight * (tapOffsetY / 100f)) + jitterY
                            }
                            AutoTapService.instance?.performTap(tapX, tapY)
                        }
                            
                        // Reset battle active status after 5 seconds
                        handler.postDelayed({ isAutoBattleActive = false }, 5000)
                    } else {
                        if (retryCounter < ocrRetryCount) {
                            retryCounter++
                            broadcastLog("Retry OCR ($retryCounter/$ocrRetryCount)", "warn")
                            // Wait and retry
                            handler.postDelayed({
                                isProcessing = false
                                captureAndRecognize()
                            }, (ocrWaitTime * 1000).toLong())
                            return@post
                        } else {
                            retryCounter = 0
                        }
                    }
                    isProcessing = false
                }
                
                // Party Scan Logic
                if (enablePartyScan && appStatus == "WALK_MODE" && Random.nextInt(10) == 0) {
                    try {
                        val partyRect = Rect(0, (fullBitmap.height * 0.8).toInt(), fullBitmap.width, fullBitmap.height)
                        val partyStartX = partyRect.left.coerceIn(0, fullBitmap.width - 1)
                        val partyStartY = partyRect.top.coerceIn(0, fullBitmap.height - 1)
                        val partyWidth = partyRect.width().coerceIn(1, fullBitmap.width - partyStartX)
                        val partyHeight = partyRect.height().coerceIn(1, fullBitmap.height - partyStartY)
                        
                        val partyBitmap = Bitmap.createBitmap(fullBitmap, partyStartX, partyStartY, partyWidth, partyHeight)
                        val partyProcessed = preprocessBitmap(partyBitmap)
                        tessBaseAPI.setImage(partyProcessed)
                        val partyText = tessBaseAPI.utF8Text
                        
                        val hpMatches = Regex("HP\\s*(\\d+)").findAll(partyText).map { it.groupValues[1] }.toList()
                        val mpMatches = Regex("MP\\s*(\\d+)").findAll(partyText).map { it.groupValues[1] }.toList()
                        
                        if (hpMatches.isNotEmpty() || mpMatches.isNotEmpty()) {
                            broadcastLog("Party Scan: HP=$hpMatches, MP=$mpMatches", "info")
                            broadcastStats("Party sync updated")
                        }
                        
                        partyBitmap.recycle()
                        partyProcessed.recycle()
                    } catch (e: Exception) {
                        Log.e(TAG, "Party Scan Error: ${e.message}")
                    }
                }
                
                if (ocrBitmap != croppedBitmap) {
                    ocrBitmap.recycle()
                }
                fullBitmap.recycle()
                croppedBitmap.recycle()
                processedBitmap.recycle()
                } catch (e: Exception) {
                    Log.e(TAG, "Error during capture/recognize: ${e.message}")
                    broadcastLog("OCR Error: ${e.message}", "error")
                    isProcessing = false
                }
            }.start()
        } else {
            isProcessing = false
        }
    }

    private fun preprocessBitmap(original: Bitmap): Bitmap {
        val width = original.width
        val height = original.height
        val bmpGrayscale = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmpGrayscale)
        val paint = android.graphics.Paint()
        val colorMatrix = android.graphics.ColorMatrix()
        colorMatrix.setSaturation(0f) // グレースケール化
        val filter = android.graphics.ColorMatrixColorFilter(colorMatrix)
        paint.colorFilter = filter
        canvas.drawBitmap(original, 0f, 0f, paint)
        
        // 二値化 (高速なバルク配列処理。JNI境界呼び出しを数万回から1回に低減)
        val pixels = IntArray(width * height)
        bmpGrayscale.getPixels(pixels, 0, width, 0, 0, width, height)
        bmpGrayscale.recycle()
        
        for (i in pixels.indices) {
            val pixel = pixels[i]
            // グレースケール化済みの画像なら、R/G/Bのいずれか1チャンネルを読み出すだけで十分高速
            val gray = (pixel shr 16) and 0xFF
            pixels[i] = if (gray > 128) Color.WHITE else Color.BLACK
        }
        
        val bmpBinary = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmpBinary.setPixels(pixels, 0, width, 0, 0, width, height)
        return bmpBinary
    }

    private fun getTargetColorCenter(bitmap: Bitmap, targetRgb: IntArray?, tolerance: Int = 30): Pair<Float, Float>? {
        if (targetRgb == null || targetRgb.size < 3) return null
        val width = bitmap.width
        val height = bitmap.height
        var matchCount = 0
        var sumX = 0L
        var sumY = 0L
        
        val step = 4
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        val tr = targetRgb[0]
        val tg = targetRgb[1]
        val tb = targetRgb[2]
        
        for (y in 0 until height step step) {
            val offset = y * width
            for (x in 0 until width step step) {
                val pixel = pixels[offset + x]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                
                val dist = Math.sqrt(
                    Math.pow((r - tr).toDouble(), 2.0) +
                    Math.pow((g - tg).toDouble(), 2.0) +
                    Math.pow((b - tb).toDouble(), 2.0)
                )
                
                if (dist <= tolerance) {
                    sumX += x
                    sumY += y
                    matchCount++
                }
            }
        }
        
        if (matchCount >= 5) {
            return Pair(sumX.toFloat() / matchCount, sumY.toFloat() / matchCount)
        }
        return null
    }

    private fun hasPotColors(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        var potPixelCount = 0
        val hsv = FloatArray(3)
        
        // 高速化：ピクセルの一括バッチ読み込み
        val step = 4
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        for (y in 0 until height step step) {
            val offset = y * width
            for (x in 0 until width step step) {
                val pixel = pixels[offset + x]
                Color.colorToHSV(pixel, hsv)
                val hue = hsv[0]
                val sat = hsv[1]
                val value = hsv[2]
                
                // Normal green pot: hue between 70.0f and 160.0f, substantial saturation and brightness
                val isGreen = (hue in 70.0f..160.0f) && (sat > 0.3f) && (value > 0.3f)
                // Event purple pot: hue between 260.0f and 340.0f, substantial saturation and brightness
                val isPurple = (hue in 260.0f..340.0f) && (sat > 0.3f) && (value > 0.3f)
                
                if (isGreen || isPurple) {
                    potPixelCount++
                }
            }
        }
        
        val matched = potPixelCount >= 20
        Log.d(TAG, "hasPotColors: potPixelCount=$potPixelCount, matched=$matched")
        return matched
    }

    private fun broadcastOcrPreview(bitmap: Bitmap) {
        try {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            val byteArray = outputStream.toByteArray()
            val base64Image = Base64.encodeToString(byteArray, Base64.DEFAULT)
            
            val intent = Intent("com.Kproject.app.UPDATE_LOG")
            intent.putExtra("type", "ocr_preview")
            intent.putExtra("image", base64Image)
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast OCR preview: ${e.message}")
        }
    }

    private fun updateDimmingOverlay() {
        handler.post {
            if (autoBrightness) {
                if (dimOverlayView == null) {
                    dimOverlayView = View(this).apply {
                        setBackgroundColor(Color.BLACK)
                        alpha = 0.7f
                    }
                    val dimParams = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                    )
                    windowManager.addView(dimOverlayView, dimParams)
                }
            } else {
                dimOverlayView?.let {
                    windowManager.removeView(it)
                    dimOverlayView = null
                }
            }
        }
    }

    private fun toggleScanArea() {
        isScanAreaVisible = !isScanAreaVisible
        val btn = overlayView.findViewById<TextView>(R.id.btn_scan_area)
        if (isScanAreaVisible) {
            btn.text = "Hide Scan Area"
            btn.setBackgroundResource(R.drawable.bg_button_active)
            btn.setTextColor(Color.parseColor("#10B981"))
            scanAreaView = View(this).apply { setBackgroundResource(R.drawable.bg_scan_frame) }
            scanAreaParams = createOverlayParams(400, 200, screenWidth / 4, screenHeight / 2)
            windowManager.addView(scanAreaView, scanAreaParams)
            setupDragWithUpdate(scanAreaView!!, scanAreaParams!!, null)
        } else {
            btn.text = "Set Scan Area"
            btn.setBackgroundResource(R.drawable.bg_button_inactive)
            btn.setTextColor(Color.parseColor("#9CA3AF"))
            scanAreaView?.let { windowManager.removeView(it) }
            scanAreaView = null
        }
    }

    private fun toggleTapPoint() {
        isTapPointVisible = !isTapPointVisible
        val btn = overlayView.findViewById<TextView>(R.id.btn_tap_point)
        val layoutFineTune = overlayView.findViewById<LinearLayout>(R.id.layout_fine_tune)

        if (isTapPointVisible) {
            btn.text = "Hide Tap Point"
            btn.setBackgroundResource(R.drawable.bg_button_active)
            btn.setTextColor(Color.parseColor("#10B981"))
            layoutFineTune.visibility = View.VISIBLE
            tapPointView = ImageView(this).apply { setImageResource(R.drawable.ic_crosshair) }
            tapPointParams = createOverlayParams(40, 40, screenWidth / 2, screenHeight / 2)
            windowManager.addView(tapPointView, tapPointParams)
            setupDragWithUpdate(tapPointView!!, tapPointParams!!, null)
            updateCoordsDisplay()
        } else {
            btn.text = "Set Tap Point"
            btn.setBackgroundResource(R.drawable.bg_button_inactive)
            btn.setTextColor(Color.parseColor("#9CA3AF"))
            layoutFineTune.visibility = View.GONE
            tapPointView?.let { windowManager.removeView(it) }
            tapPointView = null
        }
    }

    private fun updateCoordsDisplay() {
        tapPointParams?.let {
            val tvCoords = overlayView.findViewById<TextView>(R.id.tv_coords)
            tvCoords.text = "X: ${it.x + 20}, Y: ${it.y + 20}"
        }
    }

    private fun moveTapPoint(dx: Int, dy: Int) {
        if (isTapPointVisible && tapPointView != null && tapPointParams != null) {
            tapPointParams!!.x += dx
            tapPointParams!!.y += dy
            windowManager.updateViewLayout(tapPointView!!, tapPointParams!!)
            updateCoordsDisplay()
        }
    }

    private fun createOverlayParams(w: Int, h: Int, xPos: Int, yPos: Int): WindowManager.LayoutParams {
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        return WindowManager.LayoutParams(w, h, flag, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = xPos
            y = yPos
        }
    }

    private fun broadcastLog(message: String, type: String = "info") {
        val intent = Intent("com.Kproject.app.UPDATE_LOG")
        intent.putExtra("message", message)
        intent.putExtra("type", type)
        sendBroadcast(intent)
        
        // トースト通知のブリッジング
        if (type == "success" || type == "error" || type == "warn") {
            val toastIntent = Intent("com.Kproject.app.SHOW_TOAST")
            toastIntent.putExtra("message", message)
            toastIntent.putExtra("type", type)
            sendBroadcast(toastIntent)
        }
    }

    private fun broadcastStats(log: String? = null, expGain: Int = 0) {
        val intent = Intent("com.Kproject.app.UPDATE_STATS")
        intent.putExtra("kills", totalKills)
        intent.putExtra("exp", totalExp)
        intent.putExtra("expGain", expGain)
        if (log != null) intent.putExtra("log", log)
        sendBroadcast(intent)
    }

    private var calibrationView: View? = null
    private var calibrationType: String = ""

    private fun startCalibrationUI(type: String) {
        if (calibrationView != null) return
        calibrationType = type
        
        // Hide main overlay temporarily
        overlayView.visibility = View.GONE
        
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        // Set to full screen but NOT focusable so touch passes everywhere EXCEPT buttons
        val calParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        
        val ctx = this
        val frameLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#44000000"))
        }
        
        // Target Crosshair ImageView (we just draw a plus or use a text view)
        val crosshair = TextView(this).apply {
            text = "＋"
            textSize = 60f
            setTextColor(Color.RED)
            gravity = Gravity.CENTER
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        
        // Draggable container for crosshair so user can drag it around
        val crosshairContainer = FrameLayout(this).apply {
            val s = (screenWidth * 0.2f).toInt()
            layoutParams = FrameLayout.LayoutParams(s, s).apply {
                gravity = Gravity.CENTER
            }
            addView(crosshair)
        }
        
        // Setup Drag on crosshair
        var dX = 0f
        var dY = 0f
        crosshairContainer.setOnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    view.animate()
                        .x(event.rawX + dX)
                        .y(event.rawY + dY)
                        .setDuration(0)
                        .start()
                    true
                }
                else -> false
            }
        }
        
        // Top label text
        val titleText = TextView(this).apply {
            text = "キャリブレーション: $type\n十字を対象に合わせて保存を押してください"
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundColor(Color.parseColor("#88000000"))
            setPadding(20, 20, 20, 20)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP }
            gravity = Gravity.CENTER
        }
        
        // Save button
        val saveBtn = android.widget.Button(this).apply {
            text = "SAVE $type"
            setBackgroundColor(Color.parseColor("#10B981"))
            setTextColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                setMargins(0, 0, 0, 100)
            }
            setOnClickListener {
                endCalibration(crosshairContainer)
            }
        }
        
        frameLayout.addView(crosshairContainer)
        frameLayout.addView(titleText)
        frameLayout.addView(saveBtn)
        
        calibrationView = frameLayout
        windowManager.addView(calibrationView, calParams)
    }
    
    private fun endCalibration(crosshairContainer: View) {
        val centerX = crosshairContainer.x + (crosshairContainer.width / 2f)
        val centerY = crosshairContainer.y + (crosshairContainer.height / 2f)
        val xPct = (centerX / screenWidth).coerceIn(0f, 1f)
        val yPct = (centerY / screenHeight).coerceIn(0f, 1f)
        
        var colorR = 0
        var colorG = 0
        var colorB = 0
        
        val base64Str = takeScreenshot()
        if (base64Str != null) {
            try {
                val decodedBytes = android.util.Base64.decode(base64Str, android.util.Base64.DEFAULT)
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                if (bitmap != null) {
                    val px = (xPct * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                    val py = (yPct * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                    val pixel = bitmap.getPixel(px, py)
                    colorR = Color.red(pixel)
                    colorG = Color.green(pixel)
                    colorB = Color.blue(pixel)
                    bitmap.recycle()
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        
        val intent = Intent("com.Kproject.app.CALIBRATION_FINISHED")
        intent.putExtra("type", calibrationType)
        intent.putExtra("xPct", xPct)
        intent.putExtra("yPct", yPct)
        intent.putExtra("colorR", colorR)
        intent.putExtra("colorG", colorG)
        intent.putExtra("colorB", colorB)
        sendBroadcast(intent)
        
        broadcastLog("$calibrationType の位置と色(${colorR},${colorG},${colorB})を保存しました", "success")
        
        if (calibrationView != null) {
            windowManager.removeView(calibrationView)
            calibrationView = null
        }
        overlayView.visibility = View.VISIBLE
    }

    private fun setupUI() {
        overlayView.findViewById<TextView>(R.id.btnClose).setOnClickListener { stopSelf() }
        overlayView.findViewById<TextView>(R.id.btn_toggle_collapse).setOnClickListener {
            isCollapsed = !isCollapsed
            overlayView.findViewById<LinearLayout>(R.id.content_container).visibility = if (isCollapsed) View.GONE else View.VISIBLE
            (it as TextView).text = if (isCollapsed) "▶" else "▼"
            windowManager.updateViewLayout(overlayView, params)
        }

        overlayView.findViewById<TextView>(R.id.btn_auto_battle).setOnClickListener {
            isAutoBattleEnabled = !isAutoBattleEnabled
            
            val prefs = getSharedPreferences("OverlaySettings", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("isAutoBattleEnabled", isAutoBattleEnabled).apply()
            
            val intent = Intent("com.Kproject.app.UPDATE_TOGGLE_STATE")
            intent.putExtra("isAutoBattleEnabled", isAutoBattleEnabled)
            sendBroadcast(intent)
            
            (it as TextView).text = if (isAutoBattleEnabled) "Auto Battle: ON" else "Auto Battle: OFF"
            it.setBackgroundResource(if (isAutoBattleEnabled) R.drawable.bg_button_active else R.drawable.bg_button_inactive)
            it.setTextColor(if (isAutoBattleEnabled) Color.parseColor("#10B981") else Color.parseColor("#9CA3AF"))
        }

        val tvTargetWord = overlayView.findViewById<TextView>(R.id.tv_target_word)
        tvTargetWord.text = "TARGET: ${targetKeywords.firstOrNull() ?: "！"}"
        tvTargetWord.setOnClickListener { showKeywordInputDialog() }

        overlayView.findViewById<TextView>(R.id.btn_scan_area).setOnClickListener { toggleScanArea() }
        overlayView.findViewById<TextView>(R.id.btn_tap_point).setOnClickListener { toggleTapPoint() }

        overlayView.findViewById<TextView>(R.id.btn_move_up).setOnClickListener { moveTapPoint(0, -1) }
        overlayView.findViewById<TextView>(R.id.btn_move_down).setOnClickListener { moveTapPoint(0, 1) }
        overlayView.findViewById<TextView>(R.id.btn_move_left).setOnClickListener { moveTapPoint(-1, 0) }
        overlayView.findViewById<TextView>(R.id.btn_move_right).setOnClickListener { moveTapPoint(1, 0) }
    }

    private fun showKeywordInputDialog() {
        val editText = EditText(this).apply {
            val currentKeywords = targetKeywords.joinToString(",")
            setText(currentKeywords)
            setSelection(currentKeywords.length)
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Set Target Keyword")
            .setView(editText)
            .setPositiveButton("OK") { _, _ ->
                val keywordsStr = editText.text.toString()
                targetKeywords = keywordsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                overlayView.findViewById<TextView>(R.id.tv_target_word).text = "TARGET: ${targetKeywords.firstOrNull() ?: "！"}"
            }
            .setNegativeButton("Cancel", null)
            .create()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        } else {
            @Suppress("DEPRECATION")
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT)
        }
        dialog.show()
    }

    private fun setupDragWithUpdate(view: View, layoutParams: WindowManager.LayoutParams, handleId: Int? = null) {
        val target = if (handleId != null) view.findViewById<View>(handleId) else view
        target?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x; initialY = layoutParams.y
                        initialTouchX = event.rawX; initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(view, layoutParams)
                        updateCoordsDisplay()
                        
                        // 双方向同期: 座標変更をReact側に通知
                        if (view == scanAreaView) {
                            val intent = Intent("com.Kproject.app.UPDATE_SCAN_AREA")
                            intent.putExtra("x", layoutParams.x)
                            intent.putExtra("y", layoutParams.y)
                            intent.putExtra("w", layoutParams.width)
                            intent.putExtra("h", layoutParams.height)
                            sendBroadcast(intent)
                        }
                        
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun initTesseract() {
        val dataPath = File(filesDir, "tesseract")
        val tessdataDir = File(dataPath, "tessdata")
        if (!tessdataDir.exists()) tessdataDir.mkdirs()
        val trainedDataFile = File(tessdataDir, "jpn.traineddata")
        if (!trainedDataFile.exists()) {
            try {
                assets.open("tessdata/jpn.traineddata").use { input ->
                    FileOutputStream(trainedDataFile).use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy traineddata", e)
            }
        }
        tessBaseAPI = TessBaseAPI()
        if (tessBaseAPI.init(dataPath.absolutePath, "jpn")) {
            isOcrInitialized = true
            tessBaseAPI.setVariable("tessedit_char_whitelist", "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!|壺つぼツボ回復ほこら祠攻撃レベルlvi経験値かくとく+")
        }
    }

    private fun startForegroundServiceWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "KAI Service", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
        
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val status = if (isAutoBattleEnabled) "Auto Battle: ON" else "Auto Battle: OFF"
        val contentText = "Kills: $totalKills | Exp: $totalExp | $status"

        val stopIntent = Intent(this, OverlayService::class.java).apply { action = ACTION_STOP_SERVICE }
        val stopPendingIntent = android.app.PendingIntent.getService(this, 0, stopIntent, android.app.PendingIntent.FLAG_IMMUTABLE)

        val toggleIntent = Intent(this, OverlayService::class.java).apply { action = ACTION_TOGGLE_AUTO }
        val togglePendingIntent = android.app.PendingIntent.getService(this, 1, toggleIntent, android.app.PendingIntent.FLAG_IMMUTABLE)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("KAI Auto Battle")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(android.app.Notification.Action.Builder(null, "TOGGLE", togglePendingIntent).build())
            .addAction(android.app.Notification.Action.Builder(null, "STOP", stopPendingIntent).build())
            .build()
    }

    private fun updateNotification() {
        val notification = buildNotification()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun updateTime() {
        overlayView.findViewById<TextView>(R.id.tv_time).text = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    }

    fun takeScreenshot(): String? {
        if (imageReader == null) return lastCapturedBase64
        
        return try {
            val image: Image? = imageReader?.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val captureWidth = image.width
                val captureHeight = image.height
                val rowPadding = rowStride - pixelStride * captureWidth
                
                val fullBitmap = Bitmap.createBitmap(
                    captureWidth + rowPadding / pixelStride,
                    captureHeight,
                    Bitmap.Config.ARGB_8888
                )
                fullBitmap.copyPixelsFromBuffer(buffer)
                image.close()
                
                // Remove padding
                val croppedBitmap = Bitmap.createBitmap(fullBitmap, 0, 0, captureWidth, captureHeight)
                fullBitmap.recycle()
                
                // Convert to Base64
                val outputStream = java.io.ByteArrayOutputStream()
                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)
                val byteArray = outputStream.toByteArray()
                croppedBitmap.recycle()
                
                lastCapturedBase64 = android.util.Base64.encodeToString(byteArray, android.util.Base64.NO_WRAP)
            }
            lastCapturedBase64
        } catch (e: Exception) {
            Log.e(TAG, "Failed to take screenshot: ${e.message}")
            lastCapturedBase64
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        unregisterReceiver(settingsReceiver)
        wakeLock?.let { if (it.isHeld) it.release() }
        dimOverlayView?.let { windowManager.removeView(it) }
        if (::overlayView.isInitialized) windowManager.removeView(overlayView)
        scanAreaView?.let { windowManager.removeView(it) }
        tapPointView?.let { windowManager.removeView(it) }
        handler.removeCallbacksAndMessages(null)
        mediaProjection?.unregisterCallback(projectionCallback)
        virtualDisplay?.release(); imageReader?.close(); mediaProjection?.stop()
        if (isOcrInitialized) tessBaseAPI.clear()
    }
}
