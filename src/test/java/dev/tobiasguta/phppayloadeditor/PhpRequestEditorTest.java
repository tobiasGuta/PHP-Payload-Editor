package dev.tobiasguta.phppayloadeditor;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.Theme;
import burp.api.montoya.ui.UserInterface;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.EditorMode;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import org.mockito.MockedStatic;

class PhpRequestEditorTest {
    private final MontoyaApi api = mock(MontoyaApi.class);
    private final UserInterface ui = mock(UserInterface.class);
    private final EditorCreationContext context = mock(EditorCreationContext.class);
    private final HttpRequest request = mock(HttpRequest.class);
    private final HttpRequestResponse message = mock(HttpRequestResponse.class);

    private void setup(EditorMode mode) {
        when(api.userInterface()).thenReturn(ui);
        when(ui.currentTheme()).thenReturn(Theme.DARK);
        when(context.editorMode()).thenReturn(mode);
        when(message.request()).thenReturn(request);
        when(request.headerValue("Content-Type")).thenReturn("text/plain");
        ByteArray originalBody = mockByteArray("before <?php echo 1; ?> after".getBytes(StandardCharsets.UTF_8));
        ByteArray originalRequest = mockByteArray("POST / HTTP/1.1\r\n\r\nbefore <?php echo 1; ?> after".getBytes(StandardCharsets.UTF_8));
        when(request.body()).thenReturn(originalBody);
        when(request.toByteArray()).thenReturn(originalRequest);
        when(request.withBody(any(ByteArray.class))).thenAnswer(invocation -> {
            ByteArray body = invocation.getArgument(0);
            HttpRequest updated = mock(HttpRequest.class);
            when(updated.body()).thenReturn(body);
            return updated;
        });
    }

    @Test
    void writableRepeaterTabAcceptsEditsAndReturnsUpdatedRequest() throws Exception {
        onEdt(() -> {
            setup(EditorMode.DEFAULT);
            try (MockedStatic<ByteArray> factory = mockStatic(ByteArray.class)) {
            factory.when(() -> ByteArray.byteArray(any(byte[].class)))
                    .thenAnswer(inv -> mockByteArray((byte[]) inv.getRawArguments()[0]));
            PhpRequestEditor tab = new PhpRequestEditor(api, context);
            tab.setRequestResponse(message);
            RSyntaxTextArea text = locateEditor(tab.uiComponent());
            assertNotNull(text);
            assertTrue(text.isEnabled());
            assertTrue(text.isEditable(), "PHP text area should allow typing");
            text.select(text.getText().indexOf("1"), text.getText().indexOf("1") + 1);
            text.replaceSelection("123");
            assertTrue(tab.isModified(), "Edit must be detected");
            HttpRequest result = tab.getRequest();
            assertEquals("before <?php echo 123; ?> after",
                    new String(result.body().getBytes(), StandardCharsets.UTF_8));
            }
        });
    }

    @Test
    void repeatedRefreshOfSameRequestDoesNotDiscardDraft() throws Exception {
        onEdt(() -> {
            setup(EditorMode.DEFAULT);
            PhpRequestEditor tab = new PhpRequestEditor(api, context);
            tab.setRequestResponse(message);
            RSyntaxTextArea text = locateEditor(tab.uiComponent());
            text.setText("<?php echo 'changed'; ?>");
            tab.setRequestResponse(message);
            assertEquals("<?php echo 'changed'; ?>", text.getText());
            assertTrue(tab.isModified());
        });
    }

    @Test
    void popupApplyWritesBackChangedPhpRequestAndPreservesEnvelope() throws Exception {
        onEdt(() -> {
            setup(EditorMode.DEFAULT);
            try (MockedStatic<ByteArray> factory = mockStatic(ByteArray.class)) {
                factory.when(() -> ByteArray.byteArray(any(byte[].class)))
                        .thenAnswer(inv -> mockByteArray((byte[]) inv.getRawArguments()[0]));
                PhpRequestEditor tab = new PhpRequestEditor(api, context);
                tab.setRequestResponse(message);
                assertTrue(tab.applyWindowText("<?php echo 'popup edited'; ?>"));
                assertTrue(tab.isModified());
                HttpRequest output = tab.getRequest();
                assertEquals("before <?php echo 'popup edited'; ?> after",
                        new String(output.body().getBytes(), StandardCharsets.UTF_8));
            }
        });
    }

    @Test
    void readOnlyContextRemainsReadOnly() throws Exception {
        onEdt(() -> {
            setup(EditorMode.READ_ONLY);
            PhpRequestEditor tab = new PhpRequestEditor(api, context);
            tab.setRequestResponse(message);
            RSyntaxTextArea text = locateEditor(tab.uiComponent());
            assertFalse(text.isEditable());
            assertFalse(tab.isModified());
        });
    }

    private static ByteArray mockByteArray(byte[] contents) {
        ByteArray result = mock(ByteArray.class);
        when(result.getBytes()).thenReturn(contents);
        return result;
    }

    private static RSyntaxTextArea locateEditor(Component component) {
        if (component instanceof RSyntaxTextArea editor) return editor;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                RSyntaxTextArea found = locateEditor(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void onEdt(Runnable action) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try { action.run(); } catch (Throwable problem) { error.set(problem); }
        });
        if (error.get() != null) throw new AssertionError(error.get());
    }
}
