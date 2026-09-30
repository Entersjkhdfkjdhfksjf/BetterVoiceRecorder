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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.wear.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
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
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.aarav.clearscribe.ClearScribeApp
import dev.aarav.clearscribe.record.RecorderService

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
                RecorderScreen(
                    onStart = {
                        startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_START))
                    },
                    onStop = {
                        startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
                    },
                    recordingTitles = app.repository.observeAll()
                        .collectAsState(initial = emptyList()).value.map { it.title },
                )
            }
        }
    }
}

@Composable
fun RecorderScreen(
    onStart: () -> Unit,
    onStop: () -> Unit,
    recordingTitles: List<String>,
) {
    var isRecording by remember { mutableStateOf(false) }
    val listState = rememberScalingLazyListState()

    Column(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Button(onClick = {
            if (isRecording) onStop() else onStart()
            isRecording = !isRecording
        }) {
            Text(if (isRecording) "Stop" else "Record")
        }

        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(recordingTitles) { title ->
                Text(title, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}
