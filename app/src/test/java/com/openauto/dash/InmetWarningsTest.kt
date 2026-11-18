package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Brazil's weather warnings from INMET's feed (InmetWarnings). */
class InmetWarningsTest {
    private val feed = """
        {"hoje":[
          {"id":1,"descricao":"Tempestade","severidade":"Perigo","geocodes":"3550308,3509502",
           "data_inicio":"2026-11-07","hora_inicio":"10:00:00.0","data_fim":"2026-11-08","hora_fim":"10:00:00.0"},
          {"id":2,"descricao":"Acumulado de Chuva","severidade":"Perigo Potencial","geocodes":"3550308",
           "data_inicio":"2026-11-07","hora_inicio":"08:00:00.0","data_fim":"2026-11-07","hora_fim":"20:00:00.0"},
          {"id":3,"descricao":"Onda de Calor","severidade":"Grande Perigo","geocodes":"5300108",
           "data_inicio":"2026-11-07","hora_inicio":"00:00:00.0","data_fim":"2026-11-09","hora_fim":"23:00:00.0"}
        ],"futuro":[
          {"id":4,"descricao":"Vendaval","severidade":"Grande Perigo","geocodes":"3550308",
           "data_inicio":"2026-11-09","hora_inicio":"00:00:00.0","data_fim":"2026-11-10","hora_fim":"00:00:00.0"}
        ]}
    """.trimIndent()

    // 2026-11-07 12:00 in Brasília (15:00 UTC).
    private val noon = InmetWarnings.time("2026-11-07", "12:00", endOfDay = false)!!

    @Test
    fun levelsFollowInmetWords() {
        assertEquals(1, InmetWarnings.level("Perigo Potencial"))
        assertEquals(2, InmetWarnings.level("Perigo"))
        assertEquals(3, InmetWarnings.level("Grande Perigo"))
        assertNull(InmetWarnings.level("Sem aviso"))
    }

    @Test
    fun onlyTheTownsWarningsInForceTheWorstFirst() {
        val list = InmetWarnings.parse(feed, noon, "3550308", "São Paulo")
        assertEquals(listOf("Tempestade", "Acumulado de Chuva"), list.map { it.event })
        assertEquals(listOf(2, 1), list.map { it.level })
        assertTrue(list.all { it.area == "São Paulo" && it.hazard.isEmpty() })
    }

    @Test
    fun overOnesAreLeftOut() {
        val night = InmetWarnings.time("2026-11-07", "21:00", endOfDay = false)!!
        assertEquals(listOf("Tempestade"), InmetWarnings.parse(feed, night, "3550308", "São Paulo").map { it.event })
    }

    @Test
    fun timesAreBrasiliaTime() {
        assertEquals(1_794_056_400_000L, InmetWarnings.time("2026-11-07", "10:00:00.0", endOfDay = false))
        assertEquals(InmetWarnings.time("2026-11-07", "10:00", false), InmetWarnings.time("2026-11-07 10:00:00", "", false))
    }

    @Test
    fun townToIbgeCodeIgnoresAccentsAndCase() {
        val list = """[{"id":3550308,"nome":"São Paulo"},{"id":3549904,"nome":"São José dos Campos"}]"""
        assertEquals("3549904", InmetWarnings.codeIn(list, "sao jose dos campos"))
        assertNull(InmetWarnings.codeIn(list, "Campinas"))
    }
}
