package com.brouken.player.mpv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class FontFamilyNameTest {

    private static final class NameRecord {
        final int platform, encoding, language, nameId;
        final byte[] text;

        NameRecord(int platform, int encoding, int language, int nameId, String text) {
            this.platform = platform;
            this.encoding = encoding;
            this.language = language;
            this.nameId = nameId;
            this.text = text.getBytes(platform == 1
                    ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_16BE);
        }
    }

    private static byte[] nameTable(List<NameRecord> records) {
        final ByteBuffer strings = ByteBuffer.allocate(4096);
        final ByteBuffer b = ByteBuffer.allocate(6 + records.size() * 12 + 4096);
        b.putShort((short) 0).putShort((short) records.size())
                .putShort((short) (6 + records.size() * 12));
        for (NameRecord r : records) {
            b.putShort((short) r.platform).putShort((short) r.encoding)
                    .putShort((short) r.language).putShort((short) r.nameId)
                    .putShort((short) r.text.length).putShort((short) strings.position());
            strings.put(r.text);
        }
        b.put(strings.array(), 0, strings.position());
        final byte[] out = new byte[b.position()];
        System.arraycopy(b.array(), 0, out, 0, out.length);
        return out;
    }

    // a font with a dummy 'head' table and the name table, starting at fontStart
    private static byte[] font(byte[] name, int fontStart) {
        final int directory = 12 + 2 * 16;
        final byte[] head = new byte[54];
        final ByteBuffer b = ByteBuffer.allocate(directory + head.length + name.length);
        b.putInt(0x00010000).putShort((short) 2).putShort((short) 32)
                .putShort((short) 1).putShort((short) 0);
        b.putInt(0x68656164).putInt(0).putInt(fontStart + directory).putInt(head.length);
        b.putInt(0x6E616D65).putInt(0).putInt(fontStart + directory + head.length)
                .putInt(name.length);
        b.put(head).put(name);
        return b.array();
    }

    private static String read(byte[] bytes) {
        return FontFamilyName.read(ByteBuffer.wrap(bytes));
    }

    @Test
    public void prefersWindowsUsEnglish() {
        final List<NameRecord> records = new ArrayList<>();
        records.add(new NameRecord(1, 0, 0, 1, "Mac Name"));
        records.add(new NameRecord(3, 1, 0x0407, 1, "Deutscher Name"));
        records.add(new NameRecord(3, 1, 0x0409, 2, "Bold"));
        records.add(new NameRecord(3, 1, 0x0409, 1, "Test Sans"));
        assertEquals("Test Sans", read(font(nameTable(records), 0)));
    }

    @Test
    public void anyWindowsLanguageBeatsMac() {
        final List<NameRecord> records = new ArrayList<>();
        records.add(new NameRecord(1, 0, 0, 1, "Mac Name"));
        records.add(new NameRecord(3, 1, 0x0411, 1, "源ノ角ゴシック"));
        assertEquals("源ノ角ゴシック", read(font(nameTable(records), 0)));
    }

    @Test
    public void macRomanWhenThatIsAllThereIs() {
        final List<NameRecord> records = new ArrayList<>();
        records.add(new NameRecord(1, 0, 0, 4, "Old Face Regular"));
        records.add(new NameRecord(1, 0, 0, 1, "Old Face"));
        assertEquals("Old Face", read(font(nameTable(records), 0)));
    }

    @Test
    public void firstFontOfACollection() {
        final List<NameRecord> records = new ArrayList<>();
        records.add(new NameRecord(3, 1, 0x0409, 1, "Collected"));
        final int fontStart = 16;
        final byte[] first = font(nameTable(records), fontStart);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteBuffer header = ByteBuffer.allocate(fontStart);
        header.putInt(0x74746366).putInt(0x00010000).putInt(1).putInt(fontStart);
        out.write(header.array(), 0, fontStart);
        out.write(first, 0, first.length);
        assertEquals("Collected", read(out.toByteArray()));
    }

    @Test
    public void noFamilyNameOrNotAFont() {
        final List<NameRecord> records = new ArrayList<>();
        records.add(new NameRecord(3, 1, 0x0409, 4, "Full Name Only"));
        assertNull(read(font(nameTable(records), 0)));
        assertNull(read("this is not a font".getBytes(StandardCharsets.US_ASCII)));
        assertNull(read(new byte[0]));
    }
}
