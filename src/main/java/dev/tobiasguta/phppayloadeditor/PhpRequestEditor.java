package dev.tobiasguta.phppayloadeditor;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.Theme;
import burp.api.montoya.ui.editor.RawEditor;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.EditorMode;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpRequestEditor;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Burp's own RawEditor owns all text input. The syntax-colored RSyntaxTextArea
 * is deliberately read-only: it acts only as a preview, avoiding competing
 * text editors and focus conflicts inside Burp.
 */
final class PhpRequestEditor implements ExtensionProvidedHttpRequestEditor {
    private final MontoyaApi api;
    private final JPanel root = new JPanel(new BorderLayout());
    private final RawEditor sourceEditor;
    private final RSyntaxTextArea preview = new RSyntaxTextArea(18, 70);
    private final RTextScrollPane previewScroll = new RTextScrollPane(preview);
    private final JTabbedPane views = new JTabbedPane();
    private final JComboBox<PhpPayloadLocator.PhpSpan> blocks = new JComboBox<>();
    private final JLabel status = new JLabel("Load an HTTP request containing PHP to start editing.");
    private final boolean readOnly;

    private HttpRequest originalRequest;
    private byte[] originalBody = new byte[0];
    private List<PhpPayloadLocator.PhpSpan> spans = List.of();
    private final Map<Integer, String> edits = new HashMap<>();
    private int currentIndex = -1;
    private boolean loading;

