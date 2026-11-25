/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.touchhle.android

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Copies of a game's save data (its touchHLE_sandbox/<bundle id> folder).
 *
 * This is the HLE counterpart of Delta's save states: a real snapshot of the
 * running emulator (CPU, memory, host objects, GL state) isn't feasible, but
 * the files the game saved can be backed up and restored. Stored as
 * touchHLE_backups/<bundle id>/<epoch millis>_<kind>.zip.
 */
class SaveBackups(baseDir: File, private val bundleId: String) {
    enum class Kind { GENERAL, LOCKED, AUTO }
    class Backup(val file: File, val time: Long, val kind: Kind)

    private val sandbox = File(baseDir, "touchHLE_sandbox/$bundleId")
    private val dir = File(baseDir, "touchHLE_backups/$bundleId")

    fun hasSaveData() = sandbox.walkTopDown().any { it.isFile }

    fun list(): List<Backup> = (dir.listFiles() ?: emptyArray()).mapNotNull { f ->
        val m = Regex("^(\\d+)_(general|locked|auto)\\.zip$").find(f.name) ?: return@mapNotNull null
        Backup(f, m.groupValues[1].toLong(), Kind.valueOf(m.groupValues[2].uppercase()))
    }.sortedByDescending { it.time }

    @Throws(IOException::class)
    fun create(kind: Kind): Backup {
        dir.mkdirs()
        val time = System.currentTimeMillis()
        val out = File(dir, "${time}_${kind.name.lowercase()}.zip")
        val tmp = File(dir, out.name + ".part")
        try {
            ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
                sandbox.walkTopDown().filter { it.isFile }.forEach { f ->
                    zip.putNextEntry(ZipEntry(f.relativeTo(sandbox).invariantSeparatorsPath))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            if (!tmp.renameTo(out)) throw IOException("rename failed")
        } finally {
            tmp.delete()
        }
        if (kind == Kind.AUTO) {
            // Keep only the newest automatic copies.
            list().filter { it.kind == Kind.AUTO }.drop(MAX_AUTO).forEach { it.file.delete() }
        }
        return Backup(out, time, kind)
    }

    /**
     * Replaces the save data with a backup. The current data is first kept as
     * an automatic copy, so a restore can itself be undone.
     */
    @Throws(IOException::class)
    fun restore(backup: Backup) {
        val staging = File(sandbox.parentFile, "$bundleId.restoring")
        staging.deleteRecursively()
        staging.mkdirs()
        ZipInputStream(backup.file.inputStream().buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val target = File(staging, e.name).canonicalFile
                // Zip-slip guard: never write outside the staging folder.
                if (!target.path.startsWith(staging.canonicalPath + File.separator)) {
                    throw IOException("Invalid entry ${e.name}")
                }
                if (e.isDirectory) { target.mkdirs(); continue }
                target.parentFile?.mkdirs()
                target.outputStream().use { zip.copyTo(it) }
            }
        }
        // Safety copy only after extracting: rotating automatic copies could
        // otherwise delete the very backup being restored.
        if (hasSaveData()) create(Kind.AUTO)
        sandbox.deleteRecursively()
        if (!staging.renameTo(sandbox)) throw IOException("rename failed")
    }

    fun setLocked(backup: Backup, locked: Boolean): Boolean {
        val kind = if (locked) "locked" else "general"
        return backup.file.renameTo(File(dir, "${backup.time}_$kind.zip"))
    }

    companion object {
        const val MAX_AUTO = 3
    }
}
