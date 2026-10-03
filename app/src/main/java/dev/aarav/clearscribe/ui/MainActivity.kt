package dev.aarav.clearscribe.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import dev.aarav.clearscribe.ClearScribeApp
import dev.aarav.clearscribe.ModelReadyState
import dev.aarav.clearscribe.data.Recording
import dev.aarav.clearscribe.playback.PlaybackState
import dev.aarav.clearscribe.record.RecorderService
import dev.aarav.clearscribe.record.computeWaveformPeaks
import dev.aarav.clearscribe.record.readWavAsFloat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }

        val app = application as ClearScribeApp

        setContent {
            MaterialTheme {
                var selected by remember { mutableStateOf<Recording?>(null) }
                val recordings by app.repository.observeAll().collectAsState(initial = emptyList())
                val playback by app.player.state.collectAsState()
                val modelState by app.modelReadyState.collectAsState()

                val current = selected
                if (current == null) {
                    RecordingListScreen(
                        recordings = recordings,
                        modelState = modelState,
                        onStart = {
                            startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_START))
                        },
                        onStop = {
                            startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
                        },
                        onOpen = { selected = it },
                    )
                } else {
                    RecordingDetailScreen(
                        recording = current,
                        playback = playback,
                        onPlayPause = { app.player.playOrToggle(current.audioPath) },
                        onSeek = { app.player.seekTo(it) },
                        onShare = { shareRecording(this, current.audioPath) },
                        onBack = { app.player.stop(); selected = null },
                    )
                }
            }
        }
    }
}

@Composable
fun RecordingListScreen(
    recordings: List<Recording>,
    modelState: ModelReadyState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpen: (Recording) -> Unit,
) {
    var isRecording by remember { mutableStateOf(false) }
    val listState = rememberScalingLazyListState()

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (modelState) {
                is ModelReadyState.Downloading -> item {
                    Text("Downloading ${modelState.what}…", modifier = Modifier.padding(4.dp))
                }
                is ModelReadyState.Failed -> item {
                    Text("Setup failed: ${modelState.what}", modifier = Modifier.padding(4.dp))
                }
                else -> Unit
            }
            item {
                Chip(
                    onClick = {
                        if (isRecording) onStop() else onStart()
                        isRecording = !isRecording
                    },
                    label = { Text(if (isRecording) "Stop" else "Record") },
                    icon = {
                        Icon(
                            if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                            contentDescription = null,
                        )
                    },
                    colors = ChipDefaults.primaryChipColors(),
                    enabled = modelState is ModelReadyState.Ready,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            items(recordings) { recording ->
                Chip(
                    onClick = { onOpen(recording) },
                    label = { Text(recording.title, maxLines = 1) },
                    secondaryLabel = { Text(formatMs(recording.durationMs.toInt()), maxLines = 1) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
fun RecordingDetailScreen(
    recording: Recording,
    playback: PlaybackState,
    onPlayPause: () -> Unit,
    onSeek: (Int) -> Unit,
    onShare: () -> Unit,
    onBack: () -> Unit,
) {
    val scrollState = rememberScrollState()

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scrollState = scrollState) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(recording.title, maxLines = 2)

            val isThisFile = playback.path == recording.audioPath
            val position = if (isThisFile) playback.positionMs else 0
            val duration = if (isThisFile && playback.durationMs > 0) {
                playback.durationMs
            } else {
                recording.durationMs.toInt()
            }

            var peaks by remember(recording.audioPath) {
                mutableStateOf<FloatArray?>(null)
            }
            LaunchedEffect(recording.audioPath) {
                peaks = withContext(Dispatchers.IO) {
                    val samples = readWavAsFloat(File(recording.audioPath))
                    computeWaveformPeaks(samples, buckets = 60)
                }
            }
            val currentPeaks = peaks
            if (currentPeaks != null) {
                Waveform(
                    peaks = currentPeaks,
                    progress = if (duration > 0) position.toFloat() / duration else 0f,
                    onSeek = { fraction -> onSeek((fraction * duration).toInt()) },
                )
            } else {
                Text("Loading waveform…")
            }
            Text("${formatMs(position)} / ${formatMs(duration)}")

            Chip(
                onClick = onPlayPause,
                label = { Text(if (isThisFile && playback.isPlaying) "Pause" else "Play") },
                icon = {
                    Icon(
                        if (isThisFile && playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                    )
                },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Chip(
                onClick = onShare,
                label = { Text("Share") },
                icon = { Icon(Icons.Filled.Share, contentDescription = null) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Chip(
                onClick = onBack,
                label = { Text("Back") },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            if (recording.transcript.isNotBlank()) {
                Text(recording.transcript, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

private fun formatMs(ms: Int): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
