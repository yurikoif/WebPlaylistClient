package com.example.webplaylist

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.webplaylist.data.LoadedSeries
import com.example.webplaylist.data.PlaybackProgressStore
import com.example.webplaylist.data.PlaylistRepository
import com.example.webplaylist.model.SeriesSearchResult
import com.example.webplaylist.parser.MyselfBbsEpisodeListParser
import com.example.webplaylist.parser.MyselfBbsSearchParser
import com.example.webplaylist.resolver.HtmlMediaUrlResolver
import com.example.webplaylist.resolver.VpxWebSocketMediaUrlResolver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                WebPlaylistApp()
            }
        }
    }
}

@Composable
private fun WebPlaylistApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val progressStore = remember { PlaybackProgressStore(context) }
    val repository = remember {
        val client = OkHttpClient.Builder().build()
        val vpxResolver = VpxWebSocketMediaUrlResolver(client)
        PlaylistRepository(
            client = client,
            episodeParser = MyselfBbsEpisodeListParser(),
            searchParser = MyselfBbsSearchParser(),
            resolver = HtmlMediaUrlResolver(client, vpxResolver),
        )
    }
    val player = remember { ExoPlayer.Builder(context).build() }

    var query by remember { mutableStateOf("") }
    var series by remember { mutableStateOf<LoadedSeries?>(null) }
    var results by remember { mutableStateOf<List<SeriesSearchResult>>(emptyList()) }
    var selectedEpisode by remember { mutableIntStateOf(progressStore.lastEpisodeIndex()) }
    var status by remember { mutableStateOf("Loading sample series") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var resolvedEpisodeUrl by remember { mutableStateOf<String?>(null) }
    var lastSavedPosition by remember { mutableLongStateOf(progressStore.lastPositionMs()) }

    fun loadSeries(url: String) {
        scope.launch {
            loading = true
            error = null
            status = "Loading series"
            runCatching { repository.loadSeries(url) }
                .onSuccess { loaded ->
                    series = loaded
                    selectedEpisode = if (progressStore.lastSeriesUrl() == loaded.url) {
                        progressStore.lastEpisodeIndex().coerceIn(0, loaded.episodes.lastIndex.coerceAtLeast(0))
                    } else {
                        0
                    }
                    progressStore.saveSeries(loaded.url)
                    status = "${loaded.episodes.size} episodes"
                    resolvedEpisodeUrl = null
                }
                .onFailure {
                    error = it.message ?: "Series load failed"
                    status = "Series load failed"
                }
            loading = false
        }
    }

    fun search() {
        val term = query.trim()
        if (term.isBlank()) return
        scope.launch {
            loading = true
            error = null
            status = "Searching"
            runCatching { repository.searchSeries(term) }
                .onSuccess {
                    results = it
                    status = "${it.size} results"
                    if (it.isEmpty()) error = "No matching series found"
                }
                .onFailure {
                    error = it.message ?: "Search failed"
                    status = "Search failed"
                }
            loading = false
        }
    }

    fun playEpisode(index: Int, resumePositionMs: Long = 0L) {
        val currentSeries = series ?: return
        val episode = currentSeries.episodes.getOrNull(index) ?: return
        scope.launch {
            loading = true
            error = null
            selectedEpisode = index
            status = "Resolving ${episode.title}"
            runCatching { repository.resolveEpisode(episode, currentSeries.url) }
                .onSuccess { mediaUrl ->
                    resolvedEpisodeUrl = mediaUrl
                    player.setMediaItem(MediaItem.fromUri(mediaUrl), resumePositionMs)
                    player.prepare()
                    player.playWhenReady = true
                    status = episode.title
                }
                .onFailure {
                    error = it.message ?: "Could not resolve media URL"
                    status = "Playback unavailable"
                }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        val savedUrl = progressStore.lastSeriesUrl() ?: DEFAULT_SERIES_URL
        loadSeries(savedUrl)
    }

    DisposableEffect(player, series, selectedEpisode) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    val next = selectedEpisode + 1
                    if (next <= (series?.episodes?.lastIndex ?: -1)) {
                        progressStore.saveProgress(next, 0L)
                        playEpisode(next)
                    }
                }
            }
        }
        player.addListener(listener)
        onDispose {
            progressStore.saveProgress(selectedEpisode, player.currentPosition.coerceAtLeast(0L))
            player.removeListener(listener)
        }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    LaunchedEffect(player, selectedEpisode) {
        while (true) {
            delay(5000)
            val position = player.currentPosition.coerceAtLeast(0L)
            if (position != lastSavedPosition) {
                lastSavedPosition = position
                progressStore.saveProgress(selectedEpisode, position)
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF101418),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(
                modifier = Modifier
                    .width(420.dp)
                    .fillMaxHeight(),
            ) {
                Text(
                    text = "Web Playlist",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = status,
                    color = Color(0xFFB8C3CC),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Search anime") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search() }),
                    )
                    Button(onClick = { search() }) {
                        Text("Search")
                    }
                }
                Spacer(Modifier.height(14.dp))
                if (loading) {
                    CircularProgressIndicator(color = Color(0xFF78D5C6))
                    Spacer(Modifier.height(10.dp))
                }
                error?.let {
                    Text(text = it, color = Color(0xFFFFB199))
                    Spacer(Modifier.height(10.dp))
                }
                if (results.isNotEmpty()) {
                    Text("Series Results", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier
                            .height(170.dp)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(results) { _, result ->
                            FocusButton(
                                text = result.title,
                                selected = series?.url == result.url,
                                onClick = { loadSeries(result.url) },
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
                Text(
                    text = series?.title ?: "Episodes",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(series?.episodes.orEmpty()) { index, episode ->
                        val resume = if (index == progressStore.lastEpisodeIndex()) progressStore.lastPositionMs() else 0L
                        FocusButton(
                            text = episode.title,
                            selected = index == selectedEpisode,
                            onClick = { playEpisode(index, resume) },
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    if (resolvedEpisodeUrl == null) {
                        Text(
                            text = "Select an episode",
                            color = Color(0xFFB8C3CC),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    AndroidView(
                        factory = { PlayerView(it).apply { this.player = player } },
                        modifier = Modifier.fillMaxSize(),
                        update = { it.player = player },
                    )
                }
            }
        }
    }
}

@Composable
private fun FocusButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = when {
        focused -> Color(0xFF78D5C6)
        selected -> Color(0xFFE3BC5B)
        else -> Color.Transparent
    }

    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .onFocusChanged { focused = it.isFocused }
            .border(2.dp, borderColor, RoundedCornerShape(6.dp)),
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(0xFF29384A) else Color(0xFF1C232B),
            contentColor = Color.White,
        ),
    ) {
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val DEFAULT_SERIES_URL = "https://myself-bbs.com/thread-44169-1-1.html"

