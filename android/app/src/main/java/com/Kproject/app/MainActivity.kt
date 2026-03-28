package com.Kproject.app

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import com.getcapacitor.BridgeActivity
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

class MainActivity : BridgeActivity() {
    private val REQUEST_MEDIA_PROJECTION = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerPlugin(OverlayPlugin::class.java)
    }

    @CapacitorPlugin(name = "OverlayPlugin")
    inner class OverlayPlugin : Plugin() {
        @PluginMethod
        fun startOverlay(call: PluginCall) {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(call, manager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == RESULT_OK) {
            val intent = Intent(this, OverlayService::class.java)
            intent.putExtra("EXTRA_RESULT_CODE", resultCode)
            intent.putExtra("EXTRA_RESULT_DATA", data)
            startForegroundService(intent)
        }
    }
}
