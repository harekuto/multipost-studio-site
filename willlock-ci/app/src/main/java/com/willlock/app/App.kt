package com.willlock.app

import android.accessibilityservice.AccessibilityService
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
import android.os.UserManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.accessibility.AccessibilityEvent
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

    data class State(val pkg: String, val label: String, val wall: Long, val elapsed: Long, val boot: Int, val strict: Boolean)

    fun start(c: Context, pkg: String, label: String, duration: Long, strict: Boolean) {
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(P, pkg).putString(L, label)
            .putLong(W, nowWall + duration).putLong(E, nowElapsed + duration)
            .putInt(B, bootCount(c)).putBoolean(S, strict).commit()
    }

    fun state(c: Context): State? {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pkg = p.getString(P, null) ?: return null
        val wall = p.getLong(W, 0L)
        if (wall <= 0L) return null
        return State(pkg, p.getString(L, pkg) ?: pkg, wall, p.getLong(E, 0L), p.getInt(B, -1), p.getBoolean(S, true))
    }

    fun remaining(c: Context): Long {
        val s = state(c) ?: return 0L
        val r = if (s.boot == bootCount(c) && s.elapsed > 0L) s.elapsed - SystemClock.elapsedRealtime() else s.wall - System.currentTimeMillis()
        return r.coerceAtLeast(0L)
    }

    fun active(c: Context) = remaining(c) > 0L
    fun clear(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    private fun bootCount(c: Context) = try { Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT) } catch (_: Exception) { -1 }

    fun format(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val d = total / 86400
        val h = (total % 86400) / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (d > 0) "%d д. %02d:%02d:%02d".format(d, h, m, s) else "%02d:%02d:%02d".format(h, m, s)
    }
}

private object Policies {
    fun component(c: Context) = ComponentName(c, OwnerReceiver::class.java)
    private fun dpm(c: Context) = c.getSystemService(DevicePolicyManager::class.java)
    fun admin(c: Context) = dpm(c).isAdminActive(component(c))
    fun owner(c: Context) = dpm(c).isDeviceOwnerApp(c.packageName)

    fun apply(c: Context) {
        if (!owner(c) || !LockStore.active(c)) return
        val state = LockStore.state(c) ?: return
        val m = dpm(c)
        val a = component(c)
        runCatching { m.setUninstallBlocked(a, c.packageName, true) }
        runCatching { m.addUserRestriction(a, UserManager.DISALLOW_CONFIG_DATE_TIME) }
        if (!installed(c, state.pkg)) runCatching { m.addUserRestriction(a, UserManager.DISALLOW_INSTALL_APPS) }
    }

    fun releaseExpired(c: Context) {
        if (LockStore.state(c) == null || LockStore.remaining(c) > 0L) return
        if (owner(c)) {
            val m = dpm(c); val a = component(c)
            runCatching { m.setUninstallBlocked(a, c.packageName, false) }
            runCatching { m.clearUserRestriction(a, UserManager.DISALLOW_CONFIG_DATE_TIME) }
            runCatching { m.clearUserRestriction(a, UserManager.DISALLOW_INSTALL_APPS) }
        }
        LockStore.clear(c)
    }

