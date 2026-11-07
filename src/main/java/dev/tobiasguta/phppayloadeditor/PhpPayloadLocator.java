package dev.tobiasguta.phppayloadeditor;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Byte-oriented PHP block locator. Offsets are always byte offsets into the
 * original HTTP request body; non-PHP bytes are never converted to text.
 */
public final class PhpPayloadLocator {
    private static final Pattern BOUNDARY = Pattern.compile(
            "(?:^|;)\\s*boundary\\s*=\\s*(?:\"([^\"]+)\"|([^;\\s]+))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FILENAME = Pattern.compile(
            "\\bfilename\\s*=\\s*(?:\"([^\"]*)\"|([^;\\s]+))",
            Pattern.CASE_INSENSITIVE);

    private PhpPayloadLocator() {
    }

    public record PhpSpan(int start, int end, String description) {
        public PhpSpan {
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("Invalid PHP byte range");
            }
        }

        @Override
        public String toString() {
            return description;
        }
    }

    public static List<PhpSpan> find(byte[] body, String contentType) {
        if (body == null || body.length == 0) {
            return List.of();
        }
        List<PhpSpan> found = new ArrayList<>();
        if (contentType != null && contentType.toLowerCase(Locale.ROOT)
                .contains("multipart/form-data")) {
            Matcher header = BOUNDARY.matcher(contentType);
            if (!header.find()) {
                return List.of();
            }
            String boundary = header.group(1) != null ? header.group(1) : header.group(2);
            if (boundary.isEmpty() || boundary.length() > 200) {
                return List.of();
            }
            // ISO-8859-1 is intentionally used ONLY to locate ASCII delimiters;
            // each char offset is identical to the original byte offset.
            String view = new String(body, StandardCharsets.ISO_8859_1);
            Matcher match = Pattern.compile(
                    "(?m)^--" + Pattern.quote(boundary)
                            + "(--)?[ \\t]*(?:\\r?\\n|$)").matcher(view);
            List<Delimiter> delimiters = new ArrayList<>();
            while (match.find()) {
                delimiters.add(new Delimiter(match.start(), match.end(), match.group(1) != null));
            }
            for (int i = 0; i + 1 < delimiters.size(); i++) {
                Delimiter current = delimiters.get(i);
                if (current.closing()) {
                    break;
                }
                int start = current.end();
                int end = delimiters.get(i + 1).start();
                // The newline before a MIME boundary belongs to the delimiter,
                // not to the uploaded file.
                if (end > start && view.charAt(end - 1) == '\n') {
                    end--;
                    if (end > start && view.charAt(end - 1) == '\r') {
                        end--;
                    }
                }
                int split = view.indexOf("\r\n\r\n", start);
                int separatorSize = 4;
                if (split < 0 || split >= end) {
                    split = view.indexOf("\n\n", start);
                    separatorSize = 2;
                }
                if (split < 0 || split >= end) {
                    continue;
                }
                String headers = view.substring(start, split);
                String label = partLabel(headers, i + 1);
                scan(body, split + separatorSize, end, label, found);
            }
        } else {
            scan(body, 0, body.length, "Request body", found);
        }
        return List.copyOf(found);
    }

    private static String partLabel(String headers, int number) {
        Matcher filename = FILENAME.matcher(headers);
        if (filename.find()) {
            String name = filename.group(1) == null ? filename.group(2) : filename.group(1);
            name = name.replace('\r', '_').replace('\n', '_');
            if (name.length() > 80) {
                name = name.substring(0, 80) + "...";
            }
            return "Upload: " + name;
        }
        return "Form part " + number;
    }

    private static void scan(byte[] body, int start, int end, String label, List<PhpSpan> result) {
        int cursor = Math.max(0, start);
        while (cursor < end - 2) {
            int open = openingAtOrAfter(body, cursor, end);
            if (open < 0) {
                return;
            }
            int close = -1;
            for (int p = open + 2; p + 1 < end; p++) {
                if (body[p] == '?' && body[p + 1] == '>') {
                    close = p + 2;
                    break;
                }
            }
            int blockEnd = close >= 0 ? close : end;
            result.add(new PhpSpan(open, blockEnd, label + " — PHP #" + (result.size() + 1)));
            cursor = blockEnd;
        }
    }

    private static int openingAtOrAfter(byte[] bytes, int start, int end) {
        for (int p = start; p + 2 < end; p++) {
            if (bytes[p] != '<' || bytes[p + 1] != '?') {
                continue;
            }
            if (bytes[p + 2] == '=') {
                return p;
            }
            if (p + 4 < end && asciiIgnoreCase(bytes[p + 2], 'p')
                    && asciiIgnoreCase(bytes[p + 3], 'h')
                    && asciiIgnoreCase(bytes[p + 4], 'p')) {
                return p;
            }
        }
        return -1;
    }

    private static boolean asciiIgnoreCase(byte actual, char expected) {
        return actual == expected || actual == Character.toUpperCase(expected);
    }

    public static Charset charset(byte[] body, PhpSpan span) {
        ByteBuffer source = ByteBuffer.wrap(body, span.start(), span.end() - span.start());
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(source);
            return StandardCharsets.UTF_8;
        } catch (CharacterCodingException ex) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    public static String text(byte[] body, PhpSpan span) {
        return new String(body, span.start(), span.end() - span.start(), charset(body, span));
    }

    /**
     * Applies edits from highest offset to lowest so that all originally
     * detected spans remain valid, even when replacement sizes differ.
     */
    public static byte[] replace(byte[] original, List<PhpSpan> spans,
                                 Map<Integer, String> edits) throws CharacterCodingException {
        if (edits.isEmpty()) {
            return original.clone();
        }
        byte[] result = original.clone();
        List<Integer> indices = new ArrayList<>(edits.keySet());
        indices.sort(Comparator.reverseOrder());
        for (int index : indices) {
            PhpSpan span = spans.get(index);
            Charset chosen = charset(original, span);
            ByteBuffer encoded = chosen.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(edits.get(index)));
            byte[] replacement = new byte[encoded.remaining()];
            encoded.get(replacement);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(
                    result.length - (span.end() - span.start()) + replacement.length);
            bytes.write(result, 0, span.start());
            bytes.write(replacement, 0, replacement.length);
            bytes.write(result, span.end(), result.length - span.end());
            result = bytes.toByteArray();
        }
        return result;
    }

    private record Delimiter(int start, int end, boolean closing) {
    }
}
