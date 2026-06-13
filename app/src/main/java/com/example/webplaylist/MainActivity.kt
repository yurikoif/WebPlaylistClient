package com.example.webplaylist

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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
import com.example.webplaylist.data.SavedSeries
import com.example.webplaylist.parser.MyselfBbsEpisodeListParser
import com.example.webplaylist.resolver.HtmlMediaUrlResolver
import com.example.webplaylist.resolver.VpxWebSocketMediaUrlResolver
import com.example.webplaylist.site.MyselfBbsSiteAdapter
import com.example.webplaylist.site.SiteRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.net.URI

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                WebPlaylistApp()
            }
        }
    }

    override fun onDestroy() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
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
        val mediaResolver = HtmlMediaUrlResolver(client, vpxResolver)
        val siteRegistry = SiteRegistry(
            listOf(
                MyselfBbsSiteAdapter(
                    episodeParser = MyselfBbsEpisodeListParser(),
                    mediaResolver = mediaResolver,
                ),
            ),
        )
        PlaylistRepository(
            client = client,
            siteRegistry = siteRegistry,
        )
    }
    val player = remember { ExoPlayer.Builder(context).build() }

    val initialSavedSeries = remember { withDefaultSeries(progressStore.savedSeries()) }
    val initialSeriesUrl = remember {
        progressStore.lastSeriesUrl()
            ?: initialSavedSeries.firstOrNull()?.url
            ?: DEFAULT_SERIES_URL
    }
    var urlInput by remember { mutableStateOf(initialSeriesUrl) }
    var series by remember { mutableStateOf<LoadedSeries?>(null) }
    var savedSeries by remember { mutableStateOf(initialSavedSeries) }
    var selectedEpisode by remember { mutableIntStateOf(progressStore.lastEpisodeIndex(initialSeriesUrl)) }
    var status by remember { mutableStateOf("Loading sample series") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var resolvedEpisodeUrl by remember { mutableStateOf<String?>(null) }
    var lastSavedPosition by remember { mutableLongStateOf(progressStore.lastPositionMs(initialSeriesUrl)) }
    var showPlaylist by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(false) }
    var loopCurrentEpisode by remember { mutableStateOf(false) }
    var pendingAutoPlay by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    var focusPlayWhenShown by remember { mutableStateOf(false) }
    var focusBack10WhenShown by remember { mutableStateOf(false) }
    var focusForward10WhenShown by remember { mutableStateOf(false) }
    var focusUrlWhenShown by remember { mutableStateOf(false) }
    var focusSeriesWhenShown by remember { mutableStateOf(false) }
    var focusEpisodeWhenShown by remember { mutableStateOf(false) }
    var seekJob by remember { mutableStateOf<Job?>(null) }
    var leftRightPressHandledAsSeek by remember { mutableStateOf(false) }
    var playbackPositionMs by remember { mutableLongStateOf(progressStore.lastPositionMs(initialSeriesUrl)) }
    var playbackDurationMs by remember { mutableLongStateOf(0L) }
    var focusedTransportIcon by remember { mutableStateOf<TransportIcon?>(null) }
    var urlInputFocused by remember { mutableStateOf(false) }
    var playlistFocused by remember { mutableStateOf(false) }
    var seriesActionTarget by remember { mutableStateOf<SavedSeries?>(null) }
    var overlayActivityTick by remember { mutableIntStateOf(0) }
    val rootFocusRequester = remember { FocusRequester() }
    val previousEpisodeFocusRequester = remember { FocusRequester() }
    val playPauseFocusRequester = remember { FocusRequester() }
    val back10FocusRequester = remember { FocusRequester() }
    val forward10FocusRequester = remember { FocusRequester() }
    val nextEpisodeFocusRequester = remember { FocusRequester() }
    val currentEpisodeFocusRequester = remember { FocusRequester() }
    val currentSeriesFocusRequester = remember { FocusRequester() }
    val urlFocusRequester = remember { FocusRequester() }
    val episodeListState = rememberLazyListState()
    val savedSeriesListState = rememberLazyListState()

    fun loadSeries(url: String, saveToHistory: Boolean = true) {
        scope.launch {
            loading = true
            error = null
            status = "Loading series"
            runCatching { repository.loadSeries(url) }
                .onSuccess { loaded ->
                    series = loaded
                    selectedEpisode = progressStore.lastEpisodeIndex(loaded.url)
                        .coerceIn(0, loaded.episodes.lastIndex.coerceAtLeast(0))
                    if (saveToHistory) {
                        progressStore.saveSeries(loaded.url, loaded.title)
                        savedSeries = withDefaultSeries(progressStore.savedSeries())
                    }
                    urlInput = loaded.url
                    status = "${loaded.episodes.size} episodes"
                    resolvedEpisodeUrl = null
                    showPlaylist = true
                    if (loaded.episodes.isNotEmpty()) {
                        val resumePosition = progressStore.lastPositionMs(loaded.url)
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
        val normalized = repository.normalizeUrl(raw)

        if (!repository.supports(normalized)) {
            error = "Enter a supported series URL (${repository.supportedUrlHint()})"
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
            progressStore.saveProgress(currentSeries.url, index, resumePositionMs)
            lastSavedPosition = resumePositionMs
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
        loadSeries(initialSeriesUrl)
    }

    DisposableEffect(player, series, selectedEpisode, loopCurrentEpisode) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    val currentSeries = series ?: return
                    if (loopCurrentEpisode) {
                        progressStore.saveProgress(currentSeries.url, selectedEpisode, 0L)
                        player.seekTo(0L)
                        player.play()
                    } else {
                        val next = selectedEpisode + 1
                        if (next <= currentSeries.episodes.lastIndex) {
                            progressStore.saveProgress(currentSeries.url, next, 0L)
                            playEpisode(next)
                        }
                    }
                }
            }
        }
        player.addListener(listener)
        onDispose {
            series?.url?.let {
                progressStore.saveProgress(it, selectedEpisode, player.currentPosition.coerceAtLeast(0L))
            }
            player.removeListener(listener)
        }
    }

    fun saveCurrentProgress() {
        series?.url?.let { progressStore.saveSeries(it) }
        series?.url?.let {
            progressStore.saveProgress(it, selectedEpisode, player.currentPosition.coerceAtLeast(0L))
        }
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

    DisposableEffect(showPlaylist, resolvedEpisodeUrl) {
        val activity = context as? Activity
        if (!showPlaylist && resolvedEpisodeUrl != null) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(player, selectedEpisode) {
        while (true) {
            delay(500)
            val position = player.currentPosition.coerceAtLeast(0L)
            playbackPositionMs = position
            playbackDurationMs = player.duration.takeIf { it > 0L } ?: 0L
            if (position >= lastSavedPosition + PROGRESS_SAVE_INTERVAL_MS) {
                lastSavedPosition = position
                series?.url?.let { progressStore.saveProgress(it, selectedEpisode, position) }
            }
        }
    }

    fun showControlsAndPause() {
        if (resolvedEpisodeUrl != null) player.pause()
        focusPlayWhenShown = true
        overlayActivityTick++
        showPlaylist = true
    }

    fun showControlsWithoutPause(focusTarget: OverlayFocusTarget) {
        when (focusTarget) {
            OverlayFocusTarget.Url -> focusUrlWhenShown = true
            OverlayFocusTarget.Series -> focusSeriesWhenShown = true
            OverlayFocusTarget.Play -> focusPlayWhenShown = true
            OverlayFocusTarget.Back10 -> focusBack10WhenShown = true
            OverlayFocusTarget.Forward10 -> focusForward10WhenShown = true
            OverlayFocusTarget.Episode -> focusEpisodeWhenShown = true
        }
        overlayActivityTick++
        showPlaylist = true
    }

    fun hideControls() {
        seekJob?.cancel()
        seekJob = null
        leftRightPressHandledAsSeek = false
        showPlaylist = false
    }

    fun focusTargetForHorizontalKey(key: Key): OverlayFocusTarget {
        return if (key == Key.DirectionLeft) OverlayFocusTarget.Back10 else OverlayFocusTarget.Forward10
    }

    LaunchedEffect(
        showPlaylist,
        focusPlayWhenShown,
        focusBack10WhenShown,
        focusForward10WhenShown,
        focusUrlWhenShown,
        focusSeriesWhenShown,
        focusEpisodeWhenShown,
    ) {
        if (!showPlaylist) {
            rootFocusRequester.requestFocus()
        } else if (focusPlayWhenShown) {
            playPauseFocusRequester.requestFocus()
            focusPlayWhenShown = false
        } else if (focusBack10WhenShown) {
            back10FocusRequester.requestFocus()
            focusBack10WhenShown = false
        } else if (focusForward10WhenShown) {
            forward10FocusRequester.requestFocus()
            focusForward10WhenShown = false
        } else if (focusUrlWhenShown) {
            urlFocusRequester.requestFocus()
            focusUrlWhenShown = false
        } else if (focusSeriesWhenShown) {
            val index = savedSeries.indexOfFirst { it.url == series?.url }
            if (index >= 0) {
                savedSeriesListState.scrollToItem(index)
                withFrameNanos { }
                currentSeriesFocusRequester.requestFocus()
            } else {
                urlFocusRequester.requestFocus()
            }
            focusSeriesWhenShown = false
        } else if (focusEpisodeWhenShown) {
            val lastIndex = series?.episodes?.lastIndex ?: -1
            if (lastIndex >= 0) {
                episodeListState.scrollToItem(selectedEpisode.coerceIn(0, lastIndex))
                withFrameNanos { }
                currentEpisodeFocusRequester.requestFocus()
            }
            focusEpisodeWhenShown = false
        }
    }

    LaunchedEffect(showPlaylist, overlayActivityTick, resolvedEpisodeUrl) {
        if (showPlaylist && resolvedEpisodeUrl != null) {
            delay(OVERLAY_AUTO_HIDE_MS)
            hideControls()
        }
    }

    BackHandler(enabled = showPlaylist && resolvedEpisodeUrl != null) {
        hideControls()
    }

    fun playPrevious() {
        val previous = selectedEpisode - 1
        if (previous >= 0) playEpisode(previous)
    }

    fun playNext() {
        val next = selectedEpisode + 1
        if (next <= (series?.episodes?.lastIndex ?: -1)) playEpisode(next)
    }

    fun seekBy(deltaMs: Long) {
        if (resolvedEpisodeUrl == null) return
        val duration = player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        val target = (player.currentPosition + deltaMs).coerceIn(0L, duration)
        player.seekTo(target)
        playbackPositionMs = target
        playbackDurationMs = player.duration.takeIf { it > 0L } ?: playbackDurationMs
        series?.url?.let { progressStore.saveProgress(it, selectedEpisode, target) }
        lastSavedPosition = target
    }

    fun startLongSeek(deltaMs: Long) {
        if (resolvedEpisodeUrl == null || seekJob != null) return
        leftRightPressHandledAsSeek = false
        seekJob = scope.launch {
            delay(LONG_PRESS_START_MS)
            while (isActive) {
                seekBy(deltaMs)
                leftRightPressHandledAsSeek = true
                overlayActivityTick++
                delay(LONG_PRESS_SEEK_REPEAT_MS)
            }
        }
    }

    fun stopLongSeek(): Boolean {
        val wasSeek = leftRightPressHandledAsSeek
        seekJob?.cancel()
        seekJob = null
        leftRightPressHandledAsSeek = false
        return wasSeek
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (showPlaylist && event.type == KeyEventType.KeyUp && event.key == Key.Back) {
                    hideControls()
                    return@onPreviewKeyEvent true
                }

                if (showPlaylist && event.type == KeyEventType.KeyUp) {
                    overlayActivityTick++
                }
                val isHorizontalSeekKey = event.key == Key.DirectionLeft || event.key == Key.DirectionRight
                if (!showPlaylist && isHorizontalSeekKey) {
                    val delta = if (event.key == Key.DirectionLeft) -LONG_PRESS_SEEK_STEP_MS else LONG_PRESS_SEEK_STEP_MS
                    when (event.type) {
                        KeyEventType.KeyDown -> {
                            showControlsWithoutPause(focusTargetForHorizontalKey(event.key))
                            startLongSeek(delta)
                            return@onPreviewKeyEvent true
                        }
                        KeyEventType.KeyUp -> {
                            stopLongSeek()
                            overlayActivityTick++
                            return@onPreviewKeyEvent true
                        }
                        else -> return@onPreviewKeyEvent false
                    }
                }

                if (
                    showPlaylist &&
                    isHorizontalSeekKey &&
                    !urlInputFocused &&
                    !playlistFocused
                ) {
                    val delta = if (event.key == Key.DirectionLeft) -LONG_PRESS_SEEK_STEP_MS else LONG_PRESS_SEEK_STEP_MS
                    when (event.type) {
                        KeyEventType.KeyDown -> {
                            if (seekJob != null) {
                                return@onPreviewKeyEvent true
                            }
                            startLongSeek(delta)
                            overlayActivityTick++
                            return@onPreviewKeyEvent false
                        }
                        KeyEventType.KeyUp -> {
                            val wasSeek = stopLongSeek()
                            overlayActivityTick++
                            return@onPreviewKeyEvent wasSeek
                        }
                        else -> return@onPreviewKeyEvent false
                    }
                }

                if (!showPlaylist && event.type == KeyEventType.KeyUp) {
                    when (event.key) {
                        Key.DirectionCenter,
                        Key.Enter,
                        Key.NumPadEnter -> {
                            showControlsAndPause()
                            true
                        }
                        Key.DirectionUp -> {
                            showControlsWithoutPause(OverlayFocusTarget.Series)
                            true
                        }
                        Key.DirectionDown -> {
                            showControlsWithoutPause(OverlayFocusTarget.Episode)
                            true
                        }
                        Key.DirectionLeft -> {
                            showControlsWithoutPause(OverlayFocusTarget.Back10)
                            true
                        }
                        Key.DirectionRight -> {
                            showControlsWithoutPause(OverlayFocusTarget.Forward10)
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
                        .background(PANEL_BACKGROUND)
                        .border(1.dp, PANEL_BORDER, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(urlFocusRequester)
                                .onFocusChanged {
                                    urlInputFocused = it.isFocused
                                    if (it.isFocused) playlistFocused = false
                                }
                                .onPreviewKeyEvent { event ->
                                    if (event.key != Key.DirectionDown) return@onPreviewKeyEvent false
                                    when (event.type) {
                                        KeyEventType.KeyDown -> true
                                        KeyEventType.KeyUp -> {
                                            focusSeriesWhenShown = true
                                            overlayActivityTick++
                                            true
                                        }
                                        else -> false
                                    }
                                },
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
                    if (savedSeries.isEmpty()) {
                        Text(
                            text = status,
                            color = Color(0xFFB8C3CC),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onPreviewKeyEvent { event ->
                                    when (event.key) {
                                        Key.DirectionUp -> {
                                            when (event.type) {
                                                KeyEventType.KeyDown -> true
                                                KeyEventType.KeyUp -> {
                                                    urlFocusRequester.requestFocus()
                                                    overlayActivityTick++
                                                    true
                                                }
                                                else -> false
                                            }
                                        }
                                        Key.DirectionDown -> {
                                            when (event.type) {
                                                KeyEventType.KeyDown -> true
                                                KeyEventType.KeyUp -> {
                                                    playPauseFocusRequester.requestFocus()
                                                    overlayActivityTick++
                                                    true
                                                }
                                                else -> false
                                            }
                                        }
                                        else -> false
                                    }
                                },
                            state = savedSeriesListState,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            itemsIndexed(savedSeries) { index, saved ->
                                SeriesRailButton(
                                    title = saved.title,
                                    urlHint = urlHostHint(saved.url),
                                    selected = saved.url == series?.url,
                                    modifier = if (saved.url == series?.url) {
                                        Modifier
                                            .width(290.dp)
                                            .focusRequester(currentSeriesFocusRequester)
                                    } else {
                                        Modifier.width(290.dp)
                                    },
                                    onFocused = {
                                        playlistFocused = false
                                    },
                                    onLongPress = {
                                        seriesActionTarget = saved
                                        overlayActivityTick++
                                    },
                                    onClick = { loadSeries(saved.url) },
                                )
                            }
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
                                    focusEpisodeWhenShown = true
                                    true
                                }
                                else -> false
                            }
                        },
                    horizontalArrangement = Arrangement.spacedBy(42.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TransportButton(
                        icon = TransportIcon.Previous,
                        enabled = selectedEpisode > 0,
                        modifier = Modifier.focusRequester(previousEpisodeFocusRequester),
                        onFocused = {
                            playlistFocused = false
                            focusedTransportIcon = TransportIcon.Previous
                        },
                        onClick = {
                            overlayActivityTick++
                            playPrevious()
                        },
                    )
                    TransportButton(
                        icon = TransportIcon.Back10,
                        enabled = resolvedEpisodeUrl != null,
                        modifier = Modifier.focusRequester(back10FocusRequester),
                        onFocused = {
                            playlistFocused = false
                            focusedTransportIcon = TransportIcon.Back10
                        },
                        onClick = {
                            overlayActivityTick++
                            seekBy(-SEEK_STEP_MS)
                        },
                    )
                    TransportButton(
                        icon = if (isPlaying) TransportIcon.Pause else TransportIcon.Play,
                        enabled = resolvedEpisodeUrl != null,
                        modifier = Modifier.focusRequester(playPauseFocusRequester),
                        onFocused = {
                            playlistFocused = false
                            focusedTransportIcon = if (isPlaying) TransportIcon.Pause else TransportIcon.Play
                        },
                        onClick = {
                            overlayActivityTick++
                            if (player.isPlaying) {
                                player.pause()
                            } else {
                                player.play()
                            }
                        },
                    )
                    TransportButton(
                        icon = TransportIcon.Forward10,
                        enabled = resolvedEpisodeUrl != null,
                        modifier = Modifier.focusRequester(forward10FocusRequester),
                        onFocused = {
                            playlistFocused = false
                            focusedTransportIcon = TransportIcon.Forward10
                        },
                        onClick = {
                            overlayActivityTick++
                            seekBy(SEEK_STEP_MS)
                        },
                    )
                    TransportButton(
                        icon = TransportIcon.Next,
                        enabled = selectedEpisode < (series?.episodes?.lastIndex ?: 0),
                        modifier = Modifier.focusRequester(nextEpisodeFocusRequester),
                        onFocused = {
                            playlistFocused = false
                            focusedTransportIcon = TransportIcon.Next
                        },
                        onClick = {
                            overlayActivityTick++
                            playNext()
                        },
                    )
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(PANEL_BACKGROUND)
                        .border(1.dp, PANEL_BORDER, RoundedCornerShape(8.dp))
                        .onPreviewKeyEvent { event ->
                            if (event.key != Key.DirectionUp) return@onPreviewKeyEvent false
                            when (event.type) {
                                KeyEventType.KeyDown -> true
                                KeyEventType.KeyUp -> {
                                    playPauseFocusRequester.requestFocus()
                                    overlayActivityTick++
                                    true
                                }
                                else -> false
                            }
                        }
                        .padding(10.dp),
                ) {
                    PlaybackProgressPanel(
                        positionMs = playbackPositionMs,
                        durationMs = playbackDurationMs,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
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
                        }
                        Button(
                            onClick = { loopCurrentEpisode = !loopCurrentEpisode },
                            enabled = resolvedEpisodeUrl != null,
                            colors = translucentButtonColors(),
                        ) {
                            Text(if (loopCurrentEpisode) "Play loop" else "Play next")
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (series?.episodes.isNullOrEmpty()) "No episodes loaded" else "",
                        color = Color(0xFFB8C3CC),
                    )
                    if (!series?.episodes.isNullOrEmpty()) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            state = episodeListState,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            itemsIndexed(series?.episodes.orEmpty()) { index, episode ->
                                val currentSeriesUrl = series?.url
                                val resume = if (
                                    currentSeriesUrl != null &&
                                    index == progressStore.lastEpisodeIndex(currentSeriesUrl)
                                ) {
                                    progressStore.lastPositionMs(currentSeriesUrl)
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
                                    onFocused = { playlistFocused = true },
                                    onClick = { playEpisode(index, resume) },
                                )
                            }
                        }
                    }
                }
            }

            seriesActionTarget?.let { target ->
                AlertDialog(
                    onDismissRequest = { seriesActionTarget = null },
                    title = { Text(target.title) },
                    text = { Text(target.url) },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (target.url != DEFAULT_SERIES_URL) {
                                    progressStore.deleteSeries(target.url)
                                    savedSeries = withDefaultSeries(progressStore.savedSeries())
                                }
                                seriesActionTarget = null
                                overlayActivityTick++
                            },
                            enabled = target.url != DEFAULT_SERIES_URL,
                            colors = translucentButtonColors(),
                        ) {
                            Text("Delete")
                        }
                    },
                    dismissButton = {
                        Button(
                            onClick = {
                                seriesActionTarget = null
                                overlayActivityTick++
                            },
                            colors = translucentButtonColors(),
                        ) {
                            Text("Cancel")
                        }
                    },
                )
            }
        }
    }
}

