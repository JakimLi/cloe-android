package com.cloe.android

object CloePrefs {
    const val NAME = "cloe_prefs"

    const val KEY_OVERLAY_X = "overlay_offset_x"
    const val KEY_OVERLAY_Y = "overlay_offset_y"
    /** True after user saved position from settings (distinguish from default ints). */
    const val KEY_OVERLAY_SAVED = "overlay_position_saved"

    /** When false, hide the AI context usage HUD above the character (default: show). */
    const val KEY_CONTEXT_BAR_VISIBLE = "context_bar_visible"

    const val DEFAULT_OVERLAY_X = 16
    const val DEFAULT_OVERLAY_Y = 200
}
