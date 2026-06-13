package com.example.webplaylist.model

data class Episode(
    val title: String,
    val episodeNumber: Int?,
    val pageUrl: String,
    val mediaUrl: String? = null,
    val sources: List<EpisodeSource> = emptyList(),
)

data class EpisodeSource(
    val label: String,
    val pageUrl: String,
    val mediaUrl: String? = null,
)
