package com.openauto.dash

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * "What does my car answer?": asks the car which standard mode-01 PIDs it
 * serves (0100, 0120 ... 01C0), then reads each one once and shows the value.
 * The regular polling only asks for the dozen readings the tiles use; this is
 * the full list, to plan which readings a dashboard can show. Read-only:
 * nothing but mode 01 is sent. The report is also written to
 * Android/data/<package>/files/obd-scan.txt, so it can be pulled over adb.
 */

/** A standard PID the scan knows how to read: its [formula] over the reply's bytes A, B, C, D. */
data class ScanPid(val pid: Int, @StringRes val nameRes: Int, val unit: String, val formula: String, val decimals: Int = 0, val shown: Boolean = false) {
    val request: String get() = "01" + hex(pid)
    val replyHeader: String get() = "41" + hex(pid)
}

/** One line of the result: a PID the car said it serves, what it answered, and the value when the scan can read it. */
data class ScanRow(val pid: Int, val known: ScanPid?, val reply: String?, val value: Double?)

data class ScanState(
    val running: Boolean = false,
    val step: Int = 0,
    val steps: Int = 0,
    val rows: List<ScanRow> = emptyList(),
    val at: Long = 0L,
    val protocol: String? = null,
    val adapterVolts: Double? = null,
    @StringRes val error: Int? = null,
    val savedTo: String? = null
)

internal fun hex(b: Int): String = String.format(Locale.US, "%02X", b)

/** The pure part: the catalog, the supported-PID ranges and the decoding. Unit-tested. */
object ObdScanRules {
    /** Every range the scan asks about: 0100 lists 01-20, 0120 lists 21-40 ... 01C0 lists C1-E0. */
    val RANGES = listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0)

    /** The standard PIDs (SAE J1979) worth showing; [ScanPid.shown] marks what the tiles already read. */
    val CATALOG: List<ScanPid> = listOf(
        ScanPid(0x04, R.string.scan_p04, "%", "A*100/255", shown = true),
        ScanPid(0x05, R.string.scan_p05, "°C", "A-40", shown = true),
        ScanPid(0x06, R.string.scan_p06, "%", "(A-128)*100/128", 1),
        ScanPid(0x07, R.string.scan_p07, "%", "(A-128)*100/128", 1),
        ScanPid(0x08, R.string.scan_p08, "%", "(A-128)*100/128", 1),
        ScanPid(0x09, R.string.scan_p09, "%", "(A-128)*100/128", 1),
        ScanPid(0x0A, R.string.scan_p0a, "kPa", "A*3"),
        ScanPid(0x0B, R.string.scan_p0b, "kPa", "A"),
        ScanPid(0x0C, R.string.scan_p0c, "rpm", "(A*256+B)/4", shown = true),
        ScanPid(0x0D, R.string.scan_p0d, "km/h", "A", shown = true),
        ScanPid(0x0E, R.string.scan_p0e, "°", "A/2-64", 1),
        ScanPid(0x0F, R.string.scan_p0f, "°C", "A-40", shown = true),
        ScanPid(0x10, R.string.scan_p10, "g/s", "(A*256+B)/100", 2),
        ScanPid(0x11, R.string.scan_p11, "%", "A*100/255", shown = true),
        ScanPid(0x14, R.string.scan_p14, "V", "A/200", 3),
        ScanPid(0x15, R.string.scan_p15, "V", "A/200", 3),
        ScanPid(0x1C, R.string.scan_p1c, "", "A"),
        ScanPid(0x1F, R.string.scan_p1f, "s", "A*256+B"),
        ScanPid(0x21, R.string.scan_p21, "km", "A*256+B"),
        ScanPid(0x23, R.string.scan_p23, "kPa", "(A*256+B)*10"),
        ScanPid(0x24, R.string.scan_p24, "λ", "(A*256+B)*2/65536", 3),
        ScanPid(0x2C, R.string.scan_p2c, "%", "A*100/255"),
        ScanPid(0x2E, R.string.scan_p2e, "%", "A*100/255"),
        ScanPid(0x2F, R.string.scan_p2f, "%", "A*100/255", shown = true),
        ScanPid(0x30, R.string.scan_p30, "", "A"),
        ScanPid(0x31, R.string.scan_p31, "km", "A*256+B"),
        ScanPid(0x33, R.string.scan_p33, "kPa", "A"),
        ScanPid(0x34, R.string.scan_p34, "λ", "(A*256+B)*2/65536", 3),
        ScanPid(0x3C, R.string.scan_p3c, "°C", "(A*256+B)/10-40"),
        ScanPid(0x42, R.string.scan_p42, "V", "(A*256+B)/1000", 2, shown = true),
        ScanPid(0x43, R.string.scan_p43, "%", "(A*256+B)*100/255"),
        ScanPid(0x44, R.string.scan_p44, "λ", "(A*256+B)*2/65536", 3),
        ScanPid(0x45, R.string.scan_p45, "%", "A*100/255"),
        ScanPid(0x46, R.string.scan_p46, "°C", "A-40"),
        ScanPid(0x47, R.string.scan_p47, "%", "A*100/255"),
        ScanPid(0x49, R.string.scan_p49, "%", "A*100/255"),
        ScanPid(0x4A, R.string.scan_p4a, "%", "A*100/255"),
        ScanPid(0x4C, R.string.scan_p4c, "%", "A*100/255"),
        ScanPid(0x4D, R.string.scan_p4d, "min", "A*256+B"),
        ScanPid(0x4E, R.string.scan_p4e, "min", "A*256+B"),
        ScanPid(0x51, R.string.scan_p51, "", "A"),
        ScanPid(0x52, R.string.scan_p52, "%", "A*100/255"),
        ScanPid(0x5A, R.string.scan_p5a, "%", "A*100/255"),
        ScanPid(0x5C, R.string.scan_p5c, "°C", "A-40"),
        ScanPid(0x5E, R.string.scan_p5e, "L/h", "(A*256+B)/20", 1),
        ScanPid(0xA6, R.string.scan_pa6, "km", "(A*16777216+B*65536+C*256+D)/10", 1)
    )

    private val BY_PID = CATALOG.associateBy { it.pid }

    fun known(pid: Int): ScanPid? = BY_PID[pid]

    /** A PID that is itself a "which PIDs follow" bitmap (20, 40, 60...), not a reading. */
    fun isRangeMarker(pid: Int): Boolean = pid % 0x20 == 0

    /** The decoded row for [pid] from its [reply]; the value only when the catalog knows the formula. */
    fun row(pid: Int, reply: String?): ScanRow {
        val k = known(pid)
        val bytes = reply?.let { ObdParser.dataBytes(it, "41" + hex(pid)) }
        val value = if (k != null && bytes != null) Formula.eval(k.formula, bytes) else null
        return ScanRow(pid, k, reply?.replace(WS, " ")?.trim()?.ifEmpty { null }, value)
    }

    /** The value as text: "92 °C", "0.998 λ", "--". */
    fun valueText(row: ScanRow): String {
        val v = row.value ?: return "--"
        val k = row.known ?: return "--"
        return String.format(Locale.getDefault(), "%.${k.decimals}f", v) + if (k.unit.isEmpty()) "" else " " + k.unit
    }

    /** The report as tab-separated text: PID, name, value, unit, whether the tiles read it, raw reply. */
    fun report(state: ScanState, name: (ScanPid) -> String): String = buildString {
        append("# JMCAR OBD scan ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(state.at))).append('\n')
        state.protocol?.let { append("# protocol ").append(it).append('\n') }
        state.adapterVolts?.let { append("# adapter volts ").append(it).append('\n') }
        append("pid\tname\tvalue\tunit\tjmcar_reads\treply\n")
        state.rows.forEach { r ->
            append("01").append(hex(r.pid)).append('\t')
            append(r.known?.let(name) ?: "?").append('\t')
            append(r.value?.let { String.format(Locale.US, "%.${r.known?.decimals ?: 0}f", it) } ?: "").append('\t')
            append(r.known?.unit ?: "").append('\t')
            append(if (r.known?.shown == true) "yes" else "no").append('\t')
            append(r.reply ?: "").append('\n')
        }
    }

    private val WS = Regex("\\s+")
}

