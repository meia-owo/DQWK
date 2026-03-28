package com.Kproject.app

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.googlecode.tesseract.android.TessBaseAPI
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

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private lateinit var tessBaseAPI: TessBaseAPI
    private var isOcrInitialized = false

    private var isAutoBattleEnabled = true
    private var isCollapsed = false
    private var totalKills = 0
    private var totalExp = 0
    private var targetKeyword = "！"

    private val handler = Handler(Looper.getMainLooper())

    private val timeUpdater = object : Runnable {
        override fun run() {
            updateTime()
            handler.postDelayed(this, 1000)
        }
    }

    private val ocrUpdater = object : Runnable {
        override fun run() {
            if (isAutoBattleEnabled && isOcrInitialized) {
                try {
                    captureAndRecognize()
                } catch (e: Exception) {
                    Log.e(TAG, "OCR Error", e)
                }
            }
            handler.postDelayed(this, 3000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? { return null }

    override fun onCreate() {
        super.onCreate()
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

        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        Thread { initTesseract() }.start()
        handler.post(timeUpdater)
        handler.postDelayed(ocrUpdater, 3000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val resultCode = intent.getIntExtra("EXTRA_RESULT_CODE", 0)
            val resultData = intent.getParcelableExtra<Intent>("EXTRA_RESULT_DATA")
            if (resultCode != 0 && resultData != null) {
                mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
                setupVirtualDisplay()
            }
        }
        return START_NOT_STICKY
    }

    private fun setupVirtualDisplay() {
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture", screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null
        )
    }

    private fun captureAndRecognize() {
        val image: Image? = imageReader?.acquireLatestImage()
        if (image != null) {
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
                Rect(screenWidth / 4, screenHeight / 3, screenWidth * 3 / 4, screenHeight * 2 / 3)
            }

            val startX = cropRect.left.coerceIn(0, fullBitmap.width - 1)
            val startY = cropRect.top.coerceIn(0, fullBitmap.height - 1)
            val width = cropRect.width().coerceIn(1, fullBitmap.width - startX)
            val height = cropRect.height().coerceIn(1, fullBitmap.height - startY)

            val croppedBitmap = Bitmap.createBitmap(fullBitmap, startX, startY, width, height)

            tessBaseAPI.setImage(croppedBitmap)
            val recognizedText = tessBaseAPI.utF8Text.lowercase(Locale.US)
            
            handler.post {
                val triggerWords = listOf(targetKeyword.lowercase(Locale.US), "!", "i", "l", "1", "|", "i!", "攻撃")
                val isTriggered = triggerWords.any { recognizedText.contains(it) }

                if (isTriggered) {
                    totalKills += 1
                    totalExp += Random.nextInt(10, 50)
                    overlayView.findViewById<TextView>(R.id.tv_kills).text = totalKills.toString()
                    overlayView.findViewById<TextView>(R.id.tv_exp).text = totalExp.toString()

                    val tapX = if (isTapPointVisible && tapPointParams != null) {
                        tapPointParams!!.x.toFloat() + 20
                    } else {
                        screenWidth / 2f
                    }
                    val tapY = if (isTapPointVisible && tapPointParams != null) {
                        tapPointParams!!.y.toFloat() + 20
                    } else {
                        screenHeight / 2f
                    }
                    AutoTapService.instance?.performTap(tapX, tapY)
                }
            }
            fullBitmap.recycle()
            croppedBitmap.recycle()
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
            (it as TextView).text = if (isAutoBattleEnabled) "Auto Battle: ON" else "Auto Battle: OFF"
            it.setBackgroundResource(if (isAutoBattleEnabled) R.drawable.bg_button_active else R.drawable.bg_button_inactive)
            it.setTextColor(if (isAutoBattleEnabled) Color.parseColor("#10B981") else Color.parseColor("#9CA3AF"))
        }

        val tvTargetWord = overlayView.findViewById<TextView>(R.id.tv_target_word)
        tvTargetWord.text = "TARGET: $targetKeyword"
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
            setText(targetKeyword)
            setSelection(targetKeyword.length)
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Set Target Keyword")
            .setView(editText)
            .setPositiveButton("OK") { _, _ ->
                targetKeyword = editText.text.toString()
                overlayView.findViewById<TextView>(R.id.tv_target_word).text = "TARGET: $targetKeyword"
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
        if (tessBaseAPI.init(dataPath.absolutePath, "jpn")) isOcrInitialized = true
    }

    private fun startForegroundServiceWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "KAI Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, NOTIFICATION_CHANNEL_ID) else Notification.Builder(this))
            .setContentTitle("KAI").setContentText("Running...").setSmallIcon(android.R.drawable.ic_menu_camera).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateTime() {
        overlayView.findViewById<TextView>(R.id.tv_time).text = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::overlayView.isInitialized) windowManager.removeView(overlayView)
        scanAreaView?.let { windowManager.removeView(it) }
        tapPointView?.let { windowManager.removeView(it) }
        handler.removeCallbacksAndMessages(null)
        virtualDisplay?.release(); imageReader?.close(); mediaProjection?.stop()
        if (isOcrInitialized) tessBaseAPI.clear()
    }
}
