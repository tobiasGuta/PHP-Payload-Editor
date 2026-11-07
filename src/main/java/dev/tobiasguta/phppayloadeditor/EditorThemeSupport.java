package dev.tobiasguta.phppayloadeditor;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.RTextScrollPane;

import java.awt.Color;
import java.awt.Font;
import java.io.IOException;
import java.io.InputStream;

/**
 * RSyntaxTextArea does not inherit Burp's editor colors from Swing's
 * look-and-feel; explicitly load matching syntax colors and paint the gutter.
 */
final class EditorThemeSupport {
    private EditorThemeSupport() {
    }

    static void apply(RSyntaxTextArea editor, RTextScrollPane scroll,
                      boolean dark, Font configuredFont) {
        String resource = "/org/fife/ui/rsyntaxtextarea/themes/"
                + (dark ? "dark.xml" : "default.xml");
        try (InputStream in = Theme.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Editor theme resource not found: " + resource);
            }
            Theme.load(in).apply(editor);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load editor theme: " + resource, e);
        }

        // Match Burp's neutral editor surfaces while retaining syntax token
        // foregrounds from RSTA's accessible light/dark palettes.
        Color surface = new Color(dark ? 0x282828 : 0xFFFFFF);
        Color gutter = new Color(dark ? 0x242424 : 0xF3F3F3);
        editor.setBackground(surface);
        editor.setForeground(new Color(dark ? 0xE5E5E5 : 0x242424));
        editor.setCaretColor(new Color(dark ? 0xF2F2F2 : 0x202020));
        editor.setCurrentLineHighlightColor(new Color(dark ? 0x333333 : 0xF5F5E8));
        editor.setSelectionColor(new Color(dark ? 0x3D5270 : 0xBBD6F5));
        editor.setSelectedTextColor(new Color(dark ? 0xFFFFFF : 0x141414));
        if (configuredFont != null) {
            editor.setFont(configuredFont);
        }
        scroll.setBackground(surface);
        scroll.getViewport().setBackground(surface);
        scroll.getGutter().setBackground(gutter);
        scroll.getGutter().setLineNumberColor(new Color(dark ? 0x939393 : 0x686868));
    }
}
