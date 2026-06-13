package com.example.webplaylist.site

import com.example.webplaylist.model.Episode

interface SiteAdapter {
    val id: String
    val displayName: String

    fun supports(url: String): Boolean

    fun normalizeUrl(input: String): String

    fun parseTitle(html: String, seriesUrl: String): String

    fun parseEpisodes(html: String, seriesUrl: String): List<Episode>

    suspend fun resolveEpisode(episode: Episode, seriesUrl: String, sourceStartIndex: Int = 0): ResolvedMedia
}

data class ResolvedMedia(
    val url: String,
    val sourceIndex: Int,
)
