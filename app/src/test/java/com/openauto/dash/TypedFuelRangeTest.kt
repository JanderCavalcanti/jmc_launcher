package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The range typed off the trip computer, for cars whose fuel level isn't on the OBD. */
class TypedFuelRangeTest {

    @Test
    fun countsDownWithTheKilometresDriven() {
        val r = TypedRange(520, at = 0).drove(12.4).drove(7.7)
        assertEquals(500, r.nowKm)
    }

    @Test
    fun neverGoesBelowZero() {
        assertEquals(0, TypedRange(30, at = 0).drove(45.0).nowKm)
    }

    @Test
    fun noDistanceChangesNothing() {
        val r = TypedRange(300, at = 0)
        assertEquals(r, r.drove(0.0))
        assertEquals(r, r.drove(-3.0))
    }

    @Test
    fun aFullTankAtTheUsualConsumption() {
        // Taos: 50 L at 8 L/100 km.
        assertEquals(625, TypedRange.fullTankKm(50.0, 8.0))
        assertEquals(0, TypedRange.fullTankKm(50.0, 0.0))
    }

    @Test
    fun readsWhatTheDriverTyped() {
        assertEquals(520, TypedRange.parse("520"))
        assertEquals(520, TypedRange.parse(" 520 km"))
        assertEquals(1020, TypedRange.parse("1.020"))
        assertNull(TypedRange.parse(""))
        assertNull(TypedRange.parse("0"))
        assertNull(TypedRange.parse("99999"))
    }
}
