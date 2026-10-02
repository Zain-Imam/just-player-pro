package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

// Headers in every shape launching apps send: pairs, whole lines, lists, maps.
public class LaunchHeadersTest {

    private static Map<String, String> parse(final Object raw) {
        final Map<String, String> out = new LinkedHashMap<>();
        LaunchHeaders.parse(raw, out);
        return out;
    }

    @Test
    public void pairsAreReadAsNameThenValue() {
        final Map<String, String> headers = parse(new String[]{
                "Referer", "https://example.com/watch", "User-Agent", "Player/1.0"});
        assertEquals(2, headers.size());
        assertEquals("https://example.com/watch", headers.get("Referer"));
        assertEquals("Player/1.0", headers.get("User-Agent"));
    }

    @Test
    public void wholeHeadersAreSplitAtTheirColon() {
        final Map<String, String> headers = parse(new String[]{
                "Referer: https://example.com/watch", "User-Agent: Player/1.0"});
        assertEquals(2, headers.size());
        assertEquals("https://example.com/watch", headers.get("Referer"));
        assertEquals("Player/1.0", headers.get("User-Agent"));
    }

    @Test
    public void aSingleWholeHeaderIsKept() {
        final Map<String, String> headers = parse(new String[]{"Cookie: a=b"});
        assertEquals("a=b", headers.get("Cookie"));
    }

    @Test
    public void aColonInAValueDoesNotSwitchShape() {
        // Values hold URLs; only a name with a colon means whole headers.
        final Map<String, String> headers = parse(new String[]{"Origin", "https://a.b:8443"});
        assertEquals("https://a.b:8443", headers.get("Origin"));
    }

    @Test
    public void aListAndAMapAndLinesAreReadToo() {
        assertEquals("x", parse(Arrays.asList("X-Token: x")).get("X-Token"));
        final Map<String, String> map = new HashMap<>();
        map.put("Authorization", "Bearer 1");
        assertEquals("Bearer 1", parse(map).get("Authorization"));
        assertEquals("y", parse("A: x\r\nB: y").get("B"));
    }

    @Test
    public void invalidNamesAreDroppedRatherThanSent() {
        final Map<String, String> headers = parse(new String[]{"Bad Name", "v", "Good", "w"});
        assertFalse(headers.containsKey("Bad Name"));
        assertEquals("w", headers.get("Good"));
    }

    @Test
    public void tokensFollowHttp() {
        assertTrue(LaunchHeaders.isToken("User-Agent"));
        assertTrue(LaunchHeaders.isToken("X_Custom.1"));
        assertFalse(LaunchHeaders.isToken("Referer: x"));
        assertFalse(LaunchHeaders.isToken(""));
    }
}
