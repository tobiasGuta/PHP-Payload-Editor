# PHP Payload Editor

A Burp Suite extension that adds a dedicated **PHP Editor** tab to HTTP request editors, including **Repeater**, so PHP payloads can be edited without navigating the raw multipart request.

![Java 21](https://img.shields.io/badge/Java-21-blue) ![Burp Montoya](https://img.shields.io/badge/Montoya-2026.7-orange) ![Build](https://github.com/tobiasGuta/PHP-Payload-Editor/actions/workflows/build-and-release.yml/badge.svg)

## Download

**[Download the latest JAR from GitHub Releases](https://github.com/tobiasGuta/PHP-Payload-Editor/releases)**.

Every push to **main** automatically runs the tests, bundles a JAR, and publishes a **development pre-release** in the Releases section. Pushing a tag beginning with v (for example v0.1.3) publishes a versioned release. The CI run also retains a downloadable build artifact.

## Install in Burp Suite

1. Download php-payload-editor-0.1.3.jar from Releases.
2. Open **Extensions → Installed → Add** in Burp Suite.
3. Select **Extension type: Java**, then choose the downloaded JAR.
4. Open **Repeater** with a request whose body contains `<?php` or `<?=` and select the **PHP Editor** request tab.
5. Edit the PHP source directly or use **Edit in Window → Apply Changes**. The bottom status should change to **1 block(s) modified**. Switch to Raw or click Send for Burp to use the modified request.
   If your Burp build does not synchronize edits from custom tabs, **Edit in Window → Open in Repeater (new tab)** creates a new Repeater request with the patched PHP without sending it.
6. You can type directly in the PHP Editor. If keyboard focus is elsewhere, click **Edit in Window** to open a standalone PHP code editor. Select **Apply Changes** to return the edited PHP to this request, or **Open in Repeater (new tab)** to generate a patched request in a new tab without sending it. The editor follows Burp's current dark/light theme when opened. You can switch it manually with **Theme → Dark / Light**, or select **Follow Burp**.

Requires a recent Burp Suite version compatible with **Montoya API 2026.7** and Java 21.

## Features

- Automatic detection of `<?php` and `<?=` code blocks in raw request bodies and multipart/form-data parts.
- Syntax highlighting, line numbers, bracket matching, indentation, and folding using RSyntaxTextArea.
- **Dark mode now follows Burp Suite by default**: dark editor canvas, matching gutter, caret, selection, and readable PHP syntax highlighting. The toolbar's **Theme** dropdown also lets you override with Dark or Light.
- Select between multiple detected PHP blocks using a dropdown; each edit is retained.
- Preserves multipart headers, boundaries, image prefixes such as `GIF89a`, and non-PHP binary bytes.
- Changes only the selected PHP byte ranges; Montoya's `withBody` handles the new Content-Length.
- Respects read-only HTTP editor contexts.
- Edit PHP directly in Repeater, or click **Edit in Window** for a large popup with explicit **Apply Changes**, **Cancel**, and **Open in Repeater (new tab)** buttons. If Burp refreshes a request or its response while you are working, uncommitted text changes for that same request are preserved.
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

The self-contained extension is generated as `target/php-payload-editor-0.1.3.jar`. The RSyntaxTextArea dependency is bundled into the JAR; Burp supplies the Montoya API.

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
