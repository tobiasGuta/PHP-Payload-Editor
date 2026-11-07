package dev.tobiasguta.phppayloadeditor;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.Theme;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.EditorMode;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpRequestEditor;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Dialog;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One independent editor instance per Burp request editor.
 * Original headers and every byte outside edited PHP spans are retained.
 */
final class PhpRequestEditor implements ExtensionProvidedHttpRequestEditor {
    private final MontoyaApi api;
    private final JPanel root = new JPanel(new BorderLayout());
    private final RSyntaxTextArea editor = new RSyntaxTextArea(20, 75);
    private final RTextScrollPane scroll = new RTextScrollPane(editor);
    private final JComboBox<String> themeMode = new JComboBox<>(
            new String[]{"Follow Burp", "Dark", "Light"});
    private final JComboBox<PhpPayloadLocator.PhpSpan> blocks = new JComboBox<>();
    private final JButton editButton = new JButton("Edit in Window");
    private final JLabel status = new JLabel("Open a request containing PHP to edit its payload.");
    private final boolean readOnly;

    private HttpRequest originalRequest;
    private byte[] originalBody = new byte[0];
    private List<PhpPayloadLocator.PhpSpan> spans = List.of();
    private final Map<Integer, String> edits = new HashMap<>();
    private int currentIndex = -1;
    private boolean loading = false;

    PhpRequestEditor(MontoyaApi api, EditorCreationContext context) {
        this.api = api;
        readOnly = context.editorMode() == EditorMode.READ_ONLY;
        editor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_PHP);
        editor.setCodeFoldingEnabled(true);
        editor.setAntiAliasingEnabled(true);
        editor.setTabsEmulated(true);
        editor.setTabSize(4);
        editor.setMarkOccurrences(true);
        editor.setEditable(!readOnly);
        editor.setFocusable(true);

