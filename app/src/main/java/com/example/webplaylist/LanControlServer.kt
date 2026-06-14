package com.example.webplaylist

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class LanControlServer(
    private val port: Int = DEFAULT_PORT,
    private val onUrlSubmitted: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

    val address: String
        get() = "http://${localIpv4Address() ?: "TV-IP"}:$port"

    fun start() {
        if (!running.compareAndSet(false, true)) return
        serverThread = thread(name = "lan-control-server", isDaemon = true) {
            runCatching {
                ServerSocket(port).use { socket ->
                    serverSocket = socket
                    while (running.get()) {
                        val client = runCatching { socket.accept() }.getOrNull() ?: continue
                        thread(name = "lan-control-client", isDaemon = true) {
                            client.use { handleClient(it) }
                        }
                    }
                }
            }
            running.set(false)
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handleClient(client: java.net.Socket) {
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
        val requestLine = reader.readLine().orEmpty()
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
            }
        }

        val parts = requestLine.split(" ")
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty().substringBefore("?")
        if (method == "GET" && path == "/") {
            client.writeResponse(200, "text/html; charset=utf-8", controlPageHtml())
            return
        }

        if (method == "POST" && path == "/api/open") {
            val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val bodyChars = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val count = reader.read(bodyChars, read, contentLength - read)
                if (count <= 0) break
                read += count
            }
            val params = parseFormEncoded(String(bodyChars, 0, read))
            val url = params["url"].orEmpty().trim()
            if (url.isBlank()) {
                client.writeResponse(400, "text/plain; charset=utf-8", "Missing URL")
                return
            }
            onUrlSubmitted(url)
            client.writeResponse(200, "text/plain; charset=utf-8", "Sent")
            return
        }

        client.writeResponse(404, "text/plain; charset=utf-8", "Not found")
    }

    private fun java.net.Socket.writeResponse(status: Int, contentType: String, body: String) {
        val statusText = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "OK"
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        getOutputStream().write(
            buildString {
                append("HTTP/1.1 $status $statusText\r\n")
                append("Content-Type: $contentType\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Connection: close\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("\r\n")
            }.toByteArray(StandardCharsets.UTF_8),
        )
        getOutputStream().write(bytes)
    }

    private fun parseFormEncoded(body: String): Map<String, String> {
        if (body.isBlank()) return emptyMap()
        return body.split("&")
            .mapNotNull { pair ->
                val separator = pair.indexOf('=')
                if (separator < 0) return@mapNotNull null
                val key = pair.substring(0, separator).formDecode()
                val value = pair.substring(separator + 1).formDecode()
                key to value
            }
            .toMap()
    }

    private fun String.formDecode(): String {
        return URLDecoder.decode(this, StandardCharsets.UTF_8.name())
    }

    private fun controlPageHtml(): String {
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Web Playlist</title>
              <style>
                :root {
                  color-scheme: light dark;
                  --bg: #101418;
                  --panel: #ffffff;
                  --field: #e8eef5;
                  --text: #111827;
                  --muted: #64748b;
                  --accent: #0f766e;
                  --accent-dark: #115e59;
                }
                * { box-sizing: border-box; }
                body {
                  margin: 0;
                  min-height: 100vh;
                  display: grid;
                  place-items: center;
                  padding: 24px;
                  font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                  background: #eef2f6;
                  color: var(--text);
                }
                main {
                  width: min(100%, 560px);
                  background: var(--panel);
                  border-radius: 8px;
                  padding: 16px;
                }
                h1 {
                  margin: 0 0 14px;
                  font-size: 20px;
                  line-height: 1.2;
                  font-weight: 700;
                }
                form {
                  display: flex;
                  gap: 8px;
                }
                input {
                  min-width: 0;
                  flex: 1;
                  height: 56px;
                  border: 0;
                  border-radius: 6px;
                  padding: 0 16px;
                  font: inherit;
                  font-size: 16px;
                  background: var(--field);
                  color: var(--text);
                  outline: none;
                }
                input:focus {
                  box-shadow: 0 0 0 3px rgba(15, 118, 110, 0.14);
                }
                button {
                  flex: 0 0 92px;
                  height: 56px;
                  border: 0;
                  border-radius: 6px;
                  padding: 0 14px;
                  font: inherit;
                  font-weight: 700;
                  color: white;
                  background: var(--accent);
                }
                button:active { background: var(--accent-dark); }
                #status {
                  min-height: 20px;
                  margin: 12px 0 0;
                  color: var(--muted);
                  font-size: 14px;
                }
                @media (max-width: 520px) {
                  main { padding: 14px; }
                }
                @media (max-width: 360px) {
                  form { flex-direction: column; }
                  button {
                    width: 100%;
                    flex-basis: auto;
                  }
                }
                @media (prefers-color-scheme: dark) {
                  body { background: var(--bg); }
                  main {
                    --panel: #171d23;
                    --field: #26313b;
                    --text: #f8fafc;
                    --muted: #94a3b8;
                    box-shadow: none;
                  }
                  input {
                    background: #0f151b;
                    color: var(--text);
                  }
                }
              </style>
            </head>
            <body>
              <main>
                <h1>Send to TV</h1>
                <form id="sender" method="post" action="/api/open">
                  <input id="url" name="url" type="url" inputmode="url" autocomplete="url" placeholder="Paste playlist URL" required autofocus>
                  <button type="submit">Send</button>
                </form>
                <p id="status"></p>
              </main>
              <script>
                const form = document.getElementById('sender');
                const input = document.getElementById('url');
                const status = document.getElementById('status');
                form.addEventListener('submit', async (event) => {
                  event.preventDefault();
                  status.textContent = 'Sending...';
                  const body = new URLSearchParams({ url: input.value.trim() });
                  try {
                    const response = await fetch('/api/open', { method: 'POST', body });
                    if (!response.ok) throw new Error(await response.text());
                    status.textContent = 'Sent to TV.';
                  } catch (error) {
                    status.textContent = error.message || 'Could not send.';
                  }
                });
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private companion object {
        const val DEFAULT_PORT = 8765
    }
}

private fun localIpv4Address(): String? {
    return NetworkInterface.getNetworkInterfaces()
        .asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress }
        ?.hostAddress
}
