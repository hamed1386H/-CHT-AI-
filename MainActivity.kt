package com.chtai.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.*
import android.graphics.Color
import android.graphics.Typeface
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var input: EditText
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var wakeMode = false
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("cht_ai", Context.MODE_PRIVATE) }
    private val requestSpeech = 101
    private val requestMic = 102
    private val requestCorePermissions = 103
    private var permissionStatusView: TextView? = null
    private var torchOn = false
    private var dndOn = false
    // Keep a short, user-visible conversation context so the AI can follow follow-up questions.
    private val conversation = JSONArray()
    private val maxHistoryMessages = 12

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(5, 10, 20)
        window.navigationBarColor = Color.rgb(5, 10, 20)
        if (android.os.Build.VERSION.SDK_INT >= 23) window.decorView.systemUiVisibility = 0
        tts = TextToSpeech(this, this)
        restoreConversation()
        buildUi()
    }

    private fun buildUi() {
        val bg = Color.rgb(5, 10, 20)
        val panel = Color.rgb(12, 23, 39)
        val cyan = Color.rgb(52, 220, 238)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 14)
            setBackgroundColor(bg)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        // Compact floating-status capsule inspired by Dynamic Island (inside the app UI).
        val island = TextView(this).apply {
            text = "●  J.A.R.V.I.S  •  SYSTEM ONLINE"
            textSize = 11f; letterSpacing = .12f; gravity = Gravity.CENTER
            setTextColor(cyan); setPadding(18, 10, 18, 10)
            background = roundedDrawable(Color.rgb(10, 29, 47), 40, Color.rgb(20, 95, 125))
        }
        val islandRow = LinearLayout(this).apply { gravity = Gravity.CENTER; addView(island) }
        root.addView(islandRow, LinearLayout.LayoutParams(-1, -2))

        val header = TextView(this).apply {
            text = "J.A.R.V.I.S"
            textSize = 29f; letterSpacing = .20f; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, 14, 0, 0)
        }
        root.addView(header)
        val sub = TextView(this).apply {
            text = "PERSONAL INTELLIGENCE SYSTEM"
            textSize = 10f; letterSpacing = .22f; setTextColor(Color.rgb(103, 159, 185)); gravity = Gravity.CENTER
            setPadding(0, 2, 0, 8)
        }
        root.addView(sub)

        val orb = JarvisOrbView(this)
        root.addView(orb, LinearLayout.LayoutParams(-1, dp(218)))
        status = TextView(this).apply {
            text = "آماده‌ام؛ بگو «سلام هوش» یا فرمانت را لمس کن"
            textSize = 14f; setTextColor(cyan); gravity = Gravity.CENTER
            setPadding(8, 7, 8, 13)
        }
        root.addView(status)

        val scroll = ScrollView(this).apply { isFillViewport = false; clipToPadding = false }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(sectionLabel("VOICE CONTROL"))
        val voiceCard = card(panel)
        val voiceRow = horizontalRow()
        voiceRow.addView(neonButton("🎙  صحبت کن") { beginListening() }, weightParams())
        voiceRow.addView(neonButton(if (isWakeServiceRunning()) "■  توقف بیدارباش" else "◉  بیدارباش") { toggleWakeMode(); island.text = if (isWakeServiceRunning()) "●  LISTENING SERVICE ACTIVE" else "●  J.A.R.V.I.S  •  SYSTEM ONLINE" }, weightParams())
        voiceCard.addView(voiceRow); content.addView(voiceCard)

        content.addView(sectionLabel("QUICK CONTROLS"))
        val quick = card(panel)
        addQuickRow(quick, listOf("برنامه‌ها" to { showApps() }, "چراغ‌قوه" to { toggleTorch() }, "وای‌فای" to { openWifi() }))
        addQuickRow(quick, listOf("بلوتوث" to { openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) }, "صدا" to { volumeDialog() }, "روشنایی" to { brightnessDialog() }))
        addQuickRow(quick, listOf("مزاحم نشو" to { openDnd() }, "مجوزها" to { showPermissionCenter() }, "باتری" to { openSettings(Settings.ACTION_BATTERY_SAVER_SETTINGS) }))
        content.addView(quick)

        content.addView(sectionLabel("INTELLIGENCE"))
        val aiCard = card(panel)
        val aiTitle = TextView(this).apply {
            text = if (aiEnabled()) "AI MODE  •  ON" else "AI MODE  •  OFF"
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(if (aiEnabled()) cyan else Color.LTGRAY)
            setPadding(14, 12, 14, 4)
        }
        aiCard.addView(aiTitle)
        val aiDesc = TextView(this).apply {
            text = "فرمان‌های پایه مستقل از هوش مصنوعی فعال می‌مانند. برای پاسخ هوشمند آنلاین، سرویس API را تنظیم کن."
            textSize = 12f; setTextColor(Color.rgb(160, 181, 198)); setPadding(14, 2, 14, 10)
        }
        aiCard.addView(aiDesc)
        val aiRow = horizontalRow()
        aiRow.addView(neonButton("تنظیم حالت AI") { showAiModeSettings(); aiTitle.text = if (aiEnabled()) "AI MODE  •  ON" else "AI MODE  •  OFF" }, weightParams())
        aiRow.addView(neonButton("تنظیم API") { aiSettings() }, weightParams())
        aiCard.addView(aiRow); content.addView(aiCard)

        content.addView(sectionLabel("CONVERSATION"))
        log = TextView(this).apply {
            text = "سلام! من JARVIS هستم.\nفرمان‌های محاوره‌ای مثل «تلگرامو بیار بالا»، «صداشو بیشتر کن» و «چراغو روشن کن» را امتحان کن."
            textSize = 14f; setTextColor(Color.rgb(220, 234, 244)); setPadding(14, 14, 14, 14)
            background = roundedDrawable(Color.rgb(9, 19, 32), 18, Color.rgb(24, 58, 79))
        }
        content.addView(log, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        val toolsRow = horizontalRow()
        toolsRow.addView(neonButton("انتخاب صدا") { showVoicePicker() }, weightParams())
        toolsRow.addView(neonButton("پاک‌کردن گفتگو") { clearConversation() }, weightParams())
        content.addView(toolsRow)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 7, 8, 7); background = roundedDrawable(Color.rgb(12, 25, 40), 24, Color.rgb(27, 99, 128))
        }
        input = EditText(this).apply {
            hint = "فرمانت را بنویس…"; setHintTextColor(Color.rgb(111, 144, 164)); setTextColor(Color.WHITE)
            textSize = 14f; setSingleLine(true); background = null; setPadding(10, 8, 10, 8)
        }
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        composer.addView(neonButton("ارسال ➤") { val q = input.text.toString().trim(); if (q.isNotEmpty()) { input.setText(""); handleCommand(q) } })
        root.addView(composer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        setContentView(root)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun roundedDrawable(color: Int, radius: Int, stroke: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), stroke) }
    private fun card(color: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(9), dp(8), dp(9), dp(8))
        background = roundedDrawable(color, 20, Color.rgb(23, 69, 94))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    }
    private fun horizontalRow() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun sectionLabel(label: String) = TextView(this).apply {
        text = label; textSize = 10f; letterSpacing = .18f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(75, 184, 211)); setPadding(5, dp(13), 5, dp(7))
    }
    private fun neonButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label; textSize = 12f; isAllCaps = false; setTextColor(Color.rgb(207, 245, 255))
        background = roundedDrawable(Color.rgb(13, 42, 62), 14, Color.rgb(30, 135, 165))
        setPadding(dp(7), dp(8), dp(7), dp(8)); minHeight = dp(46); stateListAnimator = null
        setOnClickListener { action() }
    }
    private fun addQuickRow(parent: LinearLayout, items: List<Pair<String, () -> Unit>>) {
        val row = horizontalRow()
        items.forEach { (label, action) -> row.addView(neonButton(label, action), weightParams()) }
        parent.addView(row)
    }

    private fun weightParams() = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(3, 3, 3, 3) }
    private fun actionButton(label: String, content: String, action: () -> Unit): Button = Button(this).apply {
        text = label; textSize = 12f; isAllCaps = false; setOnClickListener { action() }
        contentDescription = content
    }

    private fun addLog(who: String, message: String) {
        log.append("\n\n$who: $message")
    }

    private fun showPermissionCenter() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
        }
        val explanation = TextView(this).apply {
            text = "مجوزها را خود برنامه آماده می‌کند و صفحه رسمی اندروید را باز می‌کند. هیچ مجوزی مخفیانه یا خودکار تأیید نمی‌شود. پس از انتخاب «اجازه دادن» یا فعال‌سازی در تنظیمات، به JARVIS برگرد و «مجوز داده شد / بررسی دوباره» را بزن."
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 12)
        }
        content.addView(explanation)
        permissionStatusView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(150, 235, 190))
            setPadding(0, 4, 0, 12)
        }
        content.addView(permissionStatusView)
        val runtimeButton = Button(this).apply {
            text = "۱) اجازه میکروفون و دوربین (چراغ‌قوه)"
            isAllCaps = false
            setOnClickListener { requestCorePermissionsNow() }
        }
        content.addView(runtimeButton)
        val writeButton = Button(this).apply {
            text = "۲) اجازه تغییر تنظیمات سیستم (روشنایی)"
            isAllCaps = false
            setOnClickListener { requestWriteSettings() }
        }
        content.addView(writeButton)
        val dndButton = Button(this).apply {
            text = "۳) اجازه حالت مزاحم نشو"
            isAllCaps = false
            setOnClickListener { openDndPermissionSettings() }
        }
        content.addView(dndButton)
        val verifyButton = Button(this).apply {
            text = "✓ مجوز داده شد / بررسی دوباره"
            isAllCaps = false
            setOnClickListener { updatePermissionStatus(); reply("وضعیت مجوزها بررسی شد") }
        }
        content.addView(verifyButton)
        val persistenceButton = Button(this).apply {
            text = "۴) پایداری سرویس در پس‌زمینه (باتری / شروع خودکار)"
            isAllCaps = false
            setOnClickListener { showBackgroundPersistenceOptions() }
        }
        content.addView(persistenceButton)
        updatePermissionStatus()
        AlertDialog.Builder(this)
            .setTitle("مرکز مجوزهای JARVIS")
            .setView(content)
            .setPositiveButton("بستن", null)
            .show()
    }

    private fun requestCorePermissionsNow() {
        val missing = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            reply("میکروفون و دوربین از قبل مجاز هستند")
            updatePermissionStatus()
            return
        }
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), requestCorePermissions)
    }

    private fun showBackgroundPersistenceOptions() {
        val choices = arrayOf("حذف محدودیت بهینه‌سازی باتری", "باز کردن تنظیمات شروع خودکار شیائومی", "صفحه اطلاعات برنامه")
        AlertDialog.Builder(this).setTitle("پایداری JARVIS")
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> {
                        try {
                            if (android.os.Build.VERSION.SDK_INT >= 23) {
                                val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
                                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                                } else reply("محدودیت بهینه‌سازی باتری قبلاً برداشته شده است")
                            }
                        } catch (_: Exception) { openAppDetails() }
                    }
                    1 -> {
                        try {
                            val intent = Intent().apply {
                                component = android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(intent)
                        } catch (_: Exception) { openAppDetails() }
                    }
                    else -> openAppDetails()
                }
            }.setNegativeButton("بستن", null).show()
    }

    private fun openAppDetails() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        catch (_: Exception) { openSettings(Settings.ACTION_SETTINGS) }
    }

    private fun openDndPermissionSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        } catch (_: Exception) {
            reply("صفحه مجوز مزاحم نشو در این نسخه اندروید باز نشد")
        }
    }

    private fun hasDndAccess(): Boolean {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        return android.os.Build.VERSION.SDK_INT < 23 || nm.isNotificationPolicyAccessGranted
    }

    private fun updatePermissionStatus() {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val write = Settings.System.canWrite(this)
        val dnd = hasDndAccess()
        permissionStatusView?.text = "وضعیت فعلی:\n${if (mic) "✓" else "○"} میکروفون\n${if (camera) "✓" else "○"} دوربین / چراغ‌قوه\n${if (write) "✓" else "○"} تغییر تنظیمات سیستم / روشنایی\n${if (dnd) "✓" else "○"} حالت مزاحم نشو\n\nعلامت ✓ یعنی مجوز فعال است."
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    override fun onPause() {
        // The foreground listening service intentionally continues while this screen is closed.
        super.onPause()
    }

    private fun beginListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), requestMic); return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "بگو چه کاری انجام بدهم")
        }
        try { startActivityForResult(intent, requestSpeech) } catch (_: Exception) { reply("تشخیص گفتار روی این گوشی در دسترس نیست") }
    }

    private fun toggleWakeMode() {
        if (isWakeServiceRunning()) {
            stopService(Intent(this, WakeWordService::class.java))
            reply("سرویس گوش‌به‌زنگ متوقف شد")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { reply("سرویس تشخیص گفتار روی گوشی در دسترس نیست"); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), requestMic)
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 104)
            return
        }
        startWakeServiceNow()
    }

    private fun startWakeServiceNow() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java))
            reply("گوش‌به‌زنگ فعال شد؛ اعلان دائمی JARVIS نمایش داده می‌شود")
        } catch (_: Exception) {
            reply("فعال‌سازی سرویس ممکن نشد؛ مجوز میکروفون، اعلان و محدودیت باتری را بررسی کن")
        }
    }

    private fun isWakeServiceRunning(): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == WakeWordService::class.java.name }
    }

    private fun showVoicePicker() {
        if (!ttsReady) { reply("موتور صدای گوشی هنوز آماده نیست"); return }
        val voices = tts.voices?.filter { it.locale.language == "fa" || it.locale.language == "en" }?.sortedWith(compareBy({ it.locale.language }, { it.name })) ?: emptyList()
        if (voices.isEmpty()) { reply("صدای جایگزین نصب‌شده‌ای پیدا نکردم؛ از تنظیمات تبدیل متن به گفتار اندروید صدا نصب کن"); return }
        val labels = voices.map { v ->
            val lang = if (v.locale.language == "fa") "فارسی" else "English"
            val quality = when (v.quality) { android.speech.tts.Voice.QUALITY_HIGH -> "کیفیت بالا"; android.speech.tts.Voice.QUALITY_NORMAL -> "معمولی"; else -> "کیفیت پایه" }
            "$lang • ${v.name} • $quality"
        }.toTypedArray()
        val selected = prefs.getString("tts_voice", "")
        val checked = voices.indexOfFirst { it.name == selected }.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("انتخاب صدای JARVIS")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val voice = voices[which]
                val result = tts.setVoice(voice)
                if (result == TextToSpeech.SUCCESS) {
                    prefs.edit().putString("tts_voice", voice.name).apply()
                    reply("صدا تغییر کرد؛ سلام، من JARVIS هستم")
                } else reply("این صدا قابل فعال‌سازی نیست")
                dialog.dismiss()
            }.setNegativeButton("بستن", null).show()
    }

    @Deprecated("Deprecated Android callback kept for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestSpeech && resultCode == RESULT_OK) {
            val words = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (words.isNotBlank()) { addLog("شما", words); handleCommand(words) }
        }
    }

    private fun normalize(s: String): String = s.lowercase(Locale("fa", "IR"))
        .replace("ي", "ی").replace("ك", "ک").replace("‌", " ").replace("ؤ", "و")
        .replace("۰", "0").replace("۱", "1").replace("۲", "2").replace("۳", "3").replace("۴", "4").replace("۵", "5").replace("۶", "6").replace("۷", "7").replace("۸", "8").replace("۹", "9")
        .replace(Regex("[،؛؟!,.]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun aiEnabled(): Boolean = prefs.getBoolean("ai_enabled", false)

    private fun changeVolumeBy(delta: Int) {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (current + delta).coerceIn(0, max), 0)
        reply(if (delta > 0) "صدا بیشتر شد" else "صدا کمتر شد")
    }

    private fun showAiModeSettings() {
        val enabled = aiEnabled()
        AlertDialog.Builder(this).setTitle("JARVIS • تنظیم هوش مصنوعی")
            .setMessage("وضعیت فعلی: ${if (enabled) "روشن" else "خاموش"}\n\nدر حالت خاموش، فرمان‌های محلی همچنان فعال می‌مانند. حالت روشن برای پاسخ هوشمند به اتصال API پیکربندی‌شده نیاز دارد.")
            .setPositiveButton(if (enabled) "خاموش‌کردن AI" else "روشن‌کردن AI") { _, _ ->
                prefs.edit().putBoolean("ai_enabled", !enabled).apply()
                reply(if (!enabled) "هوش مصنوعی روشن شد؛ اگر API تنظیم نشده باشد، پاسخ آنلاین در دسترس نیست" else "هوش مصنوعی خاموش شد؛ فرمان‌های پایه فعال هستند")
            }.setNeutralButton("تنظیم API") { _, _ -> aiSettings() }
            .setNegativeButton("بستن", null).show()
    }

    private fun handleCommand(raw: String) {
        val q = normalize(raw)
        // Conversational acknowledgements are only meaningful when a pending action exists;
        // never treat an isolated "OK" as authorization for an unrelated action.
        when {
            q in listOf("خواب جارویس", "خواب جارويس", "برو بخواب", "فعلا بخواب") -> { stopService(Intent(this, WakeWordService::class.java)); reply("وارد حالت خواب شدم") }
            q in listOf("سلام هوش", "بیدار شو", "بیدار") -> reply("سلام، در خدمتم")
            q in listOf("اوکی", "باشه", "آره", "اره", "بله", "yes") -> reply("متوجه شدم؛ چه کاری انجام بدهم؟")
            q in listOf("نه", "بی خیال", "بیخیال", "لغو کن", "کنسل", "cancel") -> reply("چیزی در انتظار تأیید نیست؛ فرمانی اجرا نشد")
            q.contains("هوش مصنوعی") || q.contains("حالت ai") || q.contains("تنظیم هوش") -> showAiModeSettings()
            q.contains("وای فای") || q.contains("wifi") -> { reply("پنل وای‌فای را باز می‌کنم؛ تغییر اتصال را از همان‌جا انجام بده"); openWifi() }
            q.contains("بلوتوث") -> { reply("تنظیمات بلوتوث را باز می‌کنم"); openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) }
            q.contains("چراغ") || q.contains("فلش") -> {
                if (q.contains("خاموش")) setTorch(false) else if (q.contains("روشن")) setTorch(true) else toggleTorch()
            }
            q.contains("روشنایی") || q.contains("نور صفحه") -> {
                val percent = Regex("(\\d{1,3})").find(q)?.value?.toIntOrNull()
                if (percent != null) setBrightness(percent.coerceIn(1, 100)) else brightnessDialog()
            }
            q.contains("صدا") || q.contains("ولوم") || q.contains("بلندی") -> {
                val percent = Regex("(\\d{1,3})").find(q)?.value?.toIntOrNull()
                when {
                    percent != null -> setVolume(percent.coerceIn(0, 100))
                    q.contains("بیشتر") || q.contains("زیاد") || q.contains("بالا") -> changeVolumeBy(2)
                    q.contains("کمتر") || q.contains("کم") || q.contains("پایین") -> changeVolumeBy(-2)
                    else -> volumeDialog()
                }
            }
            q.contains("مزاحم نشو") || q.contains("عدم مزاحمت") -> openDnd()
            q.contains("برنامه ها") || q.contains("اپلیکیشن") || q.contains("فهرست برنامه") -> showApps()
            q.contains("تنظیمات باتری") || q == "باتری" -> openSettings(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            q.contains("تنظیمات") -> openSettings(Settings.ACTION_SETTINGS)
            q.startsWith("باز کن ") || q.startsWith("بازکن ") || q.startsWith("اجرا کن ") || q.startsWith("بیار بالا ") -> {
                val name = q.replace(Regex("^(باز کن|بازکن|اجرا کن|بیار بالا)\\s*"), "").trim()
                if (name.isBlank()) reply("نام برنامه را هم بگو") else launchAppByName(name)
            }
            q.endsWith("را باز کن") || q.endsWith("رو باز کن") || q.endsWith("و باز کن") || q.endsWith("رو بیار بالا") || q.endsWith("و بیار بالا") -> {
                val name = q.replace(Regex("(را باز کن|رو باز کن|و باز کن|رو بیار بالا|و بیار بالا)$"), "").trim()
                if (name.isBlank()) reply("نام برنامه را هم بگو") else launchAppByName(name)
            }
            aiEnabled() -> askCloud(raw)
            else -> reply("این فرمان در حالت پایه پشتیبانی نمی‌شود. برای گفت‌وگوی آزاد، AI را از تنظیمات روشن و سرویس API را پیکربندی کن.")
        }
    }

    private fun openWifi() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) startActivity(Intent("android.settings.panel.action.WIFI"))
            else startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        } catch (_: Exception) { openSettings(Settings.ACTION_SETTINGS) }
    }

    private fun openSettings(action: String) {
        try { startActivity(Intent(action)) } catch (_: Exception) { reply("این صفحه تنظیمات در نسخه اندروید گوشی در دسترس نیست") }
    }

    private fun showApps() {
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(intent, 0).sortedBy { it.loadLabel(packageManager).toString() }
        val names = apps.map { it.loadLabel(packageManager).toString() }
        addLog("JARVIS", if (names.isEmpty()) "برنامه‌ای پیدا نشد" else "برنامه‌های قابل اجرا:\n" + names.joinToString("\n"))
        reply("فهرست ${names.size} برنامه را نمایش دادم")
    }

    private fun launchAppByName(name: String) {
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = packageManager.queryIntentActivities(intent, 0).firstOrNull {
            normalize(it.loadLabel(packageManager).toString()).contains(name)
        }
        if (match == null) { reply("برنامه «$name» را پیدا نکردم"); return }
        val label = match.loadLabel(packageManager).toString()
        val launch = packageManager.getLaunchIntentForPackage(match.activityInfo.packageName)
        if (launch != null) { reply("$label را باز می‌کنم"); startActivity(launch) } else reply("امکان باز کردن این برنامه وجود ندارد")
    }

    private fun volumeDialog() {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val seek = SeekBar(this).apply { this.max = max; progress = current }
        AlertDialog.Builder(this).setTitle("تنظیم صدای رسانه").setView(seek)
            .setPositiveButton("ذخیره") { _, _ -> audio.setStreamVolume(AudioManager.STREAM_MUSIC, seek.progress, 0); reply("صدای رسانه تنظیم شد") }
            .setNegativeButton("بستن", null).show()
    }

    private fun setVolume(percent: Int) {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * percent / 100f).roundToInt().coerceIn(0, max), 0)
        reply("صدای رسانه روی $percent درصد تنظیم شد")
    }

    private fun brightnessDialog() {
        val seek = SeekBar(this).apply { max = 100; progress = 60 }
        AlertDialog.Builder(this).setTitle("روشنایی صفحه").setMessage("برای تغییر روشنایی کلی ممکن است لازم باشد مجوز تغییر تنظیمات سیستم را فعال کنی.")
            .setView(seek).setPositiveButton("اعمال") { _, _ -> setBrightness(seek.progress.coerceAtLeast(1)) }
            .setNeutralButton("دادن مجوز") { _, _ -> requestWriteSettings() }.setNegativeButton("بستن", null).show()
    }

    private fun setBrightness(percent: Int) {
        val value = (255 * percent / 100f).roundToInt().coerceIn(1, 255)
        if (Settings.System.canWrite(this)) {
            try { Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, value); reply("روشنایی کلی صفحه روی $percent درصد تنظیم شد") }
            catch (_: Exception) { reply("تغییر روشنایی انجام نشد") }
        } else {
            val lp = window.attributes; lp.screenBrightness = percent / 100f; window.attributes = lp
            reply("روشنایی پنجره برنامه روی $percent درصد تنظیم شد. برای تغییر کل گوشی، مجوز تغییر تنظیمات را فعال کن.")
        }
    }

    private fun requestWriteSettings() {
        try { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))) }
        catch (_: Exception) { reply("صفحه مجوز تغییر تنظیمات باز نشد") }
    }

    private fun toggleTorch() = setTorch(!torchOn)
    private fun setTorch(enabled: Boolean) {
        try {
            val manager = getSystemService(CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            if (cameraId == null) { reply("چراغ‌قوه در این گوشی پیدا نشد"); return }
            manager.setTorchMode(cameraId, enabled); torchOn = enabled
            reply(if (enabled) "چراغ‌قوه روشن شد" else "چراغ‌قوه خاموش شد")
        } catch (_: Exception) { reply("کنترل چراغ‌قوه ممکن نشد؛ ممکن است دوربین در حال استفاده باشد") }
    }

    private fun openDnd() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 23 && !nm.isNotificationPolicyAccessGranted) {
            reply("برای کنترل حالت مزاحم نشو، دسترسی ویژه لازم است؛ صفحه مجوز را باز می‌کنم")
            try { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) } catch (_: Exception) { openSettings(Settings.ACTION_SETTINGS) }
        } else if (android.os.Build.VERSION.SDK_INT >= 23) {
            try {
                dndOn = nm.currentInterruptionFilter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                nm.setInterruptionFilter(if (dndOn) android.app.NotificationManager.INTERRUPTION_FILTER_ALL else android.app.NotificationManager.INTERRUPTION_FILTER_NONE)
                dndOn = !dndOn
                reply(if (dndOn) "حالت مزاحم نشو فعال شد" else "حالت مزاحم نشو غیرفعال شد")
            } catch (_: Exception) { openSettings(Settings.ACTION_SOUND_SETTINGS) }
        } else openSettings(Settings.ACTION_SOUND_SETTINGS)
    }

    private fun restoreConversation() {
        try {
            val saved = prefs.getString("conversation", "[]") ?: "[]"
            val parsed = JSONArray(saved)
            val start = (parsed.length() - maxHistoryMessages).coerceAtLeast(0)
            for (i in start until parsed.length()) conversation.put(parsed.getJSONObject(i))
        } catch (_: Exception) { /* Ignore an invalid old history safely. */ }
    }

    private fun saveConversation() {
        prefs.edit().putString("conversation", conversation.toString()).apply()
    }

    private fun addConversationMessage(role: String, content: String) {
        conversation.put(JSONObject().put("role", role).put("content", content))
        while (conversation.length() > maxHistoryMessages) conversation.remove(0)
        saveConversation()
    }

    private fun clearConversation() {
        AlertDialog.Builder(this).setTitle("پاک‌کردن گفتگو")
            .setMessage("تاریخچه گفت‌وگوی هوش مصنوعی از حافظه همین برنامه پاک شود؟")
            .setPositiveButton("پاک کن") { _, _ ->
                while (conversation.length() > 0) conversation.remove(0)
                prefs.edit().remove("conversation").apply()
                log.text = "گفتگو پاک شد. من JARVIS هستم؛ چه کمکی از من برمی‌آید؟"
                reply("تاریخچه گفتگو پاک شد")
            }.setNegativeButton("لغو", null).show()
    }

    private fun aiSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 4) }
        val endpoint = EditText(this).apply { hint = "نشانی API؛ نمونه: https://api.openai.com/v1/chat/completions"; setText(prefs.getString("endpoint", "https://api.openai.com/v1/chat/completions")); textSize = 13f }
        val model = EditText(this).apply { hint = "نام مدل؛ نمونه: gpt-4o-mini"; setText(prefs.getString("model", "gpt-4o-mini")) }
        val key = EditText(this).apply { hint = "کلید API (محرمانه)"; setText(prefs.getString("key", "")); inputType = 129 }
        box.addView(endpoint); box.addView(model); box.addView(key)
        AlertDialog.Builder(this).setTitle("اتصال هوش ابری")
            .setMessage("برای فعال‌شدن مغز هوشمند آنلاین، نشانی HTTPS سرویس سازگار، نام مدل و کلید API معتبر لازم است. هیچ کلیدی داخل برنامه قرار داده نشده است. کلید را محرمانه نگه دار.")
            .setView(box).setPositiveButton("ذخیره") { _, _ ->
                prefs.edit().putString("endpoint", endpoint.text.toString().trim()).putString("model", model.text.toString().trim()).putString("key", key.text.toString().trim()).apply()
                reply("تنظیمات اتصال ذخیره شد. برای آزمایش، یک پیام بفرست.")
            }.setNegativeButton("لغو", null).show()
    }

    private fun askCloud(prompt: String) {
        val endpoint = prefs.getString("endpoint", "").orEmpty()
        val key = prefs.getString("key", "").orEmpty()
        val model = prefs.getString("model", "gpt-4o-mini").orEmpty()
        if (endpoint.isBlank() || key.isBlank() || model.isBlank()) {
            reply("مغز هوشمند هنوز پیکربندی نشده است. در «تنظیم هوش» نشانی سرویس، مدل و کلید API معتبر را وارد کن.")
            return
        }
        if (!endpoint.startsWith("https://", ignoreCase = true)) {
            reply("برای محافظت از کلید API، فقط نشانی امن HTTPS پذیرفته می‌شود.")
            return
        }
        status.text = "در حال دریافت پاسخ از هوش ابری…"
        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 20000; readTimeout = 45000
                    doOutput = true; setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $key")
                }
                val messages = JSONArray().put(
                    JSONObject().put("role", "system").put("content", "تو مغز هوشمند JARVIS هستی؛ دستیار فارسی‌زبان، دقیق و مسئولیت‌پذیر. زمینه پیام‌های قبلی را در نظر بگیر. اگر کاربر فقط سؤال دارد، پاسخ عادی فارسی بده. اگر درخواست او یکی از فرمان‌های محلی پشتیبانی‌شده است، فقط یک JSON بدون markdown با قالب {\"type\":\"action\",\"command\":\"فرمان فارسی کوتاه\"} برگردان. فرمان‌های مجاز: باز کردن برنامه با عبارت «[نام برنامه] را باز کن»، باز کردن وای‌فای، باز کردن تنظیمات بلوتوث، باز کردن تنظیمات عمومی یا باتری، تنظیم صدا با درصد، تنظیم روشنایی با درصد، روشن/خاموش کردن چراغ‌قوه، باز کردن پنل مزاحم نشو، نمایش برنامه‌ها. هیچ فرمان دیگری پیشنهاد نده. هرگز ادعا نکن کاری انجام شده است. برنامه پیش از اجرای فرمان از کاربر تأیید می‌گیرد. رمز، کد تأیید یا کلید API درخواست نکن.")
                )
                for (i in 0 until conversation.length()) messages.put(conversation.getJSONObject(i))
                messages.put(JSONObject().put("role", "user").put("content", prompt))
                val body = JSONObject().put("model", model).put("messages", messages).put("temperature", 0.6)
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                if (code !in 200..299) throw Exception("خطای سرویس ($code): ${response.take(500)}")
                val answer = JSONObject(response).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                runOnUiThread {
                    addLog("شما", prompt)
                    addConversationMessage("user", prompt)
                    val action = parseAiAction(answer)
                    if (action != null) {
                        val command = action.optString("command", "").trim()
                        if (isAllowedAiCommand(command)) {
                            addLog("JARVIS", "برای اجرای این فرمان تأیید تو لازم است: $command")
                            addConversationMessage("assistant", "پیشنهاد اجرای فرمان: $command")
                            confirmAiAction(command)
                        } else {
                            val message = "این فرمان در فهرست عملیات امن و پشتیبانی‌شده نیست؛ اجرا نشد."
                            addLog("JARVIS", message); addConversationMessage("assistant", message); speak(message)
                        }
                    } else {
                        addLog("JARVIS", answer)
                        addConversationMessage("assistant", answer)
                        status.text = "پاسخ آماده شد"; speak(answer)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { reply("اتصال به هوش ابری ناموفق بود: ${e.message ?: "خطای ناشناخته"}") }
            } finally { connection?.disconnect() }
        }
    }


    private fun parseAiAction(raw: String): JSONObject? {
        return try {
            val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            if (start < 0 || end <= start) null else JSONObject(cleaned.substring(start, end + 1)).takeIf { it.optString("type") == "action" }
        } catch (_: Exception) { null }
    }

    // Strict allowlist: the model may suggest only commands already implemented locally.
    private fun isAllowedAiCommand(command: String): Boolean {
        val q = normalize(command)
        if (q.isBlank() || q.length > 100) return false
        val hasPercent = Regex("\\d{1,3}").containsMatchIn(q)
        return when {
            q.contains("وای فای") || q.contains("wifi") || q.contains("بلوتوث") -> true
            q.contains("چراغ قوه") || q.contains("چراغ") || q.contains("فلش") -> q.contains("روشن") || q.contains("خاموش")
            q.contains("روشنایی") || q.contains("نور صفحه") || q.contains("صدا") || q.contains("ولوم") -> hasPercent
            q.contains("مزاحم نشو") || q.contains("عدم مزاحمت") -> true
            q.contains("تنظیمات باتری") || q == "باتری" || q == "تنظیمات" -> true
            q.contains("فهرست برنامه") || q.contains("برنامه ها") -> true
            q.endsWith("را باز کن") || q.endsWith("رو باز کن") || q.startsWith("باز کن ") || q.startsWith("بازکن ") -> true
            else -> false
        }
    }

    private fun confirmAiAction(command: String) {
        AlertDialog.Builder(this)
            .setTitle("تأیید اجرای فرمان")
            .setMessage("هوش مصنوعی پیشنهاد داده این فرمان اجرا شود:\n\n$command\n\nفقط در صورت تأیید تو اجرا می‌شود.")
            .setPositiveButton("تأیید و اجرا") { _, _ -> handleCommand(command) }
            .setNegativeButton("لغو") { _, _ -> reply("اجرای فرمان لغو شد") }
            .show()
    }

    private fun reply(message: String) { status.text = message; addLog("JARVIS", message); speak(message) }
    private fun speak(message: String) { if (ttsReady) tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "jarvis_reply") }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestMic && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) beginListening()
        else if (requestCode == requestMic) reply("برای فرمان صوتی باید مجوز میکروفون را بدهی")
        if (requestCode == 104) {
            if (android.os.Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) startWakeServiceNow()
            else reply("مجوز اعلان داده نشد؛ برای نمایش اعلان دائمی باید آن را از تنظیمات مجاز کنی")
        }
        if (requestCode == requestCorePermissions) {
            updatePermissionStatus()
            val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            reply("نتیجه مجوزها: میکروفون ${if (micGranted) "فعال" else "غیرفعال"}، دوربین/چراغ‌قوه ${if (cameraGranted) "فعال" else "غیرفعال"}. برای مجوزهای ویژه، دکمه‌های مرکز مجوزها را جداگانه استفاده کن.")
        }
    }

    override fun onInit(code: Int) {
        if (code == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale("fa", "IR"))
            ttsReady = result >= TextToSpeech.LANG_AVAILABLE
            val savedVoice = prefs.getString("tts_voice", "")
            if (!savedVoice.isNullOrBlank()) tts.voices?.firstOrNull { it.name == savedVoice }?.let { tts.setVoice(it) }
            if (!ttsReady) { tts.setLanguage(Locale.getDefault()); ttsReady = true; status.text = "صدای فارسی نصب نیست؛ زبان پیش‌فرض استفاده می‌شود" }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        super.onDestroy()
    }
}


