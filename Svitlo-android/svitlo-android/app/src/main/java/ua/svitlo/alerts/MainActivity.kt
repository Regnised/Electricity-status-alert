package ua.svitlo.alerts

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var board: LinearLayout
    private lateinit var bulb: TextView
    private lateinit var state: TextView
    private lateinit var next: TextView
    private lateinit var plug: TextView
    private lateinit var toggle: Button
    private lateinit var warnings: TextView
    private lateinit var fix: Button
    private lateinit var intervals: EditText
    private lateinit var warnMin: EditText
    private lateinit var repeats: EditText
    private lateinit var log: TextView
    private lateinit var ttsNote: TextView
    private lateinit var labels: List<EditText>
    private lateinit var accounts: List<EditText>
    private lateinit var siteInfo: TextView
    private lateinit var fetchNow: Button

    private var tester: Announcer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 20_000) }
    }
    private val updates = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        board = findViewById(R.id.board); bulb = findViewById(R.id.bulb)
        state = findViewById(R.id.state); next = findViewById(R.id.next); plug = findViewById(R.id.plug)
        toggle = findViewById(R.id.toggle); warnings = findViewById(R.id.warnings); fix = findViewById(R.id.fix)
        intervals = findViewById(R.id.intervals); warnMin = findViewById(R.id.warnMin)
        repeats = findViewById(R.id.repeats); log = findViewById(R.id.log); ttsNote = findViewById(R.id.ttsNote)
        siteInfo = findViewById(R.id.siteInfo); fetchNow = findViewById(R.id.fetchNow)
        labels = listOf(findViewById(R.id.label1), findViewById(R.id.label2))
        accounts = listOf(findViewById(R.id.account1), findViewById(R.id.account2))
        for (slot in 0 until Prefs.SLOTS) {
            labels[slot].setText(Prefs.label(this, slot))
            accounts[slot].setText(Prefs.account(this, slot))
        }
        bindSwitch(R.id.autoSite, "autoSite", true)
        fetchNow.setOnClickListener { fetchFromSite() }
        findViewById<Button>(R.id.openSite).setOnClickListener { openSite() }

        intervals.setText(Prefs.intervals(this))
        warnMin.setText(Prefs.warnMin(this).toString())
        repeats.setText(Prefs.repeats(this).toString())

        bindSwitch(R.id.useSchedule, "useSchedule", true)
        bindSwitch(R.id.useSensor, "useSensor", true)
        bindSwitch(R.id.siren, "siren", true)
        bindSwitch(R.id.warnOn, "warnOn", true)

        findViewById<Button>(R.id.save).setOnClickListener { saveSchedule(true) }

        toggle.setOnClickListener {
            val on = !Prefs.enabled(this)
            Prefs.setBool(this, "enabled", on)
            if (on) {
                saveSchedule(false)
                savePlaces()
                askPermissions()
                PowerService.start(this)
                Prefs.addLog(this, "Сповіщення увімкнено")
            } else {
                Schedule.cancel(this)
                stopService(Intent(this, PowerService::class.java))
                Prefs.addLog(this, "Сповіщення вимкнено")
            }
            refresh()
        }

        tester = Announcer(this)
        findViewById<Button>(R.id.testOff).setOnClickListener {
            savePlaces()
            tester?.announce("off", Texts.OFF + Texts.where(this, Prefs.activeSlots(this)), 1, Prefs.siren(this))
        }
        findViewById<Button>(R.id.testOn).setOnClickListener {
            savePlaces()
            tester?.announce("on", Texts.ON + Texts.where(this, Prefs.activeSlots(this)), 1, false)
        }
        findViewById<Button>(R.id.stop).setOnClickListener {
            tester?.stop()
            if (Prefs.enabled(this)) PowerService.start(this, PowerService.ACTION_STOP_SOUND)
        }
        findViewById<Button>(R.id.ttsSettings).setOnClickListener {
            try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
        handler.postDelayed({ updateTtsNote() }, 1500)

        // Якщо сповіщення були ввімкнені — переконуємось, що служба працює
        if (Prefs.enabled(this)) PowerService.start(this)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag") // прапорці є лише з API 33
    override fun onResume() {
        super.onResume()
        val f = IntentFilter(PowerService.BROADCAST_UPDATE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(updates, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(updates, f)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(updates) } catch (_: Exception) {}
        handler.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        tester?.shutdown(); tester = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun bindSwitch(id: Int, key: String, def: Boolean) {
        val s = findViewById<Switch>(id)
        s.isChecked = Prefs.bool(this, key, def)
        s.setOnCheckedChangeListener { _, v ->
            Prefs.setBool(this, key, v)
            if (Prefs.enabled(this)) PowerService.start(this, PowerService.ACTION_REFRESH)
            refresh()
        }
    }

    private fun savePlaces() {
        for (slot in 0 until Prefs.SLOTS) {
            Prefs.setLabel(this, slot, labels[slot].text.toString())
            val v = accounts[slot].text.toString().filter { it.isDigit() }
            if (v != Prefs.account(this, slot)) {
                Prefs.setAccount(this, slot, v)
                Prefs.clearSite(this, slot) // інший рахунок — інший графік
            }
        }
    }

    private fun openSite() {
        savePlaces()
        val slots = Prefs.accountSlots(this).ifEmpty { listOf(0) }
        fun open(slot: Int) = startActivity(Intent(this, SiteActivity::class.java).putExtra("slot", slot))
        if (slots.size == 1) { open(slots[0]); return }
        AlertDialog.Builder(this)
            .setTitle("Для якого місця взяти графік?")
            .setItems(slots.map { Prefs.place(this, it) }.toTypedArray()) { _, i -> open(slots[i]) }
            .show()
    }

    private fun fetchFromSite() {
        savePlaces()
        if (Prefs.accountSlots(this).isEmpty()) {
            Toast.makeText(this, "Введіть номер особового рахунку", Toast.LENGTH_SHORT).show(); return
        }
        fetchNow.isEnabled = false
        fetchNow.text = "Завантажую…"
        Updater.update(this, force = true) { out ->
            fetchNow.isEnabled = true
            fetchNow.text = "Оновити зараз"
            Toast.makeText(this, out.message, Toast.LENGTH_LONG).show()
            if (!out.ok) Prefs.addLog(this, out.message)
            if (Prefs.enabled(this)) PowerService.start(this, PowerService.ACTION_REFRESH)
            refresh()
        }
    }

    private fun saveSchedule(toast: Boolean) {
        val text = intervals.text.toString()
        val parsed = Schedule.parse(text)
        Prefs.setIntervals(this, text)
        Prefs.setWarnMin(this, warnMin.text.toString().toIntOrNull() ?: 5)
        Prefs.setRepeats(this, repeats.text.toString().toIntOrNull() ?: 2)
        if (Prefs.enabled(this)) PowerService.start(this, PowerService.ACTION_REFRESH)
        if (toast) Toast.makeText(this,
            if (parsed.isEmpty()) "Не знайдено жодного інтервалу. Формат: 08:00-11:00"
            else "Збережено інтервалів: ${parsed.size}", Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun askPermissions() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    /** Що заважає надійній роботі у фоні. */
    private fun problems(): List<Pair<String, Intent>> {
        val out = ArrayList<Pair<String, Intent>>()
        if (!Schedule.canExact(this) && Build.VERSION.SDK_INT >= 31) {
            out += "Дозвольте точні будильники, інакше оголошення за графіком можуть запізнюватися." to
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
        }
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            out += "Вимкніть оптимізацію батареї для «Світло», щоб система не зупиняла додаток." to
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            out += "Дозвольте сповіщення, щоб бачити оголошення на екрані блокування." to
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        return out
    }

    private fun updateTtsNote() {
        val t = tester ?: return
        ttsNote.text = if (t.ukrainian) "Український голос доступний."
        else "Український голос не знайдено. Відкрийте налаштування синтезу мовлення, оберіть «Синтезатор мовлення Google» і завантажте українську мову."
    }

    private fun color(id: Int) = getColor(id)

    private fun refresh() {
        val enabled = Prefs.enabled(this)
        val plugged = isPlugged(this)
        val offSlots = if (Prefs.useSchedule(this)) Schedule.outageSlots(this) else emptyList()
        val off = offSlots.isNotEmpty()
        val many = Prefs.activeSlots(this).size > 1
        fun names(slots: List<Int>) = if (many) " (" + slots.joinToString { Prefs.place(this, it) } + ")" else ""

        val bg = GradientDrawable().apply {
            cornerRadius = 18 * resources.displayMetrics.density
            setColor(color(if (off) R.color.off_soft else R.color.on_soft))
            setStroke((2 * resources.displayMetrics.density).toInt(), color(if (off) R.color.off else R.color.on))
        }
        board.background = bg
        bulb.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color(if (off) R.color.off else R.color.on))
        }
        state.text = if (off) "Відключення за графіком${names(offSlots)}" else "Світло є за графіком"
        state.setTextColor(color(if (off) R.color.off else R.color.fg))

        val ev = Schedule.upcoming(this, false).firstOrNull()
        next.text = when {
            !Prefs.useSchedule(this) -> "Графік вимкнено"
            ev == null -> "Графік порожній"
            ev.kind == "off" -> "Наступне відключення${names(ev.slots)} о ${Texts.hhmm(ev.at)}, через ${Texts.dur(ev.at - System.currentTimeMillis())}"
            else -> "Включення${names(ev.slots)} о ${Texts.hhmm(ev.at)}, через ${Texts.dur(ev.at - System.currentTimeMillis())}"
        }
        plug.text = if (plugged) "Телефон на зарядці від мережі" else "Телефон працює від батареї"

        toggle.text = if (enabled) "Сповіщення увімкнено · вимкнути" else "Увімкнути сповіщення"
        toggle.setBackgroundColor(color(if (enabled) R.color.ok else R.color.fg))
        toggle.setTextColor(color(R.color.bg))

        val p = if (enabled) problems() else emptyList()
        if (p.isNotEmpty()) {
            warnings.visibility = View.VISIBLE
            warnings.text = p.first().first
            fix.visibility = View.VISIBLE
            fix.setOnClickListener {
                try { startActivity(p.first().second) } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            }
        } else {
            warnings.visibility = View.GONE; fix.visibility = View.GONE
        }

        val today = Schedule.dayKey(0)
        val tomorrow = Schedule.dayKey(1)
        val info = StringBuilder()
        for (slot in Prefs.activeSlots(this)) {
            if (many) info.append("— ${Prefs.place(this, slot)} —\n")
            Prefs.siteQueue(this, slot).takeIf { it.isNotEmpty() }?.let { info.append("Черга: $it\n") }
            fun day(label: String, key: String) {
                val src = Schedule.sourceFor(this, slot, key)
                val hours = Schedule.fmt(Schedule.intervalsFor(this, slot, key)).replace("\n", ", ").ifEmpty { "без відключень" }
                if (src == Schedule.Source.SITE) info.append("$label: $hours\n")
                else if (key == today) info.append(if (src == Schedule.Source.MANUAL)
                    "$label: з сайту ще немає, діє ручний графік\n" else "$label: з сайту ще немає\n")
            }
            day("Сьогодні", today)
            day("Завтра", tomorrow)
            Prefs.siteStatus(this, slot).takeIf { it.isNotEmpty() }?.let { info.append("Сайт: $it\n") }
            info.append("\n")
        }
        siteInfo.text = info.toString().trim()

        val l = Prefs.log(this)
        log.text = if (l.isEmpty()) "Тут з'являтимуться оголошення." else l.joinToString("\n")
    }
}
