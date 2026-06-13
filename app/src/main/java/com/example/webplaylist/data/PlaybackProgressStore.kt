package com.example.webplaylist.data

import android.content.Context
import android.util.Base64

class PlaybackProgressStore(context: Context) {
    private val prefs = context.getSharedPreferences("playback_progress", Context.MODE_PRIVATE)

    fun lastSeriesUrl(): String? = prefs.getString(KEY_SERIES_URL, null)

    fun lastEpisodeIndex(seriesUrl: String): Int {
        return prefs.getInt(episodeKey(seriesUrl), prefs.getInt(KEY_EPISODE_INDEX, 0))
    }

    fun lastPositionMs(seriesUrl: String): Long {
        return prefs.getLong(positionKey(seriesUrl), prefs.getLong(KEY_POSITION_MS, 0L))
    }

    fun saveSeries(url: String) {
        prefs.edit().putString(KEY_SERIES_URL, url).apply()
    }

    fun saveProgress(seriesUrl: String, episodeIndex: Int, positionMs: Long) {
        prefs.edit()
            .putInt(episodeKey(seriesUrl), episodeIndex)
            .putLong(positionKey(seriesUrl), positionMs)
            .apply()
    }

    private fun episodeKey(seriesUrl: String): String = "$KEY_EPISODE_INDEX:${encodedUrl(seriesUrl)}"

    private fun positionKey(seriesUrl: String): String = "$KEY_POSITION_MS:${encodedUrl(seriesUrl)}"

    private fun encodedUrl(seriesUrl: String): String {
        return Base64.encodeToString(
            seriesUrl.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP or Base64.URL_SAFE,
        )
    }

    private companion object {
        const val KEY_SERIES_URL = "series_url"
        const val KEY_EPISODE_INDEX = "episode_index"
        const val KEY_POSITION_MS = "position_ms"
    }
}
