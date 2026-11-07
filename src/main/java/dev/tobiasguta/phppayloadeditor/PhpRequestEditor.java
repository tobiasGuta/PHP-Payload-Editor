package dev.tobiasguta.phppayloadeditor;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.EditorMode;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpRequestEditor;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One independent editor instance per Burp request editor.
 * Original headers and every byte outside edited PHP spans are retained.
 */
final class PhpRequestEditor implements ExtensionProvidedHttpRequestEditor {
    private final JPanel root = new JPanel(new BorderLayout());
    private final RSyntaxTextArea editor = new RSyntaxTextArea(20, 75);
    private final JComboBox<PhpPayloadLocator.PhpSpan> blocks = new JComboBox<>();
    private final JLabel status = new JLabel("Open a request containing PHP to edit its payload.");
    private final boolean readOnly;

    private HttpRequest originalRequest;
    private byte[] originalBody = new byte[0];
    private List<PhpPayloadLocator.PhpSpan> spans = List.of();
    private final Map<Integer, String> edits = new HashMap<>();
    private int currentIndex = -1;
    private boolean loading = false;

    PhpRequestEditor(MontoyaApi api, EditorCreationContext context) {
        readOnly = context.editorMode() == EditorMode.READ_ONLY;
        editor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_PHP);
        editor.setCodeFoldingEnabled(true);
        editor.setAntiAliasingEnabled(true);
        editor.setTabsEmulated(true);
        editor.setTabSize(4);
        editor.setMarkOccurrences(true);
        editor.setEditable(!readOnly);

        RTextScrollPane scroll = new RTextScrollPane(editor);
        scroll.setFoldIndicatorEnabled(true);
        root.add(scroll, BorderLayout.CENTER);

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEADING, 8, 4));
        toolbar.add(new JLabel("PHP block:"));
        blocks.setPrototypeDisplayValue(new PhpPayloadLocator.PhpSpan(0, 0,
                "Upload: a_long_filename_example.php — PHP #10"));
        toolbar.add(blocks);
        JLabel hint = new JLabel(readOnly ? "Read-only view" : "Edits sync with the HTTP request");
        toolbar.add(hint);
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
        api.userInterface().applyThemeToComponent(root);
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
        loading = true;
        try {
            originalRequest = message == null ? null : message.request();
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
                status.setText("No PHP blocks were detected in this request.");
                blocks.setEnabled(false);
            } else {
                blocks.setEnabled(spans.size() > 1);
                currentIndex = 0;
                blocks.setSelectedIndex(0);
                editor.setText(PhpPayloadLocator.text(originalBody, spans.get(0)));
                editor.setCaretPosition(0);
                updateStatus();
            }
        } finally {
            loading = false;
        }
    }

    private void showBlock(int index) {
        if (index < 0 || index >= spans.size()) {
            return;
        }
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

    private void recordEdit() {
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
                + (readOnly ? "  |  Read-only" : ""));
    }

    @Override
    public boolean isModified() {
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
