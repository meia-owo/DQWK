package com.Kproject.app

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import com.getcapacitor.BridgeActivity
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

class MainActivity : BridgeActivity() {
    private val REQUEST_MEDIA_PROJECTION = 1001
    private val REQUEST_OVERLAY_PERMISSION = 1002
    private var pendingCall: PluginCall? = null

    // 現在の設定を保持
    private var currentTargetKeyword = "！"
    private var currentIsAutoBattleEnabled = true
    private var currentTapOffsetX = 0f
    private var currentTapOffsetY = 0f
    private var currentScanInterval = 3000
    private var currentEnableResultDetection = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerPlugin(OverlayPlugin::class.java)
    }

    @CapacitorPlugin(name = "OverlayPlugin")
    inner class OverlayPlugin : Plugin() {
        @PluginMethod
        fun startOverlay(call: PluginCall) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                pendingCall = call
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + context.packageName)
                )
                this@MainActivity.startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
            } else {
                startMediaProjection(call)
            }
        }

        @PluginMethod
        fun updateSettings(call: PluginCall) {
            currentTargetKeyword = call.getString("targetKeyword", "！") ?: "！"
            currentIsAutoBattleEnabled = call.getBoolean("isAutoBattleEnabled", true) ?: true
            // Capacitor の getFloat は内部的に Double を返す場合があるため、明示的に取得
            currentTapOffsetX = call.getDouble("tapOffsetX", 0.0)?.toFloat() ?: 0f
            currentTapOffsetY = call.getDouble("tapOffsetY", 0.0)?.toFloat() ?: 0f
            currentScanInterval = call.getInt("scanInterval", 3000) ?: 3000
            currentEnableResultDetection = call.getBoolean("enableResultDetection", true) ?: true

            val intent = Intent("com.Kproject.app.UPDATE_SETTINGS")
            intent.putExtra("targetKeyword", currentTargetKeyword)
            intent.putExtra("isAutoBattleEnabled", currentIsAutoBattleEnabled)
            intent.putExtra("tapOffsetX", currentTapOffsetX)
            intent.putExtra("tapOffsetY", currentTapOffsetY)
            intent.putExtra("scanInterval", currentScanInterval)
            intent.putExtra("enableResultDetection", currentEnableResultDetection)
            context.sendBroadcast(intent)
            call.resolve()
        }
    }

    private fun startMediaProjection(call: PluginCall) {
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
            }
            pendingCall = null
        } else if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == RESULT_OK) {
            val intent = Intent(this, OverlayService::class.java)
            intent.putExtra("EXTRA_RESULT_CODE", resultCode)
            intent.putExtra("EXTRA_RESULT_DATA", data)
            // 起動時に現在の設定を渡す
            intent.putExtra("targetKeyword", currentTargetKeyword)
            intent.putExtra("isAutoBattleEnabled", currentIsAutoBattleEnabled)
            intent.putExtra("tapOffsetX", currentTapOffsetX)
            intent.putExtra("tapOffsetY", currentTapOffsetY)
            intent.putExtra("scanInterval", currentScanInterval)
            intent.putExtra("enableResultDetection", currentEnableResultDetection)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }
}
