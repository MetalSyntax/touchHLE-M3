/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.touchhle.android

import android.content.Context

/**
 * Per-game options, stored by .ipa file name. Most map to touchHLE
 * command-line options (which take precedence over touchHLE_options.txt);
 * the FPS counter and filter overlay are drawn by MainActivity.
 */
class GameSettings(context: Context, private val game: String) {
    private val prefs = context.getSharedPreferences("game_settings", Context.MODE_PRIVATE)

    private fun get(key: String, def: String) = prefs.getString("$game:$key", def)!!
    private fun put(key: String, value: String) = prefs.edit().putString("$game:$key", value).apply()

    var showFps: Boolean
        get() = get("fps", "0") == "1"
        set(v) = put("fps", if (v) "1" else "0")
    var exitButton: Boolean
        get() = get("exit_button", "1") == "1"
        set(v) = put("exit_button", if (v) "1" else "0")
    /** "smooth" or "sharp" */
    var scaling by pref("scaling", "smooth")
    /** "none", "scanlines", "lcd", "crt" */
    var overlay by pref("overlay", "none")
    /** internal resolution multiplier, "1".."4" */
    var resolution by pref("resolution", "1")
    /** frames skipped per shown frame, "0".."3" */
    var frameskip by pref("frameskip", "0")
    var networkAccess: Boolean
        get() = get("network", "0") == "1"
        set(v) = put("network", if (v) "1" else "0")
    var forceComposition: Boolean
        get() = get("force_composition", "0") == "1"
        set(v) = put("force_composition", if (v) "1" else "0")
    var ignoreGlErrors: Boolean
        get() = get("ignore_gl_errors", "0") == "1"
        set(v) = put("ignore_gl_errors", if (v) "1" else "0")
    var tiltControls: Boolean
        get() = get("tilt", "1") == "1"
        set(v) = put("tilt", if (v) "1" else "0")
    /** "15", "20", "30", "60", "120" or "off" */
    var fpsLimit by pref("fps_limit", "60")
    /** "auto", "landscape-left", "landscape-right", "upside-down" */
    var orientation by pref("orientation", "auto")
    /** "system" or a language code passed to --preferred-languages */
    var language by pref("language", "system")
    /** "auto", "iphone", "ipad" */
    var device by pref("device", "auto")

    private fun pref(key: String, def: String) = object : kotlin.properties.ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = get(key, def)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: String) = put(key, value)
    }

    fun toArgs(): List<String> = buildList {
        add("--print-fps") // always counted, so the in-game menu can show it any time
        if (scaling == "sharp") add("--nearest-filter")
        if (resolution != "1") add("--scale-hack=$resolution")
        if (fpsLimit != "60") add("--fps-limit=$fpsLimit")
        if (frameskip != "0") add("--frameskip=$frameskip")
        if (networkAccess) add("--allow-network-access")
        if (forceComposition) add("--force-composition")
        if (ignoreGlErrors) add("--ignore-gl-errors")
        if (!tiltControls) add("--disable-analog-stick-tilt-controls")
        if (orientation != "auto") add("--$orientation")
        if (language != "system") add("--preferred-languages=$language")
        if (device != "auto") add("--device-family=$device")
    }
}
