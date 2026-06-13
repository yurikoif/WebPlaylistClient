package com.example.webplaylist.data

import android.content.Context

class PlaybackProgressStore(context: Context) {
    private val prefs = context.getSharedPreferences("playback_progress", Context.MODE_PRIVATE)

    fun lastSeriesUrl(): String? = prefs.getString(KEY_SERIES_URL, null)
    fun lastEpisodeIndex(): Int = prefs.getInt(KEY_EPISODE_INDEX, 0)
    fun lastPositionMs(): Long = prefs.getLong(KEY_POSITION_MS, 0L)

    fun saveSeries(url: String) {
        prefs.edit().putString(KEY_SERIES_URL, url).apply()
    }

    fun saveProgress(episodeIndex: Int, positionMs: Long) {
        prefs.edit()
            .putInt(KEY_EPISODE_INDEX, episodeIndex)
            .putLong(KEY_POSITION_MS, positionMs)
            .apply()
    }

    private companion object {
        const val KEY_SERIES_URL = "series_url"
        const val KEY_EPISODE_INDEX = "episode_index"
        const val KEY_POSITION_MS = "position_ms"
    }
}

