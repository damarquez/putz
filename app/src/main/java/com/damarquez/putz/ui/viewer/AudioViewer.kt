package com.damarquez.putz.ui.viewer

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.delay
import java.util.Locale

/** Basic audio preview: play/pause + seek bar. Streams from [filePath] (a remote URL or a local
 *  file path) via ExoPlayer's own progressive HTTP loading — no upfront full-file read or decode
 *  ever happens on this screen, so a huge remote file can't hang the UI waiting for it. */
@Composable
fun AudioViewer(
    filePath: String,
    modifier: Modifier = Modifier,
    viewModel: AudioViewerViewModel = hiltViewModel(),
) {
    // Resolved before the player is ever built — the LAN mirror endpoint needs an X-Sidekick-Key
    // header on every request (including range requests while seeking); plain put.io/local URIs
    // need none. This lookup is a couple of fast DataStore reads, never a network call.
    var headers by remember(filePath) { mutableStateOf<Map<String, String>?>(null) }
    LaunchedEffect(filePath) { headers = viewModel.headersFor(filePath) }

    val resolvedHeaders = headers
    if (resolvedHeaders == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    AudioPlayerContent(filePath = filePath, headers = resolvedHeaders, modifier = modifier)
}

@Composable
private fun AudioPlayerContent(
    filePath: String,
    headers: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val player = remember(headers) {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory().apply {
            if (headers.isNotEmpty()) setDefaultRequestProperties(headers)
        }
        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory))
            .build()
    }

    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekPreview by remember { mutableFloatStateOf(0f) }

    DisposableEffect(player, filePath) {
        val uri = if (filePath.startsWith("http://") || filePath.startsWith("https://")) {
            Uri.parse(filePath)
        } else {
            Uri.fromFile(java.io.File(filePath))
        }
        player.setMediaItem(MediaItem.fromUri(uri))

        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (player.duration > 0) durationMs = player.duration
            }

            override fun onPlayerError(playbackException: PlaybackException) {
                isBuffering = false
                error = playbackException.errorCodeName + ": " +
                    (playbackException.cause?.message ?: playbackException.message)
            }
        }
        player.addListener(listener)
        player.prepare()
        player.playWhenReady = true

        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player) {
        while (true) {
            delay(500)
            if (!isSeeking) positionMs = player.currentPosition.coerceAtLeast(0L)
            if (player.duration > 0) durationMs = player.duration
        }
    }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val currentError = error
        if (currentError != null) {
            Text(
                text = "Playback error: $currentError",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(24.dp),
            )
            return@Box
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.Center) {
                IconButton(
                    onClick = { if (isPlaying) player.pause() else player.play() },
                    modifier = Modifier.size(72.dp),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(56.dp),
                    )
                }
                if (isBuffering) {
                    CircularProgressIndicator(modifier = Modifier.size(72.dp))
                }
            }

            Slider(
                value = if (isSeeking) seekPreview else positionMs.toFloat(),
                valueRange = 0f..(durationMs.coerceAtLeast(1L).toFloat()),
                onValueChange = {
                    isSeeking = true
                    seekPreview = it
                },
                onValueChangeFinished = {
                    isSeeking = false
                    player.seekTo(seekPreview.toLong())
                },
                modifier = Modifier.fillMaxWidth().height(32.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatDuration(if (isSeeking) seekPreview.toLong() else positionMs), style = MaterialTheme.typography.labelSmall)
                Text(formatDuration(durationMs), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}
