package com.example.webplaylist.site

import com.example.webplaylist.model.Episode
import com.example.webplaylist.model.EpisodeSource
import com.example.webplaylist.parser.MyselfBbsEpisodeListParser
import com.example.webplaylist.resolver.MediaUrlResolver

class MyselfBbsSiteAdapter(
    private val episodeParser: MyselfBbsEpisodeListParser,
    private val mediaResolver: MediaUrlResolver,
) : SiteAdapter {
    override val id: String = "myself-bbs"
    override val displayName: String = "Myself BBS"

    override fun supports(url: String): Boolean {
        return seriesUrlPattern.matches(url)
    }

    override fun normalizeUrl(input: String): String {
        return when {
            input.startsWith("https://") || input.startsWith("http://") -> input
            input.startsWith("myself-bbs.com/") -> "https://$input"
            input.startsWith("thread-") -> "https://myself-bbs.com/$input"
            else -> input
        }
    }

    override fun parseTitle(html: String, seriesUrl: String): String {
        return episodeParser.parseTitle(html).ifBlank { seriesUrl }
    }

    override fun parseEpisodes(html: String, seriesUrl: String): List<Episode> {
        return episodeParser.parse(html, seriesUrl)
    }

    override suspend fun resolveEpisode(
        episode: Episode,
        seriesUrl: String,
        sourceStartIndex: Int,
    ): ResolvedMedia {
        episode.mediaUrl?.let { return ResolvedMedia(it, 0) }

        val sources = episode.sources.takeIf { it.isNotEmpty() }
            ?: listOf(EpisodeSource("Default", episode.pageUrl))
        var lastError: Throwable? = null
        sources.drop(sourceStartIndex).forEachIndexed { offset, source ->
            val sourceIndex = sourceStartIndex + offset
            runCatching { source.mediaUrl ?: mediaResolver.resolve(source.pageUrl, seriesUrl) }
                .onSuccess { return ResolvedMedia(it, sourceIndex) }
                .onFailure { lastError = it }
        }

        throw lastError ?: IllegalStateException("No playable sources found")
    }

    private companion object {
        val seriesUrlPattern = Regex("""https?://(?:www\.)?myself-bbs\.com/thread-\d+-\d+-\d+\.html""")
    }
}
