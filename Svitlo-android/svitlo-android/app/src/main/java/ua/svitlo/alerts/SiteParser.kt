package ua.svitlo.alerts

/**
 * Розбирає відповідь API cabinet.cherkasyoblenergo.com/api_new/disconn.php
 * і текст сторінки результатів cherkasyoblenergo.com/off.
 * Не залежить від Android — можна перевіряти на звичайній JVM.
 *
 * Розуміє формати:
 *   «14:00 21.10.2026 (план)   16:30 21.10.2026 (план)» (таблиця сайту і поля API)
 *   «Години відсутності ел.ен. на сьогодні: 10:00-11:00, 14:00-16:00»
 *   «21.10.2026  з 10:00 до 11:00»
 *   «21.10.2026 10:00   21.10.2026 11:00» (таблиця початок/кінець)
 */
object SiteParser {

    data class Parsed(
        /** ключ yyyy-MM-dd → інтервали «HH:MM-HH:MM» */
        val byDate: Map<String, List<String>>,
        val queue: String?,
        /** на сторінці є ознаки результату пошуку (а не порожня форма) */
        val hasResult: Boolean,
        /** сайт прямо пише, що відключень немає */
        val explicitNone: Boolean = false,
        /** відповідь охоплює і завтрашній день (запит до API на два дні) */
        val coversTomorrow: Boolean = false
    )

    private val dateRe = Regex("""(\d{1,2})\.(\d{1,2})\.(\d{4})""")
    private val timeRange = Regex(
        """(?<![\d.])(\d{1,2})[:.](\d{2})\s*(?:[-–—]|до|по)\s*(\d{1,2})[:.](\d{2})(?![\d.])""",
        RegexOption.IGNORE_CASE)
    private val timeDate = Regex("""(?<!\d)(\d{1,2}):(\d{2})\s+(\d{1,2})\.(\d{1,2})\.(\d{4})""")
    private val dateTime = Regex("""(\d{1,2})\.(\d{1,2})\.(\d{4})[\s,]+(\d{1,2}):(\d{2})""")
    private val relevant = Regex("відключ|відсут|знеструм|гпв|гав|черг|світл|ел\\.?\\s*ен|електро", RegexOption.IGNORE_CASE)
    private val noise = Regex("графік роботи|режим роботи|прийом|обід|перерв|(?<!\\p{L})(пн|вт|ср|чт|пт|сб|нд)(?!\\p{L})|понеділ|п.ятниц|субот|неділ|кол.?центр|гаряча лінія|оновлю", RegexOption.IGNORE_CASE)
    private val queueRe = listOf(
        Regex("(\\d{1,2})[ \\t]*черг\\p{L}*[ \\t,]*(\\d{1,2})[ \\t]*підчерг\\p{L}*", RegexOption.IGNORE_CASE),
        Regex("черг\\p{L}*[ \\t]*(\\d{1,2})[ \\t,]*підчерг\\p{L}*[ \\t]*(\\d{1,2})", RegexOption.IGNORE_CASE),
        Regex("черг\\p{L}*(?:[ \\t]*(?:гпв|гав))?[ \\t]*[№:]?[ \\t]*(\\d{1,2})[.,/](\\d{1,2})(?!\\d)", RegexOption.IGNORE_CASE),
        Regex("(?<!\\d)(\\d{1,2})[ \\t]*черг\\p{L}*", RegexOption.IGNORE_CASE),
        Regex("черг\\p{L}*(?:[ \\t]*(?:гпв|гав))?[ \\t]*[№:]?[ \\t]*(\\d{1,2})(?!\\d)", RegexOption.IGNORE_CASE)
    )
    private val noOutage = Regex("не заплановано|не передбачено|відключень немає|відключення відсутні|не очікується|не знайдено", RegexOption.IGNORE_CASE)

    fun key(d: Int, m: Int, y: Int) = "%04d-%02d-%02d".format(y, m, d)

    private fun hhmm(h: Int, m: Int) = "%02d:%02d".format(h % 24, m)

    /** Відключення з API: початок і кінець у форматі «14:00 21.10.2026 (план)». */
    data class Record(val start: String, val stop: String)

    fun parseApi(records: List<Record>, queueInfo: List<String>): Parsed {
        val byDate = LinkedHashMap<String, MutableList<String>>()
        for (r in records) addSpan(byDate, r.start, r.stop)
        val queue = findQueue(queueInfo.filter { Regex("\\d").containsMatchIn(it) }.joinToString("\n"))
        // API відповів, отже порожній список означає «відключень немає»
        return Parsed(clean(byDate), queue, hasResult = true,
            explicitNone = byDate.isEmpty(), coversTomorrow = true)
    }

