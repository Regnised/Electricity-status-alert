package ua.svitlo.alerts

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Налаштування та журнал. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("svitlo", Context.MODE_PRIVATE)

    fun intervals(c: Context): String = sp(c).getString("intervals", "") ?: ""
    fun setIntervals(c: Context, v: String) = sp(c).edit().putString("intervals", v).apply()

    fun warnMin(c: Context) = sp(c).getInt("warnMin", 5)
    fun setWarnMin(c: Context, v: Int) = sp(c).edit().putInt("warnMin", v.coerceIn(0, 60)).apply()

    fun repeats(c: Context) = sp(c).getInt("repeats", 2)
    fun setRepeats(c: Context, v: Int) = sp(c).edit().putInt("repeats", v.coerceIn(1, 5)).apply()

    fun bool(c: Context, key: String, def: Boolean) = sp(c).getBoolean(key, def)
    fun setBool(c: Context, key: String, v: Boolean) = sp(c).edit().putBoolean(key, v).apply()

    fun enabled(c: Context) = bool(c, "enabled", false)
    fun useSchedule(c: Context) = bool(c, "useSchedule", true)
    fun useSensor(c: Context) = bool(c, "useSensor", true)
    fun siren(c: Context) = bool(c, "siren", true)
    fun warnOn(c: Context) = bool(c, "warnOn", true)
    fun autoSite(c: Context) = bool(c, "autoSite", true)

    // ---- дані з сайту обленерго ----
    /** Скільки місць (особових рахунків) можна відстежувати. */
    const val SLOTS = 2

    /** Місце 0 зберігає старі ключі («account», «site_…»), місце 1 — «account2», «site2_…». */
    private fun k(base: String, slot: Int) = if (slot == 0) base else "$base${slot + 1}"
    private fun dayKey(slot: Int, day: String) = "${k("site", slot)}_$day"

    fun account(c: Context, slot: Int = 0): String = sp(c).getString(k("account", slot), "") ?: ""
    fun setAccount(c: Context, slot: Int, v: String) =
        sp(c).edit().putString(k("account", slot), v.filter { it.isDigit() }).apply()

    fun label(c: Context, slot: Int): String = sp(c).getString(k("label", slot), "") ?: ""
    fun setLabel(c: Context, slot: Int, v: String) = sp(c).edit().putString(k("label", slot), v.trim()).apply()

    /** Як назвати місце в оголошенні: «Дім» або «рахунок, що закінчується на 2601». */
    fun place(c: Context, slot: Int): String = label(c, slot).ifEmpty {
        val acc = account(c, slot)
        if (acc.isEmpty()) "місце ${slot + 1}" else "рахунок, що закінчується на ${acc.takeLast(4)}"
    }

    /** Місце 0 активне завжди (для нього діє ручний графік), інші — якщо введено рахунок. */
    fun activeSlots(c: Context): List<Int> = (0 until SLOTS).filter { it == 0 || account(c, it).isNotEmpty() }

    /** Місця, за якими треба запитувати сайт. */
    fun accountSlots(c: Context): List<Int> = (0 until SLOTS).filter { account(c, it).length >= 5 }

    fun hasSiteDay(c: Context, slot: Int, day: String) = sp(c).contains(dayKey(slot, day))
    fun siteDay(c: Context, slot: Int, day: String): String = sp(c).getString(dayKey(slot, day), "") ?: ""
    fun setSiteDay(c: Context, slot: Int, day: String, v: String) = sp(c).edit().putString(dayKey(slot, day), v).apply()

    fun siteQueue(c: Context, slot: Int): String = sp(c).getString(k("siteQueue", slot), "") ?: ""
    fun siteStatus(c: Context, slot: Int): String = sp(c).getString(k("siteStatus", slot), "") ?: ""
    fun setSiteInfo(c: Context, slot: Int, queue: String?, status: String) {
        val e = sp(c).edit().putString(k("siteStatus", slot), status)
        if (!queue.isNullOrBlank()) e.putString(k("siteQueue", slot), queue)
        e.apply()
    }
    fun setSiteRaw(c: Context, slot: Int, raw: String) = sp(c).edit().putString(k("siteRaw", slot), raw.take(4000)).apply()

    /** Інший рахунок — інший графік: забуваємо все, що знали про це місце. */
    fun clearSite(c: Context, slot: Int) {
        val prefix = "${k("site", slot)}_"
        val e = sp(c).edit()
        sp(c).all.keys.filter { it.startsWith(prefix) }.forEach { e.remove(it) }
        e.remove(k("siteQueue", slot)).remove(k("siteStatus", slot)).remove(k("siteRaw", slot))
            .remove(k("planned", slot)).remove(k("plannedAt", slot)).apply()
    }

    /** Збережена відповідь про планові ремонти (їх оголошують заздалегідь, тож запитуємо рідше). */
    fun planned(c: Context, slot: Int): String = sp(c).getString(k("planned", slot), "") ?: ""
    fun plannedAt(c: Context, slot: Int): Long = sp(c).getLong(k("plannedAt", slot), 0L)
    fun setPlanned(c: Context, slot: Int, v: String) =
        sp(c).edit().putString(k("planned", slot), v).putLong(k("plannedAt", slot), System.currentTimeMillis()).apply()

    fun lastFetch(c: Context): Long = sp(c).getLong("lastFetch", 0L)
    fun setLastFetch(c: Context) = sp(c).edit().putLong("lastFetch", System.currentTimeMillis()).apply()

    /** Прибирає дані сайту старші за позавчора. */
    fun pruneSite(c: Context) {
        val keep = (0 until SLOTS).flatMap { slot -> (-2..3).map { dayKey(slot, Schedule.dayKey(it)) } }.toSet()
        val e = sp(c).edit()
        sp(c).all.keys.filter { Regex("site\\d?_.*").matches(it) && it !in keep }.forEach { e.remove(it) }
        e.apply()
    }

    fun log(c: Context): List<String> =
        (sp(c).getString("log", "") ?: "").split("\n").filter { it.isNotBlank() }

    fun addLog(c: Context, text: String) {
        val t = SimpleDateFormat("dd.MM HH:mm", Locale("uk", "UA")).format(Date())
        val list = (listOf("$t  $text") + log(c)).take(40)
        sp(c).edit().putString("log", list.joinToString("\n")).apply()
    }
}

