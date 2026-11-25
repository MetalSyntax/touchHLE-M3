/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Parts of this file are derived from SDL 2's Android project template, which
 * has a different license. Please see vendor/SDL/LICENSE.txt for details.
 */
package org.touchhle.android;

import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A wrapper class over SDLActivity. Runs in its own process (":emu") so the
 * launcher survives crashes and a fresh process is used for every game.
 * Adds the on-screen FPS counter, screen filters and a Delta-style pause menu.
 */
public class MainActivity extends SDLActivity {
    public static final String EXTRA_ARGS = "org.touchhle.android.ARGS";
    public static final String EXTRA_SHOW_FPS = "org.touchhle.android.SHOW_FPS";
    public static final String EXTRA_OVERLAY = "org.touchhle.android.OVERLAY";
    public static final String EXTRA_EXIT_BUTTON = "org.touchhle.android.EXIT_BUTTON";
    public static final String EXTRA_GAME_FILE = "org.touchhle.android.GAME_FILE";
    public static final String EXTRA_GAME_NAME = "org.touchhle.android.GAME_NAME";
    public static final String EXTRA_BUNDLE_ID = "org.touchhle.android.BUNDLE_ID";
    public static final String EXTRA_ICON_PATH = "org.touchhle.android.ICON_PATH";

    private static final String[] FILTERS = {"none", "scanlines", "lcd", "crt"};

    /** Last FPS measured by the core (needs --print-fps). See src/lib.rs. */
    private static native float nativeGetFps();
    /** Freezes/unfreezes the emulated app. See src/lib.rs. */
    private static native void nativeSetPaused(boolean paused);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private TextView fpsView;
    private FilterOverlayView filterView;
    private View pauseMenu;
    private TextView pauseStatus;
    private GameSettings settings;

    @Override
    protected String[] getLibraries() {
        return new String[]{
            "SDL2",
            "touchHLE"
        };
    }

