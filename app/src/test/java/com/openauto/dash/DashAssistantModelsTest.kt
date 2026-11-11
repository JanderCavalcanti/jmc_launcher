package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Live model that last talked is tried first for a while, then the best ones again. */
class DashAssistantModelsTest {

    private val now = 1_000_000_000L
    private val last = DashAssistant.MODELS.last()

    @Test
    fun nothingRememberedKeepsTheBestFirst() {
        assertEquals(DashAssistant.MODELS, DashAssistant.modelsToTry(null, 0L, now))
    }

    @Test
    fun theModelThatTalkedGoesFirst() {
        val order = DashAssistant.modelsToTry(last, now - 60_000L, now)
        assertEquals(last, order.first())
        assertEquals(DashAssistant.MODELS.toSet(), order.toSet())
        assertEquals(DashAssistant.MODELS.size, order.size)
    }

    @Test
    fun anOldMemoryGivesTheBestTheirChanceAgain() {
        assertEquals(DashAssistant.MODELS, DashAssistant.modelsToTry(last, now - DashAssistant.REMEMBER_MS - 1, now))
    }

    @Test
    fun aModelNoLongerListedIsIgnored() {
        assertEquals(DashAssistant.MODELS, DashAssistant.modelsToTry("gemini-old-live", now, now))
    }
}
