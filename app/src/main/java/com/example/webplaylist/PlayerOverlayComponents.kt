package com.example.webplaylist

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TransportButton(
    icon: TransportIcon,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(78.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
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
        contentPadding = PaddingValues(0.dp),
    ) {
        TransportGlyph(icon = icon, enabled = enabled)
    }
}

enum class TransportIcon {
    Previous,
    Back10,
    Play,
    Pause,
    Forward10,
    Next,
}

@Composable
fun SeriesRailButton(
    title: String,
    urlHint: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    onLongPress: () -> Unit,
    onClick: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    var handledAsLongPress by remember { mutableStateOf(false) }
    val borderColor = when {
        focused -> Color.White
        selected -> CURRENT_EPISODE_BLUE
        else -> Color.Transparent
    }

    Button(
        onClick = onClick,
        modifier = modifier
            .height(48.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                val isOkKey = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                if (!isOkKey) return@onPreviewKeyEvent false

                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (longPressJob == null) {
                            handledAsLongPress = false
                            longPressJob = scope.launch {
                                delay(SERIES_ACTION_LONG_PRESS_MS)
                                handledAsLongPress = true
                                onLongPress()
                            }
                        }
                        true
                    }
                    KeyEventType.KeyUp -> {
                        longPressJob?.cancel()
                        longPressJob = null
                        if (!handledAsLongPress) {
                            onClick()
                        }
                        handledAsLongPress = false
                        true
                    }
                    else -> false
                }
            }
            .border(2.dp, borderColor, RoundedCornerShape(6.dp)),
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) CURRENT_EPISODE_BLUE.copy(alpha = 0.36f) else Color(0x33FFFFFF),
            contentColor = Color.White,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = urlHint,
                color = Color(0xFFD5EFFF),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun PlaybackProgressPanel(
    positionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    val progress = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0x44FFFFFF)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${formatPlaybackTime(positionMs)} / ${formatPlaybackTime(durationMs)}",
            color = Color(0xFFB8C3CC),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.End),
        )
    }
}

@Composable
private fun TransportGlyph(
    icon: TransportIcon,
    enabled: Boolean,
) {
    val glyphColor = if (enabled) Color.White else Color(0x66FFFFFF)
    if (icon == TransportIcon.Back10 || icon == TransportIcon.Forward10) {
        Text(
            text = if (icon == TransportIcon.Back10) "-10s" else "+10s",
            color = glyphColor,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
        return
    }

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
            TransportIcon.Back10,
            TransportIcon.Forward10 -> Unit
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
fun EpisodeRailButton(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = when {
        focused -> Color.White
        selected -> CURRENT_EPISODE_BLUE
        else -> Color.Transparent
    }

    Button(
        onClick = onClick,
        modifier = modifier
            .height(50.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .border(2.dp, borderColor, RoundedCornerShape(6.dp)),
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) CURRENT_EPISODE_BLUE.copy(alpha = 0.36f) else Color(0x33FFFFFF),
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
fun translucentButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Color(0x33FFFFFF),
    contentColor = Color.White,
    disabledContainerColor = Color(0x22FFFFFF),
    disabledContentColor = Color(0x77FFFFFF),
)

private val CURRENT_EPISODE_BLUE = Color(0xFF8FD7FF)
private const val SERIES_ACTION_LONG_PRESS_MS = 650L

private fun formatPlaybackTime(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
