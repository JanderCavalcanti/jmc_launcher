package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceLabelTest {
    @Test fun dropsCountryAndCep() {
        assertEquals(
            "R. Ainoã, 12 - Cidade Nova, Manaus - AM",
            PlaceSearch.label("12", "R. Ainoã, 12 - Cidade Nova, Manaus - AM, 69095-000, Brasil")
        )
    }

    @Test fun putsThePlaceNameInFront() {
        assertEquals(
            "JMC Tecnologia, Av. Djalma Batista, 1661 - Chapada, Manaus - AM",
            PlaceSearch.label("JMC Tecnologia", "Av. Djalma Batista, 1661 - Chapada, Manaus - AM, 69050-010, Brasil")
        )
    }

    @Test fun nameAloneWithoutAddress() {
        assertEquals("Teatro Amazonas", PlaceSearch.label("Teatro Amazonas", null))
    }
}
