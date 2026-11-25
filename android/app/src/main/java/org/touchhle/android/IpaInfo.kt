/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.touchhle.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.Inflater
import java.util.zip.ZipFile

/** Display name and icon read from an .ipa, cached on disk by file name + mtime. */
object IpaInfo {
    class Info(val name: String?, val icon: Bitmap?, val bundleId: String? = null, val iconPath: String? = null)

    fun load(ipa: File, cacheDir: File): Info {
        val dir = File(cacheDir, "ipa_info").apply { mkdirs() }
        val key = "v3_${ipa.name.hashCode()}_${ipa.lastModified()}" // bump when parsing changes
        val iconFile = File(dir, "$key.png")
        val nameFile = File(dir, "$key.txt")
        if (nameFile.exists()) {
            val lines = nameFile.readLines()
            return Info(lines.getOrNull(0)?.ifBlank { null },
                if (iconFile.exists()) BitmapFactory.decodeFile(iconFile.path) else null,
                lines.getOrNull(1)?.ifBlank { null },
                iconFile.path.takeIf { iconFile.exists() })
        }
        var name: String? = null
        var bundleId: String? = null
        var icon: Bitmap? = null
        try {
            ZipFile(ipa).use { zip ->
                val entries = zip.entries().asSequence().map { it.name }
                    .filter { !it.startsWith("__MACOSX") }.toList()
                val appDir = entries.firstNotNullOfOrNull {
                    Regex("^Payload/[^/]+\\.app/").find(it)?.value
                }
                fun bytes(n: String) = zip.getEntry(n)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }

                if (appDir != null) {
                    bytes(appDir + "Info.plist")?.let { plist ->
                        val dict = parsePlistStrings(plist)
                        name = dict["CFBundleDisplayName"] ?: dict["CFBundleName"]
                        bundleId = dict["CFBundleIdentifier"]
                    }
                }
                // Best quality first. iTunesArtwork is a plain 512px PNG/JPEG.
                val candidates = mutableListOf("iTunesArtwork")
                if (appDir != null) {
                    candidates += listOf("Icon@2x.png", "Icon-72.png", "Icon.png", "icon.png").map { appDir + it }
                    candidates += entries.filter {
                        it.startsWith(appDir) && it.count { c -> c == '/' } == 2 &&
                            it.lowercase().let { l -> l.contains("icon") && l.endsWith(".png") } &&
                            (zip.getEntry(it)?.size ?: Long.MAX_VALUE) < 64 * 1024
                    }
                }
                for (c in candidates) {
                    val data = bytes(c) ?: continue
                    icon = decodeImage(data)
                    if (icon != null) break
                }
            }
        } catch (e: Exception) {
            // Broken/unknown IPA: fall back to the monogram, the launcher shows errors on launch.
        }
        nameFile.writeText("${name ?: ""}\n${bundleId ?: ""}")
        icon?.let { b -> iconFile.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return Info(name, icon, bundleId, iconFile.path.takeIf { iconFile.exists() })
    }

    fun decodeImage(data: ByteArray): Bitmap? =
        if (isCgBI(data)) decodeCgBI(data) else BitmapFactory.decodeByteArray(data, 0, data.size)

    private fun isCgBI(d: ByteArray) =
        d.size > 16 && String(d, 12, 4, Charsets.US_ASCII) == "CgBI"

