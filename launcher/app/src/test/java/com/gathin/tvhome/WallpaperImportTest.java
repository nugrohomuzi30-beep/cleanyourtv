package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class WallpaperImportTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    /** Copies the bytes; a file named bad* fails like an unreadable picture. */
    private static final WallpaperImport.Encoder COPY = (from, to) -> {
        if (from.getName().startsWith("bad")) return false;
        try {
            Files.copy(from.toPath(), to.toPath());
            return true;
        } catch (IOException e) {
            return false;
        }
    };

    private static File picture(File dir, String name) throws IOException {
        File f = new File(dir, name);
        Files.write(f.toPath(), new byte[]{1, 2, 3});
        return f;
    }

    @Test
    public void safeNameKeepsAllowedCharactersAndEndsInJpg() {
        assertEquals("dusk-1_a.jpg", WallpaperImport.safeName("dusk-1_a.PNG", new HashSet<>()));
    }

    @Test
    public void safeNameReplacesSpacesAndThaiLetters() {
        assertEquals("my_photo.jpg", WallpaperImport.safeName("my photo.jpeg", new HashSet<>()));
        assertEquals("image.jpg", WallpaperImport.safeName("ภาพ.jpg", new HashSet<>()));
        String name = WallpaperImport.safeName("วันหยุด 2024.jpg", new HashSet<>());
        assertTrue(ThemeFiles.collectionImage(name));
    }

    @Test
    public void safeNameNeverStartsWithADotOrIsEmpty() {
        assertEquals("hidden.jpg", WallpaperImport.safeName(".hidden.jpg", new HashSet<>()));
        assertEquals("image.jpg", WallpaperImport.safeName(".jpg", new HashSet<>()));
        assertEquals("image.jpg", WallpaperImport.safeName(null, new HashSet<>()));
    }

    @Test
    public void safeNameDedupesWithCounter() {
        Set<String> taken = new HashSet<>();
        assertEquals("a.jpg", WallpaperImport.safeName("a.jpg", taken));
        assertEquals("a-2.jpg", WallpaperImport.safeName("a.png", taken));
        assertEquals("A-3.jpg", WallpaperImport.safeName("A.webp", taken));
    }

    @Test
    public void safeNameLimitsLength() {
        String name = WallpaperImport.safeName(new String(new char[300]).replace('\0', 'x') + ".jpg", new HashSet<>());
        assertTrue(name.length() <= 84);
    }

    @Test
    public void quarterTurnOrientationsSwapDimensions() {
        for (int orientation : new int[]{5, 6, 7, 8}) assertTrue(WallpaperImport.swapsDimensions(orientation));
        for (int orientation : new int[]{0, 1, 2, 3, 4}) assertFalse(WallpaperImport.swapsDimensions(orientation));
    }

    @Test
    public void selectCapsAddAtTheRemainingRoom() {
        List<File> sources = new ArrayList<>();
        for (int i = 0; i < 40; i++) sources.add(new File("p" + i + ".jpg"));
        assertEquals(40, WallpaperImport.select(sources, 0, false).size());
        assertEquals(30, WallpaperImport.select(sources, 70, false).size());
        assertEquals(0, WallpaperImport.select(sources, 100, false).size());
        assertEquals(40, WallpaperImport.select(sources, 100, true).size());
    }

    @Test
    public void importCopiesWithSafeNamesAndCountsFailures() throws IOException {
        File source = temp.newFolder("usb");
        picture(source, "one two.jpg");
        picture(source, "one_two.png");
        picture(source, "bad.jpg");
        File target = new File(temp.getRoot(), "wallpapers");
        WallpaperImport.Result result = WallpaperImport.importInto(source, target, false, COPY, null);
        assertEquals(2, result.imported);
        assertEquals(1, result.skipped);
        assertTrue(new File(target, "one_two.jpg").isFile());
        assertTrue(new File(target, "one_two-2.jpg").isFile());
        for (File f : target.listFiles()) assertTrue(f.getName(), ThemeFiles.collectionImage(f.getName()));
    }

    @Test
    public void addKeepsAndDoesNotOverwriteTheOldPictures() throws IOException {
        File source = temp.newFolder("src");
        picture(source, "a.jpg");
        File target = temp.newFolder("wallpapers");
        Files.write(new File(target, "a.jpg").toPath(), new byte[]{9, 9, 9, 9});
        picture(target, "keep.jpg");
        WallpaperImport.Result result = WallpaperImport.importInto(source, target, false, COPY, null);
        assertEquals(1, result.imported);
        assertEquals(4, new File(target, "a.jpg").length());
        assertTrue(new File(target, "a-2.jpg").isFile());
        assertTrue(new File(target, "keep.jpg").isFile());
    }

    @Test
    public void replaceDeletesOldPicturesOnlyAfterASuccess() throws IOException {
        File source = temp.newFolder("src");
        picture(source, "new.jpg");
        File target = temp.newFolder("wallpapers");
        picture(target, "old1.jpg");
        picture(target, "old2.png");
        WallpaperImport.Result result = WallpaperImport.importInto(source, target, true, COPY, null);
        assertEquals(1, result.imported);
        assertFalse(new File(target, "old1.jpg").exists());
        assertFalse(new File(target, "old2.png").exists());
        assertTrue(new File(target, "new.jpg").isFile());
    }

    @Test
    public void replaceKeepsOldPicturesWhenNothingImports() throws IOException {
        File source = temp.newFolder("src");
        picture(source, "bad1.jpg");
        File target = temp.newFolder("wallpapers");
        picture(target, "old.jpg");
        WallpaperImport.Result result = WallpaperImport.importInto(source, target, true, COPY, null);
        assertEquals(0, result.imported);
        assertEquals(1, result.skipped);
        assertTrue(new File(target, "old.jpg").isFile());
        assertEquals(1, target.listFiles().length);
    }

    @Test
    public void replaceKeepsAnImportedPictureThatShareAnOldName() throws IOException {
        File source = temp.newFolder("src");
        picture(source, "same.jpg");
        File target = temp.newFolder("wallpapers");
        Files.write(new File(target, "same.jpg").toPath(), new byte[]{9, 9, 9, 9});
        WallpaperImport.importInto(source, target, true, COPY, null);
        assertEquals(3, new File(target, "same.jpg").length());
    }

    @Test
    public void totalNeverExceedsTheCap() throws IOException {
        File source = temp.newFolder("src");
        for (int i = 0; i < 30; i++) picture(source, String.format("n%02d.jpg", i));
        File target = temp.newFolder("wallpapers");
        for (int i = 0; i < 80; i++) picture(target, String.format("o%02d.jpg", i));
        WallpaperImport.Result result = WallpaperImport.importInto(source, target, false, COPY, null);
        assertEquals(20, result.imported);
        assertEquals(10, result.skipped);
        assertEquals(100, target.listFiles().length);
    }
}
