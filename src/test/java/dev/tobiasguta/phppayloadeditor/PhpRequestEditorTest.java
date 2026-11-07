package dev.tobiasguta.phppayloadeditor;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.Theme;
import burp.api.montoya.ui.UserInterface;
import burp.api.montoya.ui.editor.RawEditor;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.EditorMode;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PhpRequestEditorTest {
    private final MontoyaApi api = mock(MontoyaApi.class);
    private final UserInterface ui = mock(UserInterface.class);
    private final EditorCreationContext context = mock(EditorCreationContext.class);
    private final RawEditor raw = mock(RawEditor.class);
    private final HttpRequest request = mock(HttpRequest.class);
    private final HttpRequestResponse message = mock(HttpRequestResponse.class);
    private final AtomicReference<ByteArray> contents = new AtomicReference<>();

    private void setup(EditorMode mode) {
        when(api.userInterface()).thenReturn(ui);
        when(ui.currentTheme()).thenReturn(Theme.DARK);
        when(ui.createRawEditor()).thenReturn(raw);
        when(raw.uiComponent()).thenReturn(new JPanel());
        when(raw.getContents()).thenAnswer(inv -> contents.get());
        doAnswer(inv -> {
            contents.set(inv.getArgument(0));
            return null;
        }).when(raw).setContents(any(ByteArray.class));
        when(context.editorMode()).thenReturn(mode);
        when(message.request()).thenReturn(request);
        when(request.headerValue("Content-Type")).thenReturn("text/plain");
        ByteArray body = mockByteArray("before <?php echo 1; ?> after".getBytes(StandardCharsets.UTF_8));
        ByteArray full = mockByteArray("POST / HTTP/1.1\r\n\r\nbefore <?php echo 1; ?> after".getBytes(StandardCharsets.UTF_8));
        when(request.body()).thenReturn(body);
        when(request.toByteArray()).thenReturn(full);
        when(request.withBody(any(ByteArray.class))).thenAnswer(inv -> {
            HttpRequest updated = mock(HttpRequest.class);
            ByteArray bytes = inv.getArgument(0);
            when(updated.body()).thenReturn(bytes);
            return updated;
        });
    }

    @Test
    void nativeEditorIsWritableAndItsChangesBecomeHttpRequestBytes() throws Exception {
        onEdt(() -> {
            setup(EditorMode.DEFAULT);
            try (MockedStatic<ByteArray> factory = mockStatic(ByteArray.class)) {
                mockFactory(factory);
                PhpRequestEditor tab = new PhpRequestEditor(api, context);
                tab.setRequestResponse(message);
                verify(raw, atLeastOnce()).setEditable(true);
                assertEquals("<?php echo 1; ?>",
                        new String(contents.get().getBytes(), StandardCharsets.UTF_8));
                // Simulates the bytes the Burp-native editor returns after typing.
                contents.set(mockByteArray("<?php echo 'changed'; ?>".getBytes(StandardCharsets.UTF_8)));
                assertTrue(tab.isModified());
                HttpRequest patched = tab.getRequest();
                assertEquals("before <?php echo 'changed'; ?> after",
                        new String(patched.body().getBytes(), StandardCharsets.UTF_8));
            }
        });
    }

    @Test
    void programmaticButtonProvesWriteBackWithoutKeyboard() throws Exception {
        onEdt(() -> {
            setup(EditorMode.DEFAULT);
            try (MockedStatic<ByteArray> factory = mockStatic(ByteArray.class)) {
                mockFactory(factory);
                PhpRequestEditor tab = new PhpRequestEditor(api, context);
                tab.setRequestResponse(message);
                JButton button = findButton(tab.uiComponent(), "Insert test comment");
                assertNotNull(button);
                button.doClick();
                assertTrue(tab.isModified());
                assertTrue(new String(tab.getRequest().body().getBytes(),
                        StandardCharsets.UTF_8).contains("/* PHP Payload Editor test */"));
            }
        });
    }

    @Test
    void applyButtonPathSynchronizesNativeEditor() throws Exception {
        onEdt(() -> {
            setup(EditorMode.DEFAULT);
            try (MockedStatic<ByteArray> factory = mockStatic(ByteArray.class)) {
                mockFactory(factory);
                PhpRequestEditor tab = new PhpRequestEditor(api, context);
                tab.setRequestResponse(message);
                assertTrue(tab.applyText("<?php echo 678; ?>"));
                assertTrue(tab.isModified());
                assertEquals("before <?php echo 678; ?> after",
                        new String(tab.getRequest().body().getBytes(), StandardCharsets.UTF_8));
                assertTrue(tab.applyText("<?php echo 1; ?>"));
                assertFalse(tab.isModified());
            }
        });
    }

    @Test
    void burpRefreshOfSameRequestDoesNotEraseDraft() throws Exception {
        onEdt(() -> {
            setup(EditorMode.DEFAULT);
            try (MockedStatic<ByteArray> factory = mockStatic(ByteArray.class)) {
                mockFactory(factory);
                PhpRequestEditor tab = new PhpRequestEditor(api, context);
                tab.setRequestResponse(message);
                contents.set(mockByteArray("<?php echo 999; ?>".getBytes(StandardCharsets.UTF_8)));
                tab.setRequestResponse(message);
                assertEquals("<?php echo 999; ?>",
                        new String(contents.get().getBytes(), StandardCharsets.UTF_8));
                assertTrue(tab.isModified());
            }
        });
    }

    @Test
    void readOnlyContextNeverPermitsWriteBack() throws Exception {
        onEdt(() -> {
            setup(EditorMode.READ_ONLY);
            try (MockedStatic<ByteArray> factory = mockStatic(ByteArray.class)) {
                mockFactory(factory);
                PhpRequestEditor tab = new PhpRequestEditor(api, context);
                tab.setRequestResponse(message);
                verify(raw, atLeastOnce()).setEditable(false);
                assertFalse(tab.applyText("<?php echo 123; ?>"));
                assertFalse(tab.isModified());
            }
        });
    }

    private static void mockFactory(MockedStatic<ByteArray> factory) {
        factory.when(() -> ByteArray.byteArray(any(byte[].class)))
                .thenAnswer(inv -> mockByteArray((byte[]) inv.getRawArguments()[0]));
    }

    private static ByteArray mockByteArray(byte[] contents) {
        ByteArray result = mock(ByteArray.class);
        when(result.getBytes()).thenReturn(contents);
        return result;
    }

    private static JButton findButton(Component component, String title) {
        if (component instanceof JButton button && button.getText().equals(title)) {
            return button;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                JButton found = findButton(child, title);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void onEdt(Runnable action) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try { action.run(); } catch (Throwable failure) { error.set(failure); }
        });
        if (error.get() != null) throw new AssertionError(error.get());
    }
}
