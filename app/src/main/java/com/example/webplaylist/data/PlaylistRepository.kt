package com.example.webplaylist.data

import com.example.webplaylist.model.Episode
import com.example.webplaylist.parser.EpisodeListParser
import com.example.webplaylist.resolver.MediaUrlResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class PlaylistRepository(
    private val client: OkHttpClient,
    private val episodeParser: EpisodeListParser,
    private val resolver: MediaUrlResolver,
) {
    suspend fun loadSeries(url: String): LoadedSeries = withContext(Dispatchers.IO) {
        val html = get(url)
        LoadedSeries(
            url = url,
            title = episodeParser.parseTitle(html).ifBlank { url },
            episodes = episodeParser.parse(html, url),
        )
    }

    suspend fun resolveEpisode(episode: Episode, seriesUrl: String): String {
        return episode.mediaUrl ?: resolver.resolve(episode.pageUrl, seriesUrl)
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Request failed: HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0"
    }
}

data class LoadedSeries(
    val url: String,
    val title: String,
    val episodes: List<Episode>,
)
