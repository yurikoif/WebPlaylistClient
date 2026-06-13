package com.example.webplaylist.parser

import com.example.webplaylist.model.Episode
import com.example.webplaylist.model.EpisodeSource
import org.jsoup.Jsoup
import java.net.URI

class MyselfBbsEpisodeListParser : EpisodeListParser {
    override fun parse(html: String, seriesUrl: String): List<Episode> {
        val document = Jsoup.parse(html, seriesUrl)
        return document.select("ul.main_list > li")
            .mapNotNull { row ->
                val title = row.select("> a").first()?.text()?.trim().orEmpty()
                val sources = row.select("a[data-href]")
                    .mapNotNull { link ->
                        val href = link.attr("data-href").cleanHref()
                            .takeIf { it.isNotBlank() }
                            ?.takeUnless { it.contains("xfplay", ignoreCase = true) }
                            ?: return@mapNotNull null
                        val pageUrl = runCatching { URI(seriesUrl).resolve(href).toString() }
                            .getOrNull()
                            ?: return@mapNotNull null
                        EpisodeSource(
                            label = link.text().trim().ifBlank { "Source" },
                            pageUrl = pageUrl,
                        )
                    }
                    .filter { it.pageUrl.startsWith("http://") || it.pageUrl.startsWith("https://") }
                    .preferInternalPlayer()
                val playerUrl = sources.firstOrNull()?.pageUrl
                    ?: return@mapNotNull null
                if (title.isBlank()) return@mapNotNull null

                Episode(
                    title = title,
                    episodeNumber = episodeNumberRegex.find(title)?.groupValues?.get(1)?.toIntOrNull(),
                    pageUrl = playerUrl,
                    sources = sources,
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

    override fun parseTitle(html: String): String {
        val document = Jsoup.parse(html)
        return document.selectFirst("title")?.text()
            ?.substringBefore(" - Myself")
            ?.trim()
            .orEmpty()
    }

    private companion object {
        val episodeNumberRegex = Regex("""第\s*(\d+)\s*話""")
    }
}

private fun String.cleanHref(): String {
    return trim().trim('\r', '\n', '\t')
}

private fun List<EpisodeSource>.preferInternalPlayer(): List<EpisodeSource> {
    return sortedBy { source ->
        if (source.label == INTERNAL_SOURCE_LABEL) 0 else 1
    }
}

private const val INTERNAL_SOURCE_LABEL = "站內"
