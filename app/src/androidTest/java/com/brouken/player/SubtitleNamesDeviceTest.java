package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

// Proves a re-encoded subtitle keeps its file name, not the media store row id.
// Saves a real Windows-1252 subtitle through MediaStore to get that kind of address.
@RunWith(AndroidJUnit4.class)
public class SubtitleNamesDeviceTest {

    private static final String NAME = "Café crème.en.srt";
    private Uri inserted;

    private Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private Uri insertWindows1252Subtitle() throws Exception {
        final ContentResolver resolver = context().getContentResolver();
        final ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, NAME);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/x-subrip");
        final Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        assertNotNull("the media store took the file", uri);
        // é and è as single Windows-1252 bytes: not UTF-8.
        final String text = "1\r\n00:00:01,000 --> 00:00:02,000\r\nCafé crème brûlée\r\n\r\n"
                + "2\r\n00:00:03,000 --> 00:00:04,000\r\nDéjà vu, naïve façade\r\n";
        try (OutputStream out = resolver.openOutputStream(uri)) {
            out.write(text.getBytes("windows-1252"));
        }
        return uri;
    }

    @After
    public void tidy() {
        if (inserted != null) {
            context().getContentResolver().delete(inserted, null, null);
        }
    }

    @Test
    public void aConvertedCopyKeepsTheFilesNameAndExtension() throws Exception {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 29);
        inserted = insertWindows1252Subtitle();
        // a media store address ending in a row id
        assertTrue(inserted.getLastPathSegment().matches("[0-9]+"));

        final Uri converted;
        try (InputStream in = context().getContentResolver().openInputStream(inserted)) {
            converted = Utils.convertInputStreamToUTF(context(), inserted, in);
        }
        assertNotNull(converted);
        assertEquals("file", converted.getScheme());
        final File file = new File(converted.getPath());
        assertEquals(NAME, file.getName());
        assertTrue(file.exists());

        final byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            assertEquals(bytes.length, in.read(bytes));
        }
        assertTrue("re-encoded as UTF-8",
                new String(bytes, StandardCharsets.UTF_8).contains("Café crème"));
    }

    @Test
    public void theNameShownIsTheFilesOwnOnBothRoutes() throws Exception {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 29);
        inserted = insertWindows1252Subtitle();
        assertEquals("Café crème.en", SubtitleNames.label(context(), inserted, null, null));

        final Uri converted;
        try (InputStream in = context().getContentResolver().openInputStream(inserted)) {
            converted = Utils.convertInputStreamToUTF(context(), inserted, in);
        }
        assertEquals("Café crème.en", SubtitleNames.label(context(), converted, null, null));
        // A name something gave it still wins.
        assertEquals("English (Addon)",
                SubtitleNames.label(context(), converted, "English (Addon)", "en"));
    }

    @Test
    public void aRowIdIsNeverTheNameWhenTheLanguageIsKnown() {
        final Uri id = Uri.parse("content://media/external/downloads/1000012345");
        final String label = SubtitleNames.label(context(), id, null, "en");
        assertFalse(label.matches("[0-9]+"));
    }

    @Test
    public void aCopyIntoTheAppKeepsTheName() throws Exception {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 29);
        inserted = insertWindows1252Subtitle();
        final Uri copy = SubtitleFiles.copy(context(), inserted, null);
        assertNotNull(copy);
        assertEquals(NAME, new File(copy.getPath()).getName());
        assertTrue(SubtitleFiles.isOwnCopy(context(), copy));
        // Copying an own copy again hands back the same file.
        assertEquals(copy, SubtitleFiles.copy(context(), copy, null));
    }
}
