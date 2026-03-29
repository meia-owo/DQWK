package com.Kproject.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class AutoTapService : AccessibilityService() {

    companion object {
        var instance: AutoTapService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun performTap(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        val builder = GestureDescription.Builder()
        // タップ時間を 50ms ~ 150ms の間でランダム化して人間らしさを出す
        val duration = kotlin.random.Random.nextLong(50, 150)
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
        dispatchGesture(builder.build(), null, null)
    }

    fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 300L) {
        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)
        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
        dispatchGesture(builder.build(), null, null)
    }
}
