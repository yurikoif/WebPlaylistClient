package com.example.webplaylist.data

import android.content.Context
import android.util.Base64

data class SavedSeries(
    val url: String,
    val title: String,
)

class PlaybackProgressStore(context: Context) {
    private val prefs = context.getSharedPreferences("playback_progress", Context.MODE_PRIVATE)

    fun lastSeriesUrl(): String? = prefs.getString(KEY_SERIES_URL, null)

    fun savedSeries(): List<SavedSeries> {
        return prefs.getString(KEY_SAVED_SERIES_URLS, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?.map { url ->
                SavedSeries(
                    url = url,
                    title = prefs.getString(titleKey(url), null)?.takeIf { it.isNotBlank() } ?: url,
                )
            }
            .orEmpty()
    }

    fun lastEpisodeIndex(seriesUrl: String): Int {
        val key = episodeKey(seriesUrl)
        return if (prefs.contains(key)) prefs.getInt(key, 0) else 0
    }

    fun lastPositionMs(seriesUrl: String): Long {
        val key = positionKey(seriesUrl)
        return if (prefs.contains(key)) prefs.getLong(key, 0L) else 0L
    }

    fun saveSeries(url: String, title: String? = null) {
        val urls = buildList {
            add(url)
            addAll(savedSeries().map { it.url }.filterNot { it == url })
        }
        prefs.edit()
            .putString(KEY_SERIES_URL, url)
            .putString(KEY_SAVED_SERIES_URLS, urls.joinToString("\n"))
            .apply {
                if (!title.isNullOrBlank()) putString(titleKey(url), title)
            }
            .apply()
    }

    fun deleteSeries(url: String) {
        val urls = savedSeries().map { it.url }.filterNot { it == url }
        prefs.edit()
            .putString(KEY_SAVED_SERIES_URLS, urls.joinToString("\n"))
            .remove(titleKey(url))
            .apply()
    }

    fun saveProgress(seriesUrl: String, episodeIndex: Int, positionMs: Long) {
        prefs.edit()
            .putInt(episodeKey(seriesUrl), episodeIndex)
            .putLong(positionKey(seriesUrl), positionMs)
            .apply()
    }

    private fun episodeKey(seriesUrl: String): String = "$KEY_EPISODE_INDEX:${encodedUrl(seriesUrl)}"

    private fun positionKey(seriesUrl: String): String = "$KEY_POSITION_MS:${encodedUrl(seriesUrl)}"

    private fun titleKey(seriesUrl: String): String = "$KEY_SERIES_TITLE:${encodedUrl(seriesUrl)}"

    private fun encodedUrl(seriesUrl: String): String {
        return Base64.encodeToString(
            seriesUrl.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP or Base64.URL_SAFE,
        )
    }

    private companion object {
        const val KEY_SERIES_URL = "series_url"
        const val KEY_SAVED_SERIES_URLS = "saved_series_urls"
        const val KEY_SERIES_TITLE = "series_title"
        const val KEY_EPISODE_INDEX = "episode_index"
        const val KEY_POSITION_MS = "position_ms"
    }
}
