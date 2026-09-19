package com.brouken.player.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The parts of the home screen that can be checked without a device.
 *
 * <p>Sizes and orders, not layout. These are the failures that hide: a folder
 * that says "1.0 GB" where it means a thousand megabytes, or a sort that puts
 * "Ätherwelle" after "Zulu" because it compared characters rather than words.
 * Neither shows up in a screenshot, and neither is worth finding on a phone.
 */
public class HomeListTest {

    private static Library.Folder folder(final String name, final int count,
                                         final long size, final long modified) {
        final Library.Folder made = new Library.Folder(name, name);
        made.count = count;
        made.size = size;
        made.modified = modified;
        return made;
    }

    private static List<String> names(final List<Library.Folder> folders) {
        final List<String> names = new ArrayList<>();
        for (final Library.Folder folder : folders) {
            names.add(folder.name);
        }
        return names;
    }

    // ------------------------------------------------------------ sizes

    @Test
    public void bytesBelowAKilobyteAreJustBytes() {
        assertEquals("0 B", Readable.size(0));
        assertEquals("999 B", Readable.size(999));
        assertEquals("1023 B", Readable.size(1023));
    }

    @Test
    public void aKilobyteIsAKilobyteAndNotAThousandBytes() {
        assertEquals("1.0 KB", Readable.size(1024));
    }

    @Test
    public void tenAndAboveLoseTheDecimal() {
        // 9.5 KB keeps its digit, 10 KB does not: a column of sizes is easier
        // to scan when the wide numbers are short.
        assertTrue(Readable.size(9 * 1024 + 512).startsWith("9."));
        assertEquals("10 KB", Readable.size(10 * 1024));
    }

    @Test
    public void itClimbsThroughTheUnits() {
        assertEquals("1.0 MB", Readable.size(1024L * 1024));
        assertEquals("1.0 GB", Readable.size(1024L * 1024 * 1024));
        assertEquals("1.0 TB", Readable.size(1024L * 1024 * 1024 * 1024));
    }

    @Test
    public void itStopsAtTerabytesRatherThanInventingAUnit() {
        final String huge = Readable.size(5000L * 1024 * 1024 * 1024 * 1024);
        assertTrue(huge, huge.endsWith(" TB"));
    }

    @Test
    public void anImpossibleSizeSaysNothingRatherThanSomethingWrong() {
        assertEquals("", Readable.size(-1));
    }

    // ------------------------------------------------------------ order

    @Test
    public void foldersSortByNameIgnoringCase() {
        final List<Library.Folder> folders = new ArrayList<>();
        folders.add(folder("zebra", 1, 1, 1));
        folders.add(folder("Apple", 1, 1, 1));
        folders.add(folder("banana", 1, 1, 1));

        Sort.apply(folders, Sort.Folders.NAME);
        assertEquals(java.util.Arrays.asList("Apple", "banana", "zebra"), names(folders));
    }

    @Test
    public void theBiggestAndTheFullestComeFirst() {
        final List<Library.Folder> folders = new ArrayList<>();
        folders.add(folder("small", 1, 10, 1));
        folders.add(folder("big", 3, 1000, 2));
        folders.add(folder("middling", 2, 500, 3));

        Sort.apply(folders, Sort.Folders.SIZE);
        assertEquals(java.util.Arrays.asList("big", "middling", "small"), names(folders));

        Sort.apply(folders, Sort.Folders.COUNT);
        assertEquals(java.util.Arrays.asList("big", "middling", "small"), names(folders));

        Sort.apply(folders, Sort.Folders.RECENT);
        assertEquals(java.util.Arrays.asList("middling", "big", "small"), names(folders));
    }

    @Test
    public void equalEntriesFallBackToTheNameRatherThanShuffling() {
        // Every folder here is the same size, so only the tie-break decides the
        // order. Without one the list would reshuffle between two runs and look
        // as though something had changed.
        final List<Library.Folder> folders = new ArrayList<>();
        folders.add(folder("charlie", 1, 100, 5));
        folders.add(folder("alpha", 1, 100, 5));
        folders.add(folder("bravo", 1, 100, 5));

        Sort.apply(folders, Sort.Folders.SIZE);
        assertEquals(java.util.Arrays.asList("alpha", "bravo", "charlie"), names(folders));
    }