    fun installed(c: Context, pkg: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= 33) c.packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        else @Suppress("DEPRECATION") c.packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) { false }
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
        override fun run() { render(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(b: Bundle?) { super.onCreate(b); buildUi() }
    override fun onResume() { super.onResume(); Policies.releaseExpired(this); handler.removeCallbacks(ticker); handler.post(ticker) }
    override fun onPause() { handler.removeCallbacks(ticker); super.onPause() }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(246, 248, 252)) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(36)) }
        scroll.addView(root)
        root.addView(TextView(this).apply { text = "WillLock"; textSize = 32f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.rgb(20, 25, 35)) })
        root.addView(TextView(this).apply { text = "Жёсткий самоконтроль. После запуска таймера досрочной отмены в приложении нет."; textSize = 15f; setTextColor(Color.DKGRAY); setPadding(0, dp(8), 0, dp(18)) })
        status = TextView(this).apply { textSize = 16f; setPadding(dp(16), dp(16), dp(16), dp(16)); background = rounded(Color.WHITE, Color.LTGRAY) }
        root.addView(status)
        root.addView(title("1. Что заблокировать"))
        appButton = button("Выбрать приложение", false).apply { setOnClickListener { chooseApp() } }
        root.addView(appButton)
        root.addView(title("2. На сколько"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        duration = EditText(this).apply { setText("24"); hint = "24"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setPadding(dp(12), 0, dp(12), 0); background = rounded(Color.WHITE, Color.LTGRAY) }
        row.addView(duration, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(8) })
        unit = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("минут", "часов", "дней")); setSelection(1) }
        row.addView(unit, LinearLayout.LayoutParams(dp(125), dp(52)))
        root.addView(row)
        strict = CheckBox(this).apply { text = "Строгий режим"; isChecked = true; textSize = 16f; setPadding(0, dp(10), 0, 0) }
        root.addView(strict)
        root.addView(TextView(this).apply { text = "В строгом режиме WillLock блокирует системные настройки во время таймера. Если выбранное приложение удалить, блокируются Play Store и известные установщики до конца срока."; textSize = 13f; setTextColor(Color.DKGRAY); setPadding(0, dp(4), 0, dp(14)) })
        root.addView(title("3. Защита"))
        root.addView(button("Включить Accessibility", false).apply { setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } })
        admin = button("Включить Device Admin", false).apply { setOnClickListener { handleAdmin() } }
        root.addView(admin, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(8) })
        start = button("ЗАПУСТИТЬ БЛОКИРОВКУ", true).apply { setOnClickListener { confirmStart() } }
        root.addView(start, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(20) })
        root.addView(TextView(this).apply { text = "Обычный APK нельзя сделать абсолютно неуязвимым для Safe Mode, factory reset или ADB. Device Owner даёт более строгую системную защиту."; textSize = 12f; setTextColor(Color.DKGRAY); setPadding(0, dp(16), 0, 0) })
        setContentView(scroll)
    }

    private fun render() {
        Policies.releaseExpired(this)
        val s = LockStore.state(this)
        val r = LockStore.remaining(this)
        val active = s != null && r > 0
        status.text = if (active) "🔒 АКТИВНО\n${s!!.label}\nОсталось: ${LockStore.format(r)}\nAccessibility: ${yes(accessibility())} · Admin: ${yes(Policies.admin(this))} · Owner: ${yes(Policies.owner(this))}" else "Готово\nAccessibility: ${yes(accessibility())} · Admin: ${yes(Policies.admin(this))} · Owner: ${yes(Policies.owner(this))}"
        appButton.isEnabled = !active; duration.isEnabled = !active; unit.isEnabled = !active; strict.isEnabled = !active; start.isEnabled = !active
        start.text = if (active) "БЛОКИРОВКА АКТИВНА" else "ЗАПУСТИТЬ БЛОКИРОВКУ"
        admin.isEnabled = !active
        admin.text = if (Policies.admin(this)) "Device Admin включён" else "Включить Device Admin"
        if (active) Policies.apply(this)
    }

    private fun chooseApp() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val results = if (Build.VERSION.SDK_INT >= 33) packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0)) else @Suppress("DEPRECATION") packageManager.queryIntentActivities(intent, 0)
        val apps = results.mapNotNull { r ->
            val pkg = r.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == packageName) null else AppItem(r.loadLabel(packageManager)?.toString()?.ifBlank { pkg } ?: pkg, pkg)
        }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
        AlertDialog.Builder(this).setTitle("Выберите приложение").setItems(apps.map { "${it.label}\n${it.pkg}" }.toTypedArray()) { _, i -> selected = apps[i]; appButton.text = apps[i].label }.setNegativeButton("Отмена", null).show()
    }

    private fun confirmStart() {
        val a = selected ?: return toast("Сначала выберите приложение")
        if (!accessibility()) { toast("Сначала включите WillLock в Accessibility"); startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return }
        if (strict.isChecked && !Policies.admin(this)) { toast("Для строгого режима включите Device Admin"); requestAdmin(); return }
        val n = duration.text.toString().replace(',', '.').toDoubleOrNull() ?: return toast("Введите срок")
        val mult = when (unit.selectedItemPosition) { 0 -> 60_000.0; 1 -> 3_600_000.0; else -> 86_400_000.0 }
        val ms = (n * mult).roundToLong()
        if (ms < 60_000L || ms > 366L * 86_400_000L) return toast("Допустимо: 1 минута — 366 дней")
        AlertDialog.Builder(this).setTitle("Запустить без досрочной отмены?").setMessage("${a.label} будет заблокировано на ${LockStore.format(ms)}.").setPositiveButton("ЗАБЛОКИРОВАТЬ") { _, _ -> LockStore.start(this, a.pkg, a.label, ms, strict.isChecked); Policies.apply(this); render() }.setNegativeButton("Назад", null).show()
    }

    private fun handleAdmin() { if (!Policies.admin(this)) requestAdmin() else toast("Device Admin можно снять в системных настройках только когда таймер не активен") }
    private fun requestAdmin() = startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply { putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, Policies.component(this@MainActivity)); putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Дополнительная защита WillLock от удаления во время самоконтроля") })
    private fun accessibility(): Boolean = getSystemService(android.view.accessibility.AccessibilityManager::class.java).getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { it.resolveInfo.serviceInfo.packageName == packageName }
    private fun yes(v: Boolean) = if (v) "да" else "нет"
    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }
    private fun title(s: String) = TextView(this).apply { text = s; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(20), 0, dp(8)) }
    private fun button(s: String, primary: Boolean) = Button(this).apply { text = s; isAllCaps = false; textSize = 15f; gravity = Gravity.CENTER; setTextColor(if (primary) Color.WHITE else Color.rgb(30, 35, 45)); background = rounded(if (primary) Color.rgb(35, 86, 216) else Color.WHITE, if (primary) Color.rgb(35, 86, 216) else Color.LTGRAY) }
    private fun rounded(fill: Int, stroke: Int) = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(fill); setStroke(dp(1), stroke) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

class BlockAccessibilityService : AccessibilityService() {
    private val installers = setOf("com.android.vending", "com.google.android.packageinstaller", "com.android.packageinstaller", "com.samsung.android.packageinstaller", "com.miui.packageinstaller")
    private var lastToast = 0L

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        Policies.releaseExpired(this)
        if (!LockStore.active(this)) return
        val s = LockStore.state(this) ?: return
        Policies.apply(this)
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return
        val missing = !Policies.installed(this, s.pkg)
        val block = pkg == s.pkg || (s.strict && pkg == "com.android.settings") || (s.strict && missing && pkg in installers)
        if (!block) return
        performGlobalAction(GLOBAL_ACTION_HOME)
        val now = System.currentTimeMillis()
        if (now - lastToast > 900) { Toast.makeText(this, "Заблокировано ещё ${LockStore.format(LockStore.remaining(this))}", Toast.LENGTH_SHORT).show(); lastToast = now }
    }
    override fun onInterrupt() = Unit
}

class OwnerReceiver : DeviceAdminReceiver()

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) { Policies.releaseExpired(c); Policies.apply(c) }
}
