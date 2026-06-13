package com.example.webplaylist.resolver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

class HtmlMediaUrlResolver(
    private val client: OkHttpClient,
    private val vpxResolver: VpxWebSocketMediaUrlResolver,
) : MediaUrlResolver {
    override suspend fun resolve(pageUrl: String, referer: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(pageUrl)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Player page failed: HTTP ${response.code}")
            val html = response.body?.string().orEmpty()
            findDirectMediaUrl(html, pageUrl) ?: vpxResolver.resolveFromHtml(html)
        }
    }

    private fun findDirectMediaUrl(html: String, baseUrl: String): String? {
        val document = Jsoup.parse(html, baseUrl)
        val src = document.select("video[src], video source[src]").firstOrNull()?.absUrl("src")
        if (!src.isNullOrBlank()) return src

        return mediaUrlRegex.find(html)?.value
            ?.replace("\\/", "/")
            ?.trim('"', '\'')
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0"
        val mediaUrlRegex = Regex("""https?:\\?/\\?/[^"'\s<>]+?\.(?:m3u8|mp4|webm)(?:\?[^"'\s<>]*)?""")
    }
}
