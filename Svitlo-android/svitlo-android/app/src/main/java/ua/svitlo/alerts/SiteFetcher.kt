package ua.svitlo.alerts

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Бере графік з того самого API, яким користується сторінка cherkasyoblenergo.com/off:
 * GET disconn.php?op=disconn_by_ls&abon_ls=…&disconn_selector=…&n_date=…&k_date=…
 * Відповідь — JSON з полями DISCONNECTIONS (DATE_START, DATE_STOP) і DISCONN_QUEUQ (QUEUE_INFO).
 */
object SiteFetcher {
    const val URL = "https://www.cherkasyoblenergo.com/off"
    private const val API = "https://cabinet.cherkasyoblenergo.com/api_new/disconn.php"

    /** Типи відключень (disconn_selector). */
    const val SCHEDULES = "2"  // графіки погодинних відключень
    const val PLANNED = "0"    // планові ремонти

    /** Відповідь API одного типу: відключення і рядки про чергу. */
    data class Raw(val records: List<SiteParser.Record>, val queue: List<String>) {
        fun encode(): String = JSONObject()
            .put("records", JSONArray(records.map { JSONArray().put(it.start).put(it.stop) }))
            .put("queue", JSONArray(queue)).toString()

        companion object {
            fun decode(s: String): Raw? = try {
                val o = JSONObject(s)
                val r = o.getJSONArray("records")
                val q = o.getJSONArray("queue")
                Raw((0 until r.length()).map { SiteParser.Record(r.getJSONArray(it).getString(0), r.getJSONArray(it).getString(1)) },
                    (0 until q.length()).map { q.getString(it) })
            } catch (_: Exception) { null }
        }
    }