    /**
     * Додає відрізок «час дата — час дата». Відключення через північ або на кілька днів
     * розбивається по днях: 22:00-24:00, 00:00-24:00, 00:00-02:00.
     */
    private fun addSpan(byDate: MutableMap<String, MutableList<String>>, start: String, stop: String): Boolean {
        val a = timeDate.find(start)?.destructured?.toList()?.map { it.toInt() } ?: return false
        val b = timeDate.find(stop)?.destructured?.toList()?.map { it.toInt() } ?: return false
        if (a[0] > 23 || b[0] > 24 || a[1] > 59 || b[1] > 59) return false
        var day = key(a[2], a[3], a[4])
        val lastDay = key(b[2], b[3], b[4])
        if (lastDay < day || (lastDay == day && b[0] * 60 + b[1] <= a[0] * 60 + a[1])) return false
        var from = hhmm(a[0], a[1])
        for (guard in 0..7) {
            if (day == lastDay) {
                val to = hhmm(b[0], b[1])
                if (from != to) byDate.getOrPut(day) { mutableListOf() } += "$from-$to"
                return true
            }
            byDate.getOrPut(day) { mutableListOf() } += "$from-24:00"
            day = shiftKey(day, 1)
            from = "00:00"
        }
        return true
    }

    private fun clean(byDate: Map<String, List<String>>) =
        byDate.mapValues { (_, v) -> v.distinct().sorted() }.filterValues { it.isNotEmpty() }

    private fun findQueue(text: String): String? {
        for (re in queueRe) {
            val m = re.find(text) ?: continue
            return if (m.groupValues.size > 2 && m.groupValues[2].isNotEmpty())
                "${m.groupValues[1]} черга, ${m.groupValues[2]} підчерга" else "${m.groupValues[1]} черга"
        }
        return null
    }

    fun parse(fullText: String, todayKey: String): Parsed {
        // Результати йдуть після кнопки «Переглянути»; меню і шапку відкидаємо
        val cut = fullText.indexOf("Переглянути")
        val body = if (cut >= 0) fullText.substring(cut + "Переглянути".length) else fullText

        val byDate = LinkedHashMap<String, MutableList<String>>()
        var current = todayKey
        var prevLine = ""
        // Рядок «на сьогодні/на завтра» перемикає дату
        for (raw in body.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val lower = line.lowercase()

            // Таблиця сайту «час дата — час дата»
            val tds = timeDate.findAll(line).toList()
            if (tds.size >= 2) {
                var i = 0
                while (i + 1 < tds.size) { addSpan(byDate, tds[i].value, tds[i + 1].value); i += 2 }
                prevLine = line
                continue
            }

            // Таблиця «дата час — дата час»
            val dts = dateTime.findAll(line).toList()
            if (dts.size >= 2) {
                var i = 0
                while (i + 1 < dts.size) {
                    val a = dts[i].destructured.toList().map { it.toInt() }
                    val b = dts[i + 1].destructured.toList().map { it.toInt() }
                    val day = key(a[0], a[1], a[2])
                    byDate.getOrPut(day) { mutableListOf() } += "${hhmm(a[3], a[4])}-${hhmm(b[3], b[4])}"
                    i += 2
                }
                current = key(dts.last().groupValues[1].toInt(), dts.last().groupValues[2].toInt(), dts.last().groupValues[3].toInt())
                prevLine = line
                continue
            }

            dateRe.findAll(line).lastOrNull()?.let {
                val (d, m, y) = it.destructured.toList().map { s -> s.toInt() }
                current = key(d, m, y)
            }
            if ("на сьогодні" in lower) current = todayKey
            if ("на завтра" in lower) current = shiftKey(todayKey, 1)

            val ranges = timeRange.findAll(line).toList()
            if (ranges.isNotEmpty() && !noise.containsMatchIn(line)) {
                val context = "$prevLine $line"
                val ok = relevant.containsMatchIn(context) || stripped(line).length <= 12
                if (ok) for (r in ranges) {
                    val (h1, m1, h2, m2) = r.destructured.toList().map { it.toInt() }
                    if (h1 <= 24 && h2 <= 24 && m1 < 60 && m2 < 60 && !(h1 == h2 && m1 == m2)) {
                        byDate.getOrPut(current) { mutableListOf() } += "${hhmm(h1, m1)}-${hhmm(h2, m2)}"
                    }
                }
            }
            prevLine = line
        }

        val queue = findQueue(body)
        val clean = clean(byDate)
        val none = noOutage.containsMatchIn(body)
        val hasResult = clean.isNotEmpty() || queue != null || none
        return Parsed(clean, queue, hasResult, none)
    }

    /** Текст рядка без часових інтервалів, розділових знаків і службових слів. */
    private fun stripped(line: String) = line
        .replace(timeRange, "")
        .replace(dateRe, "")
        .replace(Regex("(?<!\\p{L})(з|до|по|та|і|год|хв)(?!\\p{L})", RegexOption.IGNORE_CASE), "")
        .replace(Regex("[\\s,;:|•·–—\\-]+"), "")

    fun shiftKey(key: String, days: Int): String {
        val (y, m, d) = key.split("-").map { it.toInt() }
        val cal = java.util.GregorianCalendar(y, m - 1, d)
        cal.add(java.util.Calendar.DAY_OF_YEAR, days)
        return "%04d-%02d-%02d".format(
            cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH))
    }
}
