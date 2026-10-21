package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Copies the pictures of a folder (a flash drive, say) into the launcher's own wallpapers/ collection, so
 * the drive can be unplugged afterwards. Naming, selection and the replace-or-add bookkeeping are plain
 * Java and unit tested; the bitmap work sits behind {@link Encoder}.
 */
final class WallpaperImport {
    /** The most pictures the collection holds, the same limit WallpaperRotation.images reads. */
    static final int MAX_IMAGES = 100;
    private static final int JPEG_QUALITY = 85;
    private static final int MAX_NAME = 80;

    private WallpaperImport() {}

    /** Turns one source picture into a JPEG at {@code to}; false when it is unreadable or too large. */
    interface Encoder {
        boolean encode(File from, File to);
    }

    interface Progress {
        void update(int done, int total);
    }

    static final class Result {
        final int imported;
        final int skipped;

        Result(int imported, int skipped) {
            this.imported = imported;
            this.skipped = skipped;
        }
    }

    /**
     * A name ThemeFiles accepts ({@code [A-Za-z0-9_.-]+}) ending in .jpg, from a picture's file name: other
     * characters (spaces, Thai letters) become underscores, and a name already in {@code taken} gets -2,
     * -3, ... The returned name is added to {@code taken} (compared in lower case).
     */
    static String safeName(String sourceName, Set<String> taken) {
        String base = sourceName == null ? "" : sourceName;
        int dot = base.lastIndexOf('.');
        if (dot >= 0) base = base.substring(0, dot);
        base = base.replaceAll("[^A-Za-z0-9_.-]+", "_").replaceAll("^[._-]+", "");
        if (base.length() > MAX_NAME) base = base.substring(0, MAX_NAME);
        if (base.isEmpty()) base = "image";
        String name = base + ".jpg";
        for (int n = 2; !taken.add(name.toLowerCase(Locale.ROOT)); n++) name = base + "-" + n + ".jpg";
        return name;
    }

    /** The pictures to import: all of them when replacing, otherwise as many as still fit under the limit. */
    static List<File> select(List<File> sources, int existing, boolean replace) {
        int room = Math.max(0, MAX_IMAGES - (replace ? 0 : existing));
        return new ArrayList<>(sources.subList(0, Math.min(sources.size(), room)));
    }

    /**
     * Imports the pictures of {@code source} into {@code target} (the wallpapers/ folder). With
     * {@code replace} the earlier pictures are deleted, but only once at least one new one is in.
     * Runs on a worker thread; {@code progress} may be null.
     */
    static Result importInto(File source, File target, boolean replace, Encoder encoder, Progress progress) {
        List<File> found = WallpaperRotation.images(source);
        List<File> existing = WallpaperRotation.images(target);
        List<File> chosen = select(found, existing.size(), replace);
        int skipped = found.size() - chosen.size();
        if (!target.isDirectory() && !target.mkdirs()) return new Result(0, found.size());

        Set<String> taken = new HashSet<>();
        if (!replace) for (File file : existing) taken.add(file.getName().toLowerCase(Locale.ROOT));
        Set<String> written = new HashSet<>();
        int imported = 0;
        for (int i = 0; i < chosen.size(); i++) {
            if (progress != null) progress.update(i, chosen.size());
            File temp = new File(target, ".import-" + i + ".tmp");
            File dest = new File(target, safeName(chosen.get(i).getName(), taken));
            if (encoder.encode(chosen.get(i), temp) && temp.renameTo(dest)) {
                imported++;
                written.add(dest.getName());
            } else {
                temp.delete();
                skipped++;
            }
        }
        if (replace && imported > 0) {
            File[] old = target.listFiles();
            if (old != null) for (File file : old) {
                if (ThemeFiles.collectionImage(file.getName()) && !written.contains(file.getName())) file.delete();
            }
        }
        return new Result(imported, skipped);
    }

    /** Whether an EXIF orientation turns the picture a quarter turn, so its width and height trade places. */
    static boolean swapsDimensions(int orientation) {
        return orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270
                || orientation == ExifInterface.ORIENTATION_TRANSPOSE || orientation == ExifInterface.ORIENTATION_TRANSVERSE;
    }

    /** The EXIF orientation of a picture; a file without one, or an unreadable tag, counts as upright. */
    private static int orientation(File file) {
        try {
            return new ExifInterface(file.getPath()).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException | RuntimeException failure) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    /** The matrix that stands a picture upright, or null when it already is. */
    private static Matrix upright(int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_180: matrix.postRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.postScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: matrix.postRotate(90); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_90: matrix.postRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: matrix.postRotate(270); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_270: matrix.postRotate(270); break;
            default: return null;
        }
        return matrix;
    }

    /**
     * Decodes like WallpaperPlayer (sampled, then scaled so the side that limits the cover lands on the
     * screen size) and writes a JPEG, upright by its EXIF orientation; the file is written in full or not at all.
     */
    static Encoder bitmapEncoder(int screenW, int screenH) {
        return (from, to) -> {
            Bitmap bitmap = null;
            try {
                int turn = orientation(from);
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(from.getPath(), options);
                // Sizes are measured as the picture will look once upright.
                boolean swap = swapsDimensions(turn);
                int width = swap ? options.outHeight : options.outWidth;
                int height = swap ? options.outWidth : options.outHeight;
                if (width <= 0 || height <= 0) return false;
                int sample = WallpaperSize.sampleSize(width, height, screenW, screenH);
                if (!WallpaperSize.accepted(width / sample, height / sample)) return false;
                options.inJustDecodeBounds = false;
                options.inSampleSize = sample;
                int[] scale = WallpaperSize.coverScale(width / sample, height / sample, screenW, screenH);
                if (scale != null) {
                    options.inScaled = true;
                    options.inDensity = scale[0];
                    options.inTargetDensity = scale[1];
                }
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                bitmap = BitmapFactory.decodeFile(from.getPath(), options);
                if (bitmap == null) return false;
                Matrix matrix = upright(turn);
                if (matrix != null) {
                    Bitmap turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                    if (turned != bitmap) bitmap.recycle();
                    bitmap = turned;
                }
                try (FileOutputStream out = new FileOutputStream(to)) {
                    return bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
                }
            } catch (IOException | RuntimeException | OutOfMemoryError failure) {
                to.delete();
                return false;
            } finally {
                if (bitmap != null) bitmap.recycle();
            }
        };
    }
}
