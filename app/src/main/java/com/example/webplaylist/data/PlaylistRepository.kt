package com.example.webplaylist.data

import com.example.webplaylist.model.Episode
import com.example.webplaylist.model.SeriesSearchResult
import com.example.webplaylist.parser.EpisodeListParser
import com.example.webplaylist.parser.MyselfBbsSearchParser
import com.example.webplaylist.resolver.MediaUrlResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

class PlaylistRepository(
    private val client: OkHttpClient,
    private val episodeParser: EpisodeListParser,
    private val searchParser: MyselfBbsSearchParser,
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

    suspend fun searchSeries(query: String): List<SeriesSearchResult> = withContext(Dispatchers.IO) {
        val homeHtml = get("https://myself-bbs.com/portal.php")
        val formHash = Regex("""name=["']formhash["']\s+value=["']([^"']+)["']""")
            .find(homeHtml)
            ?.groupValues
            ?.get(1)

        val bodyBuilder = FormBody.Builder()
            .add("mod", "forum")
            .add("srchtype", "title")
            .add("srhfid", "0")
            .add("srhlocality", "portal::index")
            .add("srchtxt", query)
            .add("searchsubmit", "true")
        if (!formHash.isNullOrBlank()) bodyBuilder.add("formhash", formHash)

        val request = Request.Builder()
            .url("https://myself-bbs.com/search.php?searchsubmit=yes")
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://myself-bbs.com/portal.php")
            .post(bodyBuilder.build())
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Search failed: HTTP ${response.code}")
            searchParser.parse(response.body?.string().orEmpty(), "https://myself-bbs.com/")
        }
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