/** Runs the scan over the connected adapter, one at a time. */
object ObdScan {
    private const val TAG = "ObdScan"
    private const val BATCH = 6

    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state.asStateFlow()

    suspend fun run(context: Context) {
        if (_state.value.running) return
        if (ObdBluetoothManager.connectionState.value != ObdConnectionState.CONNECTED) {
            _state.value = ScanState(error = R.string.vehicle_obd_not_connected)
            return
        }
        _state.value = ScanState(running = true, steps = ObdScanRules.RANGES.size)
        try {
            val supported = sortedSetOf<Int>()
            var asked = 0
            for (base in ObdScanRules.RANGES) {
                val reply = ObdBluetoothManager.query(null, "01" + hex(base))
                asked++
                _state.value = _state.value.copy(step = asked)
                val range = reply?.let { ObdParser.parseSupportedPids(it, base) } ?: break
                supported += range
                if (base + 0x20 !in range) break
            }
            if (supported.isEmpty()) {
                _state.value = ScanState(error = R.string.scan_no_answer, at = System.currentTimeMillis())
                return
            }
            val pids = supported.filterNot(ObdScanRules::isRangeMarker)
            _state.value = _state.value.copy(step = 0, steps = pids.size)
            val rows = ArrayList<ScanRow>(pids.size)
            // A few at a time, so the regular polls (speed, revs) get the adapter between batches.
            pids.chunked(BATCH).forEach { batch ->
                val replies = ObdBluetoothManager.queryAll(null, batch.map { "01" + hex(it) })
                batch.forEachIndexed { i, pid -> rows += ObdScanRules.row(pid, replies.getOrNull(i)) }
                _state.value = _state.value.copy(step = rows.size, rows = rows.toList())
            }
            val protocol = ObdBluetoothManager.query(null, "ATDPN")?.trim()?.ifEmpty { null }
            val volts = ObdBluetoothManager.query(null, "ATRV")?.let { ObdParser.parseVoltage(it) }
            val done = ScanState(rows = rows, at = System.currentTimeMillis(), protocol = protocol, adapterVolts = volts)
            _state.value = done.copy(savedTo = save(context, done))
        } catch (e: Exception) {
            Log.w(TAG, "scan failed", e)
            _state.value = _state.value.copy(running = false, error = R.string.scan_failed)
        }
    }

    /** Writes the report where adb can pull it; the path, or null when it couldn't. */
    private suspend fun save(context: Context, s: ScanState): String? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(dir, "obd-scan.txt")
            file.writeText(ObdScanRules.report(s) { context.getString(it.nameRes) })
            file.absolutePath
        }.onFailure { Log.w(TAG, "could not save the report", it) }.getOrNull()
    }
}