        scroll.setFoldIndicatorEnabled(true);
        root.add(scroll, BorderLayout.CENTER);

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEADING, 8, 4));
        toolbar.add(new JLabel("PHP block:"));
        blocks.setPrototypeDisplayValue(new PhpPayloadLocator.PhpSpan(0, 0,
                "Upload: a_long_filename_example.php — PHP #10"));
        toolbar.add(blocks);
        JLabel hint = new JLabel(readOnly ? "Read-only view" : "Edits sync with the HTTP request");
        toolbar.add(hint);
        editButton.setEnabled(!readOnly);
        editButton.setToolTipText("Open a separate editable PHP window and apply changes to this request");
        editButton.addActionListener(event -> openEditorWindow());
        toolbar.add(editButton);
        toolbar.add(new JLabel("Theme:"));
        toolbar.add(themeMode);
        root.add(toolbar, BorderLayout.NORTH);

        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        status.setHorizontalAlignment(SwingConstants.LEADING);
        root.add(status, BorderLayout.SOUTH);
        blocks.addActionListener(event -> {
            if (!loading && blocks.getSelectedIndex() != currentIndex) {
                showBlock(blocks.getSelectedIndex());
            }
        });
        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                recordEdit();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                recordEdit();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                recordEdit();
            }
        });
        // Burp themes regular Swing controls; RSTA needs its own syntax palette.
        // Apply RSTA colors AFTER Burp to prevent a bright white editor.
        api.userInterface().applyThemeToComponent(root);
        themeMode.addActionListener(event -> applyEditorTheme());
        applyEditorTheme();
        ensureEditable();
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
        if (message == null || message.request() == null) {
            return false;
        }
        HttpRequest request = message.request();
        return !PhpPayloadLocator.find(request.body().getBytes(),
                request.headerValue("Content-Type")).isEmpty();
    }

    @Override
    public void setRequestResponse(HttpRequestResponse message) {
        HttpRequest next = message == null ? null : message.request();
        // Burp may refresh the same request while the user is typing, or when
        // its response changes. Do not throw away uncommitted PHP changes.
        if (next != null && originalRequest != null
                && Arrays.equals(next.toByteArray().getBytes(),
                        originalRequest.toByteArray().getBytes())) {
            ensureEditable();
            return;
        }
        if (themeMode.getSelectedIndex() == 0) {
            applyEditorTheme(); // Re-read Burp theme when another request is opened.
        }
        loading = true;
        try {
            originalRequest = next;
            originalBody = originalRequest == null
                    ? new byte[0] : originalRequest.body().getBytes();
            spans = originalRequest == null ? List.of() :
                    PhpPayloadLocator.find(originalBody, originalRequest.headerValue("Content-Type"));
            edits.clear();
            currentIndex = -1;
            blocks.removeAllItems();
            for (PhpPayloadLocator.PhpSpan span : spans) {
                blocks.addItem(span);
            }
            if (spans.isEmpty()) {
                editor.setText("");
                editButton.setEnabled(false);
                status.setText("No PHP blocks were detected in this request.");
                blocks.setEnabled(false);
            } else {
                blocks.setEnabled(spans.size() > 1);
                editButton.setEnabled(!readOnly);
                currentIndex = 0;
                blocks.setSelectedIndex(0);
                editor.setText(PhpPayloadLocator.text(originalBody, spans.get(0)));
                editor.setCaretPosition(0);
                updateStatus();
            }
        } finally {
            loading = false;
            ensureEditable();
        }
    }

    private void ensureEditable() {
        // Theme application / Burp UI refreshes must never leave a writable
        // Repeater request with a disabled or unfocusable Swing code editor.
        editor.setEnabled(true);
        editor.setFocusable(true);
        editor.setEditable(!readOnly);
    }

    private void applyEditorTheme() {
        int selected = themeMode.getSelectedIndex();
        boolean dark = selected == 1 ||
                (selected == 0 && api.userInterface().currentTheme() == Theme.DARK);
        EditorThemeSupport.apply(editor, scroll, dark, api.userInterface().currentEditorFont());
        ensureEditable();
    }

    private void showBlock(int index) {
        if (index < 0 || index >= spans.size()) {
            return;
        }
        syncActiveEdit();
        loading = true;
        try {
            currentIndex = index;
            PhpPayloadLocator.PhpSpan span = spans.get(index);
            editor.setText(edits.getOrDefault(index, PhpPayloadLocator.text(originalBody, span)));
            editor.setCaretPosition(0);
            updateStatus();
        } finally {
            loading = false;
        }
    }

    /**
     * Dedicated popup avoids focus/keyboard interception in certain Burp
     * request-editor contexts. Apply writes the source back to this tab,
     * and the optional Repeater action opens a NEW tab without sending.
     */
    private void openEditorWindow() {
        if (readOnly || spans.isEmpty() || currentIndex < 0) {
            return;
        }
        syncActiveEdit();
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(root),
                "PHP Payload Editor — Edit Source", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setLayout(new BorderLayout(0, 4));

        RSyntaxTextArea popupEditor = new RSyntaxTextArea(28, 90);
        popupEditor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_PHP);
        popupEditor.setCodeFoldingEnabled(true);
        popupEditor.setAntiAliasingEnabled(true);
        popupEditor.setTabsEmulated(true);
        popupEditor.setTabSize(4);
        popupEditor.setText(editor.getText());
        popupEditor.setCaretPosition(0);
        popupEditor.setEnabled(true);
        popupEditor.setEditable(true);
        popupEditor.setFocusable(true);
        RTextScrollPane popupScroll = new RTextScrollPane(popupEditor);
        popupScroll.setFoldIndicatorEnabled(true);
        dialog.add(popupScroll, BorderLayout.CENTER);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        JLabel explanation = new JLabel("Apply changes to the current request, or open an edited copy in a new Repeater tab.");
        JButton cancel = new JButton("Cancel");
        JButton apply = new JButton("Apply Changes");
        JButton newTab = new JButton("Open in Repeater (new tab)");
        cancel.addActionListener(event -> dialog.dispose());
        apply.addActionListener(event -> {
            if (applyWindowText(popupEditor.getText())) {
                dialog.dispose();
            } else {
                JOptionPane.showMessageDialog(dialog,
                        "Unable to apply edits. Check the encoding and the request's PHP block.",
                        "PHP Payload Editor", JOptionPane.WARNING_MESSAGE);
            }
        });
        newTab.addActionListener(event -> {
            if (!applyWindowText(popupEditor.getText())) {
                JOptionPane.showMessageDialog(dialog,
                        "Unable to encode edited PHP. No Repeater tab was created.",
                        "PHP Payload Editor", JOptionPane.WARNING_MESSAGE);
                return;
            }
            HttpRequest patched = getRequest();
            if (patched == null || (patched == originalRequest && isModified())) {
                JOptionPane.showMessageDialog(dialog,
                        "Unable to construct the modified request.",
                        "PHP Payload Editor", JOptionPane.WARNING_MESSAGE);
                return;
            }
            // sendToRepeater adds a tab but does not issue a network request.
            api.repeater().sendToRepeater(patched, "PHP Edited");
            dialog.dispose();
        });
        controls.add(explanation);
        controls.add(cancel);
        controls.add(newTab);
        controls.add(apply);
        dialog.add(controls, BorderLayout.SOUTH);
        api.userInterface().applyThemeToComponent(dialog.getContentPane());
        int theme = themeMode.getSelectedIndex();
        boolean dark = theme == 1 || (theme == 0 && api.userInterface().currentTheme() == Theme.DARK);
        EditorThemeSupport.apply(popupEditor, popupScroll, dark, api.userInterface().currentEditorFont());
        // Some look-and-feels change editable state while applying themes.
        popupEditor.setEnabled(true);
        popupEditor.setEditable(true);
        dialog.pack();
        dialog.setSize(Math.max(900, dialog.getWidth()), Math.max(620, dialog.getHeight()));
        dialog.setLocationRelativeTo(root);
        SwingUtilities.invokeLater(popupEditor::requestFocusInWindow);
        dialog.setVisible(true);
    }

    /**
     * Shared, testable command behind the popup's Apply buttons.
     * A rejected encoding must not corrupt the original request.
     */
    boolean applyWindowText(String text) {
        if (readOnly || currentIndex < 0 || currentIndex >= spans.size()) {
            return false;
        }
        String before = PhpPayloadLocator.text(originalBody, spans.get(currentIndex));
        Map<Integer, String> candidate = new HashMap<>(edits);
        if (before.equals(text)) {
            candidate.remove(currentIndex);
        } else {
            candidate.put(currentIndex, text);
        }
        try {
            PhpPayloadLocator.replace(originalBody, spans, candidate);
        } catch (CharacterCodingException invalid) {
            status.setText("Cannot encode PHP with the original charset. Changes not applied.");
            return false;
        }
        loading = true;
        try {
            editor.setText(text);
        } finally {
            loading = false;
        }
        syncActiveEdit();
        return true;
    }

    private void recordEdit() {
        syncActiveEdit();
    }

    private void syncActiveEdit() {
        if (loading || readOnly || currentIndex < 0 || currentIndex >= spans.size()) {
            return;
        }
        String updated = editor.getText();
        String old = PhpPayloadLocator.text(originalBody, spans.get(currentIndex));
        if (old.equals(updated)) {
            edits.remove(currentIndex);
        } else {
            edits.put(currentIndex, updated);
        }
        updateStatus();
    }

    private void updateStatus() {
        if (spans.isEmpty()) {
            return;
        }
        PhpPayloadLocator.PhpSpan current = spans.get(currentIndex);
        String encoding = PhpPayloadLocator.charset(originalBody, current).displayName();
        status.setText(spans.size() + " PHP block(s) found  |  "
                + encoding + "  |  "
                + (edits.isEmpty() ? "Unmodified" : edits.size() + " block(s) modified")
                + (readOnly ? "  |  Read-only" : "  |  Writable — or use Edit in Window"));
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
            byte[] replacement = PhpPayloadLocator.replace(originalBody, spans, edits);
            return originalRequest.withBody(ByteArray.byteArray(replacement));
        } catch (CharacterCodingException ex) {
            status.setText("Cannot encode edited text with the source charset. "
                    + "Remove unsupported characters before sending.");
            // Do not silently corrupt the original multipart or binary data.
            return originalRequest;
        }
    }

    @Override
    public Selection selectedData() {
        String selected = editor.getSelectedText();
        if (selected == null || selected.isEmpty()) {
            return null;
        }
        return Selection.selection(ByteArray.byteArray(selected.getBytes(StandardCharsets.UTF_8)));
    }
}
