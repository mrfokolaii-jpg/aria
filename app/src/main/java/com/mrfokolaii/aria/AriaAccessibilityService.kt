package com.mrfokolaii.aria

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class AriaAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // خواندن و تحلیل صفحه در مرحله Agent تکمیل می‌شود.
    }

    override fun onInterrupt() {
    }
}
