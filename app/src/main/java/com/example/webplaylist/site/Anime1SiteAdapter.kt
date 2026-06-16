package com.example.webplaylist.site

import com.example.webplaylist.model.Episode
import com.example.webplaylist.model.EpisodeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

class Anime1SiteAdapter(
    private val client: OkHttpClient,
) : PaginatedSiteAdapter {
    override val id: String = "anime1"
    override val displayName: String = "Anime1"

    override fun supports(url: String): Boolean {
        return seriesUrlPattern.matches(url)
    }

    override fun normalizeUrl(input: String): String {
        return when {
            input.startsWith("https://") || input.startsWith("http://") -> input
            input.startsWith("anime1.me/") -> "https://$input"
            input.startsWith("anime1.in/") -> "https://$input"
            input.startsWith("/category/") -> "https://anime1.me$input"
            input.startsWith("category/") -> "https://anime1.me/$input"
            else -> input
        }
    }

    override fun parseTitle(html: String, seriesUrl: String): String {
        val document = Jsoup.parse(html, seriesUrl)
        return document.selectFirst("h1.page-title")?.text()?.trim()
            ?: document.selectFirst("""meta[property=og:title]""")?.attr("content")
                ?.removeSuffix("全集")
                ?.trim()
            ?: document.selectFirst("title")?.text()?.substringBefore(" - ")?.trim()
            ?: seriesUrl
    }

    override fun parseEpisodes(html: String, seriesUrl: String): List<Episode> {
        return parseEpisodesFromDocument(Jsoup.parse(html, seriesUrl), seriesUrl)
    }

    override suspend fun parseEpisodesWithPagination(html: String, seriesUrl: String): List<Episode> = withContext(Dispatchers.IO) {
        val firstDocument = Jsoup.parse(html, seriesUrl)
        val pageUrls = linkedSeriesPageUrls(firstDocument, seriesUrl)
        val documents = buildList {
            add(firstDocument)
            pageUrls.forEach { pageUrl ->
                val request = Request.Builder()
                    .url(pageUrl)
                    .header("User-Agent", USER_AGENT)
                    .build()
                val pageHtml = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@forEach
                    response.body?.string().orEmpty()
                }
                add(Jsoup.parse(pageHtml, pageUrl))
            }
        }
        documents
            .flatMap { document -> parseEpisodesFromDocument(document, document.location().ifBlank { seriesUrl }) }
            .distinctBy { it.pageUrl }
            .sortEpisodes()
    }

    private fun parseEpisodesFromDocument(document: Document, seriesUrl: String): List<Episode> {
        return document.select("article")
            .mapNotNull { article ->
                val titleElement = article.selectFirst("h2.entry-title a") ?: return@mapNotNull null
                val title = titleElement.text().trim().ifBlank { return@mapNotNull null }
                val pageUrl = runCatching {
                    URI(seriesUrl).resolve(titleElement.attr("href").trim()).toString()
                }.getOrNull() ?: return@mapNotNull null
                val apiRequest = article.selectFirst("video[data-apireq]")
                    ?.attr("data-apireq")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                val iframeUrl = article.selectFirst("iframe[src]")
                    ?.attr("src")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { URI(seriesUrl).resolve(it).toString() }.getOrNull() }
                val source = when {
                    apiRequest != null -> EpisodeSource(
                        label = "Anime1",
                        pageUrl = pageUrl,
                        requestData = apiRequest,
                    )
                    iframeUrl != null -> EpisodeSource(
                        label = "Anime1",
                        pageUrl = iframeUrl,
                    )
                    else -> return@mapNotNull null
                }
                val episodeNumber = episodeNumberRegex.find(title)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                Episode(
                    title = title,
                    episodeNumber = episodeNumber,
                    pageUrl = pageUrl,
                    sources = listOf(source),
                )
            }
            .sortEpisodes()
    }

    override suspend fun resolveEpisode(
        episode: Episode,
        seriesUrl: String,
        sourceStartIndex: Int,
    ): ResolvedMedia = withContext(Dispatchers.IO) {
        val source = episode.sources.drop(sourceStartIndex).firstOrNull()
            ?: error("No Anime1 source found")
        source.mediaUrl?.let { return@withContext ResolvedMedia(it, sourceStartIndex) }

        val apiRequest = source.requestData
        if (apiRequest == null) {
            val request = Request.Builder()
                .url(source.pageUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", episode.pageUrl)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Anime1 player iframe failed: HTTP ${response.code}")
                val body = response.body?.string().orEmpty()
                val mediaUrl = Jsoup.parse(body, source.pageUrl)
                    .selectFirst("video source[src], source[src]")
                    ?.attr("abs:src")
                    ?.trim()
                    .orEmpty()
                if (mediaUrl.isBlank()) error("No playable Anime1 iframe source found")
                return@withContext ResolvedMedia(
                    url = mediaUrl,
                    sourceIndex = sourceStartIndex,
                    requestHeaders = mapOf("Referer" to source.pageUrl),
                )
            }
        }
        val request = Request.Builder()
            .url(apiUrlFor(seriesUrl))
            .header("User-Agent", USER_AGENT)
            .header("Referer", episode.pageUrl)
            .header("Origin", originFor(seriesUrl))
            .post("d=$apiRequest".toRequestBody(FORM_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Anime1 API failed: HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            val mediaUrl = JSONObject(body).opt("s").toMediaUrl()
            if (mediaUrl.isBlank()) error("No playable Anime1 source found")
            ResolvedMedia(
                url = mediaUrl,
                sourceIndex = sourceStartIndex,
                requestHeaders = buildMap {
                    put("Referer", source.pageUrl)
                    put("Origin", originFor(seriesUrl))
                    response.headers("Set-Cookie")
                        .toCookieHeader()
                        .takeIf { it.isNotBlank() }
                        ?.let { put("Cookie", it) }
                },
            )
        }
    }

    private fun linkedSeriesPageUrls(document: Document, seriesUrl: String): List<String> {
        val baseSeriesUrl = seriesUrl.substringBefore("/page/").trimEnd('/') + "/"
        return document.select(".pagination a[href], .nav-links a[href], a.page-numbers[href]")
            .mapNotNull { link -> link.absoluteUrl(seriesUrl) }
            .filter { url -> url.startsWith(baseSeriesUrl) && url != seriesUrl.trimEnd('/') }
            .distinct()
    }

    private fun Element.absoluteUrl(baseUrl: String): String? {
        return runCatching { URI(baseUrl).resolve(attr("href").trim()).toString() }.getOrNull()
    }

    private fun Any?.toMediaUrl(): String {
        val rawUrl = when (this) {
            is String -> this
            is JSONArray -> firstSourceUrl()
            else -> ""
        }.trim()
        return when {
            rawUrl.startsWith("//") -> "https:$rawUrl"
            else -> rawUrl
        }
    }

    private fun JSONArray.firstSourceUrl(): String {
        for (index in 0 until length()) {
            val source = optJSONObject(index) ?: continue
            val url = source.optString("src").trim()
            if (url.isNotBlank()) return url
        }
        return ""
    }

    private fun List<String>.toCookieHeader(): String {
        return mapNotNull { cookie ->
            cookie.substringBefore(";").trim().takeIf { it.contains("=") }
        }.joinToString("; ")
    }

    private fun List<Episode>.sortEpisodes(): List<Episode> {
        return if (all { it.episodeNumber != null }) {
            sortedBy { it.episodeNumber }
        } else {
            asReversed()
        }
    }

    private fun apiUrlFor(seriesUrl: String): String {
        return "https://v.${hostFor(seriesUrl)}/api"
    }

    private fun originFor(seriesUrl: String): String {
        return "https://${hostFor(seriesUrl)}"
    }

    private fun hostFor(seriesUrl: String): String {
        return URI(seriesUrl).host?.removePrefix("www.") ?: "anime1.me"
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0"
        val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()
        val seriesUrlPattern = Regex("""https?://(?:www\.)?anime1\.(?:me|in)/(?:category/.+|[^/?#]+/?(?:page/\d+/?){0,1})""")
        val episodeNumberRegex = Regex("""\[(\d+)""")
    }
}
