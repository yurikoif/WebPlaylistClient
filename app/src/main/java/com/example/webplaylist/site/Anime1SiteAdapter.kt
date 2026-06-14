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
import java.net.URI

class Anime1SiteAdapter(
    private val client: OkHttpClient,
) : SiteAdapter {
    override val id: String = "anime1"
    override val displayName: String = "Anime1"

    override fun supports(url: String): Boolean {
        return seriesUrlPattern.matches(url)
    }

    override fun normalizeUrl(input: String): String {
        return when {
            input.startsWith("https://") || input.startsWith("http://") -> input
            input.startsWith("anime1.me/") -> "https://$input"
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
        val document = Jsoup.parse(html, seriesUrl)
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
                    ?: return@mapNotNull null
                val episodeNumber = episodeNumberRegex.find(title)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                Episode(
                    title = title,
                    episodeNumber = episodeNumber,
                    pageUrl = pageUrl,
                    sources = listOf(
                        EpisodeSource(
                            label = "Anime1",
                            pageUrl = pageUrl,
                            requestData = apiRequest,
                        ),
                    ),
                )
            }
            .let { episodes ->
                if (episodes.all { it.episodeNumber != null }) {
                    episodes.sortedBy { it.episodeNumber }
                } else {
                    episodes.asReversed()
                }
            }
    }

    override suspend fun resolveEpisode(
        episode: Episode,
        seriesUrl: String,
        sourceStartIndex: Int,
    ): ResolvedMedia = withContext(Dispatchers.IO) {
        val source = episode.sources.drop(sourceStartIndex).firstOrNull()
            ?: error("No Anime1 source found")
        val apiRequest = source.requestData ?: error("Anime1 source is missing API payload")
        val request = Request.Builder()
            .url(API_URL)
            .header("User-Agent", USER_AGENT)
            .header("Referer", episode.pageUrl)
            .header("Origin", "https://anime1.me")
            .post("d=$apiRequest".toRequestBody(FORM_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Anime1 API failed: HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            val mediaUrl = JSONObject(body).opt("s").toMediaUrl()
            if (mediaUrl.isBlank()) error("No playable Anime1 source found")
            ResolvedMedia(mediaUrl, sourceStartIndex)
        }
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

    private companion object {
        const val API_URL = "https://v.anime1.me/api"
        const val USER_AGENT = "Mozilla/5.0"
        val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()
        val seriesUrlPattern = Regex("""https?://(?:www\.)?anime1\.me/category/.+""")
        val episodeNumberRegex = Regex("""\[(\d+)""")
    }
}
