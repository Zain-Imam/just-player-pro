package com.brouken.player;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// A screen's other layouts (on its side, on a tablet) must have every view its main one has:
// code finds views by id, and one missing in a single variant crashed Film info on tablets
public class LayoutVariantsTest {

    private static final Pattern ID = Pattern.compile("@\\+id/([A-Za-z0-9_]+)");

    @Test
    public void everyVariantHasTheMainLayoutsViews() throws IOException {
        final File res = new File("src/main/res");
        final File main = new File(res, "layout");
        assertTrue("run from the app module", main.isDirectory());
        final List<String> missing = new ArrayList<>();
        final File[] dirs = res.listFiles((dir, name) -> name.startsWith("layout-"));
        for (final File variant : dirs == null ? new File[0] : dirs) {
            final File[] files = variant.listFiles((dir, name) -> name.endsWith(".xml"));
            for (final File file : files == null ? new File[0] : files) {
                final File twin = new File(main, file.getName());
                if (!twin.isFile()) {
                    continue;
                }
                final Set<String> wanted = ids(twin);
                wanted.removeAll(ids(file));
                for (final String id : wanted) {
                    missing.add(variant.getName() + "/" + file.getName() + " lacks " + id);
                }
            }
        }
        assertTrue("views missing from a layout variant: " + missing, missing.isEmpty());
    }

    private static Set<String> ids(final File file) throws IOException {
        final Set<String> ids = new TreeSet<>();
        final Matcher matcher = ID.matcher(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }
}
