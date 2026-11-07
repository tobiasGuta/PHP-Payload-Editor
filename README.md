# PHP Payload Editor

A Burp Suite extension that adds a dedicated **PHP Editor** tab to HTTP request editors, including **Repeater**, so PHP payloads can be edited without navigating the raw multipart request.

![Java 21](https://img.shields.io/badge/Java-21-blue) ![Burp Montoya](https://img.shields.io/badge/Montoya-2026.7-orange) ![Build](https://github.com/tobiasGuta/PHP-Payload-Editor/actions/workflows/build-and-release.yml/badge.svg)

## Download

**[Download the latest JAR from GitHub Releases](https://github.com/tobiasGuta/PHP-Payload-Editor/releases)**.

Every push to **main** automatically runs the tests, bundles a JAR, and publishes a **development pre-release** in the Releases section. Pushing a tag beginning with v (for example v0.1.4) publishes a versioned release. The CI run also retains a downloadable build artifact.

## Install in Burp Suite

1. Download php-payload-editor-0.1.4.jar from Releases.
2. Open **Extensions → Installed → Add** in Burp Suite.
3. Select **Extension type: Java**, then choose the downloaded JAR.
4. Open **Repeater** with a request whose body contains `<?php` or `<?=` and select the **PHP Editor** request tab.
5. In **Edit PHP (native)**, type directly into Burp's built-in editor (the default sub-tab). Use **Highlighted preview** to inspect the colors.
6. To independently verify request write-back, click **Insert test comment**. The request should become modified even without keyboard input; remove the comment before sending if you do not want it.
7. **Open edited copy** opens a new Repeater tab containing the patched request, without sending it. The **Plain editor window** provides a separate, native Swing JTextArea with Apply controls.
The built-in editor and the highlighted preview both follow Burp's current theme. The preview is intentionally read-only; select **Edit PHP (native)** for input.

Requires a recent Burp Suite version compatible with **Montoya API 2026.7** and Java 21.

## Features

- Automatic detection of `<?php` and `<?=` code blocks in raw request bodies and multipart/form-data parts.
- **Editable native Burp editor (default):** uses Burp's own RawEditor to handle typing, clipboard, focus, searching, and theme.
- **Highlighted preview:** a separate read-only PHP syntax preview with line numbers, folding, and Burp-matched dark mode; open it after editing to see highlighted PHP.
- **Dark mode now follows Burp Suite by default**: dark editor canvas, matching gutter, caret, selection, and readable PHP syntax highlighting. The toolbar's **Theme** dropdown also lets you override with Dark or Light.
- Select between multiple detected PHP blocks using a dropdown; each edit is retained.
- Preserves multipart headers, boundaries, image prefixes such as `GIF89a`, and non-PHP binary bytes.
- Changes only the selected PHP byte ranges; Montoya's `withBody` handles the new Content-Length.
- Respects read-only HTTP editor contexts.
- Edit PHP using Burp's built-in RawEditor, not a third-party code widget. Use the **Plain editor window** as a separate fallback with **Apply changes** and **Apply + Open new Repeater tab**.
- The **Insert test comment** button is a no-keyboard diagnostic. It proves whether request reconstruction works independently of keyboard capture. If Burp refreshes a request or its response while you are working, uncommitted text changes for that same request are preserved.
- No network calls, payload execution, or requests initiated by the extension.

### Example

In a multipart upload like this:

```http
Content-Disposition: form-data; name="logo"; filename="version2.png.php"
Content-Type: image/jpeg

GIF89a
<style>body{font-size:0}</style>
<?php echo "Hello"; ?>
```

The tab displays only the PHP block; the `GIF89a` header, HTML, and multipart envelope are retained unmodified when you edit and send.

## Build locally

Java 21 and Maven are required:

```bash
mvn clean verify
```

The self-contained extension is generated as `target/php-payload-editor-0.1.4.jar`. The RSyntaxTextArea dependency is bundled into the JAR; Burp supplies the Montoya API.

## Limitations

- Only PHP blocks starting with `<?php` or `<?=` are recognized. The optional PHP short opening tag `<?` is deliberately excluded to avoid XML false positives.
- Block detection uses the first closing `?>` marker, not a complete PHP lexer. Markers inside a PHP string/comment may confuse the locator.
- The editor modifies PHP text **inside outgoing HTTP requests**, not files stored on a remote server.
- For a non-UTF-8 PHP snippet, ISO-8859-1 is used to preserve bytes; unrepresentable edited characters are rejected rather than silently corrupting the request.
- Multipart boundaries need to be valid and match the Content-Type header.

## Development and security

Developed with the [PortSwigger Montoya API](https://github.com/PortSwigger/burp-extensions-montoya-api) and [RSyntaxTextArea](https://github.com/bobbylight/RSyntaxTextArea). Bug reports and pull requests are welcome.

Use only on systems you own or are authorized to test.

MIT licensed. Copyright © 2026 TobiasGuta.
