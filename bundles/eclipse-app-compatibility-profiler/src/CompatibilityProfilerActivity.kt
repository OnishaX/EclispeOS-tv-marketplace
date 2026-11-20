package com.example.eclipse

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future

class CompatibilityProfilerActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val mapping by lazy { RemoteButtonMapping(this) }
    private lateinit var panel: SettingsPanelView
    private lateinit var content: FrameLayout
    private lateinit var particles: ParticleBackgroundView
    private lateinit var appSettings: ItemSettingsView
    private lateinit var qr: ProfilerQrView
    private lateinit var clock: ClockView
    private var report: JSONObject? = null
    private var scanned = false
    private var page = 0
    private var reportParentRow: String? = null
    private var busy = false
    private var generation = 0
    private var job: Future<*>? = null
    private var github: ProfilerGitHub? = null
    private var authorization: ProfilerGitHub.SignIn? = null
    private var signedIn: String? = null
    private var pullRequest: String? = null
    private var dialog: AlertDialog? = null
    private val sections = listOf("Scan", "Display", "Sound", "OS", "Remote", "Buffers", "Contribute")
    private val keys = listOf("", "display", "sound", "os", "remote", "buffers", "")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        report = savedInstanceState?.getString("report")?.let { JSONObject(it).also(TvCapabilities::parse) }
        scanned = savedInstanceState?.getBoolean("scanned") == true
        page = savedInstanceState?.getInt("page", 0) ?: 0
        reportParentRow = savedInstanceState?.getString("reportParentRow")
        pullRequest = savedInstanceState?.getString("pullRequest")
        particles = ParticleBackgroundView(this).apply {
            glowStrength = 0f
            setAccentColor(0xFF4A505C.toInt(), 0)
        }
        panel = SettingsPanelView(this).apply {
            drawsOwnBackground = false
            focusColor = 0xFF4A505C.toInt()
            pageCategories = sections.mapIndexed { index, title -> SettingsRow("profiler:$index", title) }
            showBack = reportParentRow != null
            onCategory = { reportParentRow = null; showBack = false; page = it; render() }
            onBack = {
                if (busy) cancel()
                else reportParentRow?.let { row ->
                    beginPageTransition(forward = false)
                    reportParentRow = null
                    showBack = false
                    page = 0
                    render(focusId = row)
                }
            }
            glowStrength = HomePrefs(this@CompatibilityProfilerActivity).glowIntensityOther / 100f
        }
        qr = ProfilerQrView(this)
        clock = ClockView(this)
        content = FrameLayout(this).apply {
            addView(particles, FrameLayout.LayoutParams(-1, -1))
            addView(panel, FrameLayout.LayoutParams(-1, -1))
            addView(qr, FrameLayout.LayoutParams(-1, -1))
            addView(clock, FrameLayout.LayoutParams(-1, -1))
        }
        appSettings = ItemSettingsView(this).apply {
            prefs = HomePrefs(this@CompatibilityProfilerActivity)
            particleSource = particles
            glowStrength = requireNotNull(prefs).glowIntensityOther / 100f
            onColorChanged = { particles.setAccentColor(it); panel.focusColor = it }
            onCovered = { hidden ->
                listOf(panel, qr, clock).forEach { it.visibility = if (hidden) View.INVISIBLE else View.VISIBLE }
            }
            onClosed = { panel.requestFocus() }
        }
        content.addView(appSettings, FrameLayout.LayoutParams(-1, -1))
        content.alpha = 0f
        setContentView(UiSurfaceHost.install(window, content))
        content.animate().alpha(1f).setDuration(APP_REVEAL_DURATION_MS).start()
        render()
        panel.requestFocus()
    }

    private fun openReport(row: String) {
        panel.beginPageTransition(forward = true)
        reportParentRow = row
        panel.showBack = true
        page = 1
    }

    private fun render(focusId: String? = panel.selectedRowId) {
        qr.setCode(if (page == 6) authorization?.verificationUrl else null)
        val rows = when (page) {
            0 -> listOf(
                SettingsRow("scan", "Scan this TV", R.drawable.ic_compatibility_profiler, enabled = !busy) {
                    submit(onSuccess = {
                        report = it
                        scanned = true
                        openReport("scan")
                        val missing = it.getJSONObject("verification").getJSONArray("unavailableProbes").length()
                        if (missing > 0) Toast.makeText(this, "$missing probes unavailable. Review Scan verification.", Toast.LENGTH_LONG).show()
                    }) { TvCapabilityReport(this).scan() }
                },
                SettingsRow("adb", "Authorize local ADB", R.drawable.ic_settings, enabled = !busy) { authorizeAdb() },
                SettingsRow("verification", "Scan verification", value =
                    report?.optJSONObject("verification")?.optJSONArray("unavailableProbes")?.length()?.let { "$it unavailable probes" }
                        ?: "No scan yet", enabled = report != null) {
                    val verification = report?.optJSONObject("verification")
                    explain(verification?.let { flatten(it).joinToString("\n") { (name, value) -> "$name: $value" } }
                        ?: "No verification data is recorded.")
                },
                SettingsRow("privacy", "Collection and privacy") {
                    explain("Only hardware/firmware capability fields are collected. Raw ADB dumps stay in memory and are discarded. " +
                        "Serial numbers, network addresses, SSIDs, accounts and credentials are excluded. " +
                        "Reported codec/key presence is not playback or key-delivery verification. " +
                        "Read every section before explicitly creating a public GitHub pull request.")
                },
                SettingsRow("profile", "Installed model profile", value =
                    if (EclipseBundles(this, HomePrefs(this)).activeCompatibility() == null) "Not installed" else "Inspect") {
                    EclipseBundles(this, HomePrefs(this)).activeCompatibility()?.capabilities?.let {
                        report = JSONObject(it.source)
                        scanned = false
                        openReport("profile")
                        render(focusId = null)
                    } ?: explain("No capability report is installed for this model and firmware.")
                },
                SettingsRow("writes", "Scan is read-only", value = "No display or sound changes", enabled = false),
            )
            6 -> contributionRows()
            else -> report?.optJSONObject(keys[page])?.let { section ->
                flatten(section).mapIndexed { index, (name, value) ->
                    SettingsRow("fact:$index", name, value = value) { explain("$name\n\n$value") }
                }
            } ?: listOf(SettingsRow("empty", "Run a scan first", enabled = false))
        }
        panel.message = if (busy && authorization == null) "Working..." else null
        panel.showPage(rows, category = page, focusCategories = panel.categoriesFocused, focusId = focusId)
    }

    private fun contributionRows(): List<SettingsRow> {
        authorization?.let { return listOf(
            SettingsRow("code", "GitHub code", value = it.userCode, enabled = false),
            SettingsRow("url", "Scan the QR code", value = "github.com/login/device", enabled = false),
            SettingsRow("cancel", "Cancel sign-in") { cancel() },
        ) }
        return buildList {
            add(SettingsRow("review", "Review all five report sections first", enabled = false))
            if (!scanned) add(SettingsRow("fresh", "Run a fresh scan before contributing", enabled = false))
            add(SettingsRow("scope", "GitHub permission", value = "Public repositories", enabled = false))
            if (signedIn == null) add(SettingsRow("signin", "Sign in with GitHub", enabled = scanned && report != null && !busy) { signIn() })
            else {
                add(SettingsRow("account", "Signed in as", value = signedIn, enabled = false))
                add(SettingsRow("publish", "Create public pull request", enabled = scanned && report != null && !busy) { publish() })
                add(SettingsRow("signout", "Sign out", enabled = !busy) { cancel() })
            }
            pullRequest?.let { url -> add(SettingsRow("pr", "Pull request created", value = url) { explain(url) }) }
            if (busy) add(SettingsRow("cancel", "Cancel") { cancel() })
        }
    }

    private fun authorizeAdb() {
        tvTextEntry(this, "Local ADB port", LocalNetworkAdb.get(this).port.toString(), false,
            validate = { if (it.toIntOrNull() in 1..65535) null else "Use a port between 1 and 65535." },
            onDismiss = {}, maxLength = 5) { text ->
            submit(onSuccess = {}) { LocalNetworkAdb.get(this).enableProfiler(text.toInt()) }
        }
    }

    private fun signIn() {
        val manifest = assets.open("bundles/${BundleIds.COMPATIBILITY_PROFILER}/manifest.json")
            .bufferedReader().use { JSONObject(it.readText()) }
        val client = ProfilerGitHub(manifest.getJSONObject("configuration").getString("githubClientId"))
        github?.close()
        github = client
        val current = generation + 1
        submit(onSuccess = { user: String -> signedIn = user }) {
            val auth = client.start()
            main.post { if (generation == current && !isDestroyed) { authorization = auth; render() } }
            val deadline = SystemClock.elapsedRealtime() + auth.expiresIn * 1000L
            var interval = auth.interval
            var user: String? = null
            while (user == null) {
                Thread.sleep(minOf(interval * 1000L, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)))
                if (SystemClock.elapsedRealtime() >= deadline) throw IOException("The GitHub code expired. Start sign-in again.")
                when (val result = client.poll(auth, interval)) {
                    is ProfilerGitHub.Poll.Pending -> interval = result.interval
                    is ProfilerGitHub.Poll.Authorized -> {
                        user = result.login
                    }
                }
            }
            requireNotNull(user)
        }
    }

    private fun publish() {
        val facts = requireNotNull(report)
        val client = requireNotNull(github)
        submit(onSuccess = { pullRequest = it }) {
            val bitmap = android.graphics.Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            val bytes = try {
                val icon = requireNotNull(androidx.appcompat.content.res.AppCompatResources.getDrawable(this, R.drawable.ic_tv_compatibility))
                icon.setBounds(0, 0, 512, 512)
                icon.draw(Canvas(bitmap))
                ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
            } finally { bitmap.recycle() }
            client.contribute(facts, TvDevice.current(), bytes)
        }
    }

    private fun <T> submit(onSuccess: (T) -> Unit, work: () -> T) {
        if (busy) return
        busy = true
        val current = ++generation
        render()
        job = worker.submit {
            var error: Exception? = null
            var complete: (() -> Unit)? = null
            try { val value = work(); complete = { onSuccess(value) } }
            catch (e: IOException) { error = e }
            catch (e: JSONException) { error = e }
            catch (e: IllegalArgumentException) { error = e }
            catch (e: IllegalStateException) { error = e }
            catch (e: SecurityException) { error = e }
            catch (e: InterruptedException) { Thread.currentThread().interrupt() }
            val failure = error
            main.post {
                if (generation != current || isDestroyed) return@post
                busy = false
                authorization = null
                complete?.invoke()
                failure?.let {
                    Log.w("CompatibilityProfiler", "Profiler operation failed", it)
                    Toast.makeText(this, it.message ?: "Compatibility operation failed.", Toast.LENGTH_LONG).show()
                }
                render()
            }
        }
    }

    private fun explain(message: String) {
        dialog = AlertDialog.Builder(this, R.style.Theme_Eclipse_Input).setTitle("Compatibility Profiler")
            .setMessage(message).setPositiveButton("Close", null).create().also { it.show(); UiSurfaceHost.install(it) }
    }
    private fun cancel() {
        generation++
        job?.cancel(true)
        github?.close()
        github = null
        signedIn = null
        authorization = null
        busy = false
        render()
    }
    private fun goHome() {
        startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
    private fun settings() {
        if (dialog?.isShowing == true) return
        if (appSettings.isShowing) appSettings.close()
        else appSettings.showApp(EclipseBundles(this, HomePrefs(this)).packagedItem(BundleIds.COMPATIBILITY_PROFILER), content)
    }
    override fun onResume() {
        super.onResume()
        UserButtonShortcutService.onEclipseSource = { QuickSource(packageName, "Compatibility Profiler", eclipse = true) }
        UserButtonShortcutService.onAppSettingsButton = ::settings
        UserButtonShortcutService.onCloseEclipse = ::goHome
    }
    override fun onPause() {
        UserButtonShortcutService.onEclipseSource = null
        UserButtonShortcutService.onAppSettingsButton = null
        UserButtonShortcutService.onCloseEclipse = null
        if (authorization != null) cancel()
        super.onPause()
    }
    override fun onStart() { super.onStart(); particles.setRunning(true) }
    override fun onStop() {
        particles.setRunning(false)
        if (busy || github != null) cancel()
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        report?.let { outState.putString("report", it.toString()) }
        outState.putBoolean("scanned", scanned)
        outState.putInt("page", page)
        reportParentRow?.let { outState.putString("reportParentRow", it) }
        pullRequest?.let { outState.putString("pullRequest", it) }
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { generation++; worker.shutdownNow(); github?.close(); dialog?.dismiss(); super.onDestroy() }
    override fun dispatchKeyEvent(rawEvent: KeyEvent): Boolean {
        if (!::panel.isInitialized || dialog?.isShowing == true) return super.dispatchKeyEvent(rawEvent)
        val event = mapping.mappedEvent(rawEvent)
        if (event.keyCode == KeyEvent.KEYCODE_HOME) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) goHome()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_SETTINGS) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) settings()
            return true
        }
        if (appSettings.isShowing) return appSettings.handleKey(event)
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) panel.onBack()
            return true
        }
        if (event.keyCode in NAV_KEYS) {
            val center = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
            if (event.action == if (center) KeyEvent.ACTION_UP else KeyEvent.ACTION_DOWN) {
                if (!event.isCanceled) panel.handleKey(event.keyCode, event.repeatCount > 0)
            }
            if (event.action == KeyEvent.ACTION_UP) panel.releaseKey(event.keyCode)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        private val NAV_KEYS = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)

        internal fun flatten(json: JSONObject): List<Pair<String, String>> {
            val rows = mutableListOf<Pair<String, String>>()
            fun visit(path: String, value: Any?) {
                when (value) {
                    is JSONObject -> value.keys().forEach { visit(if (path.isEmpty()) it else "$path.$it", value.get(it)) }
                    is JSONArray -> {
                        if (value.length() == 0) rows.add(path to "None reported")
                        for (index in 0 until value.length()) visit("$path[$index]", value.get(index))
                    }
                    else -> rows.add(path to if (value == null || value == JSONObject.NULL) "Unknown" else value.toString())
                }
            }
            visit("", json)
            return rows
        }
    }
}

private class ProfilerQrView(context: Context) : View(context) {
    private var current: String? = null
    private var image: Bitmap? = null
    private val paint = Paint().apply { isFilterBitmap = false }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun setCode(value: String?) {
        if (current == value) return
        current = value
        image?.recycle()
        image = value?.let {
            val matrix = QRCodeWriter().encode(it, BarcodeFormat.QR_CODE, 320, 320, mapOf(EncodeHintType.MARGIN to 2))
            Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888).apply {
                val pixels = IntArray(320 * 320) { index -> if (matrix[index % 320, index / 320]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
                setPixels(pixels, 0, 320, 0, 0, 320, 320)
            }
        }
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        val bitmap = image ?: return
        canvas.save()
        canvas.scale(width / MOCK_W, height / MOCK_H)
        canvas.drawBitmap(bitmap, null, RectF(SettingsChrome.CONTENT_LEFT, 280f, SettingsChrome.CONTENT_LEFT + 280f, 560f), paint)
        canvas.restore()
    }
}
