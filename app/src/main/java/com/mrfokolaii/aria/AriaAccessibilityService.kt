package com.mrfokolaii.aria

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AriaAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var lastPageText: String = ""
            private set
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return

        val builder = StringBuilder()

        collectText(root, builder)

        lastPageText = builder
            .toString()
            .trim()
            .take(12000)
    }

    private fun collectText(
        node: AccessibilityNodeInfo?,
        output: StringBuilder
    ) {
        if (node == null) return

        val text = node.text?.toString()
        val description = node.contentDescription?.toString()

        if (!text.isNullOrBlank()) {
            output.append(text).append('\n')
        }

        if (
            !description.isNullOrBlank() &&
            description != text
        ) {
            output.append(description).append('\n')
        }

        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), output)
        }
    }

    override fun onInterrupt() {
    }
}
