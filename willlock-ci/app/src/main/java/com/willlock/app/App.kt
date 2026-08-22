package com.willlock.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToLong

private object LockStore {
    private const val PREFS = "willlock_state"
    private const val P = "pkg"
    private const val L = "label"
    private const val W = "end_wall"
    private const val E = "end_elapsed"
    private const val B = "boot"
    private const val S = "strict"

    data class State(
        val pkg: String,
        val label: String,
        val wall: Long,
        val elapsed: Long,
        val boot: Int,
        val strict: Boolean
    )

    fun start(context: Context, pkg: String, label: String, duration: Long, strict: Boolean) {
        require(pkg != context.packageName)
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(P, pkg)
            .putString(L, label)
            .putLong(W, nowWall + duration)
            .putLong(E, nowElapsed + duration)
            .putInt(B, bootCount(context))
            .putBoolean(S, strict)
            .commit()
    }

    fun state(context: Context): State? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pkg = p.getString(P, null) ?: return null
        val wall = p.getLong(W, 0L)
        if (wall <= 0L) return null
        return State(
            pkg,
            p.getString(L, pkg) ?: pkg,
            wall,
            p.getLong(E, 0L),
            p.getInt(B, -1),
            p.getBoolean(S, true)
        )
    }

    fun remaining(context: Context): Long {
        val s = state(context) ?: return 0L
        val value = if (s.boot == bootCount(context) && s.elapsed > 0L) {
            s.elapsed - SystemClock.elapsedRealtime()
        } else {
            s.wall - System.currentTimeMillis()
        }
        return value.coerceAtLeast(0L)
    }

    fun active(context: Context): Boolean = remaining(context) > 0L

    private fun bootCount(context: Context): Int = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (_: Exception) {
        -1
    }

    fun format(ms: Long): String {
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
}

private object Policies {
    fun component(context: Context) = ComponentName(context, OwnerReceiver::class.java)
    private fun dpm(context: Context) = context.getSystemService(DevicePolicyManager::class.java)
    fun admin(context: Context): Boolean = dpm(context).isAdminActive(component(context))
    fun owner(context: Context): Boolean = OwnerGuard.isOwner(context)
    fun apply(context: Context) = OwnerGuard.apply(context)
    fun releaseExpired(context: Context) = OwnerGuard.releaseExpired(context)
}

class MainActivity : Activity() {
    data class AppItem(val label: String, val pkg: String)

