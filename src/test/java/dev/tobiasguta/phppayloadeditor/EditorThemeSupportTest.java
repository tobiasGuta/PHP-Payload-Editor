package dev.tobiasguta.phppayloadeditor;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;

import static org.junit.jupiter.api.Assertions.*;

class EditorThemeSupportTest {
    @Test
    void darkThemeIsReadableAndCoversGutter() {
        var editor = new RSyntaxTextArea(5, 35);
        editor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_PHP);
        var scroll = new RTextScrollPane(editor);
        Font requested = new Font(Font.MONOSPACED, Font.PLAIN, 15);

        EditorThemeSupport.apply(editor, scroll, true, requested);

        assertEquals(new Color(0x282828), editor.getBackground());
        assertEquals(new Color(0x242424), scroll.getGutter().getBackground());
        assertEquals(new Color(0x939393), scroll.getGutter().getLineNumberColor());
        assertEquals(requested, editor.getFont());
        assertTrue(editor.getForeground().getRed() > 150);
    }

    @Test
    void lightThemeRestoresLightSyntaxPaletteAndBackground() {
        var editor = new RSyntaxTextArea(5, 35);
        editor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_PHP);
        var scroll = new RTextScrollPane(editor);

        EditorThemeSupport.apply(editor, scroll, true, null);
        EditorThemeSupport.apply(editor, scroll, false, null);

        assertEquals(Color.WHITE, editor.getBackground());
        assertEquals(new Color(0xF3F3F3), scroll.getGutter().getBackground());
        assertEquals(new Color(0x242424), editor.getForeground());
    }
}
