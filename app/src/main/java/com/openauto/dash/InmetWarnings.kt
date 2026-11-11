package com.openauto.dash

import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Locale

/*
 * Brazil's weather warnings, from INMET (Instituto Nacional de Meteorologia):
 * the same open feed its own warnings map reads, free and without a key. Each
 * warning lists the municipalities it covers by their IBGE code ("geocodes",
 * comma separated), so the car's municipality is turned into its IBGE code
 * once (IBGE's own list of a state's municipalities, by name) and matched.
 * The levels are INMET's: "Perigo Potencial" yellow, "Perigo" orange,
 * "Grande Perigo" red. The event ("Tempestade", "Acumulado de Chuva") is
 * already in Portuguese and is shown as it comes.
 */
internal object InmetWarnings {
    const val FEED = "https://apiprevmet3.inmet.gov.br/avisos/ativos"
    private const val IBGE = "https://servicodados.ibge.gov.br/api/v1/localidades/estados/%s/municipios"

    /** Brasília time: INMET's dates and hours are local, and Brazil keeps no summer time since 2019. */
    private val BRT = ZoneOffset.ofHours(-3)

    /** INMET's severity words as the levels the tile and the voice use (1 yellow, 2 orange, 3 red). */
    fun level(severity: String): Int? {
        val s = normalize(severity)
        return when {
            s.startsWith("grande perigo") -> 3
            s.startsWith("perigo potencial") -> 1
            s.startsWith("perigo") -> 2
            else -> null
        }
    }

    /**
     * The warnings in force at [now] for the municipality [ibgeCode], each
     * named after [area] (the car's town). Both lists of the feed are read
     * ("hoje" and "futuro"); one not started yet or already over is left out.
     */
    fun parse(json: String, now: Long, ibgeCode: String, area: String): List<WeatherWarning> {
        val text = json.trim()
        val alerts = mutableListOf<JSONObject>()
        fun addAll(a: JSONArray?) { if (a != null) for (i in 0 until a.length()) a.optJSONObject(i)?.let(alerts::add) }
        if (text.startsWith("[")) addAll(JSONArray(text)) else {
            val o = JSONObject(text)
            o.keys().forEach { k -> addAll(o.optJSONArray(k)) }
        }
        return alerts.mapNotNull { a ->
            val codes = a.optString("geocodes").split(',', ';', ' ').map { it.trim() }
            if (ibgeCode !in codes) return@mapNotNull null
            val level = level(a.optString("severidade")) ?: return@mapNotNull null
            val onset = time(a.optString("data_inicio"), a.optString("hora_inicio"), endOfDay = false)
                ?: time(a.optString("inicio"), "", endOfDay = false) ?: 0L
            val expires = time(a.optString("data_fim"), a.optString("hora_fim"), endOfDay = true)
                ?: time(a.optString("fim"), "", endOfDay = true) ?: 0L
            if (onset > now) return@mapNotNull null
            if (expires in 1 until now) return@mapNotNull null
            val event = a.optString("descricao").ifBlank { a.optString("evento") }.trim()
            if (event.isEmpty()) return@mapNotNull null
            // No MeteoAlarm hazard: hazardName() then shows INMET's own words.
            WeatherWarning(area, "", event, level, onset, expires)
        }.sortedByDescending { it.level }.distinctBy { it.event }
    }

    private val datePart = Regex("(\\d{4})-(\\d{2})-(\\d{2})")
    private val timePart = Regex("(\\d{1,2}):(\\d{2})")

    /** "2026-11-07" with "14:00:00.0" (or "2026-11-07 14:00:00" alone) as epoch ms in Brasília time. */
    internal fun time(date: String, hour: String, endOfDay: Boolean): Long? {
        val d = datePart.find(date) ?: return null
        val day = runCatching { LocalDate.of(d.groupValues[1].toInt(), d.groupValues[2].toInt(), d.groupValues[3].toInt()) }.getOrNull() ?: return null
        val t = timePart.find(hour.ifBlank { date.substring(d.range.last + 1) })
        val at = t?.let { runCatching { LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }.getOrNull() }
            ?: if (endOfDay) LocalTime.of(23, 59) else LocalTime.MIDNIGHT
        return day.atTime(at).toInstant(BRT).toEpochMilli()
    }

    /** "São José dos Campos" and "sao jose dos campos" are the same town. */
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ").trim()

    /** The IBGE code of [town] in the state [uf] ("SP") from IBGE's list (a JSON array of {id, nome}). */
    fun codeIn(json: String, town: String): String? {
        val want = normalize(town)
        val list = JSONArray(json)
        for (i in 0 until list.length()) {
            val m = list.optJSONObject(i) ?: continue
            if (normalize(m.optString("nome")) == want) return m.opt("id")?.toString()
        }
        return null
    }

    private val codes = HashMap<String, String?>()

    /** The IBGE code of [town] in [uf], asked once per town; null when IBGE doesn't know it. Blocking, IO thread. */
    @Synchronized
    fun ibgeCode(uf: String, town: String): String? {
        val key = uf.uppercase(Locale.ROOT) + "|" + normalize(town)
        if (codes.containsKey(key)) return codes[key]
        val request = Request.Builder().url(String.format(Locale.US, IBGE, uf.uppercase(Locale.ROOT))).header("User-Agent", OVERPASS_AGENT).build()
        val code = Http.client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) error("IBGE HTTP ${r.code}")
            codeIn(r.body?.string().orEmpty(), town)
        }
        codes[key] = code
        return code
    }

    /** The feed itself, as text. Blocking, IO thread. */
    fun fetch(): String {
        val request = Request.Builder().url(FEED).header("User-Agent", OVERPASS_AGENT).build()
        return Http.client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) error("INMET HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
    }
}
