package dev.aarav.clearscribe

import android.app.Application
import android.util.Log
import androidx.room.Room
import dev.aarav.clearscribe.data.ClearScribeDatabase
import dev.aarav.clearscribe.data.RecordingRepository
import dev.aarav.clearscribe.pipeline.AudioCleaner
import dev.aarav.clearscribe.pipeline.FirstSentencesSummarizer
import dev.aarav.clearscribe.pipeline.GenericTitleProvider
import dev.aarav.clearscribe.pipeline.SherpaMoonshineTranscriber
import dev.aarav.clearscribe.pipeline.Summarizer
import dev.aarav.clearscribe.pipeline.TitleProvider
import dev.aarav.clearscribe.pipeline.Transcriber
import dev.aarav.clearscribe.playback.RecordingPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface ModelReadyState {
    data object Checking : ModelReadyState
    data class Downloading(val what: String) : ModelReadyState
    data object Ready : ModelReadyState
    data class Failed(val what: String, val error: Throwable) : ModelReadyState
}

/**
 * Simple manual DI. Swap any of these for a real implementation as
 * alternatives ship — nothing else in the app needs to change.
 */
class ClearScribeApp : Application() {

    lateinit var repository: RecordingRepository
        private set

    private lateinit var sherpaTranscriber: SherpaMoonshineTranscriber
    lateinit var transcriber: Transcriber
        private set

    val titleProvider: TitleProvider = GenericTitleProvider()
    val summarizer: Summarizer = FirstSentencesSummarizer()
    val player = RecordingPlayer()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _modelReadyState = MutableStateFlow<ModelReadyState>(ModelReadyState.Checking)
    val modelReadyState: StateFlow<ModelReadyState> = _modelReadyState

    override fun onCreate() {
        super.onCreate()
        val db = Room.databaseBuilder(this, ClearScribeDatabase::class.java, "clearscribe.db")
            .build()
        repository = RecordingRepository(db.recordingDao())

        sherpaTranscriber = SherpaMoonshineTranscriber(applicationContext)
        transcriber = sherpaTranscriber

        // Fetch both models now, at launch, over whatever network is
        // available — rather than silently during the first recording's
        // stop-and-process step. Clear is ~9.3 MB; Moonshine tiny is ~120 MB,
        // so expect the second step to take meaningfully longer.
        appScope.launch {
            try {
                _modelReadyState.value = ModelReadyState.Downloading("cleanup model")
                AudioCleaner(applicationContext).ensureModelReady()

                _modelReadyState.value = ModelReadyState.Downloading("transcription model")
                sherpaTranscriber.ensureModelReady()

                _modelReadyState.value = ModelReadyState.Ready
            } catch (e: Throwable) {
                Log.e("ClearScribeApp", "Model download failed", e)
                _modelReadyState.value = ModelReadyState.Failed("model download", e)
            }
        }
    }
}