    private var selected: AppItem? = null
    private lateinit var appButton: Button
    private lateinit var duration: EditText
    private lateinit var unit: Spinner
    private lateinit var strict: CheckBox
    private lateinit var status: TextView
    private lateinit var start: Button
    private lateinit var admin: Button
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        Policies.releaseExpired(this)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(246, 248, 252)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(36))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "WillLock"
            textSize = 32f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(20, 25, 35))
        })
        root.addView(TextView(this).apply {
            text = "Самоконтроль с системной защитой. В строгом Device Owner-режиме досрочной отмены нет."
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(8), 0, dp(18))
        })

        status = TextView(this).apply {
            textSize = 16f
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(Color.WHITE, Color.LTGRAY)
        }
        root.addView(status)

        root.addView(title("1. Что заблокировать"))
        appButton = button("Выбрать приложение", false).apply { setOnClickListener { chooseApp() } }
        root.addView(appButton)

        root.addView(title("2. На сколько"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        duration = EditText(this).apply {
            setText("24")
            hint = "24"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(Color.WHITE, Color.LTGRAY)
        }
        row.addView(duration, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(8) })
        unit = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("минут", "часов", "дней")
            )
            setSelection(1)
        }
        row.addView(unit, LinearLayout.LayoutParams(dp(125), dp(52)))
        root.addView(row)

        strict = CheckBox(this).apply {
            text = "Супер-защита (Device Owner)"
            isChecked = true
            textSize = 16f
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(strict)
        root.addView(TextView(this).apply {
            text = "При активном Device Owner выбранное приложение системно приостанавливается. До конца срока блокируются удаление/force-stop, ADB debugging, Safe Mode, смена времени и factory reset из Настроек."
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(4), 0, dp(14))
        })

        root.addView(title("3. Защита"))
        root.addView(button("Включить Accessibility", false).apply {
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        admin = button("Включить Device Admin", false).apply { setOnClickListener { handleAdmin() } }
        root.addView(admin, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(8) })

        root.addView(TextView(this).apply {
            text = "Device Owner активируется системным provisioning. Для ADB на подготовленном/сброшенном устройстве: adb shell dpm set-device-owner com.willlock.app/.OwnerReceiver"
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(12), 0, 0)
        })

        start = button("ЗАПУСТИТЬ БЛОКИРОВКУ", true).apply { setOnClickListener { confirmStart() } }
        root.addView(start, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(20) })

        root.addView(TextView(this).apply {
            text = "Root, разблокированный bootloader, recovery/перепрошивка и физический factory reset нельзя гарантированно запретить обычному Android-приложению."
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(16), 0, 0)
        })
        setContentView(scroll)
    }

    private fun render() {
        Policies.releaseExpired(this)
        val state = LockStore.state(this)
        val remaining = LockStore.remaining(this)
        val active = state != null && remaining > 0L
        val owner = Policies.owner(this)
        status.text = if (active) {
            "🔒 АКТИВНО\n${state!!.label}\nОсталось: ${LockStore.format(remaining)}\nAccessibility: ${yes(accessibility())} · Admin: ${yes(Policies.admin(this))} · Device Owner: ${yes(owner)}"
        } else {
            "Готово\nAccessibility: ${yes(accessibility())} · Admin: ${yes(Policies.admin(this))} · Device Owner: ${yes(owner)}"
        }

        appButton.isEnabled = !active
        duration.isEnabled = !active
        unit.isEnabled = !active
        strict.isEnabled = !active
        start.isEnabled = !active
        start.text = if (active) "БЛОКИРОВКА АКТИВНА" else "ЗАПУСТИТЬ БЛОКИРОВКУ"
        admin.isEnabled = !active
        admin.text = when {
            owner -> "Device Owner включён"
            Policies.admin(this) -> "Device Admin включён"
            else -> "Включить Device Admin"
        }
        if (active) Policies.apply(this)
    }

    private fun chooseApp() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val results = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }
        val apps = results.mapNotNull { result ->
            val pkg = result.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == packageName) null
            else AppItem(result.loadLabel(packageManager)?.toString()?.ifBlank { pkg } ?: pkg, pkg)
        }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }

        if (apps.isEmpty()) return toast("Список приложений пуст")
        AlertDialog.Builder(this)
            .setTitle("Выберите приложение")
            .setItems(apps.map { "${it.label}\n${it.pkg}" }.toTypedArray()) { _, index ->
                selected = apps[index]
                appButton.text = apps[index].label
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmStart() {
        val app = selected ?: return toast("Сначала выберите приложение")
        if (!accessibility()) {
            toast("Сначала включите WillLock в Accessibility")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (strict.isChecked && !Policies.owner(this)) {
            toast("Для супер-защиты нужен Device Owner. Обычного Device Admin недостаточно.")
            return
        }

        val number = duration.text.toString().replace(',', '.').toDoubleOrNull() ?: return toast("Введите срок")
        val multiplier = when (unit.selectedItemPosition) {
            0 -> 60_000.0
            1 -> 3_600_000.0
            else -> 86_400_000.0
        }
        val ms = (number * multiplier).roundToLong()
        if (ms < 60_000L || ms > 366L * 86_400_000L) return toast("Допустимо: 1 минута — 366 дней")

        val mode = if (strict.isChecked) "Супер-защита Device Owner будет активна." else "Будет использована Accessibility-защита."
        AlertDialog.Builder(this)
            .setTitle("Запустить без досрочной отмены?")
            .setMessage("${app.label} будет заблокировано на ${LockStore.format(ms)}.\n\n$mode")
            .setPositiveButton("ЗАБЛОКИРОВАТЬ") { _, _ ->
                LockStore.start(this, app.pkg, app.label, ms, strict.isChecked)
                Policies.apply(this)
                render()
            }
            .setNegativeButton("Назад", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun handleAdmin() {
        if (LockStore.active(this)) return toast("До окончания таймера защита не отключается")
        val manager = getSystemService(DevicePolicyManager::class.java)
        val component = Policies.component(this)
        when {
            Policies.owner(this) -> {
                AlertDialog.Builder(this)
                    .setTitle("Снять Device Owner?")
                    .setMessage("Это разрешено только когда таймер не активен. Системные ограничения будут сняты.")
                    .setPositiveButton("СНЯТЬ") { _, _ ->
                        OwnerGuard.releaseAll(this)
                        runCatching { manager.clearDeviceOwnerApp(packageName) }
                            .onFailure { toast("Android не разрешил снять Device Owner") }
                        render()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
            Policies.admin(this) -> {
                manager.removeActiveAdmin(component)
                toast("Device Admin отключается")
            }
            else -> requestAdmin()
        }
    }

    private fun requestAdmin() {
        startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, Policies.component(this@MainActivity))
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Дополнительная защита WillLock")
        })
    }

    private fun accessibility(): Boolean =
        getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }

    private fun yes(value: Boolean) = if (value) "да" else "нет"
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(8))
    }
    private fun button(text: String, primary: Boolean) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(if (primary) Color.WHITE else Color.rgb(30, 35, 45))
        background = rounded(
            if (primary) Color.rgb(35, 86, 216) else Color.WHITE,
            if (primary) Color.rgb(35, 86, 216) else Color.LTGRAY
        )
    }
    private fun rounded(fill: Int, stroke: Int) = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(fill)
        setStroke(dp(1), stroke)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

class OwnerReceiver : DeviceAdminReceiver()

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        OwnerGuard.releaseExpired(context)
        OwnerGuard.apply(context)
    }
}
