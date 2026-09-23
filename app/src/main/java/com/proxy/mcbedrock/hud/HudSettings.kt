package com.proxy.mcbedrock.hud

import android.content.Context
import android.content.SharedPreferences

/**
 * The user's HUD choices: which modules are on, where the panel sits, and whether
 * it snaps to corners.
 *
 * A tiny wrapper over SharedPreferences; the placement arithmetic lives in
 * [HudLayout] and is unit tested.
 */
class HudSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Enabled module ids; starts from the modules' own defaults. */
    var enabledIds: Set<String>
        get() {
            val raw = prefs.getStringSet(KEY_ENABLED, null)
            return raw ?: HudModule.defaultEnabledIds
        }
        set(value) = prefs.edit().putStringSet(KEY_ENABLED, value).apply()

    var corner: HudCorner
        get() = prefs.getString(KEY_CORNER, null)?.let { stored ->
            HudCorner.entries.firstOrNull { it.name == stored }
        } ?: HudCorner.TOP_START
        set(value) = prefs.edit().putString(KEY_CORNER, value.name).apply()

    /** Drag offset from the corner anchor, so a moved HUD stays where it was put. */
    var offsetX: Int
        get() = prefs.getInt(KEY_OFFSET_X, 0)
        set(value) = prefs.edit().putInt(KEY_OFFSET_X, value).apply()

    var offsetY: Int
        get() = prefs.getInt(KEY_OFFSET_Y, 0)
        set(value) = prefs.edit().putInt(KEY_OFFSET_Y, value).apply()

    var snapToCorner: Boolean
        get() = prefs.getBoolean(KEY_SNAP, true)
        set(value) = prefs.edit().putBoolean(KEY_SNAP, value).apply()

    var showFab: Boolean
        get() = prefs.getBoolean(KEY_FAB, true)
        set(value) = prefs.edit().putBoolean(KEY_FAB, value).apply()

    /** Horizontal order the lines are drawn in, module ids first. */
    var order: List<String>
        get() = prefs.getString(KEY_ORDER, null)
            ?.split(',')
            ?.filter { it.isNotBlank() }
            ?: HudModule.ordered.map { it.id }
        set(value) = prefs.edit().putString(KEY_ORDER, value.joinToString(",")).apply()

    fun enabledModules(): List<HudModule> {
        val ids = enabledIds
        val known = order.mapNotNull { HudModule.byId(it) }
        val missing = HudModule.ordered.filterNot { it in known }
        return (known + missing).filter { it.id in ids }
    }

    fun toggle(module: HudModule, enabled: Boolean) {
        val updated = enabledIds.toMutableSet()
        if (enabled) updated += module.id else updated -= module.id
        enabledIds = updated
    }

    fun resetPositions() {
        corner = HudCorner.TOP_START
        offsetX = 0
        offsetY = 0
    }

    private companion object {
        const val PREFS = "celestia.hud"
        const val KEY_ENABLED = "enabled"
        const val KEY_CORNER = "corner"
        const val KEY_OFFSET_X = "offset_x"
        const val KEY_OFFSET_Y = "offset_y"
        const val KEY_SNAP = "snap"
        const val KEY_FAB = "fab"
        const val KEY_ORDER = "order"
    }
}
