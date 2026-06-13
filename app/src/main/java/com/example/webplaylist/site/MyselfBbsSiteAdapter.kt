package com.example.webplaylist.site

import com.example.webplaylist.model.Episode
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

    override suspend fun resolveEpisode(episode: Episode, seriesUrl: String): String {
        return episode.mediaUrl ?: mediaResolver.resolve(episode.pageUrl, seriesUrl)
    }

    private companion object {
        val seriesUrlPattern = Regex("""https?://(?:www\.)?myself-bbs\.com/thread-\d+-\d+-\d+\.html""")
    }
}
