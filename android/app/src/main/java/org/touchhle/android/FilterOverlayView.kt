/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.touchhle.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View

/**
 * Screen filters drawn on top of the emulator's surface. touchHLE renders
 * through OpenGL ES 1.1 (no shaders), so these are overlays, like the
 * "overlay" filters of many emulators. Touches pass through.
 */
class FilterOverlayView(context: Context, initialMode: String) : View(context) {
    private val pattern = Paint()
    private val vignette = Paint()

    /** "none", "scanlines", "lcd" or "crt"; can be changed while the game runs. */
    var mode: String = initialMode
        set(value) {
            field = value
            buildPattern()
            onSizeChanged(width, height, width, height)
            invalidate()
        }

    init {
        isClickable = false
        isFocusable = false
        buildPattern()
    }

    private fun buildPattern() {
        val density = resources.displayMetrics.density
        // One pattern cell ≈ one original iPhone pixel row/column on a typical phone.
        val cell = maxOf(2, (2 * density).toInt())
        val tile = Bitmap.createBitmap(cell, cell, Bitmap.Config.ARGB_8888)
        val dark = Color.argb(if (mode == "crt") 110 else 80, 0, 0, 0)
        for (x in 0 until cell) for (y in 0 until cell) {
            val line = y == cell - 1 || (mode == "lcd" && x == cell - 1)
            tile.setPixel(x, y, if (line) dark else Color.TRANSPARENT)
        }
        pattern.shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (mode == "crt" && w > 0 && h > 0) {
            vignette.shader = RadialGradient(w / 2f, h / 2f, maxOf(w, h) * 0.6f,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(150, 0, 0, 0)),
                floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (mode == "none") return
        canvas.drawPaint(pattern)
        if (mode == "crt") canvas.drawPaint(vignette)
    }
}
