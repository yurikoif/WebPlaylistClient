package com.example.webplaylist.model

data class Episode(
    val title: String,
    val episodeNumber: Int?,
    val pageUrl: String,
    val mediaUrl: String? = null,
)

