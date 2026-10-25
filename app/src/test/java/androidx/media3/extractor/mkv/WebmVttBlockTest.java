package androidx.media3.extractor.mkv;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

// WebM WebVTT blocks turned into the WebVTT sample Media3's parser reads.
public class WebmVttBlockTest {

    private static final String PREFIX = "WEBVTT\n\n00:00:00.000 --> 00:00:01.700\n";

    private static String convert(final String block) {
        final byte[] prefix = PREFIX.getBytes(StandardCharsets.UTF_8);
        final byte[] body = block.getBytes(StandardCharsets.UTF_8);
        // spare room after the block, like the extractor's reused buffer
        final byte[] data = new byte[prefix.length + body.length + 16];
        System.arraycopy(prefix, 0, data, 0, prefix.length);
        System.arraycopy(body, 0, data, prefix.length, body.length);
        final int limit = WebmVttBlock.toCue(data, prefix.length, prefix.length + body.length);
        return new String(data, 0, limit, StandardCharsets.UTF_8);
    }

    @Test
    public void ffmpegBlockWithNoIdOrSettings() {
        // the bytes FFmpeg 8.1 wrote into .smoke-media/v51/vtt-ffmpeg.mkv
        assertEquals("WEBVTT\n\n00:00:00.000 --> 00:00:01.700\nVTT line 1 <b>bold</b>",
                convert("\n\nVTT line 1 <b>bold</b>"));
    }

    @Test
    public void settingsMoveOntoTheTimingLine() {
        assertEquals("WEBVTT\n\n00:00:00.000 --> 00:00:01.700 align:start line:10%\nHello\nthere",
                convert("cue-7\nalign:start line:10%\nHello\nthere"));
    }

    @Test
    public void idIsDroppedWhenThereAreNoSettings() {
        assertEquals("WEBVTT\n\n00:00:00.000 --> 00:00:01.700\nHello",
                convert("intro\n\nHello"));
    }

    @Test
    public void windowsLineBreaksInTheHeader() {
        assertEquals("WEBVTT\n\n00:00:00.000 --> 00:00:01.700 position:20%\nHello",
                convert("a\r\nposition:20%\r\nHello"));
    }

    @Test
    public void emptyText() {
        assertEquals("WEBVTT\n\n00:00:00.000 --> 00:00:01.700\n", convert("\n\n"));
    }

    @Test
    public void blockWithoutTheTwoHeaderLinesIsLeftAlone() {
        assertEquals(PREFIX + "just text", convert("just text"));
        assertEquals(PREFIX + "id\ntext", convert("id\ntext"));
    }
}
