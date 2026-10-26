package ua.svitlo.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteParserTest {
    private val today = "2026-10-21"

    /** Справжня відповідь API для 5 черги 1 підчерги, 21.10.2026 13:00. */
    @Test fun api() {
        val p = SiteParser.parseApi(
            listOf(
                SiteParser.Record("10:00 21.10.2026", "11:03 21.10.2026"),
                SiteParser.Record("14:00 21.10.2026 (план)", "16:30 21.10.2026 (план)")
            ),
            listOf("Графік аварійних відключень - в чергу не включено",
                   "Графік погодинних відключень - 5 черга 1 підчерга"))
        assertEquals(mapOf(today to listOf("10:00-11:03", "14:00-16:30")), p.byDate)
        assertEquals("5 черга, 1 підчерга", p.queue)
        assertTrue(p.coversTomorrow)
    }

    @Test fun apiEmpty() {
        val p = SiteParser.parseApi(emptyList(), emptyList())
        assertTrue(p.hasResult && p.explicitNone && p.byDate.isEmpty())
    }

    @Test fun overMidnight() {
        val p = SiteParser.parseApi(listOf(
            SiteParser.Record("22:00 21.10.2026", "02:00 23.10.2026"),
            SiteParser.Record("20:00 21.10.2026", "00:00 22.10.2026")), emptyList())
        assertEquals(mapOf(
            today to listOf("20:00-24:00", "22:00-24:00"),
            "2026-10-22" to listOf("00:00-24:00"),
            "2026-10-23" to listOf("00:00-02:00")), p.byDate)
        val ivs = Schedule.parse(p.byDate.getValue("2026-10-22").joinToString("\n"))
        assertEquals(listOf(Interval(0, 1440)), ivs)
    }

    /** Текст сторінки після «Переглянути» (document.body.innerText). */
    @Test fun pageText() {
        val text = """
            Особовий рахунок
            Переглянути
            Графік аварійних відключень - в чергу не включено
            Графік погодинних відключень - 5 черга 1 підчерга
            Вид робіт	Статус	Початок	Кінець
            ГПВ	Завершено	10:00 21.10.2026	11:03 21.10.2026
            ГПВ	Очікується	14:00 21.10.2026 (план)	16:30 21.10.2026 (план)
            КОЛ-ЦЕНТР (цілодобово)
        """.trimIndent()
        val p = SiteParser.parse(text, today)
        assertEquals(mapOf(today to listOf("10:00-11:03", "14:00-16:30")), p.byDate)
        assertEquals("5 черга, 1 підчерга", p.queue)
    }

    /** Android (ICU) не підтримує вбудований прапорець U і падає при завантаженні класу. */
    @Test fun noUnicodeFlagForAndroid() {
        java.io.File("src/main/java/ua/svitlo/alerts").listFiles()!!.forEach { f ->
            assertTrue(f.name, !Regex("""\(\?[a-zA-Z]*U""").containsMatchIn(f.readText()))
        }
    }
}
