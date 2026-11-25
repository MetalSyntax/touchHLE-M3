/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.touchhle.android

import android.content.Context
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch

/** Per-game settings as a Material 3 bottom sheet. Changes are saved immediately. */
object SettingsSheet {
    fun show(context: Context, game: String, title: String, icon: Bitmap?) {
        val s = GameSettings(context, game)
        val dialog = BottomSheetDialog(context)
        val inflater = LayoutInflater.from(context)
        val root = inflater.inflate(R.layout.sheet_settings, null)
        root.findViewById<TextView>(R.id.sheet_title).text = title
        root.findViewById<ImageView>(R.id.sheet_icon).apply {
            if (icon != null) setImageBitmap(icon) else visibility = View.GONE
        }
        val body = root.findViewById<LinearLayout>(R.id.sheet_body)

        fun section(label: Int) {
            inflater.inflate(R.layout.sheet_section, body, false).let {
                (it as TextView).setText(label); body.addView(it)
            }
        }
        fun choice(label: Int, options: List<Pair<String, String>>, current: String, set: (String) -> Unit) {
            section(label)
            val group = inflater.inflate(R.layout.sheet_chips, body, false) as ChipGroup
            for ((value, text) in options) {
                val chip = inflater.inflate(R.layout.sheet_chip, group, false) as Chip
                chip.text = text
                chip.id = View.generateViewId()
                chip.isChecked = value == current
                chip.setOnCheckedChangeListener { _, checked -> if (checked) set(value) }
                group.addView(chip)
            }
            body.addView(group)
        }
        fun str(id: Int) = context.getString(id)

        // Display
        fun toggle(label: Int, value: Boolean, set: (Boolean) -> Unit) {
            val sw = inflater.inflate(R.layout.sheet_switch, body, false) as MaterialSwitch
            sw.setText(label)
            sw.isChecked = value
            sw.setOnCheckedChangeListener { _, v -> set(v) }
            body.addView(sw)
        }
        toggle(R.string.opt_show_fps, s.showFps) { s.showFps = it }
        toggle(R.string.opt_exit_button, s.exitButton) { s.exitButton = it }
        choice(R.string.opt_filter, listOf(
            "none" to str(R.string.filter_none), "scanlines" to str(R.string.filter_scanlines),
            "lcd" to str(R.string.filter_lcd), "crt" to str(R.string.filter_crt),
        ), s.overlay) { s.overlay = it }
        choice(R.string.opt_scaling, listOf(
            "smooth" to str(R.string.scaling_smooth), "sharp" to str(R.string.scaling_sharp),
        ), s.scaling) { s.scaling = it }
        choice(R.string.opt_resolution, listOf("1" to "1×", "2" to "2×", "3" to "3×", "4" to "4×"),
            s.resolution) { s.resolution = it }
        section(R.string.section_performance)
        choice(R.string.opt_fps_limit, listOf(
            "15" to "15", "20" to "20", "30" to "30", "60" to "60", "120" to "120",
            "off" to str(R.string.fps_unlimited),
        ), s.fpsLimit) { s.fpsLimit = it }
        choice(R.string.opt_frameskip, listOf(
            "0" to str(R.string.frameskip_off), "1" to "1", "2" to "2", "3" to "3",
        ), s.frameskip) { s.frameskip = it }
        // Device
        choice(R.string.opt_orientation, listOf(
            "auto" to str(R.string.auto), "landscape-left" to str(R.string.orient_land_left),
            "landscape-right" to str(R.string.orient_land_right), "upside-down" to str(R.string.orient_upside),
        ), s.orientation) { s.orientation = it }
        choice(R.string.opt_device, listOf(
            "auto" to str(R.string.auto), "iphone" to "iPhone", "ipad" to "iPad",
        ), s.device) { s.device = it }
        choice(R.string.opt_language, listOf(
            "system" to str(R.string.lang_system), "en" to "English", "es" to "Español",
            "pt" to "Português", "fr" to "Français", "de" to "Deutsch", "it" to "Italiano",
            "ja" to "日本語", "ko" to "한국어", "zh-Hans" to "中文",
        ), s.language) { s.language = it }

        section(R.string.section_advanced)
        toggle(R.string.opt_tilt, s.tiltControls) { s.tiltControls = it }
        toggle(R.string.opt_network, s.networkAccess) { s.networkAccess = it }
        toggle(R.string.opt_force_composition, s.forceComposition) { s.forceComposition = it }
        toggle(R.string.opt_ignore_gl_errors, s.ignoreGlErrors) { s.ignoreGlErrors = it }

        root.findViewById<MaterialButton>(R.id.sheet_reset).setOnClickListener {
            context.getSharedPreferences("game_settings", Context.MODE_PRIVATE).edit().apply {
                context.getSharedPreferences("game_settings", Context.MODE_PRIVATE).all.keys
                    .filter { it.startsWith("$game:") }.forEach { remove(it) }
            }.apply()
            dialog.dismiss()
            show(context, game, title, icon)
        }
        root.findViewById<MaterialButton>(R.id.sheet_done).setOnClickListener { dialog.dismiss() }
        dialog.setContentView(root)
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }
}
