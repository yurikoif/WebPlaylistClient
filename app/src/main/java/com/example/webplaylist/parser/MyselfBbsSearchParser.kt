package com.example.webplaylist.parser

import com.example.webplaylist.model.SeriesSearchResult
import org.jsoup.Jsoup

class MyselfBbsSearchParser {
    fun parse(html: String, baseUrl: String): List<SeriesSearchResult> {
        val document = Jsoup.parse(html, baseUrl)
        val seen = linkedSetOf<String>()
        return document.select("a[href]")
            .mapNotNull { anchor ->
                val href = anchor.attr("href")
                if (!threadPattern.containsMatchIn(href)) return@mapNotNull null
                val url = anchor.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val title = anchor.attr("title").ifBlank { anchor.text() }
                    .replace(Regex("""\s+"""), " ")
                    .trim()
                if (title.isBlank() || !seen.add(url)) return@mapNotNull null
                SeriesSearchResult(title, url)
            }
    }

    private companion object {
        val threadPattern = Regex("""thread-\d+-\d+-\d+\.html""")
    }
}

