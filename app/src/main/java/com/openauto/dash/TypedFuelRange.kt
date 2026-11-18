package com.openauto.dash

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/*
 * The range the driver read on the car's own trip computer and typed in, for
 * cars whose fuel level reaches neither the CANbox nor the OBD (many VW/Audi
 * engine ECUs answer NO DATA to PID 012F: the level lives in the cluster).
 * Counted down by the kilometres driven since, so the fuel-to-destination
 * card has a range to compare the route with. A real reading always wins.
 */

/** [km] of range as of [at], with [drivenSince] kilometres driven after it. */
internal data class TypedRange(val km: Int, val at: Long, val drivenSince: Double = 0.0) {
    /** What's left now: never below zero. */
    val nowKm: Int get() = (km - drivenSince).roundToInt().coerceAtLeast(0)

    fun drove(distanceKm: Double): TypedRange =
        if (distanceKm <= 0.0) this else copy(drivenSince = drivenSince + distanceKm)

    companion object {
        /** The range of a full tank at the car's usual consumption. */
        fun fullTankKm(tankL: Double, litersPer100: Double): Int =
            if (litersPer100 <= 0.0) 0 else (tankL / litersPer100 * 100).roundToInt()

        /** A typed figure: digits only ("520", "520 km", "1.020"); null when it isn't a plausible range. */
        fun parse(text: String): Int? {
            val digits = text.filter { it.isDigit() }
            if (digits.isEmpty() || digits.length > 5) return null
            return digits.toInt().takeIf { it in 1..RangeMapping.MAX_RANGE_KM }
        }
    }
}

internal object TypedFuelRange {
    private const val PREFS = "fuel_typed_range"
    private var appContext: Context? = null
    private val _state = MutableStateFlow<TypedRange?>(null)
    val state: StateFlow<TypedRange?> = _state.asStateFlow()
    private var drivenUnsaved = 0.0

    fun start(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val km = p.getInt("km", -1)
        if (km > 0) _state.value = TypedRange(km, p.getLong("at", 0L), p.getFloat("driven", 0f).toDouble())
    }

    /** The driver read [km] on the trip computer just now; null forgets it. */
    fun set(km: Int?) {
        _state.value = km?.takeIf { it > 0 }?.let { TypedRange(it, System.currentTimeMillis()) }
        drivenUnsaved = 0.0
        save()
    }

    /** Just filled up: a full tank's range at the car's usual consumption. */
    fun fullTank(car: CarProfile = CarProfileStore.current) =
        set(TypedRange.fullTankKm(car.tank, FuelLog.litersPer100() ?: car.typicalUse))

    /** The drive monitor (or the GPS trip) counted [km] more kilometres. */
    fun drove(km: Double) {
        val now = _state.value ?: return
        if (km <= 0.0) return
        _state.value = now.drove(km)
        drivenUnsaved += km
        if (drivenUnsaved >= 1.0) {
            drivenUnsaved = 0.0
            save()
        }
    }

    private fun save() {
        val app = appContext ?: return
        val s = _state.value
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (s == null) clear() else {
                putInt("km", s.km)
                putLong("at", s.at)
                putFloat("driven", s.drivenSince.toFloat())
            }
        }.apply()
    }
}
