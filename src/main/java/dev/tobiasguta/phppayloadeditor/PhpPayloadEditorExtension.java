package dev.tobiasguta.phppayloadeditor;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpRequestEditor;
import burp.api.montoya.ui.editor.extension.HttpRequestEditorProvider;

public final class PhpPayloadEditorExtension implements BurpExtension {
    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("PHP Payload Editor");
        api.userInterface().registerHttpRequestEditorProvider(new HttpRequestEditorProvider() {
            @Override
            public ExtensionProvidedHttpRequestEditor provideHttpRequestEditor(
                    EditorCreationContext context) {
                return new PhpRequestEditor(api, context);
            }
        });
        api.logging().logToOutput("PHP Payload Editor loaded: select the PHP Editor request tab.");
    }
}
