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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
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

/** Simple three-screen nav — no back stack library needed for this small an app. */
private sealed interface Screen {
    data object List : Screen
    data class Detail(val recording: Recording) : Screen
    data object Settings : Screen
}

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
            val theme by app.settings.theme.collectAsState()

            MaterialTheme(colors = colorsFor(theme)) {
                var screen by remember { mutableStateOf<Screen>(Screen.List) }
                val recordings by app.repository.observeAll().collectAsState(initial = emptyList())
                val playback by app.player.state.collectAsState()
                val modelState by app.modelReadyState.collectAsState()
                val cleanupAvailable by app.cleanupAvailable.collectAsState()

                when (val current = screen) {
                    is Screen.List -> RecordingListScreen(
                        recordings = recordings,
                        modelState = modelState,
                        cleanupAvailable = cleanupAvailable,
                        theme = theme,
                        onStart = {
                            startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_START))
                        },
                        onStop = {
                            startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
                        },
                        onOpen = { screen = Screen.Detail(it) },
                        onOpenSettings = { screen = Screen.Settings },
                    )
                    is Screen.Detail -> RecordingDetailScreen(
                        recording = current.recording,
                        playback = playback,
                        theme = theme,
                        onPlayPause = { app.player.playOrToggle(current.recording.audioPath) },
                        onSeek = { app.player.seekTo(it) },
                        onShare = { shareRecording(this, current.recording.audioPath) },
                        onBack = { app.player.stop(); screen = Screen.List },
                    )
                    is Screen.Settings -> SettingsScreen(
                        theme = theme,
                        modelState = modelState,
                        cleanupAvailable = cleanupAvailable,
                        onThemeSelected = { app.settings.setTheme(it) },
                        onPrepareModels = { app.prepareModels() },
                        onBack = { screen = Screen.List },
                    )
                }
            }
        }
    }
}

/** Renders as a flat Wear Chip on One UI, a translucent GlassChip on Liquid Glass. */
@Composable
private fun AppChip(
    theme: AppTheme,
    onClick: () -> Unit,
    label: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null,
    secondaryLabel: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    if (theme == AppTheme.LIQUID_GLASS) {
        GlassChip(
            onClick = onClick,
            label = label,
            icon = icon,
            secondaryLabel = secondaryLabel,
            enabled = enabled,
            accent = if (primary) MaterialTheme.colors.primary else MaterialTheme.colors.secondary,
            modifier = modifier,
        )
    } else {
        Chip(
            onClick = onClick,
            label = label,
            icon = icon,
            secondaryLabel = secondaryLabel,
            enabled = enabled,
            colors = if (primary) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
            modifier = modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun RecordingListScreen(
    recordings: List<Recording>,
    modelState: ModelReadyState,
    cleanupAvailable: Boolean?,
    theme: AppTheme,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpen: (Recording) -> Unit,
    onOpenSettings: () -> Unit,
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
                is ModelReadyState.NotRequested -> item {
                    Text("Models not downloaded — open Settings", modifier = Modifier.padding(4.dp))
                }
                is ModelReadyState.Downloading -> item {
                    Text("Downloading ${modelState.what}…", modifier = Modifier.padding(4.dp))
                }
                is ModelReadyState.Failed -> item {
                    Text("Setup failed: ${modelState.what}", modifier = Modifier.padding(4.dp))
                }
                else -> Unit
            }
            if (cleanupAvailable == false) {
                item {
                    Text(
                        "Cleanup unavailable on this device — recording raw audio",
                        modifier = Modifier.padding(4.dp),
                    )
                }
            }
            item {
                AppChip(
                    theme = theme,
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
                    primary = true,
                    enabled = modelState is ModelReadyState.Ready,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            items(recordings) { recording ->
                AppChip(
                    theme = theme,
                    onClick = { onOpen(recording) },
                    label = { Text(recording.title, maxLines = 1) },
                    secondaryLabel = { Text(formatMs(recording.durationMs.toInt()), maxLines = 1) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                AppChip(
                    theme = theme,
                    onClick = onOpenSettings,
                    label = { Text("Settings") },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
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
    theme: AppTheme,
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

            AppChip(
                theme = theme,
                onClick = onPlayPause,
                label = { Text(if (isThisFile && playback.isPlaying) "Pause" else "Play") },
                icon = {
                    Icon(
                        if (isThisFile && playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                    )
                },
                primary = true,
                modifier = Modifier.fillMaxWidth(),
            )
            AppChip(
                theme = theme,
                onClick = onShare,
                label = { Text("Share") },
                icon = { Icon(Icons.Filled.Share, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
            )
            AppChip(
                theme = theme,
                onClick = onBack,
                label = { Text("Back") },
                modifier = Modifier.fillMaxWidth(),
            )

            if (recording.transcript.isNotBlank()) {
                Text(recording.transcript, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
fun SettingsScreen(
    theme: AppTheme,
    modelState: ModelReadyState,
    cleanupAvailable: Boolean?,
    onThemeSelected: (AppTheme) -> Unit,
    onPrepareModels: () -> Unit,
    onBack: () -> Unit,
) {
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
            item { Text("Theme", modifier = Modifier.padding(4.dp)) }
            item {
                AppChip(
                    theme = theme,
                    onClick = { onThemeSelected(AppTheme.GALAXY_ONE_UI) },
                    label = { Text("Galaxy One UI") },
                    icon = if (theme == AppTheme.GALAXY_ONE_UI) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else null,
                    primary = theme == AppTheme.GALAXY_ONE_UI,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                AppChip(
                    theme = theme,
                    onClick = { onThemeSelected(AppTheme.LIQUID_GLASS) },
                    label = { Text("Liquid Glass") },
                    icon = if (theme == AppTheme.LIQUID_GLASS) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else null,
                    primary = theme == AppTheme.LIQUID_GLASS,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item { Text("Models", modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
            item {
                val statusText = when (modelState) {
                    is ModelReadyState.NotRequested -> "Not downloaded"
                    is ModelReadyState.Downloading -> "Downloading ${modelState.what}…"
                    is ModelReadyState.Ready -> "Ready" + if (cleanupAvailable == false) " (cleanup unavailable on this device)" else ""
                    is ModelReadyState.Failed -> "Failed: ${modelState.what}"
                }
                Text(statusText, modifier = Modifier.padding(4.dp))
            }
            item {
                AppChip(
                    theme = theme,
                    onClick = onPrepareModels,
                    label = {
                        Text(
                            when (modelState) {
                                is ModelReadyState.Ready -> "Re-check models"
                                is ModelReadyState.Downloading -> "Downloading…"
                                else -> "Download models"
                            }
                        )
                    },
                    icon = { Icon(Icons.Filled.CloudDownload, contentDescription = null) },
                    enabled = modelState !is ModelReadyState.Downloading,
                    primary = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                AppChip(
                    theme = theme,
                    onClick = onBack,
                    label = { Text("Back") },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
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