data class Interval(val from: Int, val to: Int)

object Schedule {
    private val re = Regex("""(\d{1,2})[:.](\d{2})\s*[-–—]\s*(\d{1,2})[:.](\d{2})""")

    fun parse(text: String): List<Interval> = text.lines().mapNotNull { line ->
        val m = re.find(line) ?: return@mapNotNull null
        val (h1, m1, h2, m2) = m.destructured.toList().map { it.toInt() }
        if (h1 > 24 || h2 > 24 || m1 > 59 || m2 > 59) return@mapNotNull null
        val a = (h1 * 60 + m1) % 1440
        // «24:00» — кінець доби (частина відключення, що триває через північ)
        val b = if (h2 == 24 && m2 == 0) 1440 else (h2 * 60 + m2) % 1440
        if (a == b) null else Interval(a, b)
    }

    fun fmt(list: List<Interval>) = list.joinToString("\n") {
        "%02d:%02d-%02d:%02d".format(it.from / 60, it.from % 60, it.to / 60, it.to % 60)
    }

    fun dayKey(offset: Int): String {
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset) }
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    }

    /** Звідки береться графік на конкретний день. */
    enum class Source { SITE, MANUAL, NONE }

    fun sourceFor(c: Context, slot: Int, day: String): Source = when {
        Prefs.autoSite(c) && Prefs.hasSiteDay(c, slot, day) -> Source.SITE
        slot == 0 -> Source.MANUAL
        else -> Source.NONE
    }

    fun intervalsFor(c: Context, slot: Int, day: String): List<Interval> = when (sourceFor(c, slot, day)) {
        Source.SITE -> parse(Prefs.siteDay(c, slot, day))
        Source.MANUAL -> parse(Prefs.intervals(c))
        Source.NONE -> emptyList()
    }

    /** Подія розкладу. kind: off, on, warnOff, warnOn; slots — яких місць стосується. */
    data class Ev(val at: Long, val kind: String, val minutes: Int, val slots: List<Int>)

    private fun forSlot(c: Context, slot: Int, includeWarnings: Boolean): List<Ev> {
        val w = Prefs.warnMin(c)
        val one = listOf(slot)
        val out = ArrayList<Ev>()
        for (dayOffset in -1..2) {
            val midnight = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, dayOffset)
            }.timeInMillis
            for (iv in intervalsFor(c, slot, dayKey(dayOffset))) {
                val offAt = midnight + iv.from * 60_000L
                val onAt = midnight + iv.to * 60_000L + (if (iv.to < iv.from) 86_400_000L else 0L)
                out += Ev(offAt, "off", 0, one)
                out += Ev(onAt, "on", 0, one)
                if (includeWarnings && w > 0) {
                    out += Ev(offAt - w * 60_000L, "warnOff", w, one)
                    if (Prefs.warnOn(c)) out += Ev(onAt - w * 60_000L, "warnOn", w, one)
                }
            }
        }
        // Суміжні інтервали (10:00-11:00 і 11:00-12:00) — не оголошуємо включення на 0 хвилин
        val offTimes = out.filter { it.kind == "off" }.map { it.at }.toSet()
        val onTimes = out.filter { it.kind == "on" }.map { it.at }.toSet()
        return out.filterNot { (it.kind == "on" && it.at in offTimes) || (it.kind == "off" && it.at in onTimes) }
            .distinctBy { it.at to it.kind }
    }

    /** Події всіх місць; однакові події в одну мить об'єднуються («Дім та Батьки»). */
    private fun all(c: Context, includeWarnings: Boolean): List<Ev> =
        Prefs.activeSlots(c).flatMap { forSlot(c, it, includeWarnings) }
            .groupBy { it.at to it.kind }
            .map { (_, evs) -> evs[0].copy(slots = evs.flatMap { it.slots }.distinct().sorted()) }
            .sortedBy { it.at }

    fun upcoming(c: Context, includeWarnings: Boolean): List<Ev> {
        val now = System.currentTimeMillis()
        return all(c, includeWarnings).filter { it.at > now + 500 }
    }

    /** Усі події, що припадають на мить [at] (для спрацювання будильника). */
    fun eventsAt(c: Context, at: Long): List<Ev> = all(c, true).filter { it.at == at }

    /** Місця, де зараз відключення за графіком. */
    fun outageSlots(c: Context): List<Int> {
        val now = System.currentTimeMillis()
        return Prefs.activeSlots(c).filter { slot ->
            forSlot(c, slot, false).sortedBy { it.at }.lastOrNull { it.at <= now }?.kind == "off"
        }
    }

    private fun pi(c: Context, at: Long?): PendingIntent {
        val i = Intent(c, AlarmReceiver::class.java)
        if (at != null) i.putExtra("at", at)
        return PendingIntent.getBroadcast(c, 1, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun canExact(c: Context): Boolean {
        val am = c.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    /** Ставить будильник на найближчу подію (ланцюжком). */
    fun scheduleNext(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        am.cancel(pi(c, null))
        if (!Prefs.enabled(c) || !Prefs.useSchedule(c)) return
        val ev = upcoming(c, true).firstOrNull() ?: return
        val p = pi(c, ev.at)
        if (canExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ev.at, p)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ev.at, p)
    }

    private fun fetchPi(c: Context) = PendingIntent.getBroadcast(c, 2,
        Intent(c, AlarmReceiver::class.java).setAction(PowerService.ACTION_FETCH),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /**
     * Наступне оновлення з сайту. Будильник, а не Handler: відлік Handler зупиняється,
     * коли телефон спить, і оновлення могли б не відбуватися годинами.
     */
    fun scheduleFetch(c: Context, delayMs: Long) {
        val am = c.getSystemService(AlarmManager::class.java)
        val at = System.currentTimeMillis() + delayMs
        // точний будильник дозволяє запустити службу з фону (Android 12+)
        if (canExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fetchPi(c))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fetchPi(c))
    }

    fun cancel(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        am.cancel(pi(c, null))
        am.cancel(fetchPi(c))
    }
}

object Texts {
    fun plural(n: Int, one: String, few: String, many: String): String {
        val m10 = n % 10; val m100 = n % 100
        return when {
            m10 == 1 && m100 != 11 -> one
            m10 in 2..4 && (m100 < 12 || m100 > 14) -> few
            else -> many
        }
    }
    private fun mins(n: Int) = "$n ${plural(n, "хвилину", "хвилини", "хвилин")}"

    const val OFF = "Увага! Відключення електроенергії. Зарядіть пристрої та подбайте про освітлення."
    const val ON = "Відбій відключення. Електроенергію відновлено."
    const val SENSOR_OFF = "Увага! Зникло живлення від мережі. Ймовірно, відключення електроенергії."
    const val SENSOR_ON = "Відбій. Живлення від мережі відновлено. Світло є."
    fun warnOff(n: Int) = "Увага! Через ${mins(n)} планове відключення електроенергії."
    fun warnOn(n: Int) = "Через ${mins(n)} очікується включення електроенергії."

    /** Чи називати місце: є друге місце або людина дала назву першому. */
    private fun namePlaces(c: Context) = Prefs.activeSlots(c).size > 1 || Prefs.label(c, 0).isNotEmpty()

    private fun places(c: Context, slots: List<Int>) = slots.map { Prefs.place(c, it) }.let {
        if (it.size > 1) it.dropLast(1).joinToString(", ") + " та " + it.last() else it.joinToString()
    }

    /** « Місце: Дім та Батьки.» — у кінці оголошення. */
    fun where(c: Context, slots: List<Int>): String =
        if (slots.isEmpty() || !namePlaces(c)) "" else " Місце: ${places(c, slots)}."

    fun event(c: Context, ev: Schedule.Ev): String = when (ev.kind) {
        "off" -> OFF
        "on" -> ON
        "warnOff" -> warnOff(ev.minutes)
        else -> warnOn(ev.minutes)
    } + where(c, ev.slots)

    /** «з 10:00 до 11:00, з 14:00 до 16:00» */
    fun spoken(list: List<Interval>): String = if (list.isEmpty()) "відключень не заплановано" else
        list.joinToString(", ") {
            "з %d:%02d до %d:%02d".format(it.from / 60, it.from % 60, it.to / 60, it.to % 60)
        }

    fun scheduleChanged(c: Context, changes: Map<Int, List<Interval>>): String {
        val parts = changes.map { (slot, list) ->
            (if (namePlaces(c)) "${Prefs.place(c, slot)}: " else "Відключення: ") + spoken(list) + "."
        }
        return "Увага! Графік відключень на сьогодні змінено. " + parts.joinToString(" ")
    }

    fun hhmm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))
    fun dur(ms: Long): String {
        val m = (ms / 60_000L).toInt(); val h = m / 60
        return (if (h > 0) "$h год " else "") + "${m % 60} хв"
    }
}

fun isPlugged(c: Context): Boolean {
    val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    return (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
}