/** Animated cyan holographic orb used as a live visual state indicator. */
private class JarvisOrbView(context: Context) : View(context) {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private val cyan = Color.rgb(44, 218, 244)
    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f; val cy = height / 2f
        val radius = minOf(width * .29f, height * .43f)
        phase += .035f
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = 1.4f
        for (i in 0..3) {
            val r = radius * (1.08f + i * .12f + kotlin.math.sin(phase + i) * .012f)
            paint.color = Color.argb(90 - i * 15, 45, 205, 239)
            canvas.drawCircle(cx, cy, r, paint)
        }
        paint.style = android.graphics.Paint.Style.FILL
        paint.shader = android.graphics.RadialGradient(cx - radius * .25f, cy - radius * .28f, radius * 1.35f,
            intArrayOf(Color.rgb(207, 255, 255), cyan, Color.rgb(8, 65, 117), Color.rgb(3, 17, 42)),
            floatArrayOf(0f, .22f, .68f, 1f), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius * (1f + kotlin.math.sin(phase) * .012f), paint)
        paint.shader = null
        paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = Color.argb(170, 104, 244, 255)
        canvas.drawArc(cx-radius*.78f, cy-radius*.78f, cx+radius*.78f, cy+radius*.78f, phase*35f, 125f, false, paint)
        paint.style = android.graphics.Paint.Style.FILL; paint.color = Color.WHITE; paint.textAlign = android.graphics.Paint.Align.CENTER; paint.textSize = 11f
        canvas.drawText("J A R V I S", cx, cy + radius + dp(31), paint)
        postInvalidateDelayed(40)
    }
    private fun dp(v: Int) = v * resources.displayMetrics.density
}
