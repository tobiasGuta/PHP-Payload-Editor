package dev.tobiasguta.phppayloadeditor;

import org.junit.jupiter.api.Test;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PhpPayloadLocatorTest {
    private static final String TYPE = "multipart/form-data; boundary=\"AaB03x\"";

    @Test void extractsPhpAndPreservesPolyglotBytes() throws Exception {
        String form = "--AaB03x\r\nContent-Disposition: form-data; name=\"logo\"; filename=\"version2.png.php\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\nGIF89a\n<style>body{font-size:0}</style>\n"
                + "<?php echo 'old'; ?>\n\r\n--AaB03x--\r\n";
        byte[] input = form.getBytes(StandardCharsets.UTF_8);
        var spans = PhpPayloadLocator.find(input, TYPE);
        assertEquals(1, spans.size());
        assertTrue(spans.get(0).description().contains("version2.png.php"));
        assertEquals("<?php echo 'old'; ?>", PhpPayloadLocator.text(input, spans.get(0)));
        String next = "<?php echo 'new and longer'; ?>";
        assertArrayEquals(form.replace("<?php echo 'old'; ?>", next).getBytes(StandardCharsets.UTF_8),
                PhpPayloadLocator.replace(input, spans, Map.of(0, next)));
    }

    @Test void editsSeveralMultipartSnippetsEvenWhenLengthsChange() throws Exception {
        String form = "--AaB03x\r\nContent-Disposition: form-data; name=\"a\"; filename=\"a.php\"\r\n\r\n"
                + "<?php echo 1; ?>\r\n--AaB03x\r\n"
                + "Content-Disposition: form-data; name=\"b\"; filename=\"b.php\"\r\n\r\n"
                + "<?php echo 2; ?>\r\n--AaB03x--\r\n";
        byte[] input = form.getBytes(StandardCharsets.UTF_8);
        var blocks = PhpPayloadLocator.find(input, TYPE);
        assertEquals(2, blocks.size());
        String output = new String(PhpPayloadLocator.replace(input, blocks, Map.of(
                0, "<?php echo 'much longer'; ?>", 1, "<?php echo 3; ?>")), StandardCharsets.UTF_8);
        assertTrue(output.contains("<?php echo 'much longer'; ?>"));
        assertTrue(output.contains("<?php echo 3; ?>"));
        assertTrue(output.contains("filename=\"a.php\""));
        assertTrue(output.endsWith("--AaB03x--\r\n"));
    }

    @Test void rawPhpWithoutClosingTagAndShortEchoTag() throws Exception {
        byte[] body = "prefix\n<?php echo 1;".getBytes(StandardCharsets.UTF_8);
        var spans = PhpPayloadLocator.find(body, "text/plain");
        assertEquals("<?php echo 1;", PhpPayloadLocator.text(body, spans.get(0)));
        assertArrayEquals("prefix\n<?php echo 2;".getBytes(StandardCharsets.UTF_8),
                PhpPayloadLocator.replace(body, spans, Map.of(0, "<?php echo 2;")));
        byte[] echo = "hi <?= $name ?> there".getBytes(StandardCharsets.UTF_8);
        assertEquals("<?= $name ?>", PhpPayloadLocator.text(echo,
                PhpPayloadLocator.find(echo, "application/octet-stream").get(0)));
    }

    @Test void neverReadsMultipartHeadersAsPayload() {
        String form = "--AaB03x\r\nContent-Disposition: form-data; name=\"x\"; "
                + "filename=\"<?php fake ?>.txt\"\r\n\r\nhello\r\n--AaB03x--\r\n";
        assertTrue(PhpPayloadLocator.find(form.getBytes(StandardCharsets.UTF_8), TYPE).isEmpty());
    }

    @Test void preservesArbitraryBinarySuffix() throws Exception {
        byte[] body = "prefix<?php echo 1; ?>".getBytes(StandardCharsets.US_ASCII);
        byte[] raw = java.util.Arrays.copyOf(body, body.length + 2);
        raw[raw.length - 2] = (byte) 0xff;
        raw[raw.length - 1] = (byte) 0x80;
        byte[] out = PhpPayloadLocator.replace(raw, PhpPayloadLocator.find(raw, null),
                Map.of(0, "<?php echo 123; ?>"));
        assertEquals((byte) 0xff, out[out.length - 2]);
        assertEquals((byte) 0x80, out[out.length - 1]);
    }

    @Test void rejectsCharactersUnrepresentableInOriginalEncoding() throws Exception {
        byte[] raw = new byte[]{'<', '?', 'p', 'h', 'p', ' ', (byte) 0xff, '?', '>'};
        var spans = PhpPayloadLocator.find(raw, null);
        assertEquals(StandardCharsets.ISO_8859_1, PhpPayloadLocator.charset(raw, spans.get(0)));
        assertArrayEquals(raw, PhpPayloadLocator.replace(raw, spans,
                Map.of(0, PhpPayloadLocator.text(raw, spans.get(0)))));
        assertThrows(CharacterCodingException.class, () -> PhpPayloadLocator.replace(raw,
                spans, Map.of(0, "<?php \uD83D\uDE80 ?>")));
    }

    @Test void recognizesLfBoundariesAndMissingBoundary() {
        String form = "--AaB03x\nContent-Disposition: form-data; name=\"code\"\n\n"
                + "<?php echo 1; ?>\n--AaB03x--\n";
        byte[] input = form.getBytes(StandardCharsets.UTF_8);
        var spans = PhpPayloadLocator.find(input, "multipart/form-data; boundary=AaB03x");
        assertEquals(1, spans.size());
        assertEquals("<?php echo 1; ?>", PhpPayloadLocator.text(input, spans.get(0)));
        assertTrue(PhpPayloadLocator.find(input, "multipart/form-data").isEmpty());
    }
}
