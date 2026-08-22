package com.willlock.app

import android.app.admin.DeviceAdminService
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings

object OwnerGuard {
    data class State(
        val pkg: String,
        val label: String,
        val endWall: Long,
        val endElapsed: Long,
        val boot: Int,
        val strict: Boolean
    )

    private const val PREFS = "willlock_state"
    private const val KEY_PACKAGE = "pkg"
    private const val KEY_LABEL = "label"
    private const val KEY_END_WALL = "end_wall"
    private const val KEY_END_ELAPSED = "end_elapsed"
    private const val KEY_BOOT = "boot"
    private const val KEY_STRICT = "strict"

    private val restrictions = arrayOf(
        UserManager.DISALLOW_APPS_CONTROL,
        UserManager.DISALLOW_UNINSTALL_APPS,
        UserManager.DISALLOW_DEBUGGING_FEATURES,
        UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_FACTORY_RESET,
        UserManager.DISALLOW_CONFIG_DATE_TIME
    )

    fun isOwner(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java)
            .isDeviceOwnerApp(context.packageName)

    fun current(context: Context): State? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pkg = p.getString(KEY_PACKAGE, null) ?: return null
        val wall = p.getLong(KEY_END_WALL, 0L)
        if (wall <= 0L) return null
        return State(
            pkg = pkg,
            label = p.getString(KEY_LABEL, pkg) ?: pkg,
            endWall = wall,
            endElapsed = p.getLong(KEY_END_ELAPSED, 0L),
            boot = p.getInt(KEY_BOOT, -1),
            strict = p.getBoolean(KEY_STRICT, true)
        )
    }

    fun remaining(context: Context, state: State = current(context) ?: return 0L): Long {
        val value = if (state.boot == bootCount(context) && state.endElapsed > 0L) {
            state.endElapsed - SystemClock.elapsedRealtime()
        } else {
            state.endWall - System.currentTimeMillis()
        }
        return value.coerceAtLeast(0L)
    }

    fun apply(context: Context) {
        val state = current(context) ?: return
        if (remaining(context, state) <= 0L) {
            releaseExpired(context)
            return
        }
        if (!state.strict || !isOwner(context)) return

        val manager = context.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(context, OwnerReceiver::class.java)

        runCatching { manager.setUninstallBlocked(admin, context.packageName, true) }
        runCatching { manager.setUninstallBlocked(admin, state.pkg, true) }

        if (installed(context, state.pkg)) {
            runCatching { manager.setPackagesSuspended(admin, arrayOf(state.pkg), true) }
        } else {
            runCatching { manager.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS) }
        }

        restrictions.forEach { key ->
            runCatching { manager.addUserRestriction(admin, key) }
        }

        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                manager.setUserControlDisabledPackages(
                    admin,
                    listOf(context.packageName, state.pkg)
                )
            }
        }
    }

    fun releaseExpired(context: Context) {
        val state = current(context) ?: return
        if (remaining(context, state) > 0L) return
        release(context, state)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    fun releaseAll(context: Context) {
        val state = current(context)
        if (state != null) release(context, state)
        else release(context, null)
    }

    private fun release(context: Context, state: State?) {
        if (!isOwner(context)) return
        val manager = context.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(context, OwnerReceiver::class.java)

        state?.let {
            runCatching { manager.setPackagesSuspended(admin, arrayOf(it.pkg), false) }
            runCatching { manager.setUninstallBlocked(admin, it.pkg, false) }
        }
        runCatching { manager.setUninstallBlocked(admin, context.packageName, false) }
        runCatching { manager.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS) }
        restrictions.forEach { key ->
            runCatching { manager.clearUserRestriction(admin, key) }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching { manager.setUserControlDisabledPackages(admin, emptyList()) }
        }
    }

    private fun installed(context: Context, pkg: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(pkg, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun bootCount(context: Context): Int = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (_: Exception) {
        -1
    }
}

class OwnerWatchService : DeviceAdminService() {
    private val handler = Handler(Looper.getMainLooper())
    private val watcher = object : Runnable {
        override fun run() {
            OwnerGuard.releaseExpired(this@OwnerWatchService)
            OwnerGuard.apply(this@OwnerWatchService)
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        handler.post(watcher)
    }

    override fun onDestroy() {
        handler.removeCallbacks(watcher)
        super.onDestroy()
    }
}