    @Override
    protected String[] getArguments() {
        String[] args = getIntent().getStringArrayExtra(EXTRA_ARGS);
        return args != null ? args : new String[0];
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (mLayout == null || mBrokenLibraries) {
            return;
        }
        String game = getIntent().getStringExtra(EXTRA_GAME_FILE);
        settings = game != null ? new GameSettings(this, game) : null;

        filterView = new FilterOverlayView(this, orDefault(getIntent().getStringExtra(EXTRA_OVERLAY), "none"));
        mLayout.addView(filterView, new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        fpsView = new TextView(this);
        fpsView.setTextColor(Color.WHITE);
        fpsView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        fpsView.setTextSize(13);
        fpsView.setBackgroundColor(Color.argb(140, 0, 0, 0));
        fpsView.setPadding((int) dp(8), (int) dp(3), (int) dp(8), (int) dp(3));
        fpsView.setGravity(Gravity.CENTER);
        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        lp.addRule(RelativeLayout.ALIGN_PARENT_START);
        lp.setMargins((int) dp(12), (int) dp(12), 0, 0);
        mLayout.addView(fpsView, lp);
        setFpsVisible(getIntent().getBooleanExtra(EXTRA_SHOW_FPS, false));

        if (getIntent().getBooleanExtra(EXTRA_EXIT_BUTTON, true)) {
            ImageButton menu = new ImageButton(this);
            menu.setImageResource(R.drawable.ic_pause);
            menu.setContentDescription(getString(R.string.pause_title));
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(Color.argb(110, 0, 0, 0));
            menu.setBackground(circle);
            menu.setAlpha(0.6f);
            int pad = (int) dp(8);
            menu.setPadding(pad, pad, pad, pad);
            RelativeLayout.LayoutParams bp = new RelativeLayout.LayoutParams((int) dp(40), (int) dp(40));
            bp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
            bp.addRule(RelativeLayout.ALIGN_PARENT_END);
            bp.setMargins(0, (int) dp(12), (int) dp(12), 0);
            menu.setOnClickListener(v -> showPauseMenu());
            mLayout.addView(menu, bp);
        }
    }

    private static String orDefault(String s, String def) {
        return s != null ? s : def;
    }

    // --- FPS counter ---

    private void setFpsVisible(boolean visible) {
        fpsView.setVisibility(visible ? View.VISIBLE : View.GONE);
        handler.removeCallbacks(updateFps);
        if (visible) handler.post(updateFps);
    }

    private final Runnable updateFps = new Runnable() {
        @Override
        public void run() {
            float fps = nativeGetFps();
            fpsView.setText(fps > 0 ? String.format(Locale.ROOT, "%.0f FPS", fps) : "-- FPS");
            handler.postDelayed(this, 500);
        }
    };

    // --- Pause menu ---

    private void showPauseMenu() {
        if (pauseMenu != null) return;
        nativeSetPaused(true);
        // SDLActivity uses a non-Material theme; the menu needs the app's theme.
        LayoutInflater inflater = LayoutInflater.from(new ContextThemeWrapper(this, R.style.Theme_TouchHLE));
        pauseMenu = inflater.inflate(R.layout.pause_menu, mLayout, false);
        pauseMenu.setOnClickListener(v -> hidePauseMenu()); // tap outside the card resumes
        ((TextView) pauseMenu.findViewById(R.id.pause_title))
            .setText(orDefault(getIntent().getStringExtra(EXTRA_GAME_NAME), getString(R.string.app_name)));
        ImageView icon = pauseMenu.findViewById(R.id.pause_icon);
        String iconPath = getIntent().getStringExtra(EXTRA_ICON_PATH);
        Bitmap bmp = iconPath != null ? BitmapFactory.decodeFile(iconPath) : null;
        if (bmp != null) icon.setImageBitmap(bmp); else icon.setVisibility(View.GONE);
        pauseStatus = pauseMenu.findViewById(R.id.pause_status);
        pauseStatus.setText(R.string.pause_back_hint);

        GridLayout grid = pauseMenu.findViewById(R.id.pause_grid);
        addTile(inflater, grid, R.drawable.ic_play, getString(R.string.pause_resume), v -> hidePauseMenu());
        addTile(inflater, grid, R.drawable.ic_camera, getString(R.string.pause_screenshot), v -> takeScreenshot());
        String bundleId = getIntent().getStringExtra(EXTRA_BUNDLE_ID);
        if (bundleId != null) {
            addTile(inflater, grid, R.drawable.ic_save, getString(R.string.pause_save_copy), v -> saveCopy(bundleId));
        }
        addTile(inflater, grid, R.drawable.ic_speed, fpsLabel(), v -> {
            boolean show = fpsView.getVisibility() != View.VISIBLE;
            setFpsVisible(show);
            if (settings != null) settings.setShowFps(show);
            ((MaterialButton) v).setText(fpsLabel());
        });
        addTile(inflater, grid, R.drawable.ic_filter, filterLabel(), v -> {
            int i = java.util.Arrays.asList(FILTERS).indexOf(filterView.getMode());
            String next = FILTERS[(i + 1) % FILTERS.length];
            filterView.setMode(next);
            if (settings != null) settings.setOverlay(next);
            ((MaterialButton) v).setText(filterLabel());
        });
        addTile(inflater, grid, R.drawable.ic_exit, getString(R.string.exit_button), v -> exitToLibrary());

        mLayout.addView(pauseMenu, new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private String fpsLabel() {
        return getString(fpsView.getVisibility() == View.VISIBLE ? R.string.pause_fps_on : R.string.pause_fps_off);
    }

    private String filterLabel() {
        int id;
        switch (filterView.getMode()) {
            case "scanlines": id = R.string.filter_scanlines; break;
            case "lcd": id = R.string.filter_lcd; break;
            case "crt": id = R.string.filter_crt; break;
            default: id = R.string.filter_none;
        }
        return getString(R.string.pause_filter, getString(id));
    }

    private void addTile(LayoutInflater inflater, GridLayout grid, int icon, String label, View.OnClickListener l) {
        MaterialButton b = (MaterialButton) inflater.inflate(R.layout.pause_tile, grid, false);
        b.setIconResource(icon);
        b.setText(label);
        b.setOnClickListener(l);
        grid.addView(b);
    }

    private void hidePauseMenu() {
        if (pauseMenu == null) return;
        mLayout.removeView(pauseMenu);
        pauseMenu = null;
        nativeSetPaused(false);
    }

    private void setStatus(int resId, Object... args) {
        if (pauseStatus != null) pauseStatus.setText(getString(resId, args));
    }

    // --- Screenshot ---

    private void takeScreenshot() {
        if (Build.VERSION.SDK_INT < 24 || mSurface == null || mSurface.getWidth() == 0) {
            setStatus(R.string.screenshot_failed);
            return;
        }
        // Only the game surface is copied: no menu, no filter overlay.
        Bitmap bmp = Bitmap.createBitmap(mSurface.getWidth(), mSurface.getHeight(), Bitmap.Config.ARGB_8888);
        HandlerThread t = new HandlerThread("screenshot");
        t.start();
        PixelCopy.request(mSurface, bmp, result -> {
            t.quitSafely();
            if (result != PixelCopy.SUCCESS) {
                handler.post(() -> setStatus(R.string.screenshot_failed));
                return;
            }
            io.execute(() -> {
                String where = saveScreenshot(bmp);
                handler.post(() -> {
                    if (where != null) setStatus(R.string.screenshot_saved, where);
                    else setStatus(R.string.screenshot_failed);
                });
            });
        }, new Handler(t.getLooper()));
    }

    /** Saves to Pictures/touchHLE; returns a user-readable location or null. */
    private String saveScreenshot(Bitmap bmp) {
        String game = orDefault(getIntent().getStringExtra(EXTRA_GAME_NAME), "touchHLE")
            .replaceAll("[^A-Za-z0-9 _-]", "").trim();
        String name = game + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date()) + ".png";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/touchHLE");
                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return null;
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null || !bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) return null;
                }
                return Environment.DIRECTORY_PICTURES + "/touchHLE";
            } else {
                // No storage permission needed for the app's own folder.
                File dir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "touchHLE");
                if (!dir.isDirectory() && !dir.mkdirs()) return null;
                try (OutputStream out = new FileOutputStream(new File(dir, name))) {
                    if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) return null;
                }
                return dir.getPath();
            }
        } catch (Exception e) {
            return null;
        }
    }

    // --- Save data copy ---

    private void saveCopy(String bundleId) {
        setStatus(R.string.backup_working);
        File base = getExternalFilesDir(null);
        io.execute(() -> {
            boolean ok;
            try {
                // Emulation is paused, so the game isn't writing its files now.
                new SaveBackups(base != null ? base : getFilesDir(), bundleId).create(SaveBackups.Kind.GENERAL);
                ok = true;
            } catch (Exception e) {
                ok = false;
            }
            boolean done = ok;
            handler.post(() -> setStatus(done ? R.string.backup_done : R.string.backup_failed));
        });
    }

    // --- Leaving ---

    private void exitToLibrary() {
        nativeSetPaused(false);
        // This activity lives in its own task, so bring the library back
        // explicitly instead of landing on the home screen.
        startActivity(new Intent(this, LauncherActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        // Same path as SDL's back button: SDL sends quit, touchHLE runs the
        // app's termination handlers, then onDestroy ends this process.
        finish();
    }

    private long lastBack;

    /** Back opens the pause menu; back again while it's open returns to the library. */
    private void onBack() {
        long now = SystemClock.uptimeMillis();
        // SDL can deliver one press twice (Java key path + native manualBackButton).
        if (now - lastBack < 300) return;
        lastBack = now;
        if (pauseMenu == null) showPauseMenu(); else exitToLibrary();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // SDL would otherwise consume the back key, forward it to the app, and
        // later quit immediately via superOnBackPressed().
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) onBack();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        onBack();
    }

    @Override
    public void superOnBackPressed() {
        // Called by SDL's native side (SDL_AndroidBackButton).
        onBack();
    }

    @Override
    protected void onPause() {
        // touchHLE quits when backgrounded; it can't do that while frozen.
        if (!mBrokenLibraries) nativeSetPaused(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        io.shutdown();
        super.onDestroy();
        // SDL and the emulator core can't be safely re-initialised in-process.
        if (isFinishing()) {
            System.exit(0);
        }
    }
}
