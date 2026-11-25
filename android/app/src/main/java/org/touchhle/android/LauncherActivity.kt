/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.touchhle.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/** Material 3 front-end: lists apps in touchHLE_apps and launches the emulator. */
class LauncherActivity : AppCompatActivity() {
    companion object {
        /** Intent extra (from app shortcuts): file name in touchHLE_apps to launch. */
        const val EXTRA_LAUNCH = "org.touchhle.android.LAUNCH"
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var baseDir: File
    private lateinit var appsDir: File
    private lateinit var logFile: File
    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private lateinit var progress: LinearProgressIndicator
    private lateinit var fab: ExtendedFloatingActionButton
    private var apps: List<File> = emptyList()
    /** Name/icon read from each IPA (loaded in the background, cached on disk). */
    private val infos = HashMap<String, IpaInfo.Info>()
    private val prefs by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }
    /** "list" or "grid" */
    private var viewMode: String
        get() = prefs.getString("view", "list")!!
        set(v) = prefs.edit().putString("view", v).apply()
    /** "name", "name_desc", "played", "added", "size" */
    private var sortMode: String
        get() = prefs.getString("sort", "name")!!
        set(v) = prefs.edit().putString("sort", v).apply()

    private val importPicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) importApps(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)
        setSupportActionBar(findViewById(R.id.toolbar))

        // Same directory the Rust core uses (SDL_AndroidGetExternalStoragePath).
        baseDir = getExternalFilesDir(null) ?: filesDir
        appsDir = File(baseDir, "touchHLE_apps")
        logFile = File(baseDir, "touchHLE_log.txt")

        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        progress = findViewById(R.id.progress)
        fab = findViewById(R.id.fab_import)
        list.adapter = Adapter()
        applyViewMode()
        fab.setOnClickListener { importPicker.launch(arrayOf("*/*")) }
        findViewById<View>(R.id.empty_import).setOnClickListener { fab.performClick() }
        if (savedInstanceState == null) main.post { handleIntent(intent) }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0) fab.shrink() else if (dy < 0) fab.extend()
            }
        })
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyViewMode() // grid column count depends on screen width
    }

    override fun onResume() {
        super.onResume()
        refresh()
        checkLastRun()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.launcher, menu)
        updateViewMenuItem(menu.findItem(R.id.action_view))
        return true
    }

    private fun updateViewMenuItem(item: android.view.MenuItem?) {
        // The icon shows what tapping switches *to*.
        val grid = viewMode == "grid"
        item?.setIcon(if (grid) R.drawable.ic_view_list else R.drawable.ic_view_grid)
        item?.setTitle(if (grid) R.string.action_view_list else R.string.action_view_grid)
    }

    private fun applyViewMode() {
        list.layoutManager = if (viewMode == "grid") {
            val widthDp = resources.configuration.screenWidthDp
            GridLayoutManager(this, maxOf(3, widthDp / 110))
        } else {
            LinearLayoutManager(this)
        }
        list.adapter?.notifyDataSetChanged()
    }

    private fun chooseSort() {
        val modes = listOf("name", "name_desc", "played", "added", "size")
        val labels = arrayOf(R.string.sort_name, R.string.sort_name_desc, R.string.sort_played,
            R.string.sort_added, R.string.sort_size).map { getString(it) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_sort)
            .setSingleChoiceItems(labels, modes.indexOf(sortMode).coerceAtLeast(0)) { d, which ->
                sortMode = modes[which]
                refresh()
                d.dismiss()
            }
            .show()
    }

    private fun lastPlayed(f: File) = prefs.getLong("played:${f.name}", 0)

    private fun showAbout() {
        val view = layoutInflater.inflate(R.layout.dialog_about, null)
        view.findViewById<TextView>(R.id.about_version).text =
            getString(R.string.about_version, BuildConfig.VERSION_NAME)
        view.findViewById<TextView>(R.id.about_folder).text = getString(R.string.about_folder, appsDir.path)
        fun link(id: Int, url: String) = view.findViewById<View>(id).setOnClickListener {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { }
        }
        link(R.id.about_link_touchhle, "https://touchhle.org/")
        link(R.id.about_link_source, "https://github.com/touchHLE/touchHLE")
        link(R.id.about_link_mod, "https://github.com/MetalSyntax")
        MaterialAlertDialogBuilder(this)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_log -> showLog()
            R.id.action_picker -> launch(null)
            R.id.action_about -> showAbout()
            R.id.action_sort -> chooseSort()
            R.id.action_view -> {
                viewMode = if (viewMode == "grid") "list" else "grid"
                updateViewMenuItem(item)
                applyViewMode()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun refresh() {
        appsDir.mkdirs()
        apps = (appsDir.listFiles() ?: emptyArray())
            .filter { it.name.endsWith(".ipa", true) || (it.isDirectory && it.name.endsWith(".app", true)) }
            .let { files ->
                val byName = files.sortedBy { displayName(it).lowercase() }
                when (sortMode) {
                    "name_desc" -> byName.reversed()
                    "played" -> byName.sortedByDescending { lastPlayed(it) }
                    "added" -> byName.sortedByDescending { it.lastModified() }
                    "size" -> byName.sortedByDescending { if (it.isDirectory) 0 else it.length() }
                    else -> byName
                }
            }
        list.adapter?.notifyDataSetChanged()
        empty.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
    }

    // --- Launching & error reporting ---

    private fun launch(app: File?) {
        if (app != null) {
            val problem = validate(app)
            if (problem != null) {
                error(getString(R.string.err_cant_open_title), problem)
                return
            }
        }
        getPreferences(MODE_PRIVATE).edit()
            .putLong("launched_at", System.currentTimeMillis())
            .putString("launched_name", app?.let { displayName(it) })
            .apply()
        // SDL prepends argv[0] itself.
        val intent = Intent(this, MainActivity::class.java)
        if (app == null) {
            startActivity(intent)
            return
        }
        prefs.edit().putLong("played:${app.name}", System.currentTimeMillis()).apply()
        val settings = GameSettings(this, app.name)
        intent.putExtra(MainActivity.EXTRA_ARGS, (listOf(app.absolutePath) + settings.toArgs()).toTypedArray())
            .putExtra(MainActivity.EXTRA_SHOW_FPS, settings.showFps)
            .putExtra(MainActivity.EXTRA_OVERLAY, settings.overlay)
            .putExtra(MainActivity.EXTRA_EXIT_BUTTON, settings.exitButton)
            .putExtra(MainActivity.EXTRA_GAME_FILE, app.name)
            .putExtra(MainActivity.EXTRA_GAME_NAME, displayName(app))
        progress.visibility = View.VISIBLE
        io.execute {
            // Usually cached already; needed for the bundle id (save data folder).
            val info = if (app.isDirectory) null else IpaInfo.load(app, cacheDir)
            info?.bundleId?.let { id ->
                // Automatic copy of the save data before every launch (Delta's
                // backup save states): a bad session can always be undone.
                try {
                    val b = SaveBackups(baseDir, id)
                    if (b.hasSaveData()) b.create(SaveBackups.Kind.AUTO)
                } catch (e: Exception) { /* never block playing */ }
                intent.putExtra(MainActivity.EXTRA_BUNDLE_ID, id)
            }
            info?.iconPath?.let { intent.putExtra(MainActivity.EXTRA_ICON_PATH, it) }
            main.post {
                progress.visibility = View.GONE
                pushRecentShortcut(app, info)
                startActivity(intent)
            }
        }
    }

    /** Long-press shortcuts on the app icon for recently played games (like Delta). */
    private fun pushRecentShortcut(app: File, info: IpaInfo.Info?) {
        try {
            val icon = info?.icon?.let { androidx.core.graphics.drawable.IconCompat.createWithAdaptiveBitmap(padForAdaptive(it)) }
                ?: androidx.core.graphics.drawable.IconCompat.createWithResource(this, R.drawable.icon)
            val shortcut = androidx.core.content.pm.ShortcutInfoCompat.Builder(this, "game:${app.name}")
                .setShortLabel(displayName(app).take(25))
                .setIcon(icon)
                .setIntent(Intent(this, LauncherActivity::class.java)
                    .setAction(Intent.ACTION_MAIN)
                    .putExtra(EXTRA_LAUNCH, app.name))
                .build()
            androidx.core.content.pm.ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)
        } catch (e: Exception) { /* shortcuts are a nicety */ }
    }

    /** Adaptive icons crop the outer third; add a margin so artwork isn't cut. */
    private fun padForAdaptive(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val size = 216
        val out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        c.drawColor(android.graphics.Color.BLACK)
        val inset = 36f
        c.drawBitmap(src, null, android.graphics.RectF(inset, inset, size - inset, size - inset),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Launch requests from shortcuts, and .ipa files opened from other apps. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_LAUNCH)?.let { name ->
            intent.removeExtra(EXTRA_LAUNCH)
            val f = File(appsDir, name)
            if (f.exists()) launch(f) else error(getString(R.string.err_cant_open_title), getString(R.string.err_missing))
            return
        }
        if (intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_SEND) {
            val uri = intent.data ?: @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            intent.action = Intent.ACTION_MAIN // don't import again on rotation/resume
            uri?.let { importApps(listOf(it)) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private var pendingExport: File? = null
    private val exportPicker =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            val src = pendingExport ?: return@registerForActivityResult
            pendingExport = null
            uri ?: return@registerForActivityResult
            io.execute {
                val ok = try {
                    contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } } != null
                } catch (e: IOException) { false }
                main.post {
                    Snackbar.make(list, if (ok) R.string.backup_exported else R.string.backup_failed, Snackbar.LENGTH_SHORT)
                        .setAnchorView(fab).show()
                }
            }
        }

    private fun showBackups(app: File) {
        io.execute {
            val info = IpaInfo.load(app, cacheDir)
            main.post {
                val id = info.bundleId
                if (id == null) {
                    error(getString(R.string.action_backups), getString(R.string.backups_no_id))
                } else {
                    BackupsSheet.show(this, baseDir, id, displayName(app), io) { file ->
                        pendingExport = file
                        exportPicker.launch("${fileName(app)}_${file.name}")
                    }
                }
            }
        }
    }

    /** Cheap pre-flight check so a broken file gives a clear message, not a silent exit. */
    private fun validate(app: File): String? {
        if (!app.exists()) return getString(R.string.err_missing)
        if (app.isDirectory) return null
        return try {
            ZipFile(app).use { zip ->
                val hasApp = zip.entries().asSequence().any {
                    it.name.startsWith("Payload/") && it.name.contains(".app/")
                }
                if (hasApp) null else getString(R.string.err_no_payload)
            }
        } catch (e: IOException) {
            getString(R.string.err_bad_zip, e.message ?: "")
        }
    }

    /** After the emulator process exits, turn a panic in the log into a readable dialog. */
    private fun checkLastRun() {
        val prefs = getPreferences(MODE_PRIVATE)
        val launchedAt = prefs.getLong("launched_at", 0)
        if (launchedAt == 0L) return
        prefs.edit().remove("launched_at").apply()
        if (!logFile.exists() || logFile.lastModified() < launchedAt) return
        val log = try { logFile.readText() } catch (e: IOException) { return }
        val line = log.lineSequence().firstOrNull {
            it.startsWith("Panic at") || it.startsWith("touchHLE errored")
        } ?: return
        val name = prefs.getString("launched_name", null) ?: BuildConfig.APP_NAME
        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_error)
            .setTitle(getString(R.string.err_crash_title, name))
            .setMessage(explain(line) + "\n\n" + line.take(400))
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.action_share_log) { _, _ -> shareLog() }
            .setNegativeButton(R.string.action_copy) { _, _ -> copy(line) }
            .show()
    }

    private fun explain(line: String): String = when {
        "does not respond to selector" in line -> getString(R.string.why_selector)
        "Unknown function" in line || "unimplemented" in line.lowercase() || "not yet implemented" in line ->
            getString(R.string.why_unimplemented)
        "memory" in line.lowercase() -> getString(R.string.why_memory)
        "No such file" in line || "Could not open" in line -> getString(R.string.why_file)
        else -> getString(R.string.why_generic)
    }

    private fun showLog() {
        val text = try { logFile.readText().takeLast(20000) } catch (e: IOException) { getString(R.string.log_empty) }
        val view = layoutInflater.inflate(R.layout.dialog_log, null)
        view.findViewById<TextView>(R.id.log_text).text = text.ifBlank { getString(R.string.log_empty) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_log)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.action_share_log) { _, _ -> shareLog() }
            .show()
    }

    private fun shareLog() {
        val text = try { logFile.readText().takeLast(100000) } catch (e: IOException) { "" }
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
            getString(R.string.action_share_log)))
    }

    private fun copy(text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("touchHLE", text))
        Snackbar.make(list, R.string.copied, Snackbar.LENGTH_SHORT).setAnchorView(fab).show()
    }

    private fun error(title: String, message: String) {
        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_error)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // --- Import / delete ---

    private fun importApps(uris: List<Uri>) {
        progress.visibility = View.VISIBLE
        fab.isEnabled = false
        io.execute {
            var ok = 0
            val failures = mutableListOf<String>()
            for (uri in uris) {
                val name = queryName(uri) ?: "app_${System.currentTimeMillis()}.ipa"
                val target = File(appsDir, name)
                val tmp = File(appsDir, "$name.part")
                try {
                    if (!name.endsWith(".ipa", true)) throw IOException(getString(R.string.err_not_ipa))
                    contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    } ?: throw IOException(getString(R.string.err_read))
                    validate(tmp)?.let { throw IOException(it) }
                    if (!tmp.renameTo(target)) throw IOException(getString(R.string.err_write))
                    ok++
                } catch (e: Exception) {
                    tmp.delete()
                    failures += "• $name: ${e.message}"
                }
            }
            main.post {
                progress.visibility = View.GONE
                fab.isEnabled = true
                refresh()
                if (failures.isEmpty()) {
                    Snackbar.make(list, resources.getQuantityString(R.plurals.imported, ok, ok), Snackbar.LENGTH_SHORT)
                        .setAnchorView(fab).show()
                } else {
                    error(getString(R.string.err_import_title), failures.joinToString("\n"))
                }
            }
        }
    }

    private fun queryName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.replace('/', '_') else null
        }

    private fun confirmDelete(app: File) {
        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_delete)
            .setTitle(getString(R.string.delete_title, displayName(app)))
            .setMessage(R.string.delete_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                if (app.deleteRecursively()) refresh() else error(getString(R.string.err_delete_title), app.path)
            }
            .show()
    }

    private fun displayName(f: File) = infos[f.name]?.name ?: fileName(f)

    private fun fileName(f: File) =
        f.name.removeSuffix(".ipa").removeSuffix(".IPA").removeSuffix(".app")
            .replace('_', ' ').replace(Regex("\\s+"), " ").trim()

    private fun loadInfo(app: File) {
        if (app.isDirectory || infos.containsKey(app.name)) return
        infos[app.name] = IpaInfo.Info(null, null) // placeholder: load once
        io.execute {
            val info = IpaInfo.load(app, cacheDir)
            main.post {
                infos[app.name] = info
                if (info.name != null && sortMode.startsWith("name")) {
                    main.removeCallbacks(resort); main.postDelayed(resort, 150)
                }
                apps.indexOf(app).takeIf { it >= 0 }?.let { list.adapter?.notifyItemChanged(it) }
            }
        }
    }

    private val resort = Runnable { refresh() }

    private fun showMenu(anchor: View, app: File) {
        PopupMenu(anchor.context, anchor).apply {
            menu.add(0, 1, 0, R.string.action_play)
            menu.add(0, 2, 1, R.string.action_settings)
            if (!app.isDirectory) menu.add(0, 4, 2, R.string.action_backups)
            menu.add(0, 3, 3, R.string.action_delete)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> launch(app)
                    4 -> showBackups(app)
                    2 -> SettingsSheet.show(this@LauncherActivity, app.name, displayName(app), infos[app.name]?.icon)
                    else -> confirmDelete(app)
                }
                true
            }
        }.show()
    }

    // --- List ---

    private inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: TextView = v.findViewById(R.id.avatar)
        val icon: android.widget.ImageView = v.findViewById(R.id.icon)
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
        val more: View = v.findViewById(R.id.more)
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = apps.size
        override fun getItemViewType(position: Int) = if (viewMode == "grid") 1 else 0
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(
            LayoutInflater.from(parent.context)
                .inflate(if (type == 1) R.layout.item_app_grid else R.layout.item_app, parent, false))

        override fun onBindViewHolder(h: Holder, pos: Int) {
            val app = apps[pos]
            loadInfo(app)
            val name = displayName(app)
            val iconBitmap = infos[app.name]?.icon
            h.title.text = name
            h.avatar.text = name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
            h.icon.setImageBitmap(iconBitmap)
            h.icon.visibility = if (iconBitmap != null) View.VISIBLE else View.GONE
            val size = if (app.isDirectory) getString(R.string.app_bundle)
                else Formatter.formatShortFileSize(this@LauncherActivity, app.length())
            // Show the file name when the real app name replaced it, so files stay identifiable.
            h.subtitle.text = if (name != fileName(app)) "$size · ${app.name}" else size
            h.itemView.setOnClickListener { launch(app) }
            h.itemView.setOnLongClickListener {
                SettingsSheet.show(this@LauncherActivity, app.name, name, iconBitmap); true
            }
            h.more.setOnClickListener { v -> showMenu(v, app) }
        }
    }
}
