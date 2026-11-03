package com.brouken.player.subtitle.parser;

import static org.junit.Assert.assertEquals;

import com.sigpwned.chardet4j.Chardet;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

// TubeMate saves YouTube captions (often machine translated) as .smi; Greek ones may not be UTF-8.
// The app turns a subtitle into UTF-8 with Chardet first (Utils.convertInputStreamToUTF), as here
public class SamiEncodingTest {

    private static final String[] GREEK = {
            "Καλημέρα σε όλους",
            "Σήμερα θα μιλήσουμε για την ιστορία",
            "Ο άνθρωπος και η φύση",
            "Ευχαριστώ πολύ που παρακολουθήσατε",
    };

    private static String sami(final String langClass, final String[] lines) {
        final StringBuilder out = new StringBuilder("<SAMI>\r\n<HEAD>\r\n<TITLE></TITLE>\r\n"
                + "<STYLE TYPE=\"text/css\">\r\n<!--\r\nP { text-align:center; }\r\n."
                + langClass + " { Name:Lang; SAMIType:CC; }\r\n-->\r\n</STYLE>\r\n</HEAD>\r\n<BODY>\r\n");
        for (int i = 0; i < lines.length; i++) {
            out.append("<SYNC Start=").append(1000 + i * 3000).append("><P Class=").append(langClass)
                    .append(">").append(lines[i]).append("\r\n");
            out.append("<SYNC Start=").append(3500 + i * 3000).append("><P Class=").append(langClass)
                    .append(">&nbsp;\r\n");
        }
        return out.append("</BODY>\r\n</SAMI>\r\n").toString();
    }

    private static List<TextCues.Line> throughApp(final byte[] file) throws Exception {
        final StringBuilder text = new StringBuilder();
        try (Reader reader = Chardet.decode(new ByteArrayInputStream(file), StandardCharsets.UTF_8)) {
            final char[] buffer = new char[512];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                text.append(buffer, 0, read);
            }
        }
        final byte[] utf8 = text.toString().getBytes(StandardCharsets.UTF_8);
        return SamiParser.parse(TextCues.decode(utf8, 0, utf8.length));
    }

    private static void assertGreek(final byte[] file) throws Exception {
        final List<TextCues.Line> lines = throughApp(file);
        assertEquals(GREEK.length, lines.size());
        for (int i = 0; i < GREEK.length; i++) {
            assertEquals(GREEK[i], lines.get(i).text);
        }
    }

    @Test
    public void greekInUtf8() throws Exception {
        assertGreek(sami("ELCC", GREEK).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void greekInWindows1253() throws Exception {
        assertGreek(sami("ELCC", GREEK).getBytes(Charset.forName("windows-1253")));
    }

    @Test
    public void greekInIso88597() throws Exception {
        assertGreek(sami("ELCC", GREEK).getBytes(Charset.forName("ISO-8859-7")));
    }

    @Test
    public void greekInUtf16WithBom() throws Exception {
        final byte[] body = sami("ELCC", GREEK).getBytes(StandardCharsets.UTF_16LE);
        final byte[] file = new byte[body.length + 2];
        file[0] = (byte) 0xFF;
        file[1] = (byte) 0xFE;
        System.arraycopy(body, 0, file, 2, body.length);
        assertGreek(file);
    }

    @Test
    public void englishWithAKoreanClassName() throws Exception {
        final String[] english = {"Good morning everyone", "Today we talk about history"};
        final List<TextCues.Line> lines = throughApp(sami("KRCC", english).getBytes(StandardCharsets.UTF_8));
        assertEquals(2, lines.size());
        assertEquals(english[1], lines.get(1).text);
    }
}