    fun fetch(ctx: Context, account: String, selectors: List<String>, cb: (Map<String, Raw>?, String?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        Thread {
            val result = try {
                selectors.associateWith { load(account, it) } to null
            } catch (e: java.io.IOException) {
                null to "немає з'єднання з сайтом обленерго"
            } catch (e: Exception) {
                null to "незрозуміла відповідь сайту (${e.javaClass.simpleName})"
            }
            main.post { cb(result.first, result.second) }
        }.start()
    }

    private fun load(account: String, selector: String): Raw {
        val url = Uri.parse(API).buildUpon()
            .appendQueryParameter("op", "disconn_by_ls")
            .appendQueryParameter("disconn_selector", selector)
            .appendQueryParameter("n_date", Schedule.dayKey(0).toSiteDate())
            .appendQueryParameter("k_date", Schedule.dayKey(1).toSiteDate())
            .appendQueryParameter("abon_ls", account)
            .build().toString()
        val o = JSONObject(get(url))
        val records = items(o, "DISCONNECTIONS").map { d ->
            val stop = d.optString("DATE_STOP").ifBlank { d.optString("DATE_TIME").replace(Regex("<[^>]*>"), " ") }
            SiteParser.Record(d.optString("DATE_START"), stop)
        }
        return Raw(records, items(o, "DISCONN_QUEUQ").map { it.optString("QUEUE_INFO") })
    }

    /** Поле може бути масивом або об'єктом {"0": {...}, "1": {...}}. */
    private fun items(o: JSONObject, name: String): List<JSONObject> = when (val v = o.opt(name)) {
        is JSONArray -> (0 until v.length()).mapNotNull { v.optJSONObject(it) }
        is JSONObject -> v.keys().asSequence().mapNotNull { v.optJSONObject(it) }.toList()
        else -> emptyList()
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("Referer", "https://www.cherkasyoblenergo.com/")
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** yyyy-MM-dd → dd.MM.yyyy */
    private fun String.toSiteDate() = split("-").reversed().joinToString(".")

    /** evaluateJavascript повертає JSON-літерал; розгортаємо рядок. */
    fun decode(r: String?): String? {
        if (r == null || r == "null") return null
        return try {
            if (r.startsWith("\"")) JSONArray("[$r]").getString(0) else r
        } catch (_: Exception) { null }
    }

    /**
     * Для WebView у SiteActivity: сервер API не віддає проміжний сертифікат, і WebView
     * повідомляє про помилку. Пропускаємо лише сертифікат доменів обленерго,
     * підписаний саме тим проміжним сертифікатом Sectigo, що вкладений у додаток.
     */
    fun trusted(ctx: Context, error: SslError): Boolean {
        if (Build.VERSION.SDK_INT < 29 || error.primaryError != SslError.SSL_UNTRUSTED) return false
        val host = Uri.parse(error.url).host ?: return false
        if (host != "cherkasyoblenergo.com" && !host.endsWith(".cherkasyoblenergo.com")) return false
        return try {
            val leaf = error.certificate.x509Certificate ?: return false
            val ca = ctx.resources.openRawResource(R.raw.sectigo_dv_r36).use {
                CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
            }
            leaf.checkValidity()
            leaf.verify(ca.publicKey)
            val names = leaf.subjectAlternativeNames?.filter { it[0] == 2 }?.map { it[1].toString() } ?: emptyList()
            names.any { n -> n == host || (n.startsWith("*.") && host.endsWith(n.substring(1)) &&
                host.count { it == '.' } == n.count { it == '.' }) }
        } catch (_: Exception) { false }
    }
}

/** Завантажує графік із сайту для кожного місця і зберігає його по датах. */
object Updater {
    @Volatile private var running = false
    private var failures = 0

    private const val HOUR = 60 * 60_000L
    /** Планові ремонти оголошують за кілька днів — досить перевіряти раз на 6 годин. */
    private const val PLANNED_EVERY = 6 * HOUR

    /**
     * Коли наступного разу питати сайт. Дані на сайті оновлюються раз на годину,
     * вночі без найближчих подій — раз на 3 години, після збою — одна швидка повторна спроба.
     */
    fun nextDelay(c: Context): Long {
        if (failures == 1) return 20 * 60_000L
        val now = System.currentTimeMillis()
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (hour >= 23 || hour < 6) {
            val soon = Schedule.upcoming(c, false).firstOrNull()?.at
            if (soon == null || soon - now > 2 * HOUR) return 3 * HOUR
        }
        return HOUR
    }

    /** changes: місце → новий графік на сьогодні (лише там, де змінилися майбутні відключення). */
    data class Outcome(val ok: Boolean, val message: String, val changes: Map<Int, List<Interval>>)

    /** force — людина натиснула «Оновити зараз»: беремо і планові ремонти. */
    fun update(c: Context, force: Boolean = false, onDone: (Outcome) -> Unit) {
        val slots = Prefs.accountSlots(c)
        if (slots.isEmpty()) { onDone(Outcome(false, "Введіть номер особового рахунку", emptyMap())); return }
        if (running) { onDone(Outcome(false, "Оновлення вже триває", emptyMap())); return }
        if (force && System.currentTimeMillis() - Prefs.lastFetch(c) < 60_000L) {
            onDone(Outcome(true, "Графік щойно оновлено", emptyMap())); return
        }
        running = true
        val results = ArrayList<Pair<Int, Outcome>>()
        fun next(i: Int) {
            if (i == slots.size) {
                running = false
                failures = if (results.all { it.second.ok }) 0 else failures + 1
                Prefs.setLastFetch(c)
                c.sendBroadcast(Intent(PowerService.BROADCAST_UPDATE).setPackage(c.packageName))
                onDone(combine(c, results))
                return
            }
            val slot = slots[i]
            val cached = SiteFetcher.Raw.decode(Prefs.planned(c, slot))
            val askPlanned = force || cached == null ||
                System.currentTimeMillis() - Prefs.plannedAt(c, slot) > PLANNED_EVERY
            val selectors = if (askPlanned) listOf(SiteFetcher.SCHEDULES, SiteFetcher.PLANNED) else listOf(SiteFetcher.SCHEDULES)
            SiteFetcher.fetch(c, Prefs.account(c, slot), selectors) { raws, err ->
                val sched = raws?.get(SiteFetcher.SCHEDULES)
                val planned = raws?.get(SiteFetcher.PLANNED)?.also { Prefs.setPlanned(c, slot, it.encode()) } ?: cached
                results += slot to (if (sched == null || planned == null) fail(c, slot, err ?: "невідома помилка")
                    else applyParsed(c, slot,
                        SiteParser.parseApi(sched.records + planned.records, sched.queue + planned.queue), err))
                next(i + 1)
            }
        }
        next(0)
    }

    private fun combine(c: Context, results: List<Pair<Int, Outcome>>): Outcome {
        val named = results.size > 1
        return Outcome(
            ok = results.all { it.second.ok },
            message = results.joinToString("\n") { (slot, o) ->
                if (named) "${Prefs.place(c, slot).replaceFirstChar { it.uppercase() }}: ${o.message}" else o.message
            },
            changes = results.flatMap { it.second.changes.entries }.associate { it.key to it.value })
    }

    private fun fail(c: Context, slot: Int, err: String): Outcome {
        Prefs.setSiteInfo(c, slot, null, "${Texts.hhmm(System.currentTimeMillis())} — $err")
        return Outcome(false, "Не вдалося оновити графік: $err", emptyMap())
    }

    /** Застосовує текст сторінки з ручного перегляду сайту. */
    fun apply(c: Context, slot: Int, text: String): Outcome {
        Prefs.setSiteRaw(c, slot, text)
        return applyParsed(c, slot, SiteParser.parse(text, Schedule.dayKey(0)), null)
    }

    private fun applyParsed(c: Context, slot: Int, p: SiteParser.Parsed, err: String?): Outcome {
        val today = Schedule.dayKey(0)
        if (!p.hasResult) return fail(c, slot, err ?: "на сторінці не знайдено графіка")
        // Черга знайдена, а годин немає і сайт не пише «відключень немає» — не вгадуємо
        if (p.byDate.isEmpty() && !p.explicitNone) {
            Prefs.setSiteInfo(c, slot, p.queue, "")
            return fail(c, slot, "не вдалося прочитати години відключень")
        }

        val before = if (Prefs.hasSiteDay(c, slot, today)) Schedule.parse(Prefs.siteDay(c, slot, today)) else null
        for ((day, list) in p.byDate) Prefs.setSiteDay(c, slot, day, list.joinToString("\n"))
        // Є дані на інші дні або сайт пише «відключень немає» — на сьогодні без відключень
        if (today !in p.byDate) Prefs.setSiteDay(c, slot, today, "")
        // Обленерго скасувало завтрашні відключення — прибираємо старі дані
        val tomorrow = Schedule.dayKey(1)
        if (p.coversTomorrow && tomorrow !in p.byDate && Prefs.hasSiteDay(c, slot, tomorrow)) Prefs.setSiteDay(c, slot, tomorrow, "")
        Prefs.pruneSite(c)

        val now = Schedule.parse(Prefs.siteDay(c, slot, today))
        // Зміни в минулому (фактичний кінець 11:03 замість 11:00) не оголошуємо
        val changed = before != null && future(before) != future(now)
        Prefs.setSiteInfo(c, slot, p.queue, "оновлено о ${Texts.hhmm(System.currentTimeMillis())}")
        val hours = Schedule.fmt(now).replace("\n", ", ")
        if (changed) Prefs.addLog(c, "Графік змінено (${Prefs.place(c, slot)}): ${hours.ifEmpty { "без відключень" }}")
        val msg = "Графік на сьогодні: " + hours.ifEmpty { "відключень немає" }
        return Outcome(true, msg, if (changed) mapOf(slot to now) else emptyMap())
    }

    private fun future(list: List<Interval>): List<Interval> {
        val cal = java.util.Calendar.getInstance()
        val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        return list.filter { it.to > nowMin || it.to < it.from }
    }
}