private enum class OverlayFocusTarget {
    Url,
    Series,
    Play,
    Back10,
    Forward10,
    Episode,
}

private fun withDefaultSeries(savedSeries: List<SavedSeries>): List<SavedSeries> {
    return if (savedSeries.any { it.url == DEFAULT_SERIES_URL }) {
        savedSeries
    } else {
        savedSeries + SavedSeries(DEFAULT_SERIES_URL, DEFAULT_SERIES_URL)
    }
}

private fun urlHostHint(url: String): String {
    return runCatching {
        URI(url).host?.removePrefix("www.") ?: url
    }.getOrElse { url }
}

private const val DEFAULT_SERIES_URL = "https://myself-bbs.com/thread-44169-1-1.html"
private const val USER_AGENT = "Mozilla/5.0"
private const val SEEK_STEP_MS = 10_000L
private const val LONG_PRESS_SEEK_STEP_MS = 30_000L
private const val LONG_PRESS_START_MS = 500L
private const val LONG_PRESS_SEEK_REPEAT_MS = 700L
private const val OVERLAY_AUTO_HIDE_MS = 10_000L
private const val PROGRESS_SAVE_INTERVAL_MS = 5_000L
private val PANEL_BACKGROUND = Color(0xC8242B33)
private val PANEL_BORDER = Color(0x88D7E0EA)
