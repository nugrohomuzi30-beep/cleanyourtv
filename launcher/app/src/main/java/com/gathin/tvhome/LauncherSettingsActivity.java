package com.gathin.tvhome;

import android.app.Activity;
import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    private static final int STORAGE_REQUEST = 1;
    private static final int[] ROTATION_VALUES = {0, 1, 5, 15, 30, 60};
    private static final int[] DIM_VALUES = {0, 15, 30, 45, 60};
    private static final int[] SKY_VALUES = {0, 25, 50, 75, 100};
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private final TextView[] rotationChoices = new TextView[ROTATION_VALUES.length];
    private final TextView[] dimChoices = new TextView[DIM_VALUES.length];
    private final TextView[] skyChoices = new TextView[SKY_VALUES.length];
    private TextView rotationHint;
    private TextView dimHint;
    private TextView skyHint;
    private TextView wallpaperChoice;
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private int columns;
    private int rowsBelowDock;
    private boolean animations;
    private boolean advancedEffects;
    private int rotation;
    private int dim;
    private int sky;
    private List<File> storageRoots = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;
        rotation = config.wallpaperRotationMinutes;
        dim = config.wallpaperDim;
        sky = config.skyIntensity;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        // Keep the numeric choices increasing in the Hebrew reading direction. Reorder the
        // actual views so D-pad focus follows their positions without changing the saved values.
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
        }
        bindChoices(rotationChoices, new int[]{R.id.rotationOff, R.id.rotation1, R.id.rotation5,
                R.id.rotation15, R.id.rotation30, R.id.rotation60}, ROTATION_VALUES,
                Config.WALLPAPER_ROTATION_KEY, () -> rotation, value -> rotation = value);
        bindChoices(dimChoices, new int[]{R.id.dim0, R.id.dim15, R.id.dim30, R.id.dim45, R.id.dim60},
                DIM_VALUES, Config.WALLPAPER_DIM_KEY, () -> dim, value -> dim = value);
        bindChoices(skyChoices, new int[]{R.id.sky0, R.id.sky25, R.id.sky50, R.id.sky75, R.id.sky100},
                SKY_VALUES, Config.SKY_INTENSITY_KEY, () -> sky, value -> sky = value);
        rotationHint = findViewById(R.id.rotationHint);
        dimHint = findViewById(R.id.dimHint);
        skyHint = findViewById(R.id.skyHint);
        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        wallpaperChoice.setOnClickListener(v -> showWallpaperPicker());
        findViewById(R.id.wallpaperImportChoice).setOnClickListener(v -> requestImport());
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            setResult(RESULT_OK);
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                    .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY)
                    .remove(Config.WALLPAPER_ROTATION_KEY).remove(Config.WALLPAPER_DIM_KEY)
                    .remove(Config.SKY_INTENSITY_KEY).commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            rotation = current.wallpaperRotationMinutes;
            dim = current.wallpaperDim;
            sky = current.skyIntensity;
            setResult(RESULT_OK);
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(new Intent(Settings.ACTION_SETTINGS)));
        refresh();
        columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    /** One row of numeric chips saved under {@code key}; like the columns row, in the reading direction. */
    private void bindChoices(TextView[] views, int[] ids, int[] values, String key,
                             IntSupplier current, IntConsumer set) {
        for (int i = 0; i < ids.length; i++) {
            final int value = values[i];
            views[i] = findViewById(ids[i]);
            views[i].setOnClickListener(v -> {
                if (current.getAsInt() == value) return;
                set.accept(value);
                choices.edit().putInt(key, value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        if (getResources().getBoolean(R.bool.rtl_app_grid)) reverseRow(views);
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    private void showWallpaperPicker() {
        File dir = Config.dir(this);
        List<File> images = new java.util.ArrayList<>(WallpaperRotation.images(
                dir == null ? null : new File(dir, "wallpapers")));
        // A theme with a single wallpaper.jpg offers that picture too (Config maps the choice back to it).
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);
        String[] labels = new String[images.size() + 1];
        for (int i = 0; i < images.size(); i++) labels[i] = images.get(i).getName();
        labels[images.size()] = getString(R.string.wallpaper_none);
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        int checked = "none".equals(current) ? images.size()
                : indexOf(images, current);
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == images.size()) {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
                    } else {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY,
                                images.get(which).getName()).apply();
                    }
                    setResult(RESULT_OK);
                    Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNeutralButton(R.string.wallpaper_theme_default, (dialog, which) -> {
                    choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
                    setResult(RESULT_OK);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static int indexOf(List<File> images, String name) {
        if (name == null) return -1;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    private void requestImport() {
        String permission = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            showStorages();
        } else {
            requestPermissions(new String[]{permission}, STORAGE_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != STORAGE_REQUEST) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            showStorages();
        } else {
            Toast.makeText(this, R.string.storage_permission_denied, Toast.LENGTH_LONG).show();
        }
    }

    /** Top of the folder browser: the TV's own storage and every mounted drive (/storage/XXXX-XXXX). */
    private void showStorages() {
        storageRoots = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        File internal = Environment.getExternalStorageDirectory();
        storageRoots.add(internal);
        labels.add(getString(R.string.import_internal_storage) + "  " + internal.getPath());
        File[] volumes = new File("/storage").listFiles();
        if (volumes != null) {
            Arrays.sort(volumes, Comparator.comparing(File::getName));
            StorageManager manager = getSystemService(StorageManager.class);
            for (File volume : volumes) {
                String name = volume.getName();
                if (name.equals("emulated") || name.equals("self") || !volume.isDirectory()
                        || !volume.canRead()) continue;
                StorageVolume info = manager == null ? null : manager.getStorageVolume(volume);
                String described = info == null ? null : info.getDescription(this);
                storageRoots.add(volume);
                labels.add((described != null ? described : getString(R.string.import_usb_storage))
                        + "  " + volume.getPath());
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.import_choose_folder)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> showFolder(storageRoots.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showFolder(File dir) {
        int count = WallpaperRotation.images(dir).size();
        File[] listed = dir.listFiles(f -> f.isDirectory() && !f.getName().startsWith("."));
        List<File> folders = new ArrayList<>();
        if (listed != null) folders.addAll(Arrays.asList(listed));
        folders.sort(Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        List<String> labels = new ArrayList<>();
        labels.add(getString(R.string.import_use_folder, count));
        labels.add(getString(R.string.import_up));
        for (File folder : folders) labels.add(folder.getName());
        new AlertDialog.Builder(this)
                .setTitle(dir.getPath())
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (which == 0) {
                        confirmImport(dir, count);
                    } else if (which == 1) {
                        File parent = dir.getParentFile();
                        if (storageRoots.contains(dir) || parent == null) showStorages();
                        else showFolder(parent);
                    } else {
                        showFolder(folders.get(which - 2));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmImport(File source, int count) {
        File dir = Config.dir(this);
        if (dir == null) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
            return;
        }
        if (count == 0) {
            Toast.makeText(this, R.string.import_no_images, Toast.LENGTH_SHORT).show();
            showFolder(source);
            return;
        }
        File target = new File(dir, ThemeFiles.WALLPAPERS);
        AlertDialog confirm = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.import_confirm_title, count))
                .setMessage(getString(R.string.import_confirm_message, WallpaperRotation.images(target).size()))
                .setPositiveButton(R.string.import_replace, (d, which) -> startImport(source, target, count, true))
                .setNeutralButton(R.string.import_add, (d, which) -> startImport(source, target, count, false))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        // One press of OK must not wipe the collection: start on Add, not on Replace.
        confirm.getButton(AlertDialog.BUTTON_NEUTRAL).requestFocus();
    }

    /** Copies on a worker thread behind a dialog with no buttons: decoding a camera photo takes a moment. */
    private void startImport(File source, File target, int count, boolean replace) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle(R.string.import_progress_title)
                .setMessage(getString(R.string.import_progress, 0, count))
                .setCancelable(false)
                .show();
        new Thread(() -> {
            WallpaperImport.Result result = WallpaperImport.importInto(source, target, replace,
                    WallpaperImport.bitmapEncoder(metrics.widthPixels, metrics.heightPixels),
                    (done, total) -> runOnUiThread(() ->
                            progress.setMessage(getString(R.string.import_progress, done + 1, total))));
            runOnUiThread(() -> finishImport(progress, result));
        }, "wallpaper-import").start();
    }

    private void finishImport(AlertDialog progress, WallpaperImport.Result result) {
        if (!isDestroyed()) progress.dismiss();
        if (result.imported == 0) {
            Toast.makeText(this, R.string.import_failed, Toast.LENGTH_LONG).show();
            return;
        }
        // The new set must show: drop a single-picture pick, turn the rotation on if nothing did, and
        // let the player choose a fresh picture.
        SharedPreferences.Editor edit = choices.edit().remove(Config.WALLPAPER_CHOICE_KEY);
        if (rotation == 0) {
            rotation = 15;
            edit.putInt(Config.WALLPAPER_ROTATION_KEY, rotation);
        }
        edit.apply();
        getSharedPreferences("wallpaper_rotation", MODE_PRIVATE).edit().clear().apply();
        setResult(RESULT_OK);
        Toast.makeText(this, result.skipped == 0
                ? getString(R.string.import_done, result.imported)
                : getString(R.string.import_done_skipped, result.imported, result.skipped),
                Toast.LENGTH_LONG).show();
        refresh();
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        setResult(RESULT_OK);
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        for (int i = 0; i < rowChoices.length; i++) {
            rowChoices[i].setSelected(rowsBelowDock == i);
        }
        showChoice(rotationChoices, ROTATION_VALUES, rotation, rotationHint, null);
        showChoice(dimChoices, DIM_VALUES, dim, dimHint, null);
        showChoice(skyChoices, SKY_VALUES, sky, skyHint, getString(R.string.sky_hint));
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        wallpaperChoice.setText(getString(R.string.wallpaper_state,
                "none".equals(wallpaperCurrent) ? getString(R.string.wallpaper_none)
                        : wallpaperCurrent != null ? wallpaperCurrent
                        : getString(R.string.wallpaper_theme_default)));
        followThemeChoice.setSelected(!hasOnTvChoices());
    }

    /** Highlights the chip holding {@code value}; a theme value between chips selects none and is named in the hint. */
    private void showChoice(TextView[] views, int[] values, int value, TextView hint, String normalHint) {
        boolean matched = false;
        for (int i = 0; i < views.length; i++) {
            boolean selected = values[i] == value;
            views[i].setSelected(selected);
            matched |= selected;
        }
        if (!matched) hint.setText(getString(R.string.theme_value_hint, value));
        else if (normalHint != null) hint.setText(normalHint);
        hint.setVisibility(matched && normalHint == null ? View.GONE : View.VISIBLE);
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY)
                || choices.contains(Config.WALLPAPER_ROTATION_KEY)
                || choices.contains(Config.WALLPAPER_DIM_KEY) || choices.contains(Config.SKY_INTENSITY_KEY);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
