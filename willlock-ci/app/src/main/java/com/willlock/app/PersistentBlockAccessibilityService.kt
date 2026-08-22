package com.willlock.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

class PersistentBlockAccessibilityService : AccessibilityService() {
    private data class State(
        val pkg: String,
        val label: String,
        val endWall: Long,
        val endElapsed: Long,
        val boot: Int,
        val strict: Boolean
    )

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
            try {
                tick()
            } finally {
                handler.postDelayed(this, 250L)
            }
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
        val pkg = event?.packageName?.toString()
        if (pkg != null) blockIfNeeded(pkg)
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            foregroundPackage()?.let(::blockIfNeeded)
        }
    }

    private fun tick() {
        val state = state() ?: return
        val remaining = remaining(state)
        if (remaining <= 0L) {
            releaseOwnerAndClear()
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastPolicyAt >= 5_000L) {
            applyOwnerPolicy(state)
            lastPolicyAt = now
        }

        foregroundPackage()?.let(::blockIfNeeded)
    }

    private fun foregroundPackage(): String? {
        rootInActiveWindow?.packageName?.toString()?.let { return it }
        return runCatching {
            windows.firstOrNull { it.isFocused || it.isActive }
                ?.root
                ?.packageName
                ?.toString()
        }.getOrNull()
    }

    private fun blockIfNeeded(pkg: String) {
        val state = state() ?: return
        if (remaining(state) <= 0L) {
            releaseOwnerAndClear()
            return
        }
        if (pkg == packageName || pkg == "com.android.systemui") return

        val targetMissing = !installed(state.pkg)
        val shouldBlock = pkg == state.pkg ||
            (state.strict && pkg == "com.android.settings") ||
            (state.strict && targetMissing && pkg in installers)
        if (!shouldBlock) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastActionAt < 180L) return
        lastActionAt = now

        val wentHome = performGlobalAction(GLOBAL_ACTION_HOME)
        if (!wentHome) performGlobalAction(GLOBAL_ACTION_BACK)

        if (now - lastToastAt >= 1_200L) {
            Toast.makeText(
                this,
                "${state.label} заблокировано ещё ${formatRemaining(remaining(state))}",
                Toast.LENGTH_SHORT
            ).show()
            lastToastAt = now
        }
    }

    private fun state(): State? {
        val p = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pkg = p.getString(KEY_PACKAGE, null) ?: return null
        val endWall = p.getLong(KEY_END_WALL, 0L)
        if (endWall <= 0L) return null
        return State(
            pkg = pkg,
            label = p.getString(KEY_LABEL, pkg) ?: pkg,
            endWall = endWall,
            endElapsed = p.getLong(KEY_END_ELAPSED, 0L),
            boot = p.getInt(KEY_BOOT, -1),
            strict = p.getBoolean(KEY_STRICT, true)
        )
    }

    private fun remaining(state: State): Long {
        val value = if (state.boot == bootCount() && state.endElapsed > 0L) {
            state.endElapsed - SystemClock.elapsedRealtime()
        } else {
            state.endWall - System.currentTimeMillis()
        }
        return value.coerceAtLeast(0L)
    }

    private fun bootCount(): Int = try {
        Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT)
    } catch (_: Exception) {
        -1
    }

    private fun installed(pkg: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(pkg, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun applyOwnerPolicy(state: State) {
        val manager = getSystemService(DevicePolicyManager::class.java)
        if (!manager.isDeviceOwnerApp(packageName)) return
        val admin = ComponentName(this, OwnerReceiver::class.java)
        runCatching { manager.setUninstallBlocked(admin, packageName, true) }
        runCatching { manager.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_DATE_TIME) }
        if (!installed(state.pkg)) {
            runCatching { manager.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS) }
        }
    }

    private fun releaseOwnerAndClear() {
        val manager = getSystemService(DevicePolicyManager::class.java)
        if (manager.isDeviceOwnerApp(packageName)) {
            val admin = ComponentName(this, OwnerReceiver::class.java)
            runCatching { manager.setUninstallBlocked(admin, packageName, false) }
            runCatching { manager.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_DATE_TIME) }
            runCatching { manager.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS) }
        }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun formatRemaining(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val days = total / 86_400L
        val hours = (total % 86_400L) / 3_600L
        val minutes = (total % 3_600L) / 60L
        val seconds = total % 60L
        return if (days > 0L) {
            "%d д. %02d:%02d:%02d".format(days, hours, minutes, seconds)
        } else {
            "%02d:%02d:%02d".format(hours, minutes, seconds)
        }
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

    companion object {
        private const val PREFS = "willlock_state"
        private const val KEY_PACKAGE = "pkg"
        private const val KEY_LABEL = "label"
        private const val KEY_END_WALL = "end_wall"
        private const val KEY_END_ELAPSED = "end_elapsed"
        private const val KEY_BOOT = "boot"
        private const val KEY_STRICT = "strict"
    }
}
