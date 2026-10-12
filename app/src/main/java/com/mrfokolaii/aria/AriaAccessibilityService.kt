package com.mrfokolaii.aria

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AriaAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AriaA11y"

        @Volatile
        var lastPageText: String = ""
            private set

        @Volatile
        var instance: AriaAccessibilityService? = null
            private set
    }

    private var lastScanMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        try {
            val type = event.eventType
            if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            ) return

            val now = System.currentTimeMillis()
            if (now - lastScanMs < 400) return // throttle — prevents ANR / malfunctioning
            lastScanMs = now

            val root = rootInActiveWindow ?: return
            try {
                val builder = StringBuilder()
                collectText(root, builder, 0)
                lastPageText = builder.toString().trim().take(8000)
            } finally {
                try { root.recycle() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "onAccessibilityEvent: ${e.message}")
        }
    }

    private fun collectText(
        node: AccessibilityNodeInfo?,
        output: StringBuilder,
        depth: Int
    ) {
        if (node == null || depth > 18) return
        if (output.length > 8000) return

        try {
            val text = node.text?.toString()
            val description = node.contentDescription?.toString()

            if (!text.isNullOrBlank()) {
                output.append(text).append('\n')
            }
            if (!description.isNullOrBlank() && description != text) {
                output.append(description).append('\n')
            }

            val count = node.childCount
            for (i in 0 until count) {
                val child = try { node.getChild(i) } catch (_: Exception) { null }
                if (child != null) {
                    try {
                        collectText(child, output, depth + 1)
                    } finally {
                        try { child.recycle() } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "collectText: ${e.message}")
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }
}
