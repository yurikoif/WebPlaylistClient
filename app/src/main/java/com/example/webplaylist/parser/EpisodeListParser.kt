package com.example.webplaylist.parser

import com.example.webplaylist.model.Episode

interface EpisodeListParser {
    fun parse(html: String, seriesUrl: String): List<Episode>
    fun parseTitle(html: String): String
}

