package com.example.webplaylist

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.webplaylist.data.LoadedSeries
import com.example.webplaylist.data.PlaybackProgressStore
import com.example.webplaylist.data.PlaylistRepository
import com.example.webplaylist.parser.MyselfBbsEpisodeListParser
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
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val progressStore = remember { PlaybackProgressStore(context) }
    val repository = remember {
        val client = OkHttpClient.Builder().build()
        val vpxResolver = VpxWebSocketMediaUrlResolver(client)
        PlaylistRepository(
            client = client,
            episodeParser = MyselfBbsEpisodeListParser(),
            resolver = HtmlMediaUrlResolver(client, vpxResolver),
        )
    }
    val player = remember { ExoPlayer.Builder(context).build() }

    var urlInput by remember { mutableStateOf(DEFAULT_SERIES_URL) }
    var series by remember { mutableStateOf<LoadedSeries?>(null) }
    var selectedEpisode by remember { mutableIntStateOf(progressStore.lastEpisodeIndex()) }
    var status by remember { mutableStateOf("Loading sample series") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var resolvedEpisodeUrl by remember { mutableStateOf<String?>(null) }
    var lastSavedPosition by remember { mutableLongStateOf(progressStore.lastPositionMs()) }
    var showPlaylist by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(false) }
    var loopCurrentEpisode by remember { mutableStateOf(false) }
    var pendingAutoPlay by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    var focusPlayWhenShown by remember { mutableStateOf(false) }
    var focusUrlWhenShown by remember { mutableStateOf(false) }
    var focusEpisodeWhenShown by remember { mutableStateOf(false) }
    val rootFocusRequester = remember { FocusRequester() }
    val playPauseFocusRequester = remember { FocusRequester() }
    val currentEpisodeFocusRequester = remember { FocusRequester() }
    val urlFocusRequester = remember { FocusRequester() }

    fun loadSeries(url: String) {
        scope.launch {
            loading = true
            error = null
            status = "Loading series"
            runCatching { repository.loadSeries(url) }
                .onSuccess { loaded ->
                    val resumeSeries = progressStore.lastSeriesUrl() == loaded.url
                    series = loaded
                    selectedEpisode = if (resumeSeries) {
                        progressStore.lastEpisodeIndex().coerceIn(0, loaded.episodes.lastIndex.coerceAtLeast(0))
                    } else {
                        0
                    }
                    progressStore.saveSeries(loaded.url)
                    urlInput = loaded.url
                    status = "${loaded.episodes.size} episodes"
                    resolvedEpisodeUrl = null
                    showPlaylist = true
                    if (loaded.episodes.isNotEmpty()) {
                        val resumePosition = if (resumeSeries) progressStore.lastPositionMs() else 0L
                        pendingAutoPlay = selectedEpisode to resumePosition
                    }
                }
                .onFailure {
                    error = it.message ?: "Series load failed"
                    status = "Series load failed"
                }
            loading = false
        }
    }

    fun openEnteredUrl() {
        val raw = urlInput.trim()
        if (raw.isBlank()) return
        val normalized = when {
            raw.startsWith("https://") || raw.startsWith("http://") -> raw
            raw.startsWith("myself-bbs.com/") -> "https://$raw"
            raw.startsWith("thread-") -> "https://myself-bbs.com/$raw"
            else -> raw
        }

        if (!seriesUrlPattern.containsMatchIn(normalized)) {
            error = "Enter a series URL like https://myself-bbs.com/thread-44169-1-1.html"
            return
        }

        loadSeries(normalized)
    }

    fun playEpisode(index: Int, resumePositionMs: Long = 0L) {
        val currentSeries = series ?: return
        val episode = currentSeries.episodes.getOrNull(index) ?: return
        scope.launch {
            loading = true
            error = null
            selectedEpisode = index
            progressStore.saveProgress(index, resumePositionMs)
            status = "Resolving ${episode.title}"
            runCatching { repository.resolveEpisode(episode, currentSeries.url) }
                .onSuccess { mediaUrl ->
                    resolvedEpisodeUrl = mediaUrl
                    val mediaItemBuilder = MediaItem.Builder().setUri(mediaUrl)
                    if (mediaUrl.substringBefore("?").endsWith(".m3u8", ignoreCase = true)) {
                        mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
                    }
                    val mediaItem = mediaItemBuilder.build()
                    val dataSourceFactory = DefaultHttpDataSource.Factory()
                        .setUserAgent(USER_AGENT)
                        .setDefaultRequestProperties(
                            mapOf(
                                "Referer" to episode.pageUrl,
                                "Origin" to "https://v.myself-bbs.com",
                            )
                        )
                    val mediaSource = if (mediaUrl.substringBefore("?").endsWith(".m3u8", ignoreCase = true)) {
                        HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
                    } else {
                        ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
                    }
                    player.setMediaSource(mediaSource, resumePositionMs)
                    player.prepare()
                    player.playWhenReady = true
                    status = episode.title
                    showPlaylist = false
                }
                .onFailure {
                    error = it.message ?: "Could not resolve media URL"
                    status = "Playback unavailable"
                }
            loading = false
        }
    }

    LaunchedEffect(pendingAutoPlay) {
        val request = pendingAutoPlay ?: return@LaunchedEffect
        pendingAutoPlay = null
        playEpisode(request.first, request.second)
    }

    LaunchedEffect(Unit) {
        val savedUrl = progressStore.lastSeriesUrl() ?: DEFAULT_SERIES_URL
        loadSeries(savedUrl)
    }

    DisposableEffect(player, series, selectedEpisode, loopCurrentEpisode) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    if (loopCurrentEpisode) {
                        progressStore.saveProgress(selectedEpisode, 0L)
                        player.seekTo(0L)
                        player.play()
                    } else {
                        val next = selectedEpisode + 1
                        if (next <= (series?.episodes?.lastIndex ?: -1)) {
                            progressStore.saveProgress(next, 0L)
                            playEpisode(next)
                        }
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

    fun saveCurrentProgress() {
        series?.url?.let { progressStore.saveSeries(it) }
        progressStore.saveProgress(selectedEpisode, player.currentPosition.coerceAtLeast(0L))
    }

    DisposableEffect(lifecycleOwner, series?.url, selectedEpisode) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                saveCurrentProgress()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            saveCurrentProgress()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
            }

            override fun onPlayerError(playbackError: PlaybackException) {
                error = "Player error: ${playbackError.errorCodeName}"
                status = "Playback failed"
                showPlaylist = true
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
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

    fun showControlsAndPause() {
        if (resolvedEpisodeUrl != null) player.pause()
        focusPlayWhenShown = true
        showPlaylist = true
    }

    fun showControlsWithoutPause(focusTarget: OverlayFocusTarget) {
        when (focusTarget) {
            OverlayFocusTarget.Url -> focusUrlWhenShown = true
            OverlayFocusTarget.Play -> focusPlayWhenShown = true
            OverlayFocusTarget.Episode -> focusEpisodeWhenShown = true
        }
        showPlaylist = true
    }

    LaunchedEffect(showPlaylist, focusPlayWhenShown, focusUrlWhenShown, focusEpisodeWhenShown) {
        if (!showPlaylist) {
            rootFocusRequester.requestFocus()
        } else if (focusPlayWhenShown) {
            playPauseFocusRequester.requestFocus()
            focusPlayWhenShown = false
        } else if (focusUrlWhenShown) {
            urlFocusRequester.requestFocus()
            focusUrlWhenShown = false
        } else if (focusEpisodeWhenShown) {
            currentEpisodeFocusRequester.requestFocus()
            focusEpisodeWhenShown = false
        }
    }

    BackHandler(enabled = showPlaylist && resolvedEpisodeUrl != null) {
        showPlaylist = false
    }

    fun playPrevious() {
        val previous = selectedEpisode - 1
        if (previous >= 0) playEpisode(previous)
    }

    fun playNext() {
        val next = selectedEpisode + 1
        if (next <= (series?.episodes?.lastIndex ?: -1)) playEpisode(next)
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (!showPlaylist && event.type == KeyEventType.KeyUp) {
                    when (event.key) {
                        Key.DirectionCenter,
                        Key.Enter,
                        Key.NumPadEnter -> {
                            showControlsAndPause()
                            true
                        }
                        Key.DirectionUp -> {
                            showControlsWithoutPause(OverlayFocusTarget.Url)
                            true
                        }
                        Key.DirectionDown -> {
                            showControlsWithoutPause(OverlayFocusTarget.Episode)
                            true
                        }
                        Key.DirectionLeft,
                        Key.DirectionRight -> {
                            showControlsWithoutPause(OverlayFocusTarget.Play)
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            },
        color = Color(0xFF101418),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        this.player = player
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                update = {
                    it.player = player
                    it.useController = false
                },
            )

            if (resolvedEpisodeUrl == null && !showPlaylist) {
                Text(
                    text = "Press Select for playlist",
                    color = Color(0xFFB8C3CC),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            if (showPlaylist) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 18.dp)
                        .width(860.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xAA101418))
                        .border(1.dp, Color(0x663A4654), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Web Playlist",
                                color = Color.White,
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Text(
                                text = status,
                                color = Color(0xFFB8C3CC),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (resolvedEpisodeUrl != null) {
                            Button(
                                onClick = { showPlaylist = false },
                                colors = translucentButtonColors(),
                            ) {
                                Text("Hide")
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(urlFocusRequester),
                            singleLine = true,
                            label = { Text("Series URL") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { openEnteredUrl() }),
                        )
                        Button(
                            onClick = { openEnteredUrl() },
                            colors = translucentButtonColors(),
                        ) {
                            Text("Open")
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (loading) {
                        Spacer(Modifier.height(6.dp))
                        CircularProgressIndicator(color = Color.White)
                    }
                    error?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(text = it, color = Color(0xFFFFB199))
                    }
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionUp -> {
                                    urlFocusRequester.requestFocus()
                                    true
                                }
                                Key.DirectionDown -> {
                                    currentEpisodeFocusRequester.requestFocus()
                                    true
                                }
                                else -> false
                            }
                        },
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TransportButton(
                        icon = TransportIcon.Previous,
                        enabled = selectedEpisode > 0,
                        onClick = { playPrevious() },
                    )
                    TransportButton(
                        icon = if (isPlaying) TransportIcon.Pause else TransportIcon.Play,
                        enabled = resolvedEpisodeUrl != null,
                        modifier = Modifier.focusRequester(playPauseFocusRequester),
                        onClick = {
                            if (player.isPlaying) {
                                player.pause()
                            } else {
                                player.play()
                            }
                        },
                    )
                    TransportButton(
                        icon = TransportIcon.Next,
                        enabled = selectedEpisode < (series?.episodes?.lastIndex ?: 0),
                        onClick = { playNext() },
                    )
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 18.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xAA101418))
                        .border(1.dp, Color(0x663A4654), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = series?.title ?: "Episodes",
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (loopCurrentEpisode) "Mode: loop current episode" else "Mode: next episode",
                                color = Color(0xFFB8C3CC),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Button(
                            onClick = { loopCurrentEpisode = !loopCurrentEpisode },
                            enabled = resolvedEpisodeUrl != null,
                            colors = translucentButtonColors(),
                        ) {
                            Text(if (loopCurrentEpisode) "Loop" else "Next")
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (series?.episodes.isNullOrEmpty()) "No episodes loaded" else "",
                        color = Color(0xFFB8C3CC),
                    )
                    if (!series?.episodes.isNullOrEmpty()) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            itemsIndexed(series?.episodes.orEmpty()) { index, episode ->
                                val resume = if (index == progressStore.lastEpisodeIndex()) {
                                    progressStore.lastPositionMs()
                                } else {
                                    0L
                                }
                                EpisodeRailButton(
                                    text = episode.title,
                                    selected = index == selectedEpisode,
                                    modifier = if (index == selectedEpisode) {
                                        Modifier
                                            .width(220.dp)
                                            .focusRequester(currentEpisodeFocusRequester)
                                    } else {
                                        Modifier.width(220.dp)
                                    },
                                    onClick = { playEpisode(index, resume) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransportButton(
    icon: TransportIcon,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(78.dp)
            .onFocusChanged { focused = it.isFocused }
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) Color.White else Color(0x88FFFFFF),
                shape = CircleShape,
            ),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (focused) Color(0x66FFFFFF) else Color(0x33FFFFFF),
            contentColor = Color.White,
            disabledContainerColor = Color(0x22FFFFFF),
            disabledContentColor = Color(0x66FFFFFF),
        ),
    ) {
        TransportGlyph(icon = icon, enabled = enabled)
    }
}

private enum class TransportIcon {
    Previous,
    Play,
    Pause,
    Next,
}

private enum class OverlayFocusTarget {
    Url,
    Play,
    Episode,
}

@Composable
private fun TransportGlyph(
    icon: TransportIcon,
    enabled: Boolean,
) {
    val glyphColor = if (enabled) Color.White else Color(0x66FFFFFF)
    Canvas(modifier = Modifier.size(34.dp)) {
        val w = size.width
        val h = size.height
        when (icon) {
            TransportIcon.Play -> {
                val path = Path().apply {
                    moveTo(w * 0.34f, h * 0.22f)
                    lineTo(w * 0.34f, h * 0.78f)
                    lineTo(w * 0.78f, h * 0.5f)
                    close()
                }
                drawPath(path, glyphColor)
            }
            TransportIcon.Pause -> {
                drawRoundRect(glyphColor, topLeft = androidx.compose.ui.geometry.Offset(w * 0.26f, h * 0.22f), size = androidx.compose.ui.geometry.Size(w * 0.16f, h * 0.56f))
                drawRoundRect(glyphColor, topLeft = androidx.compose.ui.geometry.Offset(w * 0.58f, h * 0.22f), size = androidx.compose.ui.geometry.Size(w * 0.16f, h * 0.56f))
            }
            TransportIcon.Previous -> {
                drawRoundRect(glyphColor, topLeft = androidx.compose.ui.geometry.Offset(w * 0.18f, h * 0.22f), size = androidx.compose.ui.geometry.Size(w * 0.12f, h * 0.56f))
                val path = Path().apply {
                    moveTo(w * 0.78f, h * 0.2f)
                    lineTo(w * 0.78f, h * 0.8f)
                    lineTo(w * 0.32f, h * 0.5f)
                    close()
                }
                drawPath(path, glyphColor)
            }
            TransportIcon.Next -> {
                val path = Path().apply {
                    moveTo(w * 0.22f, h * 0.2f)
                    lineTo(w * 0.22f, h * 0.8f)
                    lineTo(w * 0.68f, h * 0.5f)
                    close()
                }
                drawPath(path, glyphColor)
                drawRoundRect(glyphColor, topLeft = androidx.compose.ui.geometry.Offset(w * 0.72f, h * 0.22f), size = androidx.compose.ui.geometry.Size(w * 0.12f, h * 0.56f))
            }
        }
    }
}

@Composable
private fun EpisodeRailButton(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = when {
        focused -> Color.White
        selected -> Color(0xBBFFFFFF)
        else -> Color.Transparent
    }

    Button(
        onClick = onClick,
        modifier = modifier
            .height(50.dp)
            .onFocusChanged { focused = it.isFocused }
            .border(2.dp, borderColor, RoundedCornerShape(6.dp)),
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(0x55FFFFFF) else Color(0x33FFFFFF),
            contentColor = Color.White,
        ),
    ) {
        Text(
            text = text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun translucentButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Color(0x33FFFFFF),
    contentColor = Color.White,
    disabledContainerColor = Color(0x22FFFFFF),
    disabledContentColor = Color(0x77FFFFFF),
)

private const val DEFAULT_SERIES_URL = "https://myself-bbs.com/thread-44169-1-1.html"
private const val USER_AGENT = "Mozilla/5.0"
private val seriesUrlPattern = Regex("""https?://(?:www\.)?myself-bbs\.com/thread-\d+-\d+-\d+\.html""")
