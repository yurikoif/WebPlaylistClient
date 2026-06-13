package com.example.webplaylist.data

import com.example.webplaylist.model.Episode
import com.example.webplaylist.site.SiteRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class PlaylistRepository(
    private val client: OkHttpClient,
    private val siteRegistry: SiteRegistry,
) {
    suspend fun loadSeries(url: String): LoadedSeries = withContext(Dispatchers.IO) {
        val normalizedUrl = siteRegistry.normalizeUrl(url)
        val adapter = siteRegistry.adapterFor(normalizedUrl)
        val html = get(normalizedUrl)
        LoadedSeries(
            url = normalizedUrl,
            siteId = adapter.id,
            siteName = adapter.displayName,
            title = adapter.parseTitle(html, normalizedUrl),
            episodes = adapter.parseEpisodes(html, normalizedUrl),
        )
    }

    suspend fun resolveEpisode(episode: Episode, seriesUrl: String): String {
        return siteRegistry.adapterFor(seriesUrl).resolveEpisode(episode, seriesUrl)
    }

    fun normalizeUrl(input: String): String {
        return siteRegistry.normalizeUrl(input)
    }

    fun supports(url: String): Boolean {
        return runCatching { siteRegistry.adapterFor(url) }.isSuccess
    }

    fun supportedUrlHint(): String {
        return siteRegistry.supportedUrlHint()
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
    val siteId: String,
    val siteName: String,
    val title: String,
    val episodes: List<Episode>,
)