    @Test
    public void accentsFileWhereAReaderWouldLookForThem() {
        final List<Library.Folder> folders = new ArrayList<>();
        folders.add(folder("Zulu", 1, 1, 1));
        folders.add(folder("Ätherwelle", 1, 1, 1));
        folders.add(folder("Atlas", 1, 1, 1));

        Sort.apply(folders, Sort.Folders.NAME);
        final List<String> ordered = names(folders);
        assertTrue(ordered.toString(),
                ordered.indexOf("Ätherwelle") < ordered.indexOf("Zulu"));
    }

    @Test
    public void anEmptyListSortsWithoutComplaint() {
        Sort.apply(new ArrayList<Library.Folder>(), Sort.Folders.SIZE);
    }

    // ----------------------------------------------------------- locale

    @Test
    public void sizesDoNotDependOnTheDeviceLanguage() {
        // A decimal comma is correct in half of Europe; what matters is that it
        // is a number and a unit, not which separator the device chose.
        final Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            final String german = Readable.size(1536);
            assertTrue(german, german.endsWith(" KB"));
            assertTrue(german, german.startsWith("1"));
        } finally {
            Locale.setDefault(before);
        }
    }

    // ------------------------------------------------------- reversing

    @Test
    public void reversingNameGoesZToA() {
        final List<Library.Folder> folders = new ArrayList<>();
        folders.add(folder("Apple", 1, 1, 1));
        folders.add(folder("banana", 1, 1, 1));
        folders.add(folder("zebra", 1, 1, 1));

        Sort.apply(folders, Sort.Folders.NAME, true);
        assertEquals(java.util.Arrays.asList("zebra", "banana", "Apple"), names(folders));
    }

    @Test
    public void reversingSizePutsTheSmallestFirst() {
        final List<Library.Folder> folders = new ArrayList<>();
        folders.add(folder("big", 3, 1000, 2));
        folders.add(folder("small", 1, 10, 1));
        folders.add(folder("middling", 2, 500, 3));

        Sort.apply(folders, Sort.Folders.SIZE, true);
        assertEquals(java.util.Arrays.asList("small", "middling", "big"), names(folders));
    }

    @Test
    public void reversingIsExactlyTheSameListUpsideDown() {
        // Including where two entries tie: the name breaks the tie in the
        // direction asked for as well, so the reversed list really is the
        // other one read backwards and not a third order of its own.
        final List<Library.Folder> forwards = new ArrayList<>();
        forwards.add(folder("charlie", 1, 100, 5));
        forwards.add(folder("alpha", 1, 100, 5));
        forwards.add(folder("bravo", 1, 100, 5));
        Sort.apply(forwards, Sort.Folders.SIZE, false);

        final List<Library.Folder> backwards = new ArrayList<>(forwards);
        Sort.apply(backwards, Sort.Folders.SIZE, true);

        final List<String> expected = new ArrayList<>(names(forwards));
        java.util.Collections.reverse(expected);
        assertEquals(expected, names(backwards));
    }

    @Test
    public void notReversingIsWhatItAlwaysWas() {
        // The two-argument form is the one the rest of the app used before a
        // direction existed, and it has to keep meaning the same thing.
        final List<Library.Folder> one = new ArrayList<>();
        one.add(folder("big", 3, 1000, 2));
        one.add(folder("small", 1, 10, 1));
        Sort.apply(one, Sort.Folders.SIZE);

        final List<Library.Folder> two = new ArrayList<>();
        two.add(folder("big", 3, 1000, 2));
        two.add(folder("small", 1, 10, 1));
        Sort.apply(two, Sort.Folders.SIZE, false);

        assertEquals(names(one), names(two));
    }

    @Test
    public void everyOrderNamesBothOfItsDirections() {
        // A direction row that said "A to Z" under Size would be worse than no
        // direction at all, so every order has to carry its own two labels and
        // they have to differ from each other.
        for (final Sort.Folders order : Sort.Folders.values()) {
            assertTrue(order.name(), order.first != 0);
            assertTrue(order.name(), order.reversedFirst != 0);
            assertTrue(order.name(), order.first != order.reversedFirst);
        }
        for (final Sort.Videos order : Sort.Videos.values()) {
            assertTrue(order.name(), order.first != 0);
            assertTrue(order.name(), order.reversedFirst != 0);
            assertTrue(order.name(), order.first != order.reversedFirst);
        }
    }

    // ------------------------------------------- telling folders apart

    private static Library.Folder at(final String path) {
        final String name = path.substring(path.lastIndexOf('/') + 1);
        return new Library.Folder(path, name);
    }

    private static String nameOf(final List<Library.Folder> folders, final String path) {
        for (final Library.Folder folder : folders) {
            if (folder.id.equals(path)) {
                return folder.name;
            }
        }
        return null;
    }

    @Test
    public void foldersWithUniqueNamesAreLeftAlone() {
        // Most libraries have no clashes at all, and turning every row into a
        // path for the sake of the ones that do would be the wrong trade.
        final List<Library.Folder> folders = new ArrayList<>();
        folders.add(at("/storage/emulated/0/DCIM/Camera"));
        folders.add(at("/storage/emulated/0/Movies"));

        Library.disambiguate(folders);
        assertEquals("Camera", nameOf(folders, "/storage/emulated/0/DCIM/Camera"));
        assertEquals("Movies", nameOf(folders, "/storage/emulated/0/Movies"));
    }

    @Test
    public void clashingFoldersGetTheLeastThatTellsThemApart() {
        // The real shape from a phone with two WhatsApp accounts: every copy
        // sits under a folder called "Media", so naming the parent tells you
        // nothing. What differs is the account number, one level further up.
        final String base = "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp";
        final List<Library.Folder> folders = new ArrayList<>();
        folders.add(at(base + "/accounts/1001/Media/WhatsApp Video"));
        folders.add(at(base + "/accounts/1003/Media/WhatsApp Video"));

        Library.disambiguate(folders);
        assertEquals("WhatsApp Video  ·  1001",
                nameOf(folders, base + "/accounts/1001/Media/WhatsApp Video"));
        assertEquals("WhatsApp Video  ·  1003",
                nameOf(folders, base + "/accounts/1003/Media/WhatsApp Video"));
    }

    @Test
    public void oneLevelIsNotEnoughWhenTwoOfThemShareIt() {
        // /WhatsApp/Media/X against /Android/media/com.whatsapp/WhatsApp/Media/X.
        // Both read "WhatsApp" one level past the common "Media", so the deeper
        // one has to go further before it says anything useful.
        final List<Library.Folder> folders = new ArrayList<>();
        folders.add(at("/storage/emulated/0/WhatsApp/Media/WhatsApp Video"));
        folders.add(at("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video"));

        Library.disambiguate(folders);
        final String shallow = nameOf(folders, "/storage/emulated/0/WhatsApp/Media/WhatsApp Video");
        final String deep = nameOf(folders,
                "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video");
        assertEquals("WhatsApp Video  ·  WhatsApp", shallow);
        assertEquals("WhatsApp Video  ·  com.whatsapp/WhatsApp", deep);
    }

    @Test
    public void everyClashingFolderIsLabelled() {
        // Including the shallowest, which used to be left bare because its path
        // ran out before a unique slice was found -- and a row with no location
        // beside three that have one reads as a bug.
        final List<Library.Folder> folders = new ArrayList<>();
        folders.add(at("/storage/emulated/0/WhatsApp/Media/Gifs"));
        folders.add(at("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/Gifs"));
        folders.add(at("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/accounts/1001/Media/Gifs"));

        Library.disambiguate(folders);
        for (final Library.Folder folder : folders) {
            assertTrue(folder.id + " -> " + folder.name, folder.name.contains("·"));
        }
    }

    @Test
    public void theStorageRootNeverAppearsInALabel() {
        // Internal storage is /storage/emulated/0, and "0" is an Android user
        // id that means nothing to anybody reading a folder list.
        final List<Library.Folder> folders = new ArrayList<>();
        folders.add(at("/storage/emulated/0/Gifs"));
        folders.add(at("/storage/emulated/0/WhatsApp/Gifs"));

        Library.disambiguate(folders);
        for (final Library.Folder folder : folders) {
            assertFalse(folder.name, folder.name.contains("emulated"));
            assertFalse(folder.name, folder.name.contains("·  0"));
            assertFalse(folder.name, folder.name.endsWith("/0"));
        }
    }
}
