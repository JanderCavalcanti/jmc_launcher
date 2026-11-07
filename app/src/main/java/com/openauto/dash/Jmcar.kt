package com.openauto.dash

import android.content.Context

/*
 * JMCAR runs on the Carlinkit tbox (CPC200, Android 13), plugged into the car's
 * own screen. It is not a Chinese head unit: no FM tuner, no reversing-camera
 * input, no MCU for the steering-wheel buttons, no CAN box, no TPMS app, and the
 * system keeps other apps full screen (it refuses app windows over the launcher
 * and the Maps dock). What only such a unit can do is left out here, in one place.
 */
internal object Jmcar {
    /** The bar's Layout menu: only one layout is left ("Panels only"), so no button. */
    const val LAYOUT_MENU = false

    /** An app in a tile (inside it or in a window over it): the tbox keeps apps full screen. */
    const val APP_WINDOWS = false

    /**
     * The map tile shows the real Google Maps, running inside it
     * ([BuiltinKind.MAPS_INSIDE], see [EmbeddedApp]), wherever Android grants
     * JMCAR the system permissions that takes: on the tbox, whose firmware is
     * signed with the public AOSP platform key, JMCAR is signed with it too.
     * Elsewhere, or if the permissions go, the tile is the MapLibre map again.
     * Applied to every layout as it is loaded, so a saved layout keeps one map
     * tile whichever way it goes.
     */
    fun mapTiles(context: Context, pages: List<List<DashboardItem>>): List<List<DashboardItem>> {
        val inside = EmbeddedApp.allowed(context)
        val from = if (inside) BuiltinKind.NAVMAP else BuiltinKind.MAPS_INSIDE
        val to = if (inside) BuiltinKind.MAPS_INSIDE else BuiltinKind.NAVMAP
        if (pages.none { page -> page.any { it is DashboardItem.BuiltinWidget && it.kind == from } }) return pages
        return pages.map { page ->
            page.map { if (it is DashboardItem.BuiltinWidget && it.kind == from) it.copy(kind = to) else it }
        }
    }

    /** The reversing view settings (guide lines, radar over the camera): the tbox has no camera input. */
    const val REVERSE_VIEW = false

    /** The French and German station price feeds: nothing in Brazil, the driver types the prices (JmcarFuel.kt). */
    const val STATION_FEED = false

    /** Tiles that need a head unit's own hardware: FM radio, CAN box, doors, tyres, the car app's data. */
    val HEAD_UNIT_TILES: Set<BuiltinKind> = setOf(
        BuiltinKind.RADIO_PRESETS,
        BuiltinKind.CAN_MON,
        BuiltinKind.DOORS,
        BuiltinKind.TYRES,
        BuiltinKind.CAR_STATUS
    )
}
