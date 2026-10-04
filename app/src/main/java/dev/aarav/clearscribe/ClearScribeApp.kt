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

/**
 * Overall app readiness. Deliberately tracks ONLY the transcription model —
 * that's the one thing recording genuinely can't work without. Cleanup
 * (Clear) is best-effort: see [AudioCleaner.isAvailable] and
 * [ClearScribeApp.cleanupAvailable] for that separately, since a device
 * that can't run Clear (confirmed real: some Wear OS devices are locked to
 * a 32-bit userspace, and Clear's native libs have only ever shown up for
 * arm64-v8a/x86_64) should still be able to record and transcribe.
 */
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

    lateinit var audioCleaner: AudioCleaner
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

    private val _cleanupAvailable = MutableStateFlow<Boolean?>(null) // null = not checked yet
    val cleanupAvailable: StateFlow<Boolean?> = _cleanupAvailable

    override fun onCreate() {
        super.onCreate()
        val db = Room.databaseBuilder(this, ClearScribeDatabase::class.java, "clearscribe.db")
            .build()
        repository = RecordingRepository(db.recordingDao())

        audioCleaner = AudioCleaner(applicationContext)
        sherpaTranscriber = SherpaMoonshineTranscriber(applicationContext)
        transcriber = sherpaTranscriber

        appScope.launch {
            // Clear is best-effort and must never block the rest of setup —
            // ensureModelReady() already catches its own failures (network,
            // or a device with no 32-bit Clear build) and returns false
            // rather than throwing.
            _modelReadyState.value = ModelReadyState.Downloading("cleanup model")
            _cleanupAvailable.value = audioCleaner.ensureModelReady()

            try {
                _modelReadyState.value = ModelReadyState.Downloading("transcription model")
                sherpaTranscriber.ensureModelReady()
                _modelReadyState.value = ModelReadyState.Ready
            } catch (e: Throwable) {
                Log.e("ClearScribeApp", "Transcription model setup failed", e)
                _modelReadyState.value = ModelReadyState.Failed("transcription model", e)
            }
        }
    }
}