    PhpRequestEditor(MontoyaApi api, EditorCreationContext context) {
        this.api = api;
        this.readOnly = context.editorMode() == EditorMode.READ_ONLY;
        // Burp handles keyboard, clipboard, search, editor focus and dark mode.
        sourceEditor = api.userInterface().createRawEditor();
        sourceEditor.setEditable(!readOnly);
        views.addTab("Edit PHP (native)", sourceEditor.uiComponent());

        preview.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_PHP);
        preview.setCodeFoldingEnabled(true);
        preview.setAntiAliasingEnabled(true);
        preview.setEditable(false);
        previewScroll.setFoldIndicatorEnabled(true);
        views.addTab("Highlighted preview", previewScroll);
        root.add(views, BorderLayout.CENTER);

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEADING, 7, 4));
        toolbar.add(new JLabel("PHP block:"));
        blocks.setPrototypeDisplayValue(new PhpPayloadLocator.PhpSpan(
                0, 0, "Upload: sample_filename.php — PHP #10"));
        toolbar.add(blocks);

        JButton popup = new JButton("Plain editor window");
        popup.setToolTipText("Use a regular editable text field in a separate window");
        popup.addActionListener(event -> openPlainWindow());
        popup.setEnabled(!readOnly);
        toolbar.add(popup);

        JButton test = new JButton("Insert test comment");
        test.setToolTipText("Checks that the PHP text and HTTP request can actually be changed");
        test.setEnabled(!readOnly);
        test.addActionListener(event -> insertTestComment());
        toolbar.add(test);

        JButton repeater = new JButton("Open edited copy");
        repeater.setToolTipText("Open the patched request in a new Repeater tab (does not send it)");
        repeater.setEnabled(!readOnly);
        repeater.addActionListener(event -> openEditedCopy());
        toolbar.add(repeater);
        root.add(toolbar, BorderLayout.NORTH);

        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        status.setHorizontalAlignment(SwingConstants.LEADING);
        root.add(status, BorderLayout.SOUTH);
        blocks.addActionListener(event -> {
            if (!loading && blocks.getSelectedIndex() != currentIndex) {
                selectBlock(blocks.getSelectedIndex());
            }
        });
        views.addChangeListener(event -> {
            if (!loading && !spans.isEmpty()) {
                syncActiveEdit();
                if (views.getSelectedIndex() == 1) {
                    refreshPreview();
                }
            }
        });

        // Apply Burp styling to ordinary Swing controls ONLY. Recursively
        // theming Burp's own editor or a third-party text area is unsafe.
        api.userInterface().applyThemeToComponent(toolbar);
        api.userInterface().applyThemeToComponent(status);
        applyPreviewTheme();
    }

    @Override
    public String caption() {
        return "PHP Editor";
    }

    @Override
    public Component uiComponent() {
        return root;
    }

    @Override
    public boolean isEnabledFor(HttpRequestResponse message) {
        return message != null && message.request() != null &&
                !PhpPayloadLocator.find(message.request().body().getBytes(),
                        message.request().headerValue("Content-Type")).isEmpty();
    }

    @Override
    public void setRequestResponse(HttpRequestResponse message) {
        HttpRequest next = message == null ? null : message.request();
        if (next != null && originalRequest != null &&
                Arrays.equals(next.toByteArray().getBytes(),
                        originalRequest.toByteArray().getBytes())) {
            // Burp can invoke this on response refresh. Do not lose the draft.
            return;
        }

        loading = true;
        try {
            originalRequest = next;
            originalBody = next == null ? new byte[0] : next.body().getBytes();
            spans = next == null ? List.of()
                    : PhpPayloadLocator.find(originalBody, next.headerValue("Content-Type"));
            edits.clear();
            currentIndex = -1;
            blocks.removeAllItems();
            for (PhpPayloadLocator.PhpSpan span : spans) {
                blocks.addItem(span);
            }
            blocks.setEnabled(spans.size() > 1);
            if (spans.isEmpty()) {
                sourceEditor.setContents(ByteArray.byteArray(new byte[0]));
                preview.setText("");
                status.setText("No PHP block detected.");
            } else {
                currentIndex = 0;
                blocks.setSelectedIndex(0);
                showText(PhpPayloadLocator.text(originalBody, spans.get(0)));
                updateStatus();
            }
            sourceEditor.setEditable(!readOnly);
            applyPreviewTheme();
        } finally {
            loading = false;
        }
    }

    private void applyPreviewTheme() {
        boolean dark = api.userInterface().currentTheme() == Theme.DARK;
        EditorThemeSupport.apply(preview, previewScroll, dark,
                api.userInterface().currentEditorFont());
        preview.setEditable(false);
    }

    private void selectBlock(int next) {
        if (next < 0 || next >= spans.size()) {
            return;
        }
        syncActiveEdit();
        loading = true;
        try {
            currentIndex = next;
            String text = edits.getOrDefault(next, PhpPayloadLocator.text(originalBody, spans.get(next)));
            showText(text);
            updateStatus();
        } finally {
            loading = false;
        }
    }

    private void showText(String text) {
        sourceEditor.setContents(ByteArray.byteArray(text.getBytes(StandardCharsets.UTF_8)));
        sourceEditor.setEditable(!readOnly);
        preview.setText(text);
        preview.setCaretPosition(0);
    }

    private String currentText() {
        return new String(sourceEditor.getContents().getBytes(), StandardCharsets.UTF_8);
    }

    private void refreshPreview() {
        preview.setText(currentText());
        preview.setCaretPosition(0);
    }

    private void syncActiveEdit() {
        if (loading || readOnly || currentIndex < 0 || currentIndex >= spans.size()) {
            return;
        }
        String text = currentText();
        String original = PhpPayloadLocator.text(originalBody, spans.get(currentIndex));
        if (text.equals(original)) {
            edits.remove(currentIndex);
        } else {
            edits.put(currentIndex, text);
        }
        updateStatus();
    }

    private void updateStatus() {
        if (spans.isEmpty() || currentIndex < 0) {
            return;
        }
        String charset = PhpPayloadLocator.charset(originalBody, spans.get(currentIndex)).displayName();
        status.setText(spans.size() + " PHP block(s) found | " + charset + " | "
                + (edits.isEmpty() ? "Unmodified" : edits.size() + " block(s) modified")
                + (readOnly ? " | Read only" : " | Native editor: type directly"));
    }

    /** Can be used independently of keyboard input to diagnose write-back. */
    private void insertTestComment() {
        if (readOnly || spans.isEmpty() || currentIndex < 0) {
            return;
        }
        String text = currentText();
        int close = text.lastIndexOf("?>");
        String comment = "\n/* PHP Payload Editor test */\n";
        String revised = close >= 0
                ? text.substring(0, close) + comment + text.substring(close)
                : text + comment;
        applyText(revised);
    }

    /**
     * Validates the replacement before updating the native editor, so we
     * never accidentally send invalid bytes for ISO-8859-1 PHP snippets.
     */
    boolean applyText(String text) {
        if (readOnly || currentIndex < 0 || currentIndex >= spans.size()) {
            return false;
        }
        syncActiveEdit();
        Map<Integer, String> nextEdits = new HashMap<>(edits);
        String original = PhpPayloadLocator.text(originalBody, spans.get(currentIndex));
        if (original.equals(text)) {
            nextEdits.remove(currentIndex);
        } else {
            nextEdits.put(currentIndex, text);
        }
        try {
            PhpPayloadLocator.replace(originalBody, spans, nextEdits);
        } catch (CharacterCodingException invalid) {
            status.setText("Unable to encode source with the original charset. No changes applied.");
            return false;
        }
        loading = true;
        try {
            showText(text);
            edits.clear();
            edits.putAll(nextEdits);
        } finally {
            loading = false;
        }
        updateStatus();
        return true;
    }

    /**
     * Uses ordinary JTextArea with NO Burp recursive theme override.
     * Provides an independent plain-text input path when needed.
     */
    private void openPlainWindow() {
        if (readOnly || spans.isEmpty()) {
            return;
        }
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(root),
                "PHP Payload Editor — Plain Text", Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setLayout(new BorderLayout());
        JTextArea plain = new JTextArea(currentText(), 25, 85);
        Font mono = api.userInterface().currentEditorFont();
        if (mono != null) {
            plain.setFont(mono);
        }
        if (api.userInterface().currentTheme() == Theme.DARK) {
            plain.setBackground(new Color(0x282828));
            plain.setForeground(new Color(0xE5E5E5));
            plain.setCaretColor(Color.WHITE);
            plain.setSelectionColor(new Color(0x3D5270));
        }
        plain.setEditable(true);
        plain.setFocusable(true);
        dialog.add(new JScrollPane(plain), BorderLayout.CENTER);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        JButton cancel = new JButton("Cancel");
        JButton apply = new JButton("Apply changes");
        JButton copy = new JButton("Apply + Open new Repeater tab");
        cancel.addActionListener(e -> dialog.dispose());
        apply.addActionListener(e -> {
            if (applyText(plain.getText())) {
                dialog.dispose();
            } else {
                JOptionPane.showMessageDialog(dialog, status.getText());
            }
        });
        copy.addActionListener(e -> {
            if (applyText(plain.getText())) {
                if (openEditedCopy()) {
                    dialog.dispose();
                }
            } else {
                JOptionPane.showMessageDialog(dialog, status.getText());
            }
        });
        controls.add(cancel);
        controls.add(copy);
        controls.add(apply);
        dialog.add(controls, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setSize(Math.max(850, dialog.getWidth()), Math.max(600, dialog.getHeight()));
        dialog.setLocationRelativeTo(root);
        dialog.setVisible(true);
        SwingUtilities.invokeLater(plain::requestFocusInWindow);
    }

    private boolean openEditedCopy() {
        HttpRequest patched = getRequest();
        if (patched == null) {
            return false;
        }
        if (patched == originalRequest) {
            status.setText("No valid changes to copy. Make an edit first.");
            return false;
        }
        api.repeater().sendToRepeater(patched, "PHP Edited");
        return true;
    }

    @Override
    public boolean isModified() {
        syncActiveEdit();
        return !readOnly && !edits.isEmpty();
    }

    @Override
    public HttpRequest getRequest() {
        if (originalRequest == null || !isModified()) {
            return originalRequest;
        }
        try {
            byte[] changed = PhpPayloadLocator.replace(originalBody, spans, edits);
            return originalRequest.withBody(ByteArray.byteArray(changed));
        } catch (CharacterCodingException invalid) {
            status.setText("Edited text cannot be encoded with the original charset.");
            return originalRequest;
        }
    }

    @Override
    public Selection selectedData() {
        if (views.getSelectedIndex() == 0) {
            return sourceEditor.selection().orElse(null);
        }
        String selected = preview.getSelectedText();
        return selected == null || selected.isEmpty() ? null
                : Selection.selection(ByteArray.byteArray(selected.getBytes(StandardCharsets.UTF_8)));
    }
}
