package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The full OBD scan: catalog, decoding and the report. */
class ObdScanTest {

    @Test
    fun everyCatalogFormulaReadsFourBytes() {
        val bytes = listOf(0x7B, 0x40, 0x10, 0x20)
        ObdScanRules.CATALOG.forEach { p ->
            assertTrue("01%02X".format(p.pid), Formula.eval(p.formula, bytes) != null)
        }
    }

    @Test
    fun catalogHasNoDuplicatesNorRangeMarkers() {
        val pids = ObdScanRules.CATALOG.map { it.pid }
        assertEquals(pids.size, pids.toSet().size)
        assertTrue(pids.none(ObdScanRules::isRangeMarker))
    }

    @Test
    fun theUsualRepliesDecode() {
        assertEquals(1726.0, ObdScanRules.row(0x0C, "41 0C 1A F8").value!!, 0.0)
        assertEquals(50.0, ObdScanRules.row(0x05, "41 05 5A").value!!, 0.0)
        assertEquals(0x80 * 100 / 255.0, ObdScanRules.row(0x52, "41 52 80").value!!, 1e-9)
        assertEquals(12345.6, ObdScanRules.row(0xA6, "41 A6 00 01 E2 40").value!!, 1e-9)
    }

    @Test
    fun silenceAndUnknownPidsHaveNoValue() {
        assertNull(ObdScanRules.row(0x0C, "NO DATA").value)
        assertNull(ObdScanRules.row(0x0C, null).value)
        val unknown = ObdScanRules.row(0x9D, "41 9D 01 02")
        assertNull(unknown.known)
        assertNull(unknown.value)
        assertEquals("41 9D 01 02", unknown.reply)
        assertEquals("--", ObdScanRules.valueText(unknown))
    }

    @Test
    fun theReportIsOneLinePerPid() {
        val s = ScanState(rows = listOf(ObdScanRules.row(0x0D, "41 0D 32"), ObdScanRules.row(0x46, "41 46 4E")), at = 0L)
        val lines = ObdScanRules.report(s) { "n" }.trim().lines()
        assertEquals(4, lines.size)
        assertTrue(lines[2].startsWith("010D\tn\t50\tkm/h\tyes\t"))
        assertTrue(lines[3].startsWith("0146\tn\t38\t°C\tno\t"))
        assertFalse(lines[1].startsWith("01"))
    }
}
