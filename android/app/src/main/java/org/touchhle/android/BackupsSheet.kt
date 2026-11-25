/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.touchhle.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.Date
import java.util.concurrent.Executor

/** Lists a game's save-data copies (Delta's "save states" for this HLE). */
object BackupsSheet {
    fun show(
        context: Context, baseDir: File, bundleId: String, title: String,
        io: Executor, export: (File) -> Unit,
    ) {
        val backups = SaveBackups(baseDir, bundleId)
        val main = Handler(Looper.getMainLooper())
        val dialog = BottomSheetDialog(context)
        val inflater = LayoutInflater.from(context)
        val root = inflater.inflate(R.layout.sheet_backups, null)
        root.findViewById<TextView>(R.id.backups_game).text = title
        val listView = root.findViewById<LinearLayout>(R.id.backups_list)
        val status = root.findViewById<TextView>(R.id.backups_status)
        val create = root.findViewById<MaterialButton>(R.id.backups_create)

        fun kindLabel(k: SaveBackups.Kind) = context.getString(when (k) {
            SaveBackups.Kind.GENERAL -> R.string.backup_kind_general
            SaveBackups.Kind.LOCKED -> R.string.backup_kind_locked
            SaveBackups.Kind.AUTO -> R.string.backup_kind_auto
        })
        fun dateLabel(t: Long) =
            "${DateFormat.getMediumDateFormat(context).format(Date(t))} ${DateFormat.getTimeFormat(context).format(Date(t))}"

        lateinit var refresh: () -> Unit
        fun run(work: () -> Unit, okMsg: Int) {
            status.setText(R.string.backup_working)
            io.execute {
                val ok = try { work(); true } catch (e: Exception) { false }
                main.post {
                    status.setText(if (ok) okMsg else R.string.backup_failed)
                    refresh()
                }
            }
        }

        refresh = {
            listView.removeAllViews()
            val items = backups.list()
            root.findViewById<View>(R.id.backups_empty).visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            for (b in items) {
                val row = inflater.inflate(R.layout.item_backup, listView, false)
                row.findViewById<TextView>(R.id.backup_title).text = dateLabel(b.time)
                row.findViewById<TextView>(R.id.backup_subtitle).text =
                    "${kindLabel(b.kind)} · ${Formatter.formatShortFileSize(context, b.file.length())}"
                row.findViewById<View>(R.id.backup_lock_icon).visibility =
                    if (b.kind == SaveBackups.Kind.LOCKED) View.VISIBLE else View.GONE
                val restore = View.OnClickListener {
                    MaterialAlertDialogBuilder(context)
                        .setIcon(R.drawable.ic_restore)
                        .setTitle(R.string.backup_restore)
                        .setMessage(context.getString(R.string.backup_restore_confirm, dateLabel(b.time)))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.backup_restore) { _, _ -> run({ backups.restore(b) }, R.string.backup_restored) }
                        .show()
                }
                row.setOnClickListener(restore)
                row.findViewById<View>(R.id.backup_more).setOnClickListener { v ->
                    PopupMenu(v.context, v).apply {
                        val locked = b.kind == SaveBackups.Kind.LOCKED
                        menu.add(0, 1, 0, R.string.backup_restore)
                        menu.add(0, 2, 1, if (locked) R.string.backup_unlock else R.string.backup_lock)
                        menu.add(0, 3, 2, R.string.backup_export)
                        menu.add(0, 4, 3, R.string.backup_delete).isEnabled = !locked
                        setOnMenuItemClickListener {
                            when (it.itemId) {
                                1 -> restore.onClick(row)
                                2 -> { backups.setLocked(b, !locked); refresh() }
                                3 -> export(b.file)
                                4 -> MaterialAlertDialogBuilder(context)
                                    .setTitle(R.string.backup_delete)
                                    .setMessage(dateLabel(b.time))
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .setPositiveButton(R.string.backup_delete) { _, _ -> b.file.delete(); refresh() }
                                    .show()
                            }
                            true
                        }
                    }.show()
                }
                listView.addView(row)
            }
        }

        create.setOnClickListener {
            if (!backups.hasSaveData()) status.setText(R.string.backup_no_data)
            else run({ backups.create(SaveBackups.Kind.GENERAL) }, R.string.backup_done)
        }
        refresh()
        dialog.setContentView(root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }
}
