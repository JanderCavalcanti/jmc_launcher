package com.openauto.dash

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

    /** The reversing view settings (guide lines, radar over the camera): the tbox has no camera input. */
    const val REVERSE_VIEW = false

    /** Tiles that need a head unit's own hardware: FM radio, CAN box, doors, tyres, the car app's data. */
    val HEAD_UNIT_TILES: Set<BuiltinKind> = setOf(
        BuiltinKind.RADIO_PRESETS,
        BuiltinKind.CAN_MON,
        BuiltinKind.DOORS,
        BuiltinKind.TYRES,
        BuiltinKind.CAR_STATUS
    )
}
