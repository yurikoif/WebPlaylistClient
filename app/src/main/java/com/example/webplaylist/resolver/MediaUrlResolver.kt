package com.example.webplaylist.resolver

interface MediaUrlResolver {
    suspend fun resolve(pageUrl: String, referer: String): String
}

