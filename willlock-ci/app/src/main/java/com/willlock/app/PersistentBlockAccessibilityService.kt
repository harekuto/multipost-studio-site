package com.willlock.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

class PersistentBlockAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val installers = setOf(
        "com.android.vending",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.miui.packageinstaller"
    )
    private var lastActionAt = 0L
    private var lastToastAt = 0L
    private var lastPolicyAt = 0L

    private val watchdog = object : Runnable {
        override fun run() {
            try { tick() } finally { handler.postDelayed(this, 250L) }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 20L
        }
        handler.removeCallbacks(watchdog)
        handler.post(watchdog)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.packageName?.toString()?.let(::blockIfNeeded)
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) foregroundPackage()?.let(::blockIfNeeded)
    }

    private fun tick() {
        OwnerGuard.releaseExpired(this)
        val state = OwnerGuard.current(this) ?: return
        if (OwnerGuard.remaining(this, state) <= 0L) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastPolicyAt >= 2_000L) {
            OwnerGuard.apply(this)
            lastPolicyAt = now
        }
        foregroundPackage()?.let(::blockIfNeeded)
    }

    private fun foregroundPackage(): String? {
        rootInActiveWindow?.packageName?.toString()?.let { return it }
        return runCatching {
            windows.firstOrNull { it.isFocused || it.isActive }
                ?.root?.packageName?.toString()
        }.getOrNull()
    }

    private fun blockIfNeeded(pkg: String) {
        val state = OwnerGuard.current(this) ?: return
        val remaining = OwnerGuard.remaining(this, state)
        if (remaining <= 0L) {
            OwnerGuard.releaseExpired(this)
            return
        }
        if (pkg == packageName || pkg == "com.android.systemui") return

        val targetMissing = runCatching { packageManager.getLaunchIntentForPackage(state.pkg) == null }.getOrDefault(false)
        val shouldBlock = pkg == state.pkg ||
            (state.strict && pkg == "com.android.settings") ||
            (state.strict && targetMissing && pkg in installers)
        if (!shouldBlock) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastActionAt < 180L) return
        lastActionAt = now
        if (!performGlobalAction(GLOBAL_ACTION_HOME)) performGlobalAction(GLOBAL_ACTION_BACK)

        if (now - lastToastAt >= 1_200L) {
            Toast.makeText(
                this,
                "${state.label} заблокировано ещё ${formatRemaining(remaining)}",
                Toast.LENGTH_SHORT
            ).show()
            lastToastAt = now
        }
    }

    private fun formatRemaining(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val days = total / 86_400L
        val hours = (total % 86_400L) / 3_600L
        val minutes = (total % 3_600L) / 60L
        val seconds = total % 60L
        return if (days > 0L) "%d д. %02d:%02d:%02d".format(days, hours, minutes, seconds)
        else "%02d:%02d:%02d".format(hours, minutes, seconds)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacks(watchdog)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        super.onDestroy()
    }
}
