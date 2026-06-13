package com.example.webplaylist.resolver

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class VpxWebSocketMediaUrlResolver(
    private val client: OkHttpClient,
) {
    suspend fun resolveFromHtml(html: String): String {
        val tid = jsStringValue("tid", html) ?: error("VPX tid not found")
        val vid = jsStringValue("vid", html) ?: error("VPX vid not found")
        val id = jsStringValue("id", html).orEmpty()
        val payload = JSONObject()
            .put("tid", tid)
            .put("vid", vid)
            .put("id", id)
            .toString()

        return suspendCancellableCoroutine { continuation ->
            var socket: WebSocket? = null
            val request = Request.Builder()
                .url("wss://v.myself-bbs.com/ws")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(payload)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val json = JSONObject(text)
                        if (json.optString("status") == "ok") {
                            val video = json.optString("video")
                            if (video.isNotBlank()) {
                                if (continuation.isActive) continuation.resume(video)
                                webSocket.close(1000, null)
                                return
                            }
                        }
                        if (continuation.isActive) {
                            continuation.resumeWithException(IllegalStateException("VPX response did not contain a playable video URL"))
                        }
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    } finally {
                        webSocket.close(1000, null)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (continuation.isActive) continuation.resumeWithException(t)
                }
            })

            continuation.invokeOnCancellation {
                socket?.cancel()
            }
        }
    }

    private fun jsStringValue(name: String, html: String): String? {
        return Regex("""\b$name\s*=\s*["']([^"']*)["']""")
            .find(html)
            ?.groupValues
            ?.get(1)
    }
}