    /**
     * Apple's "CgBI" PNG variant (pngcrush -iphone): raw deflate without zlib
     * header, BGRA byte order, premultiplied alpha. Only 8-bit RGB(A).
     */
    fun decodeCgBI(data: ByteArray): Bitmap? {
        val buf = ByteBuffer.wrap(data)
        if (data.size < 8 || buf.getLong(0) != -0x76afb1b8f2f5e5f6L) return null // PNG signature
        var pos = 8
        var width = 0
        var height = 0
        var colorType = -1
        var isCgBI = false
        val idat = ByteArrayOutputStream()
        while (pos + 8 <= data.size) {
            val len = buf.getInt(pos)
            val type = String(data, pos + 4, 4, Charsets.US_ASCII)
            val body = pos + 8
            if (len < 0 || body + len > data.size) return null
            when (type) {
                "CgBI" -> isCgBI = true
                "IHDR" -> {
                    width = buf.getInt(body); height = buf.getInt(body + 4)
                    if (data[body + 8].toInt() != 8) return null
                    colorType = data[body + 9].toInt()
                }
                "IDAT" -> idat.write(data, body, len)
                "IEND" -> break
            }
            pos = body + len + 4
        }
        val bpp = when (colorType) { 6 -> 4; 2 -> 3; else -> return null }
        if (!isCgBI || width <= 0 || height <= 0 || width > 4096 || height > 4096) return null

        val stride = width * bpp
        val raw = ByteArray((stride + 1) * height)
        val inflater = Inflater(true)
        try {
            // nowrap mode needs one extra dummy byte to emit the final output.
            inflater.setInput(idat.toByteArray() + 0)
            var n = 0
            while (n < raw.size && !inflater.finished()) {
                val r = inflater.inflate(raw, n, raw.size - n)
                if (r == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                n += r
            }
            if (n < raw.size) return null
        } finally {
            inflater.end()
        }

        val px = ByteArray(stride * height)
        for (y in 0 until height) {
            val filter = raw[y * (stride + 1)].toInt()
            val src = y * (stride + 1) + 1
            val dst = y * stride
            for (x in 0 until stride) {
                val a = if (x >= bpp) px[dst + x - bpp].toInt() and 0xff else 0
                val b = if (y > 0) px[dst - stride + x].toInt() and 0xff else 0
                val c = if (x >= bpp && y > 0) px[dst - stride + x - bpp].toInt() and 0xff else 0
                val v = raw[src + x].toInt() and 0xff
                px[dst + x] = (when (filter) {
                    0 -> v
                    1 -> v + a
                    2 -> v + b
                    3 -> v + (a + b) / 2
                    4 -> v + paeth(a, b, c)
                    else -> return null
                }).toByte()
            }
        }
        val colors = IntArray(width * height) { i ->
            val o = i * bpp
            val bl = px[o].toInt() and 0xff
            val g = px[o + 1].toInt() and 0xff
            val r = px[o + 2].toInt() and 0xff
            val al = if (bpp == 4) px[o + 3].toInt() and 0xff else 255
            fun un(c: Int) = if (al == 0) 0 else minOf(255, c * 255 / al)
            (al shl 24) or (un(r) shl 16) or (un(g) shl 8) or un(bl)
        }
        return Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = Math.abs(p - a); val pb = Math.abs(p - b); val pc = Math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    /** String values of the top-level dict of an XML or binary plist. */
    fun parsePlistStrings(data: ByteArray): Map<String, String> {
        if (data.size > 8 && String(data, 0, 6, Charsets.US_ASCII) == "bplist") {
            return try { parseBinaryPlist(data) } catch (e: Exception) { emptyMap() }
        }
        val xml = String(data, Charsets.UTF_8)
        return Regex("<key>([^<]+)</key>\\s*<string>([^<]*)</string>").findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun parseBinaryPlist(d: ByteArray): Map<String, String> {
        val b = ByteBuffer.wrap(d)
        val t = d.size - 32
        val offSize = d[t + 6].toInt()
        val refSize = d[t + 7].toInt()
        val top = b.getLong(t + 16).toInt()
        val tableOff = b.getLong(t + 24).toInt()
        fun uint(pos: Int, size: Int): Int {
            var v = 0
            for (i in 0 until size) v = (v shl 8) or (d[pos + i].toInt() and 0xff)
            return v
        }
        fun offset(obj: Int) = uint(tableOff + obj * offSize, offSize)
        // Returns (length, position of payload) for a marker at `pos`.
        fun lenAt(pos: Int): Pair<Int, Int> {
            val low = d[pos].toInt() and 0x0f
            if (low != 0x0f) return low to pos + 1
            val intSize = 1 shl (d[pos + 1].toInt() and 0x0f)
            return uint(pos + 2, intSize) to pos + 2 + intSize
        }
        fun string(obj: Int): String? {
            val pos = offset(obj)
            val (len, p) = lenAt(pos)
            return when (d[pos].toInt() and 0xf0) {
                0x50 -> String(d, p, len, Charsets.US_ASCII)
                0x60 -> String(d, p, len * 2, Charsets.UTF_16BE)
                else -> null
            }
        }
        val pos = offset(top)
        if (d[pos].toInt() and 0xf0 != 0xd0) return emptyMap()
        val (count, p) = lenAt(pos)
        val out = HashMap<String, String>()
        for (i in 0 until count) {
            val k = string(uint(p + i * refSize, refSize)) ?: continue
            val v = string(uint(p + (count + i) * refSize, refSize)) ?: continue
            out[k] = v
        }
        return out
    }
}
