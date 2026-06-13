package com.example.webplaylist.site

import com.example.webplaylist.model.Episode
import com.example.webplaylist.model.EpisodeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI

class NnyySiteAdapter(
    private val client: OkHttpClient,
) : SiteAdapter {
    override val id: String = "nnyy"
    override val displayName: String = "Nnyy"

    override fun supports(url: String): Boolean {
        return seriesUrlPattern.matches(url)
    }

    override fun normalizeUrl(input: String): String {
        return when {
            input.startsWith("https://") || input.startsWith("http://") -> input
            input.startsWith("nnyy.in/") -> "https://$input"
            input.startsWith("/dongman/") -> "https://nnyy.in$input"
            input.startsWith("dongman/") -> "https://nnyy.in/$input"
            else -> input
        }
    }

    override fun parseTitle(html: String, seriesUrl: String): String {
        val document = Jsoup.parse(html, seriesUrl)
        return document.selectFirst("h1.product-title")?.ownText()?.trim()
            ?: document.selectFirst("title")?.text()?.substringBefore(" - ")?.trim()
            ?: seriesUrl
    }

    override fun parseEpisodes(html: String, seriesUrl: String): List<Episode> {
        val seriesId = seriesId(seriesUrl)
        val baseUri = URI(seriesUrl)
        return Jsoup.parse(html, seriesUrl).select("li.play-btn[ep_slug]")
            .mapNotNull { item ->
                val title = item.text().trim().ifBlank { return@mapNotNull null }
                val slug = item.attr("ep_slug").trim().ifBlank { return@mapNotNull null }
                val episodeNumber = episodeNumberRegex.find(title)?.groupValues?.get(1)?.toIntOrNull()
                val sourceUrl = baseUri.resolve("/_gp/$seriesId/$slug").toString()
                Episode(
                    title = title,
                    episodeNumber = episodeNumber,
                    pageUrl = sourceUrl,
                    sources = listOf(EpisodeSource("Nnyy", sourceUrl)),
                )
            }
            .let { episodes ->
                if (episodes.all { it.episodeNumber != null }) {
                    episodes.sortedBy { it.episodeNumber }
                } else {
                    episodes
                }
            }
    }

    override suspend fun resolveEpisode(
        episode: Episode,
        seriesUrl: String,
        sourceStartIndex: Int,
    ): ResolvedMedia = withContext(Dispatchers.IO) {
        val sourceUrl = episode.sources.firstOrNull()?.pageUrl ?: episode.pageUrl
        val request = Request.Builder()
            .url(sourceUrl)
            .header("User-Agent", USER_AGENT)
            .header("Referer", seriesUrl)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Nnyy source list failed: HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            val plays = JSONObject(body).getJSONArray("video_plays")
            for (index in sourceStartIndex until plays.length()) {
                val play = plays.getJSONObject(index)
                val mediaUrl = play.optString("play_data").trim()
                if (mediaUrl.isNotBlank()) {
                    return@withContext ResolvedMedia(mediaUrl, index)
                }
            }
        }
        error("No playable Nnyy sources found")
    }

    private fun seriesId(url: String): String {
        return seriesUrlPattern.find(url)?.groupValues?.get(1)
            ?: error("Unsupported Nnyy URL")
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0"
        val seriesUrlPattern = Regex("""https?://(?:www\.)?nnyy\.in/dongman/(\d+)\.html""")
        val episodeNumberRegex = Regex("""第\s*(\d+)\s*集""")
    }
}
