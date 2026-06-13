package com.example.webplaylist.parser

import com.example.webplaylist.model.Episode
import org.jsoup.Jsoup
import java.net.URI

class MyselfBbsEpisodeListParser : EpisodeListParser {
    override fun parse(html: String, seriesUrl: String): List<Episode> {
        val document = Jsoup.parse(html, seriesUrl)
        return document.select("ul.main_list > li")
            .mapNotNull { row ->
                val title = row.select("> a").first()?.text()?.trim().orEmpty()
                val playerHref = row.select("a[data-href]").first()?.attr("data-href")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                if (title.isBlank()) return@mapNotNull null

                Episode(
                    title = title,
                    episodeNumber = episodeNumberRegex.find(title)?.groupValues?.get(1)?.toIntOrNull(),
                    pageUrl = URI(seriesUrl).resolve(playerHref).toString(),
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
