package com.brouken.player;

import static org.junit.Assert.assertEquals;

import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Proves every shape of launch subtitle extra is read in order without a crash.
// On a device: a real Bundle returns lists unchecked. Each goes through a Parcel.
@RunWith(AndroidJUnit4.class)
public class LaunchSubtitlesDeviceTest {

    private static final Uri ONE = Uri.parse("http://host/one.srt");
    private static final Uri TWO = Uri.parse("http://host/two.srt");

    private static Bundle overTheWire(final Bundle bundle) {
        final Parcel parcel = Parcel.obtain();
        try {
            bundle.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return Bundle.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }

    private static void assertBoth(final Bundle sent) {
        final Bundle bundle = overTheWire(sent);
        final List<Uri> expected = Arrays.asList(ONE, TWO);
        assertEquals(expected, LaunchSubtitles.uris(bundle, LaunchSubtitles.FILES));
        assertEquals(expected, LaunchSubtitles.urisByPosition(bundle, LaunchSubtitles.FILES));
    }

    @Test
    public void anArrayOfUris() {
        final Bundle bundle = new Bundle();
        bundle.putParcelableArray("subs", new Parcelable[]{ONE, TWO});
        assertBoth(bundle);
    }

    @Test
    public void aListOfUris() {
        final Bundle bundle = new Bundle();
        bundle.putParcelableArrayList("subs", new ArrayList<>(Arrays.asList(ONE, TWO)));
        assertBoth(bundle);
    }

    @Test
    public void anArrayOfStrings() {
        final Bundle bundle = new Bundle();
        bundle.putStringArray("subs", new String[]{ONE.toString(), TWO.toString()});
        assertBoth(bundle);
    }

    @Test
    public void aListOfStrings() {
        final Bundle bundle = new Bundle();
        bundle.putStringArrayList("subs",
                new ArrayList<>(Arrays.asList(ONE.toString(), TWO.toString())));
        assertBoth(bundle);
    }

    @Test
    public void namesInAListOfUrisAreNotReadAsStrings() {
        final Bundle bundle = new Bundle();
        bundle.putParcelableArrayList("subs.name", new ArrayList<>(Arrays.asList(ONE, TWO)));
        // no crash; only text counts as a name
        assertEquals(0, LaunchSubtitles.strings(overTheWire(bundle), LaunchSubtitles.NAMES).length);
    }
}
